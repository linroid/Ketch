package com.linroid.ketch.torrent

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Runtime-owned shared fetches; cancellation of one caller does not cancel other waiters. */
internal class TorrentMetadataCache(
  private val scope: CoroutineScope,
  capacityBytes: Int = 32 * 1024 * 1024,
  parentBudget: TorrentBufferBudget? = null,
) {
  private val budget = TorrentBufferBudget(capacityBytes, parentBudget)
  internal val retainedBytes: Int get() = budget.allocated

  private val mutex = Mutex()
  private class Entry(val metadata: TorrentMetadata, val lease: TorrentBufferBudget.Lease)
  private val entries = linkedMapOf<InfoHash, Entry>()
  private data class PendingKey(val hash: InfoHash, val privacy: TorrentDiscoveryPrivacy)
  private val pending = mutableMapOf<PendingKey, Deferred<TorrentMetadata>>()
  private var closed = false
  private var cleanupJob: Job? = null

  init {
    // Start before returning the cache so even immediate owner cancellation runs cleanup.
    cleanupJob = scope.launch(start = CoroutineStart.UNDISPATCHED) {
      try { awaitCancellation() } finally { withContext(NonCancellable) { close() } }
    }
  }

  suspend fun get(hash: InfoHash): TorrentMetadata? = mutex.withLock {
    scope.coroutineContext.ensureActive()
    check(!closed) { "Metadata cache is closed" }
    entries.remove(hash)?.also { entries[hash] = it }?.metadata
  }

  /**
   * Shares one in-flight fetch per hash *and* discovery privacy. A restricted caller must never
   * join a public operation: that operation may already be using explicit peers or DHT on its
   * behalf, and its rejection of private metadata does not apply to the restricted caller.
   * Completed entries stay shared because the hash authenticates them regardless of discovery.
   */
  suspend fun resolve(
    hash: InfoHash,
    privacy: TorrentDiscoveryPrivacy = TorrentDiscoveryPrivacy.PUBLIC,
    fetch: suspend () -> TorrentMetadata,
  ): TorrentMetadata {
    get(hash)?.let { return it }
    val key = PendingKey(hash, privacy)
    val operation = mutex.withLock {
      scope.coroutineContext.ensureActive()
      check(!closed) { "Metadata cache is closed" }
      pending.getOrPut(key) {
        check(pending.size < 16) { "Too many pending metadata requests" }
        scope.async {
          try {
            val metadata = get(hash) ?: fetch()
            require(metadata.infoHash == hash)
            put(metadata)
          } finally {
            withContext(NonCancellable) { mutex.withLock { pending.remove(key) } }
          }
        }
      }
    }
    return operation.await()
  }

  suspend fun put(value: TorrentMetadata): TorrentMetadata = mutex.withLock {
    scope.coroutineContext.ensureActive()
    check(!closed) { "Metadata cache is closed" }
    val size = cacheWeight(value)
    entries.remove(value.infoHash)?.lease?.close()
    var lease: TorrentBufferBudget.Lease? = null
    if (size <= budget.capacity) {
      if (entries.size >= 8) evictOldest()
      lease = budget.reserve(size.toInt())
      while (lease == null && entries.isNotEmpty()) {
        evictOldest()
        lease = budget.reserve(size.toInt())
      }
    }
    try {
      // The hash authenticates the info dictionary, never caller tracker credentials.
      val metadata = value.copy(
        trackers = emptyList(),
        trackerTiers = emptyList(),
        comment = null,
        createdBy = null,
        metainfoBytes = metainfoFromInfo(value.infoBytes, emptyList()),
      )
      if (lease != null) entries[metadata.infoHash] = Entry(metadata, lease)
      metadata
    } catch (failure: Throwable) {
      lease?.close()
      throw failure
    }
  }

  /** Reject future use, release retained entries, and await canceled shared fetches. */
  suspend fun close(): Unit = withContext(NonCancellable) {
    cleanupJob?.cancel()
    val operations = mutex.withLock {
      closed = true
      entries.values.forEach { it.lease.close() }
      entries.clear()
      pending.values.toList()
    }
    // Fetch finalizers take the same mutex, so never await them while holding it.
    operations.forEach { it.cancel() }
    operations.forEach { it.cancelAndJoin() }
  }

  private fun evictOldest() {
    entries.remove(entries.keys.first())?.lease?.close()
  }
}

/** Retained arrays, strings, file records, and container allowance; not process RSS. */
internal fun cacheWeight(metadata: TorrentMetadata): Long =
  metadata.infoBytes.size.toLong() * 2 + metadata.pieceHashes.size +
    metadata.name.length * 2L + metadata.files.sumOf { it.path.length * 2L + 64 } + 512
