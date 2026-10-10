package com.linroid.ketch.api

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlin.time.Duration
import kotlin.time.Instant

/**
 * Represents the lifecycle state of a download task.
 *
 * State transitions follow this general flow:
 * ```
 * Scheduled -> Queued -> Downloading -> Completed
 *                |            |
 *                v            v
 *             Canceled      Paused(User | Preempted | Shutdown) -> Downloading
 *                             |
 *                             v
 *                           Failed
 * ```
 *
 * A task paused for [PauseReason.Preempted] still waits in the queue. One that asked to wait for
 * a file selection goes from Queued to Paused([PauseReason.AwaitingFileSelection]) once its files
 * are known, and a completed task whose selection gains files goes back to Queued.
 *
 * @see DownloadTask.state
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

  /** Actively downloading. [progress] is updated periodically. */
  @Serializable
  @SerialName("downloading")
  data class Downloading(val progress: DownloadProgress) : DownloadState()

  /**
   * Download paused. [reason] says why: by the user, or by the engine, which then resumes it on
   * its own (see [PauseReason]). Instances and servers that do not report a reason give
   * [PauseReason.User].
   */
  @Serializable
  @SerialName("paused")
  data class Paused(
    val progress: DownloadProgress,
    val reason: PauseReason = PauseReason.User,
  ) : DownloadState()

  /**
   * Download finished successfully.
   *
   * @property outputPath the resolved output location
   * @property totalBytes size of the downloaded content in bytes, or `null` if unknown
   * @property downloadTime time spent downloading, summed over every run of the task and
   *   excluding time it was scheduled, queued or paused; `null` if unknown, such as for a
   *   task started by a version of Ketch that did not track it
   * @property completedAt when the download finished, stamped each time it completes (a
   *   selection that adds files clears it until then); `null` if unknown, such as for a task
   *   that finished in a version of Ketch that did not record it, or one reported by an older
   *   server
   * @property seeding `true` while its source shares the finished content with other peers (a
   *   seeding torrent). Not persisted; `false` from servers that do not report it.
   */
  @Serializable
  @SerialName("completed")
  data class Completed(
    val outputPath: String,
    val totalBytes: Long? = null,
    val downloadTime: Duration? = null,
    val completedAt: Instant? = null,
    val seeding: Boolean = false,
  ) : DownloadState()

  /** Download failed with [error]. May be retried if the error is retryable. */
  @Serializable
  @SerialName("failed")
  data class Failed(val error: KetchError) : DownloadState()

  /** Download was explicitly canceled. */
  @Serializable
  @SerialName("canceled")
  data object Canceled : DownloadState()

  /** `true` when the task has reached a final state and cannot be resumed. */
  val isTerminal: Boolean
    get() = this is Completed || this is Failed || this is Canceled

  /** `true` when the task is actively using a download slot. */
  val isActive: Boolean
    get() = this is Downloading
}
