package com.linroid.kdown.core.task

import com.linroid.kdown.api.DownloadCondition
import com.linroid.kdown.api.DownloadPriority
import com.linroid.kdown.api.DownloadRequest
import com.linroid.kdown.api.DownloadSchedule
import com.linroid.kdown.api.DownloadState
import com.linroid.kdown.api.DownloadTask
import com.linroid.kdown.api.Segment
import com.linroid.kdown.api.SpeedLimit
import kotlinx.coroutines.flow.StateFlow
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
  private val rescheduleAction: suspend (DownloadSchedule, List<DownloadCondition>) -> Unit,
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
    conditions: List<DownloadCondition>,
  ) {
    rescheduleAction(schedule, conditions)
  }

  override suspend fun remove() {
    removeAction()
  }
}
