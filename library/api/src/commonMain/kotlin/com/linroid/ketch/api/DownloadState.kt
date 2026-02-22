package com.linroid.ketch.api

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Represents the lifecycle state of a download task.
 *
 * State transitions follow this general flow:
 * ```
 * Scheduled -> Queued -> Downloading -> Completed
 *                |            |
 *                v            v
 *             Canceled      Paused -> Downloading
 *                             |
 *                             v
 *                           Failed
 * ```
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

  /** Download paused by the user or preempted by the scheduler. */
  @Serializable
  @SerialName("paused")
  data class Paused(val progress: DownloadProgress) : DownloadState()

  /** Download finished successfully. [outputPath] is the resolved output location. */
  @Serializable
  @SerialName("completed")
  data class Completed(val outputPath: String) : DownloadState()

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
