package com.linroid.ketch.core.task

import com.linroid.ketch.api.Destination
import com.linroid.ketch.api.DownloadCondition
import com.linroid.ketch.api.DownloadPriority
import com.linroid.ketch.api.DownloadRequest
import com.linroid.ketch.api.DownloadSchedule
import com.linroid.ketch.api.DownloadState
import com.linroid.ketch.api.DownloadTask
import com.linroid.ketch.api.Segment
import com.linroid.ketch.api.SpeedLimit
import kotlinx.coroutines.flow.StateFlow
import kotlin.time.Instant

internal class DownloadTaskImpl(
  override val taskId: String,
  override val request: DownloadRequest,
  override val createdAt: Instant,
  override val state: StateFlow<DownloadState>,
  override val segments: StateFlow<List<Segment>>,
  private val pauseAction: suspend () -> Unit,
  private val resumeAction: suspend (Destination?) -> Unit,
  private val cancelAction: suspend () -> Unit,
  private val removeAction: suspend () -> Unit,
  private val setSpeedLimitAction: suspend (SpeedLimit) -> Unit,
  private val setPriorityAction: suspend (DownloadPriority) -> Unit,
  private val setConnectionsAction: suspend (Int) -> Unit,
  private val rescheduleAction: suspend (DownloadSchedule, List<DownloadCondition>) -> Unit,
) : DownloadTask {

  override suspend fun pause() {
    pauseAction()
  }

  override suspend fun resume(destination: Destination?) {
    resumeAction(destination)
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

  override suspend fun setConnections(connections: Int) {
    setConnectionsAction(connections)
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
