package com.linroid.ketch.task

import com.linroid.ketch.api.Destination
import com.linroid.ketch.api.DownloadCondition
import com.linroid.ketch.api.DownloadProgress
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
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.time.Instant

class RealDownloadTaskTest {
  @Test
  fun setPriority_persistsAndPublishesBeforeUpdatingQueue() = runTest {
    val store = InMemoryTaskStore()
    val request = DownloadRequest(url = "https://example.com/file")
    val now = Instant.fromEpochMilliseconds(0)
    var queuePriority: DownloadPriority? = null
    val task = RealDownloadTask(
      taskId = "task",
      request = request,
      createdAt = now,
      initialState = DownloadState.Paused(DownloadProgress(0, 100)),
      initialSegments = emptyList(),
      controller = object : TaskController by UnusedController {
        override suspend fun setPriority(taskId: String, priority: DownloadPriority) {
          assertEquals(priority, store.load(taskId)?.request?.priority)
          queuePriority = priority
        }
      },
      taskStore = store,
      record = TaskRecord(
        taskId = "task",
        request = request,
        createdAt = now,
        updatedAt = now,
      ),
    )
    val changed = async { task.requestState.first { it.priority == DownloadPriority.URGENT } }

    task.setPriority(DownloadPriority.URGENT)

    assertEquals(DownloadPriority.URGENT, changed.await().priority)
    assertEquals(DownloadPriority.URGENT, task.request.priority)
    assertEquals(DownloadPriority.URGENT, queuePriority)
    assertEquals(DownloadState.Paused(DownloadProgress(0, 100)), task.state.value)
  }

  @Test
  fun setSpeedLimit_persistsAndPublishesBeforeApplying() = runTest {
    val store = InMemoryTaskStore()
    var applied: SpeedLimit? = null
    val task = pausedTask(store, object : TaskController by UnusedController {
      override suspend fun setSpeedLimit(taskId: String, limit: SpeedLimit) {
        assertEquals(limit, store.load(taskId)?.request?.speedLimit)
        applied = limit
      }
    })

    task.setSpeedLimit(SpeedLimit.of(4096))

    assertEquals(SpeedLimit.of(4096), task.requestState.value.speedLimit)
    assertEquals(SpeedLimit.of(4096), applied)
  }

  @Test
  fun setConnections_persistsAndPublishesBeforeApplying() = runTest {
    val store = InMemoryTaskStore()
    var applied: Int? = null
    val task = pausedTask(store, object : TaskController by UnusedController {
      override suspend fun setConnections(taskId: String, connections: Int) {
        assertEquals(connections, store.load(taskId)?.request?.connections)
        applied = connections
      }
    })

    task.setConnections(6)

    assertEquals(6, task.requestState.value.connections)
    assertEquals(6, applied)
  }

  @Test
  fun setConnections_zero_throwsWithoutPersisting() = runTest {
    val store = InMemoryTaskStore()
    val task = pausedTask(store, UnusedController)

    assertFailsWith<IllegalArgumentException> { task.setConnections(0) }

    assertNull(store.load(task.taskId))
    assertEquals(0, task.request.connections)
  }

  private fun pausedTask(store: InMemoryTaskStore, controller: TaskController): RealDownloadTask {
    val request = DownloadRequest(url = "https://example.com/file")
    val now = Instant.fromEpochMilliseconds(0)
    return RealDownloadTask(
      taskId = "task",
      request = request,
      createdAt = now,
      initialState = DownloadState.Paused(DownloadProgress(0, 100)),
      initialSegments = emptyList(),
      controller = controller,
      taskStore = store,
      record = TaskRecord(taskId = "task", request = request, createdAt = now, updatedAt = now),
    )
  }

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
    override suspend fun pause(handle: TaskHandle) = error("Unexpected call")
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
