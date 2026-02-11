package com.linroid.kdown.api

/**
 * Represents the lifecycle state of a download task.
 *
 * State transitions follow this general flow:
 * ```
 * Idle -> Scheduled -> Queued -> Pending -> Downloading -> Completed
 *                                  |            |
 *                                  v            v
 *                               Canceled      Paused -> Downloading
 *                                               |
 *                                               v
 *                                             Failed
 * ```
 *
 * @see DownloadTask.state
 */
sealed class DownloadState {
  /** Initial state before the task has been submitted. */
  data object Idle : DownloadState()

  /** Waiting for a [DownloadSchedule] trigger or [DownloadCondition]s. */
  data class Scheduled(val schedule: DownloadSchedule) : DownloadState()

  /** Waiting in the download queue for an available slot. */
  data object Queued : DownloadState()

  /** Slot acquired; download is about to start. */
  data object Pending : DownloadState()

  /** Actively downloading. [progress] is updated periodically. */
  data class Downloading(val progress: DownloadProgress) : DownloadState()

  /** Download paused by the user or preempted by the scheduler. */
  data class Paused(val progress: DownloadProgress) : DownloadState()

  /** Download finished successfully. [filePath] is the output file. */
  data class Completed(val filePath: String) : DownloadState()

  /** Download failed with [error]. May be retried if the error is retryable. */
  data class Failed(val error: KDownError) : DownloadState()

  /** Download was explicitly canceled. */
  data object Canceled : DownloadState()

  /** `true` when the task has reached a final state and cannot be resumed. */
  val isTerminal: Boolean
    get() = this is Completed || this is Failed || this is Canceled

  /** `true` when the task is actively using a download slot. */
  val isActive: Boolean
    get() = this is Pending || this is Downloading
}
