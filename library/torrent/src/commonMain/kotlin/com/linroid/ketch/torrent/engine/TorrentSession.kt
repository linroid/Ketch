package com.linroid.ketch.torrent.engine

import com.linroid.ketch.torrent.api.TorrentStall
import kotlinx.coroutines.flow.MutableStateFlow
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

  /** Why the download makes no progress, while it does not; sessions that cannot tell, null. */
  val stall: StateFlow<TorrentStall?> get() = NO_STALL

  /** Sets per-torrent download rate limit (bytes/sec, 0=unlimited). */
  fun setDownloadRateLimit(bytesPerSecond: Long)

  /**
   * Replaces the task's limits, which payload pays after the engine's and this torrent's: the
   * [throttle][com.linroid.ketch.core.engine.DownloadContext.throttle] of the run that follows
   * the session now, such as a run that takes over a seeding session an earlier run opened, or
   * the [global limit][com.linroid.ketch.core.engine.DownloadContext.globalThrottle] alone for a
   * seeder no run follows. Payload already waiting keeps the limits it waits for.
   */
  fun setTaskThrottle(throttle: suspend (Int) -> Unit) {}

  /**
   * A download run took this session over, such as a seeder Ketch restored: from now on it
   * downloads what its selection misses, as a session a download opened does, rather than
   * stopping or setting pieces aside. Sessions that never only seed ignore it.
   */
  fun endSeedOnly() {}

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

  /**
   * The resume data [saveResumeData] would return now, read without writing anything or waiting
   * for a [pause] under way, for a host that cannot wait for a disk, such as one shutting down.
   * Sessions that keep their resume data in memory return what [saveResumeData] does.
   */
  suspend fun currentResumeData(): ByteArray? = saveResumeData()
}

/**
 * What a session received and uploaded; [uploadSpeed] is null where it is not tracked. Of what
 * it [received], [discarded] was thrown away rather than verified: duplicate or late blocks,
 * pieces that failed their hash check, and partial pieces given up.
 */
internal data class TorrentPayloadCounters(
  val received: Long,
  val uploaded: Long,
  val uploadSpeed: Long?,
  val discarded: Long = 0,
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

private val NO_STALL: StateFlow<TorrentStall?> = MutableStateFlow(null)
