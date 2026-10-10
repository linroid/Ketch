package com.linroid.ketch.torrent

import kotlinx.coroutines.flow.StateFlow

/**
 * Handle for a single active torrent download.
 *
 * Provides control over file selection, speed limiting,
 * pause/resume, and progress monitoring.
 */
internal interface TorrentSession {
  /** Hex-encoded info hash. */
  val infoHash: String

  /** Observable download progress in bytes. */
  val downloadedBytes: StateFlow<Long>

  /** Observable torrent state. */
  val state: StateFlow<TorrentSessionState>

  /** Total bytes of the selected files; follows [changeSelection]. */
  val totalBytes: Long

  /** Current download speed in bytes/sec. */
  val downloadSpeed: Long

  /** Pauses the torrent. */
  suspend fun pause()

  /** Resumes a paused torrent. */
  suspend fun resume()

  /** IDs of the files this session downloads: metainfo indices (v1) or output mapping IDs (v2). */
  val selectedFileIds: Set<String>

  /**
   * Applies [fileIds], a non-empty set of known file IDs, without reconnecting. True when this
   * session will deliver them before it finishes: a running transfer or seeding swarm took the
   * change, or a file check restarted with it. False when it only saved them because it is
   * paused, finished or stopped; the next [resume] downloads them.
   */
  suspend fun changeSelection(fileIds: Set<String>): Boolean

  /** Payload received from and sent to peers, and the current upload speed when tracked. */
  suspend fun payloadCounters(): TorrentPayloadCounters

  /** Sets per-torrent download rate limit (bytes/sec, 0=unlimited). */
  fun setDownloadRateLimit(bytesPerSecond: Long)

  /**
   * Sets this torrent's upload rate limit (bytes/sec, 0=unlimited), applied after the engine's
   * shared one. Sessions that never upload ignore it.
   */
  fun setUploadRateLimit(bytesPerSecond: Long) {}

  /**
   * Saves resume data for later session recovery.
   *
   * @return raw resume data bytes, or null if unavailable
   */
  suspend fun saveResumeData(): ByteArray?
}

/** What a session received and uploaded; [uploadSpeed] is null where it is not tracked. */
internal data class TorrentPayloadCounters(
  val received: Long,
  val uploaded: Long,
  val uploadSpeed: Long?,
)

/** A seed-only start found a completed torrent's files changed on disk, so it did not seed. */
internal class IncompleteSeedException : IllegalStateException("Completed torrent changed on disk")

/** State of a torrent session. */
internal enum class TorrentSessionState {
  /** Waiting for metadata (magnet link). */
  CHECKING_METADATA,

  /** Checking existing files on disk. */
  CHECKING_FILES,

  /** Actively downloading. */
  DOWNLOADING,

  /** Download complete, may still be seeding. */
  FINISHED,

  /** Seeding (uploading). */
  SEEDING,

  /** Paused by user. */
  PAUSED,

  /** Stopped or errored. */
  STOPPED,
}
