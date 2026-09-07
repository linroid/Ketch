package com.linroid.ketch.torrent

import kotlin.time.TimeSource

/** Serialized per-session tracker lifecycle; callers supply whole-torrent verified state. */
internal class TrackerDiscovery(
  private val metadata: TorrentMetadata,
  private val peerId: ByteArray,
  private val port: Int,
  private val trackers: TrackerTiers,
  private val onPrivateTrackerChanged: suspend () -> Unit = {},
  private val nowMs: () -> Long = monotonicClock(),
) {
  private var nextAnnounce = 0L
  private var started = false
  private var completed = false
  private var source: String? = null
  private val key = okio.Buffer().write(torrentRandomBytes(4)).readInt()

  suspend fun poll(
    verified: BooleanArray,
    downloaded: Long,
    uploaded: Long,
    stopped: Boolean = false,
  ): TrackerResponse? {
    require(verified.size == metadata.pieceHashes.size / 20)
    val left = verified.indices.sumOf { index ->
      if (verified[index]) 0L else minOf(metadata.pieceLength,
        metadata.totalBytes - index.toLong() * metadata.pieceLength)
    }
    val event = when {
      stopped && started -> TrackerEvent.STOPPED
      stopped -> return null
      !started -> TrackerEvent.STARTED
      left == 0L && !completed -> TrackerEvent.COMPLETED
      else -> TrackerEvent.NONE
    }
    if (event == TrackerEvent.NONE && nowMs() < nextAnnounce) return null
    val result = trackers.announce(TrackerAnnounce(metadata.infoHash, peerId, port, downloaded,
      left, uploaded, event, key))
    if (metadata.isPrivate && source != null && source != result.source) {
      onPrivateTrackerChanged()
    }
    source = result.source
    started = event != TrackerEvent.STOPPED
    if (event == TrackerEvent.COMPLETED || (event == TrackerEvent.STARTED && left == 0L)) {
      completed = true
    }
    nextAnnounce = nowMs() + result.intervalSeconds * 1000
    return result
  }
}

internal fun monotonicClock(): () -> Long {
  val origin = TimeSource.Monotonic.markNow()
  return { origin.elapsedNow().inWholeMilliseconds }
}
