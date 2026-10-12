package com.linroid.ketch.api

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlin.time.Duration
import kotlin.time.Instant

/**
 * The lifecycle state of a [DownloadTask]: usually Scheduled, Queued, Downloading, then
 * Completed, with Paused and Failed (resumable) or Canceled on the way. A completed task whose
 * [file selection][DownloadTask.selectFiles] gains files goes back to Queued.
 */
@Serializable
sealed class DownloadState {
  /** Waiting for a [DownloadSchedule] trigger or [DownloadCondition]s. */
  @Serializable
  @SerialName("scheduled")
  data class Scheduled(val schedule: DownloadSchedule) : DownloadState()

  /** Waiting in the download queue for an available slot. */
  @Serializable
  @SerialName("queued")
  data object Queued : DownloadState()

  /** Downloading; [progress] updates periodically. */
  @Serializable
  @SerialName("downloading")
  data class Downloading(val progress: DownloadProgress) : DownloadState()

  /** Paused, for [reason]; [PauseReason.User] from servers that do not report one. */
  @Serializable
  @SerialName("paused")
  data class Paused(
    val progress: DownloadProgress,
    val reason: PauseReason = PauseReason.User,
  ) : DownloadState()

  /**
   * Download finished. Fields are `null` when unknown, such as from older versions.
   *
   * @property totalBytes size of the content in bytes
   * @property downloadTime time spent downloading over every run, excluding waits and pauses
   * @property completedAt when the download last finished
   * @property sharing whether the source is sharing the content, such as a seeding torrent; not
   *   saved
   * @property checksum the digest the file was verified against: the request's, or one the
   *   server published (not kept across restarts)
   */
  @Serializable
  @SerialName("completed")
  data class Completed(
    val outputPath: String,
    val totalBytes: Long? = null,
    val downloadTime: Duration? = null,
    val completedAt: Instant? = null,
    val sharing: Boolean = false,
    val checksum: Checksum? = null,
  ) : DownloadState()

  /** Failed with [error]; unknown error types read as [KetchError.Unknown]. */
  @Serializable
  @SerialName("failed")
  data class Failed(
    @Serializable(with = KetchErrorSerializer::class) val error: KetchError,
  ) : DownloadState()

  /** Canceled. */
  @Serializable
  @SerialName("canceled")
  data object Canceled : DownloadState()

  /** Whether the task completed, failed or was canceled. */
  val isTerminal: Boolean
    get() = this is Completed || this is Failed || this is Canceled

  /** Whether the task is downloading. */
  val isActive: Boolean
    get() = this is Downloading
}
