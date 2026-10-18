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
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Runtime-owned shared fetches. A fetch runs while some caller waits for it: one caller leaving
 * never cancels it for the others, and [grace] after the last one left it is cancelled, so a task
 * paused, canceled or removed, or a preview given up, stops its discovery then. A caller that
 * comes back meanwhile, as a task paused and resumed or the download of a preview's link does,
 * carries on with it and the peers it found, unless its link names a source the fetch does not
 * ask ([LookupSources]): that fetch then ends at once, and one of the caller's starts.
 */
internal class TorrentMetadataCache(
  private val scope: CoroutineScope,
  capacityBytes: Int = 32 * 1024 * 1024,
  parentBudget: TorrentBufferBudget? = null,
  /** How long a fetch goes on once no caller waits for it. */
  private val grace: Duration = ABANDONED_LOOKUP_GRACE,
) {
  private val budget = TorrentBufferBudget(capacityBytes, parentBudget)
  internal val retainedBytes: Int get() = budget.allocated

  private val mutex = Mutex()
  private class Entry(val metadata: TorrentMetadata, val lease: TorrentBufferBudget.Lease)
  private val entries = linkedMapOf<InfoHash, Entry>()
  private data class PendingKey(val hash: InfoHash, val privacy: TorrentDiscoveryPrivacy)
  /** A shared fetch, where it looks and how many callers wait for it, guarded by [mutex]. */
  private class Pending(val fetch: Deferred<TorrentMetadata>, val sources: LookupSources) {
    var waiters = 1
    /** Cancels [fetch] once its grace ends, while no caller waits for it. */
    var abandon: Job? = null
  }
  private val pending = mutableMapOf<PendingKey, Pending>()
  /** How many fetches [pending] holds, which callers past [MAX_PENDING_METADATA] wait on. */
  private val pendingCount = MutableStateFlow(0)
  internal val pendingFetches: Int get() = pendingCount.value
  /** Runs every fetch, also those that left [pending] and are still ending, which [close] ends. */
  private val fetches = CoroutineScope(scope.coroutineContext +
    SupervisorJob(scope.coroutineContext[Job]))
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
   * Beyond [MAX_PENDING_METADATA] fetches, those in their grace included, a new one waits for
   * another to end. [fetch] looks in [sources] besides what every fetch of [hash] asks.
   */
  suspend fun resolve(
    hash: InfoHash,
    privacy: TorrentDiscoveryPrivacy = TorrentDiscoveryPrivacy.PUBLIC,
    sources: LookupSources = LookupSources(),
    fetch: suspend () -> TorrentMetadata,
  ): TorrentMetadata {
    get(hash)?.let { return it }
    val key = PendingKey(hash, privacy)
    val operation = join(key, sources, fetch)
    try {
      return operation.fetch.await()
    } finally {
      withContext(NonCancellable) { leave(key, operation) }
    }
  }

  /**
   * Joins the fetch of [key], also one in its grace that asks every one of [sources], or starts
   * one once fewer than the bound are pending. One in its grace that misses some is cancelled at
   * once, and the caller's takes its place.
   */
  private suspend fun join(
    key: PendingKey,
    sources: LookupSources,
    fetch: suspend () -> TorrentMetadata,
  ): Pending {
    while (true) {
      val joined = mutex.withLock {
        scope.coroutineContext.ensureActive()
        check(!closed) { "Metadata cache is closed" }
        val current = pending[key]
        if (current != null && (current.waiters > 0 || current.sources.covers(sources))) {
          current.waiters++
          current.abandon?.cancel()
          current.abandon = null
          current
        } else if (current != null || pending.size < MAX_PENDING_METADATA) {
          current?.abandon?.cancel()
          current?.fetch?.cancel()
          Pending(start(key, fetch, after = current?.fetch), sources).also {
            pending[key] = it
            pendingCount.value = pending.size
          }
        } else null
      }
      if (joined != null) return joined
      pendingCount.first { it < MAX_PENDING_METADATA }
    }
  }

  /**
   * Under [mutex]: the fetch of [key], which leaves [pending] as it ends. It begins once [after],
   * the fetch it replaces, has ended, so what that one says as it ends, such as its trackers'
   * `stopped`, goes first.
   */
  private fun start(
    key: PendingKey,
    fetch: suspend () -> TorrentMetadata,
    after: Job? = null,
  ): Deferred<TorrentMetadata> = fetches.async {
    val self = coroutineContext.job
    try {
      after?.join()
      val metadata = get(key.hash) ?: fetch()
      require(metadata.infoHash == key.hash)
      put(metadata)
    } finally {
      // Its grace may have ended already, and another started anew.
      withContext(NonCancellable) {
        mutex.withLock {
          pending[key]?.takeIf { it.fetch === self }?.let {
            it.abandon?.cancel()
            remove(key)
          }
        }
      }
    }
  }

  /**
   * A caller of [operation] left. Once the last left, it goes on for [grace], for a caller that
   * comes back, then frees its place and is cancelled, unless it ended or a caller joined it. It
   * ends in the engine scope, holding no caller.
   */
  private suspend fun leave(key: PendingKey, operation: Pending) {
    mutex.withLock {
      operation.waiters--
      if (operation.waiters == 0 && pending[key] === operation) {
        operation.abandon = scope.launch {
          delay(grace)
          val abandoned = mutex.withLock {
            (operation.waiters == 0 && pending[key] === operation).also { if (it) remove(key) }
          }
          if (abandoned) operation.fetch.cancel()
        }
      }
    }
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

  /**
   * Reject future use, release retained entries, and cancel every fetch, those in their grace
   * too, and await them.
   */
  suspend fun close(): Unit = withContext(NonCancellable) {
    cleanupJob?.cancel()
    mutex.withLock {
      closed = true
      entries.values.forEach { it.lease.close() }
      entries.clear()
    }
    // Fetch finalizers take the same mutex, so never await them while holding it.
    fetches.coroutineContext.job.cancelAndJoin()
  }

  private fun evictOldest() {
    entries.remove(entries.keys.first())?.lease?.close()
  }
}

/**
 * Where a magnet lookup looks besides what every lookup of its hash asks, such as the DHT: its
 * link's usable [trackers] and the [peers] it names. A link's web seeds are not among them: they
 * find no metadata, and each caller's own join what the lookup found.
 */
internal data class LookupSources(
  val trackers: Set<String> = emptySet(),
  val peers: Set<String> = emptySet(),
) {
  /** Whether a lookup from these asks everywhere one from [other] would. */
  fun covers(other: LookupSources): Boolean =
    trackers.containsAll(other.trackers) && peers.containsAll(other.peers)
}

/** Distinct in-flight metadata fetches per runtime; further ones wait for one to end. */
internal const val MAX_PENDING_METADATA = 16

/**
 * How long a magnet lookup goes on once nothing waits for it, for a task paused and resumed, or
 * the download of a preview's link, to carry on with it.
 */
internal val ABANDONED_LOOKUP_GRACE: Duration = 5.seconds

/** Retained arrays, strings, file records, and container allowance; not process RSS. */
internal fun cacheWeight(metadata: TorrentMetadata): Long =
  metadata.infoBytes.size.toLong() * 2 + metadata.pieceHashes.size +
    metadata.name.length * 2L + metadata.files.sumOf { it.path.length * 2L + 64 } + 512
