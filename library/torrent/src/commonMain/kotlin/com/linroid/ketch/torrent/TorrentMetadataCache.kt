package com.linroid.ketch.torrent

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Runtime-owned shared fetches; cancellation of one caller does not cancel other waiters. */
internal class TorrentMetadataCache(
  private val scope: CoroutineScope,
  private val capacityBytes: Int = 32 * 1024 * 1024,
) {
  init { require(capacityBytes > 0) }

  private val mutex = Mutex()
  private val entries = linkedMapOf<InfoHash, TorrentMetadata>()
  private val pending = mutableMapOf<InfoHash, Deferred<TorrentMetadata>>()
  private var bytes = 0L

  suspend fun get(hash: InfoHash): TorrentMetadata? = mutex.withLock {
    scope.coroutineContext.ensureActive()
    entries.remove(hash)?.also { entries[hash] = it }
  }

  suspend fun resolve(hash: InfoHash, fetch: suspend () -> TorrentMetadata): TorrentMetadata {
    get(hash)?.let { return it }
    val operation = mutex.withLock {
      scope.coroutineContext.ensureActive()
      pending.getOrPut(hash) {
        check(pending.size < 16) { "Too many pending metadata requests" }
        scope.async {
          try {
            val metadata = get(hash) ?: fetch()
            require(metadata.infoHash == hash)
            put(metadata)
          } finally {
            withContext(NonCancellable) { mutex.withLock { pending.remove(hash) } }
          }
        }
      }
    }
    return operation.await()
  }

  suspend fun put(value: TorrentMetadata): TorrentMetadata = mutex.withLock {
    // The info hash authenticates only the info dictionary, never caller tracker credentials.
    val metadata = value.copy(
      trackers = emptyList(),
      trackerTiers = emptyList(),
      comment = null,
      createdBy = null,
      metainfoBytes = metainfoFromInfo(value.infoBytes, emptyList()),
    )
    scope.coroutineContext.ensureActive()
    val size = weight(metadata)
    entries.remove(metadata.infoHash)?.let { bytes -= weight(it) }
    if (size > capacityBytes) return@withLock metadata
    while (entries.isNotEmpty() && (bytes + size > capacityBytes || entries.size >= 8)) {
      val key = entries.keys.first()
      bytes -= weight(checkNotNull(entries.remove(key)))
    }
    entries[metadata.infoHash] = metadata
    bytes += size
    metadata
  }

  private fun weight(metadata: TorrentMetadata): Long = metadata.metainfoBytes.size.toLong() +
    metadata.infoBytes.size + metadata.pieceHashes.size + metadata.files.sumOf { it.path.length * 2L }
}
