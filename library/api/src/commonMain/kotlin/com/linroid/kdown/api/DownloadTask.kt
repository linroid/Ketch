package com.linroid.kdown.api

import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlin.time.Instant

/**
 * Represents a download task with reactive state and control methods.
 *
 * @property taskId Unique identifier for this download task
 * @property request The download request configuration
 * @property createdAt Timestamp when the task was created
 * @property state Observable download state
 * @property segments Observable list of download segments with their progress
 */
interface DownloadTask {
  val taskId: String
  val request: DownloadRequest
  val createdAt: Instant
  val state: StateFlow<DownloadState>
  val segments: StateFlow<List<Segment>>

  /** Pauses the download, preserving segment progress for later resume. */
  suspend fun pause()

  /** Resumes a paused or failed download from where it left off. */
  suspend fun resume()

  /** Cancels the download. This is a terminal action. */
  suspend fun cancel()

  /**
   * Updates the speed limit for this download task.
   * Takes effect immediately on all active segments.
   *
   * @param limit the new speed limit, or [SpeedLimit.Unlimited] to remove
   */
  suspend fun setSpeedLimit(limit: SpeedLimit)

  /**
   * Updates the queue priority for this download task.
   * If the task is currently queued, it may be re-ordered or promoted.
   *
   * @param priority the new priority level
   */
  suspend fun setPriority(priority: DownloadPriority)

  /**
   * Reschedules this download with a new schedule and optional conditions.
   * Active downloads are paused (preserving progress) before rescheduling.
   * Works from any non-terminal state.
   *
   * @param schedule the new schedule to apply
   * @param conditions optional conditions that must be met before starting
   * @throws KDownError if the task is in a terminal state
   */
  suspend fun reschedule(
    schedule: DownloadSchedule,
    conditions: List<DownloadCondition> = emptyList(),
  )

  /**
   * Cancels the download and removes it from the task store and tasks list.
   */
  suspend fun remove()

  /**
   * Suspends until the download reaches a terminal state.
   *
   * @return [Result.success] with the output file path on completion,
   *   or [Result.failure] with a [KDownError] on failure or cancellation
   */
  suspend fun await(): Result<String>
}

/**
 * Default [await] implementation for [DownloadTask].
 */
suspend fun DownloadTask.awaitDefault(): Result<String> {
  val finalState = state.first { it.isTerminal }
  return when (finalState) {
    is DownloadState.Completed -> Result.success(finalState.filePath)
    is DownloadState.Failed -> Result.failure(finalState.error)
    is DownloadState.Canceled -> Result.failure(KDownError.Canceled)
    else -> Result.failure(KDownError.Unknown(null))
  }
}
