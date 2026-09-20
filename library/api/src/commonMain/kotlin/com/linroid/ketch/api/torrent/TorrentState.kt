package com.linroid.ketch.api.torrent

import kotlinx.serialization.Serializable

/** Transfer lifecycle is independent of the download task's selected-data completion. */
@Serializable
enum class TorrentActivity {
  RESOLVING,
  CHECKING,
  QUEUED,
  DOWNLOADING,
  SEEDING,
  PAUSED,
  STOPPED,
  FAILED,
}

/**
 * Aggregate counters. Payload excludes padding and protocol overhead; retries/discards are
 * separate.
 * [selectedVerifiedBytes] can decrease after selection changes or a failed recheck. Completion is
 * scoped to the snapshot's selection generation. Ratio is undefined when downloaded payload is
 * zero.
 */
@Serializable
data class TorrentCounters(
  val totalPayloadBytes: Long,
  val wantedBytes: Long,
  val selectedVerifiedBytes: Long,
  val receivedPayloadBytes: Long,
  val uploadedPayloadBytes: Long,
  val discardedPayloadBytes: Long,
  val protocolBytes: Long,
  val downloadBytesPerSecond: Long,
  val uploadBytesPerSecond: Long,
  val seedSeconds: Long,
) {
  init {
    require(totalPayloadBytes >= 0 && wantedBytes in 0..totalPayloadBytes)
    require(selectedVerifiedBytes in 0..wantedBytes)
    require(receivedPayloadBytes >= 0 && uploadedPayloadBytes >= 0)
    require(discardedPayloadBytes >= 0 && protocolBytes >= 0)
    require(downloadBytesPerSecond >= 0 && uploadBytesPerSecond >= 0 && seedSeconds >= 0)
  }
}

/**
 * Bounded task summary. Peer/file lists are paginated separately at the same revision.
 * Metadata may be absent while resolving; [counters] then remains null rather than implying zero.
 * A completed selection does not imply full-content availability or stopped seeding.
 */
@Serializable
data class TorrentSnapshot(
  val taskId: String,
  val revision: TorrentRevision,
  val activity: TorrentActivity,
  val selectionGeneration: Long,
  val selectionComplete: Boolean,
  val counters: TorrentCounters? = null,
) {
  init {
    require(taskId.isNotBlank() && taskId.length <= 128)
    require(selectionGeneration >= 0)
    require(!selectionComplete || counters != null &&
      counters.selectedVerifiedBytes == counters.wantedBytes)
  }
}

/** Bounded page request with opaque cursors, invalidated by relevant mutations. */
@Serializable
data class TorrentPageRequest(val limit: Int = 100, val cursor: String? = null) {
  init {
    require(limit in 1..1000)
    require(cursor == null || cursor.isNotBlank() && cursor.length <= 4096)
  }
}
