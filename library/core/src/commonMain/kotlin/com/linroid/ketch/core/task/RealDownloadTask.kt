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
  private val controller: TaskController,
  taskStore: TaskStore,
  record: TaskRecord,
) : DownloadTask, TaskHandle {
  override val mutableState = MutableStateFlow(initialState)
  override val mutableSegments = MutableStateFlow(initialSegments)

  override val state: StateFlow<DownloadState> = mutableState.asStateFlow()
  override val segments: StateFlow<List<Segment>> = mutableSegments.asStateFlow()

  override val record = AtomicSaver(record) { taskStore.save(it) }

  private val log = KetchLogger("DownloadTask")

  override suspend fun pause() {
    if (mutableState.value.isActive) {
      controller.pause(taskId)
    } else {
      log.w { "Ignoring pause for taskId=$taskId in state ${mutableState.value}" }
    }
  }

  override suspend fun resume(destination: Destination?) {
    val s = mutableState.value
    if (s is DownloadState.Paused || s is DownloadState.Failed) {
      controller.resume(this, destination)
    } else {
      log.w { "Ignoring resume for taskId=$taskId in state $s" }
    }
  }

  override suspend fun cancel() {
    if (!mutableState.value.isTerminal) {
      controller.cancel(this)
    } else {
      log.w { "Ignoring cancel for taskId=$taskId in state ${mutableState.value}" }
    }
  }

  override suspend fun setSpeedLimit(limit: SpeedLimit) {
    controller.setSpeedLimit(taskId, limit)
  }

  override suspend fun setPriority(priority: DownloadPriority) {
    controller.setPriority(taskId, priority)
  }

  override suspend fun setConnections(connections: Int) {
    controller.setConnections(taskId, connections)
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
    controller.reschedule(this, schedule, conditions)
  }

  override suspend fun remove() {
    controller.remove(this)
  }
}
