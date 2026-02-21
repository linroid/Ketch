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
import com.linroid.ketch.api.log.KetchLogger
import com.linroid.ketch.core.engine.DownloadCoordinator
import com.linroid.ketch.core.engine.DownloadQueue
import com.linroid.ketch.core.engine.DownloadScheduler
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.time.Instant

internal class RealDownloadTask(
  override val taskId: String,
  override val request: DownloadRequest,
  override val createdAt: Instant,
  initialState: DownloadState,
  initialSegments: List<Segment>,
  private val coordinator: DownloadCoordinator,
  private val scheduler: DownloadQueue,
  private val scheduleManager: DownloadScheduler,
  private val removeAction: suspend () -> Unit,
) : DownloadTask {
  internal val mutableState = MutableStateFlow(initialState)
  internal val mutableSegments = MutableStateFlow(initialSegments)

  override val state: StateFlow<DownloadState> = mutableState.asStateFlow()
  override val segments: StateFlow<List<Segment>> = mutableSegments.asStateFlow()

  private val log = KetchLogger("DownloadTask")

  override suspend fun pause() {
    if (mutableState.value.isActive) {
      coordinator.pause(taskId)
    } else {
      log.w { "Ignoring pause for taskId=$taskId in state ${mutableState.value}" }
    }
  }

  override suspend fun resume(destination: Destination?) {
    val s = mutableState.value
    if (s is DownloadState.Paused || s is DownloadState.Failed) {
      val resumed = coordinator.resume(
        taskId, mutableState, mutableSegments, destination,
      )
      if (!resumed) {
        coordinator.startFromRecord(
          taskId, request, mutableState, mutableSegments,
        )
      }
    } else {
      log.w { "Ignoring resume for taskId=$taskId in state $s" }
    }
  }

  override suspend fun cancel() {
    val s = mutableState.value
    if (!s.isTerminal) {
      scheduleManager.cancel(taskId)
      scheduler.dequeue(taskId)
      coordinator.cancel(taskId)
      if (s is DownloadState.Scheduled) {
        mutableState.value = DownloadState.Canceled
      }
    } else {
      log.w { "Ignoring cancel for taskId=$taskId in state $s" }
    }
  }

  override suspend fun setSpeedLimit(limit: SpeedLimit) {
    coordinator.setTaskSpeedLimit(taskId, limit)
  }

  override suspend fun setPriority(priority: DownloadPriority) {
    scheduler.setPriority(taskId, priority)
  }

  override suspend fun setConnections(connections: Int) {
    coordinator.setTaskConnections(taskId, connections)
  }

  override suspend fun reschedule(
    schedule: DownloadSchedule,
    conditions: List<DownloadCondition>,
  ) {
    val s = mutableState.value
    if (s.isTerminal) {
      log.w { "Ignoring reschedule for taskId=$taskId in terminal state $s" }
      return
    }
    log.i { "Rescheduling taskId=$taskId, schedule=$schedule, conditions=${conditions.size}" }
    scheduleManager.cancel(taskId)
    if (s.isActive) {
      coordinator.pause(taskId)
    }
    scheduler.dequeue(taskId)
    scheduleManager.reschedule(
      taskId, request, schedule, conditions,
      createdAt, mutableState, mutableSegments,
    )
  }

  override suspend fun remove() {
    removeAction()
  }
}
