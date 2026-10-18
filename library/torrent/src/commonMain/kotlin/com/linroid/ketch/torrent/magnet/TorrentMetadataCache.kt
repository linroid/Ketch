package com.linroid.ketch.torrent.magnet

import com.linroid.ketch.torrent.TorrentDiscoveryPrivacy
import com.linroid.ketch.torrent.engine.TorrentBufferBudget
import com.linroid.ketch.torrent.metainfo.InfoHash
import com.linroid.ketch.torrent.metainfo.TorrentMetadata
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Runtime-owned shared fetches. A fetch runs while some caller waits for it: one caller leaving
 * never cancels it for the others, and the last one leaving cancels it, so a task paused, canceled
 * or removed, or a preview given up, stops its discovery at once.
 */
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
  /** A shared fetch and how many callers wait for it, guarded by [mutex]. */
  private class Pending(val fetch: Deferred<TorrentMetadata>) {
    var waiters = 1
  }
  private val pending = mutableMapOf<PendingKey, Pending>()
  /** How many fetches [pending] holds, which callers past [MAX_PENDING_METADATA] wait on. */
  private val pendingCount = MutableStateFlow(0)
  internal val pendingFetches: Int get() = pendingCount.value
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
   * Beyond [MAX_PENDING_METADATA] fetches, a new one waits for another to end.
   */
  suspend fun resolve(
    hash: InfoHash,
    privacy: TorrentDiscoveryPrivacy = TorrentDiscoveryPrivacy.PUBLIC,
    fetch: suspend () -> TorrentMetadata,
  ): TorrentMetadata {
    get(hash)?.let { return it }
    val key = PendingKey(hash, privacy)
    val operation = join(key, fetch)
    try {
      return operation.fetch.await()
    } finally {
      withContext(NonCancellable) { leave(key, operation) }
    }
  }

  /** Joins the fetch of [key], or starts one once fewer than the bound are pending. */
  private suspend fun join(key: PendingKey, fetch: suspend () -> TorrentMetadata): Pending {
    while (true) {
      val joined = mutex.withLock {
        scope.coroutineContext.ensureActive()
        check(!closed) { "Metadata cache is closed" }
        pending[key]?.also { it.waiters++ } ?: if (pending.size < MAX_PENDING_METADATA) {
          Pending(start(key, fetch)).also {
            pending[key] = it
            pendingCount.value = pending.size
          }
        } else null
      }
      if (joined != null) return joined
      pendingCount.first { it < MAX_PENDING_METADATA }
    }
  }

  /** Under [mutex]: the fetch of [key], which leaves [pending] as it ends. */
  private fun start(
    key: PendingKey,
    fetch: suspend () -> TorrentMetadata,
  ): Deferred<TorrentMetadata> = scope.async {
    val self = coroutineContext.job
    try {
      val metadata = get(key.hash) ?: fetch()
      require(metadata.infoHash == key.hash)
      put(metadata)
    } finally {
      // The last caller to leave may have removed it already, and another started anew.
      withContext(NonCancellable) {
        mutex.withLock { if (pending[key]?.fetch === self) remove(key) }
      }
    }
  }

  /**
   * A caller of [operation] left: the last to leave frees its place and cancels it, unless it
   * ended. It ends in the engine scope, with any bounded `stopped` announce, holding no caller.
   */
  private suspend fun leave(key: PendingKey, operation: Pending) {
    val abandoned = mutex.withLock {
      operation.waiters--
      if (operation.waiters == 0 && pending[key] === operation) remove(key)
      operation.waiters == 0
    }
    if (abandoned) operation.fetch.cancel()
  }

  /** Under [mutex]. */
  private fun remove(key: PendingKey) {
    pending.remove(key)
    pendingCount.value = pending.size
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
      pending.values.map { it.fetch }
    }
    // Fetch finalizers take the same mutex, so never await them while holding it.
    operations.forEach { it.cancel() }
    operations.forEach { it.cancelAndJoin() }
  }

  private fun evictOldest() {
    entries.remove(entries.keys.first())?.lease?.close()
  }
}

/** Distinct in-flight metadata fetches per runtime; further ones wait for one to end. */
internal const val MAX_PENDING_METADATA = 16

/** Retained arrays, strings, file records, and container allowance; not process RSS. */
internal fun cacheWeight(metadata: TorrentMetadata): Long =
  metadata.infoBytes.size.toLong() * 2 + metadata.pieceHashes.size +
    metadata.name.length * 2L + metadata.files.sumOf { it.path.length * 2L + 64 } + 512
