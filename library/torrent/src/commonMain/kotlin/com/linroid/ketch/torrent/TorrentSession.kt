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

  /** Total bytes of selected files. */
  val totalBytes: Long

  /** Current download speed in bytes/sec. */
  val downloadSpeed: Long

  /** Pauses the torrent. */
  suspend fun pause()

  /** Resumes a paused torrent. */
  suspend fun resume()

  /**
   * Sets file priorities. Index 0 = skip, 4 = normal, 7 = high.
   *
   * @param priorities map of file index to priority (0=skip, 4=normal)
   */
  fun setFilePriorities(priorities: Map<Int, Int>)

  /** Sets per-torrent download rate limit (bytes/sec, 0=unlimited). */
  fun setDownloadRateLimit(bytesPerSecond: Long)

  /**
   * Saves resume data for later session recovery.
   *
   * @return raw resume data bytes, or null if unavailable
   */
  suspend fun saveResumeData(): ByteArray?
}

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
