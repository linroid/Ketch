package com.linroid.kdown.task

import com.linroid.kdown.DownloadRequest
import com.linroid.kdown.DownloadState
import com.linroid.kdown.SpeedLimit
import com.linroid.kdown.error.KDownError
import com.linroid.kdown.segment.Segment
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.io.files.Path
import kotlin.time.Instant

/**
 * Represents a download task with reactive state and control methods.
 *
 * @property taskId Unique identifier for this download task
 * @property request The download request configuration
 * @property createdAt Timestamp when the task was created
 * @property state Observable download state (Pending, Downloading, Paused, Completed, Failed, Canceled)
 * @property segments Observable list of download segments with their progress
 */
class DownloadTask internal constructor(
  val taskId: String,
  val request: DownloadRequest,
  val createdAt: Instant,
  val state: StateFlow<DownloadState>,
  val segments: StateFlow<List<Segment>>,
  private val pauseAction: suspend () -> Unit,
  private val resumeAction: suspend () -> Unit,
  private val cancelAction: suspend () -> Unit,
  private val removeAction: suspend () -> Unit,
  private val setSpeedLimitAction: suspend (SpeedLimit) -> Unit
) {
  suspend fun pause() {
    pauseAction()
  }

  suspend fun resume() {
    resumeAction()
  }

  suspend fun cancel() {
    cancelAction()
  }

  /**
   * Updates the speed limit for this download task.
   * Takes effect immediately on all active segments.
   *
   * @param limit the new speed limit, or [SpeedLimit.Unlimited] to remove
   */
  suspend fun setSpeedLimit(limit: SpeedLimit) {
    setSpeedLimitAction(limit)
  }

  /**
   * Cancels the download and removes it from the task store and tasks list.
   */
  suspend fun remove() {
    removeAction()
  }

  suspend fun await(): Result<Path> {
    val finalState = state.first { it.isTerminal }
    return when (finalState) {
      is DownloadState.Completed -> Result.success(finalState.filePath)
      is DownloadState.Failed -> Result.failure(finalState.error)
      is DownloadState.Canceled -> Result.failure(KDownError.Canceled)
      else -> Result.failure(KDownError.Unknown(null))
    }
  }
}
