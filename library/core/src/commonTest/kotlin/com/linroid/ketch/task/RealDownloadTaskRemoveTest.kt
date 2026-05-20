package com.linroid.ketch.task

import com.linroid.ketch.api.Destination
import com.linroid.ketch.api.DownloadCondition
import com.linroid.ketch.api.DownloadPriority
import com.linroid.ketch.api.DownloadRequest
import com.linroid.ketch.api.DownloadSchedule
import com.linroid.ketch.api.DownloadState
import com.linroid.ketch.api.SpeedLimit
import com.linroid.ketch.core.task.RealDownloadTask
import com.linroid.ketch.core.task.TaskController
import com.linroid.ketch.core.task.TaskHandle
import com.linroid.ketch.core.task.TaskRecord
import com.linroid.ketch.core.task.TaskState
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Clock

class RealDownloadTaskRemoveTest {

  private class CapturingController : TaskController {
    var lastRemoveDeleteFiles: Boolean? = null

    override suspend fun pause(taskId: String) = Unit
    override suspend fun resume(handle: TaskHandle, destination: Destination?) = Unit
    override suspend fun cancel(handle: TaskHandle) = Unit
    override suspend fun remove(handle: TaskHandle, deleteFiles: Boolean) {
      lastRemoveDeleteFiles = deleteFiles
    }
    override suspend fun setSpeedLimit(taskId: String, limit: SpeedLimit) = Unit
    override suspend fun setConnections(taskId: String, connections: Int) = Unit
    override suspend fun setPriority(taskId: String, priority: DownloadPriority) = Unit
    override suspend fun reschedule(
      handle: TaskHandle,
      schedule: DownloadSchedule,
      conditions: List<DownloadCondition>,
    ) = Unit
  }

  private fun createTask(controller: TaskController): RealDownloadTask {
    val now = Clock.System.now()
    val request = DownloadRequest(
      url = "https://example.com/file.zip",
      destination = Destination("/tmp/"),
    )
    val record = TaskRecord(
      taskId = "task-1",
      request = request,
      state = TaskState.QUEUED,
      createdAt = now,
      updatedAt = now,
    )
    return RealDownloadTask(
      taskId = "task-1",
      request = request,
      createdAt = now,
      initialState = DownloadState.Queued,
      initialSegments = emptyList(),
      controller = controller,
      taskStore = NoopTaskStore,
      record = record,
    )
  }

  @Test
  fun remove_defaultsToFalse() = runTest {
    val controller = CapturingController()
    val task = createTask(controller)

    task.remove()

    assertEquals(false, controller.lastRemoveDeleteFiles)
  }

  @Test
  fun remove_withDeleteFilesTrue_forwardsFlag() = runTest {
    val controller = CapturingController()
    val task = createTask(controller)

    task.remove(deleteFiles = true)

    assertEquals(true, controller.lastRemoveDeleteFiles)
  }

  @Test
  fun remove_withDeleteFilesFalse_forwardsFlag() = runTest {
    val controller = CapturingController()
    val task = createTask(controller)

    task.remove(deleteFiles = false)

    assertEquals(false, controller.lastRemoveDeleteFiles)
  }

  private object NoopTaskStore : com.linroid.ketch.core.task.TaskStore {
    override suspend fun save(record: TaskRecord) = Unit
    override suspend fun load(taskId: String): TaskRecord? = null
    override suspend fun loadAll(): List<TaskRecord> = emptyList()
    override suspend fun remove(taskId: String) = Unit
  }
}
