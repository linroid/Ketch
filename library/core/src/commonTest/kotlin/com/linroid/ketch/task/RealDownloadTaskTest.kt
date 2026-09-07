package com.linroid.ketch.task

import com.linroid.ketch.api.Destination
import com.linroid.ketch.api.DownloadCondition
import com.linroid.ketch.api.DownloadPriority
import com.linroid.ketch.api.DownloadRequest
import com.linroid.ketch.api.DownloadSchedule
import com.linroid.ketch.api.DownloadState
import com.linroid.ketch.api.SpeedLimit
import com.linroid.ketch.core.task.InMemoryTaskStore
import com.linroid.ketch.core.task.RealDownloadTask
import com.linroid.ketch.core.task.TaskController
import com.linroid.ketch.core.task.TaskHandle
import com.linroid.ketch.core.task.TaskRecord
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Instant

class RealDownloadTaskTest {
  @Test
  fun recordUpdate_settingsChange_publishesCurrentRequest() = runTest {
    val request = DownloadRequest(url = "https://example.com/file")
    val now = Instant.fromEpochMilliseconds(0)
    val task = RealDownloadTask(
      taskId = "task",
      request = request,
      createdAt = now,
      initialState = DownloadState.Queued,
      initialSegments = emptyList(),
      controller = UnusedController,
      taskStore = InMemoryTaskStore(),
      record = TaskRecord(
        taskId = "task",
        request = request,
        createdAt = now,
        updatedAt = now,
      ),
    )
    val changed = async { task.requestState.first { it != request } }
    val updated = request.copy(speedLimit = SpeedLimit.of(1024), connections = 2)

    task.record.update { it.copy(request = updated) }

    assertEquals(updated, changed.await())
    assertEquals(updated, task.request)
    assertEquals(DownloadState.Queued, task.state.value)
  }

  private object UnusedController : TaskController {
    override suspend fun pause(taskId: String) = error("Unexpected call")
    override suspend fun resume(handle: TaskHandle, destination: Destination?) =
      error("Unexpected call")
    override suspend fun cancel(handle: TaskHandle) = error("Unexpected call")
    override suspend fun remove(handle: TaskHandle, deleteFiles: Boolean) = error("Unexpected call")
    override suspend fun setSpeedLimit(taskId: String, limit: SpeedLimit) =
      error("Unexpected call")
    override suspend fun setConnections(taskId: String, connections: Int) =
      error("Unexpected call")
    override suspend fun setPriority(taskId: String, priority: DownloadPriority) =
      error("Unexpected call")
    override suspend fun reschedule(
      handle: TaskHandle,
      schedule: DownloadSchedule,
      conditions: List<DownloadCondition>,
    ) = error("Unexpected call")
  }
}
