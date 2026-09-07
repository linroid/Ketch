package com.linroid.ketch.server

import com.linroid.ketch.api.Destination
import com.linroid.ketch.api.DownloadCondition
import com.linroid.ketch.api.DownloadPriority
import com.linroid.ketch.api.DownloadRequest
import com.linroid.ketch.api.DownloadSchedule
import com.linroid.ketch.api.DownloadState
import com.linroid.ketch.api.DownloadTask
import com.linroid.ketch.api.Segment
import com.linroid.ketch.api.SpeedLimit
import com.linroid.ketch.endpoints.model.TaskEvent
import com.linroid.ketch.server.api.taskEvents
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.time.Instant

class TaskEventsTest {
  @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
  @Test
  fun settingsAndSegmentsChange_withoutStateChange_emitsUpdatedEvents() = runTest {
    val task = ObservableTask()
    val events = mutableListOf<TaskEvent>()
    backgroundScope.launch { taskEvents(task).collect { events.add(it) } }
    runCurrent()
    assertEquals(1, events.size)

    task.requestState.value = task.request.copy(speedLimit = SpeedLimit.of(1024))
    runCurrent()
    assertEquals(2, events.size)
    assertEquals(task.request, assertIs<TaskEvent.StateChanged>(events.last()).request)

    task.segments.value = listOf(Segment(0, 0, 99, 100))
    runCurrent()
    assertEquals(3, events.size)
    val event = assertIs<TaskEvent.StateChanged>(events.last())
    assertEquals(task.segments.value, event.segments)
    assertEquals(DownloadState.Queued, event.state)
  }

  private class ObservableTask : DownloadTask {
    override val taskId = "task"
    override val requestState = MutableStateFlow(DownloadRequest("https://example.com/file"))
    override val request get() = requestState.value
    override val createdAt = Instant.fromEpochMilliseconds(0)
    override val state = MutableStateFlow<DownloadState>(DownloadState.Queued)
    override val segments = MutableStateFlow<List<Segment>>(emptyList())
    override suspend fun pause() = error("Unexpected call")
    override suspend fun resume(destination: Destination?) = error("Unexpected call")
    override suspend fun cancel() = error("Unexpected call")
    override suspend fun remove(deleteFiles: Boolean) = error("Unexpected call")
    override suspend fun setSpeedLimit(limit: SpeedLimit) = error("Unexpected call")
    override suspend fun setPriority(priority: DownloadPriority) = error("Unexpected call")
    override suspend fun setConnections(connections: Int) = error("Unexpected call")
    override suspend fun reschedule(
      schedule: DownloadSchedule,
      conditions: List<DownloadCondition>,
    ) = error("Unexpected call")
  }
}
