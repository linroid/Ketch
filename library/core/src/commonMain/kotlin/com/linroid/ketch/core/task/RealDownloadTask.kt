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
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.time.Clock
import kotlin.time.Instant

internal class RealDownloadTask(
  override val taskId: String,
  request: DownloadRequest,
  override val createdAt: Instant,
  initialState: DownloadState,
  initialSegments: List<Segment>,
  private val controller: TaskController,
  taskStore: TaskStore,
  record: TaskRecord,
) : DownloadTask, TaskHandle {
  private val settingsMutex = Mutex()
  private val mutableRequest = MutableStateFlow(request)
  override val requestState: StateFlow<DownloadRequest> = mutableRequest.asStateFlow()
  override val request: DownloadRequest get() = requestState.value

  override val mutableState = MutableStateFlow(initialState)
  override val mutableSegments = MutableStateFlow(initialSegments)

  override val state: StateFlow<DownloadState> = mutableState.asStateFlow()
  override val segments: StateFlow<List<Segment>> = mutableSegments.asStateFlow()

  override val record = AtomicSaver(record) {
    taskStore.save(it)
    mutableRequest.value = it.request
  }

  private val log = KetchLogger("DownloadTask")

  override suspend fun pause() {
    val s = mutableState.value
    if (s.isActive || s is DownloadState.Queued) {
      controller.pause(this)
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

  // Settings are persisted before they are applied, so an execution that starts in between
  // reads the new value, and tasks that are not running keep it for their next start.
  override suspend fun setSpeedLimit(limit: SpeedLimit): Unit = settingsMutex.withLock {
    record.update {
      it.copy(request = it.request.copy(speedLimit = limit), updatedAt = Clock.System.now())
    }
    controller.setSpeedLimit(taskId, limit)
  }

  override suspend fun setPriority(priority: DownloadPriority): Unit = settingsMutex.withLock {
    record.update {
      it.copy(request = it.request.copy(priority = priority), updatedAt = Clock.System.now())
    }
    controller.setPriority(taskId, priority)
  }

  override suspend fun setConnections(connections: Int) {
    require(connections > 0) { "Connections must be greater than 0" }
    settingsMutex.withLock {
      record.update {
        it.copy(
          request = it.request.copy(connections = connections),
          updatedAt = Clock.System.now(),
        )
      }
      controller.setConnections(taskId, connections)
    }
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

  override suspend fun remove(deleteFiles: Boolean) {
    controller.remove(this, deleteFiles)
  }
}
