package com.linroid.kdown.task

import com.linroid.kdown.DownloadCondition
import com.linroid.kdown.DownloadPriority
import com.linroid.kdown.DownloadRequest
import com.linroid.kdown.DownloadSchedule
import com.linroid.kdown.DownloadState
import com.linroid.kdown.SpeedLimit
import com.linroid.kdown.error.KDownError
import com.linroid.kdown.segment.Segment
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.io.files.Path
import kotlin.time.Instant

internal class DownloadTaskImpl(
  override val taskId: String,
  override val request: DownloadRequest,
  override val createdAt: Instant,
  override val state: StateFlow<DownloadState>,
  override val segments: StateFlow<List<Segment>>,
  private val pauseAction: suspend () -> Unit,
  private val resumeAction: suspend () -> Unit,
  private val cancelAction: suspend () -> Unit,
  private val removeAction: suspend () -> Unit,
  private val setSpeedLimitAction: suspend (SpeedLimit) -> Unit,
  private val setPriorityAction: suspend (DownloadPriority) -> Unit,
  private val rescheduleAction: suspend (DownloadSchedule, List<DownloadCondition>) -> Unit
) : DownloadTask {

  override suspend fun pause() {
    pauseAction()
  }

  override suspend fun resume() {
    resumeAction()
  }

  override suspend fun cancel() {
    cancelAction()
  }

  override suspend fun setSpeedLimit(limit: SpeedLimit) {
    setSpeedLimitAction(limit)
  }

  override suspend fun setPriority(priority: DownloadPriority) {
    setPriorityAction(priority)
  }

  override suspend fun reschedule(
    schedule: DownloadSchedule,
    conditions: List<DownloadCondition>
  ) {
    rescheduleAction(schedule, conditions)
  }

  override suspend fun remove() {
    removeAction()
  }

  override suspend fun await(): Result<Path> {
    val finalState = state.first { it.isTerminal }
    return when (finalState) {
      is DownloadState.Completed -> Result.success(finalState.filePath)
      is DownloadState.Failed -> Result.failure(finalState.error)
      is DownloadState.Canceled -> Result.failure(KDownError.Canceled)
      else -> Result.failure(KDownError.Unknown(null))
    }
  }
}
