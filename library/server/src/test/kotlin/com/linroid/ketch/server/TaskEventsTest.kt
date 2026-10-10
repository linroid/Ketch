package com.linroid.ketch.server

import com.linroid.ketch.api.Destination
import com.linroid.ketch.api.DownloadCondition
import com.linroid.ketch.api.DownloadPriority
import com.linroid.ketch.api.DownloadProgress
import com.linroid.ketch.api.DownloadRequest
import com.linroid.ketch.api.DownloadSchedule
import com.linroid.ketch.api.DownloadState
import com.linroid.ketch.api.DownloadTask
import com.linroid.ketch.api.ResolvedSource
import com.linroid.ketch.api.Segment
import com.linroid.ketch.api.SourceFile
import com.linroid.ketch.api.SpeedLimit
import com.linroid.ketch.endpoints.model.TaskEvent
import com.linroid.ketch.server.api.taskEvents
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
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

  @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
  @Test
  fun taskEvents_queuePositionChange_emitsStateChangedWithPosition() = runTest {
    val task = ObservableTask()
    task.queuePosition.value = 2
    val events = mutableListOf<TaskEvent>()
    backgroundScope.launch { taskEvents(task).collect { events.add(it) } }
    runCurrent()
    assertEquals(2, assertIs<TaskEvent.StateChanged>(events.single()).queuePosition)

    task.queuePosition.value = 1
    runCurrent()
    assertEquals(2, events.size)
    val event = assertIs<TaskEvent.StateChanged>(events.last())
    assertEquals(1, event.queuePosition)
    assertEquals(DownloadState.Queued, event.state)
  }

  @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
  @Test
  fun progress_unchangedRequest_isOmitted() = runTest {
    val task = ObservableTask()
    task.state.value = DownloadState.Downloading(DownloadProgress(0, 100))
    val events = mutableListOf<TaskEvent>()
    backgroundScope.launch { taskEvents(task).collect { events.add(it) } }
    runCurrent()
    // The first event of a stream always carries the request.
    assertEquals(task.request, assertIs<TaskEvent.Progress>(events.single()).request)

    task.state.value = DownloadState.Downloading(DownloadProgress(50, 100))
    runCurrent()
    assertNull(assertIs<TaskEvent.Progress>(events.last()).request)

    task.requestState.value = task.request.copy(speedLimit = SpeedLimit.of(2048))
    runCurrent()
    assertEquals(task.request, assertIs<TaskEvent.Progress>(events.last()).request)

    task.segments.value = listOf(Segment(0, 0, 99, 60))
    runCurrent()
    val progress = assertIs<TaskEvent.Progress>(events.last())
    assertNull(progress.request)
    assertEquals(task.segments.value, progress.segments)
  }

  @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
  @Test
  fun stateChanged_alwaysCarriesRequest() = runTest {
    val task = ObservableTask()
    task.state.value = DownloadState.Downloading(DownloadProgress(0, 100))
    val events = mutableListOf<TaskEvent>()
    backgroundScope.launch { taskEvents(task).collect { events.add(it) } }
    runCurrent()
    task.state.value = DownloadState.Downloading(DownloadProgress(10, 100))
    runCurrent()

    task.state.value = DownloadState.Paused(DownloadProgress(10, 100))
    runCurrent()
    assertEquals(task.request, assertIs<TaskEvent.StateChanged>(events.last()).request)
    task.queuePosition.value = 3
    runCurrent()
    assertEquals(task.request, assertIs<TaskEvent.StateChanged>(events.last()).request)
  }

  @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
  @Test
  fun snapshot_dropsMetainfoKeepsFiles() = runTest {
    val files = listOf(SourceFile("0", "a.bin", 10), SourceFile("1", "b.bin", 20))
    val resolved = ResolvedSource(
      url = "magnet:?xt=urn:btih:00", sourceType = "torrent", totalBytes = 30,
      supportsResume = true, suggestedFileName = "pack", maxSegments = 2,
      metadata = mapOf(
        ResolvedSource.METAINFO_KEY to "ZDQ6aW5mb2Vl", "infoHash" to "00",
        "big" to "x".repeat(5000),
      ),
      files = files,
    )
    val task = ObservableTask()
    task.requestState.value = task.request.copy(resolvedSource = resolved)

    val snapshot = TaskMapper.toSnapshot(task)
    val events = mutableListOf<TaskEvent>()
    backgroundScope.launch { taskEvents(task).collect { events.add(it) } }
    runCurrent()

    for (request in listOf(
      snapshot.request,
      assertNotNull(assertIs<TaskEvent.StateChanged>(events.single()).request),
    )) {
      val view = assertNotNull(request.resolvedSource)
      assertEquals(files, view.files)
      assertEquals(mapOf("infoHash" to "00"), view.metadata)
    }
    val wire = Json.encodeToString(snapshot)
    assertFalse("metainfo" in wire)
    // The task itself keeps everything.
    assertEquals(resolved, task.request.resolvedSource)
  }

  private class ObservableTask : DownloadTask {
    override val taskId = "task"
    override val requestState = MutableStateFlow(DownloadRequest("https://example.com/file"))
    override val request get() = requestState.value
    override val createdAt = Instant.fromEpochMilliseconds(0)
    override val state = MutableStateFlow<DownloadState>(DownloadState.Queued)
    override val segments = MutableStateFlow<List<Segment>>(emptyList())
    override val queuePosition = MutableStateFlow<Int?>(null)
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
