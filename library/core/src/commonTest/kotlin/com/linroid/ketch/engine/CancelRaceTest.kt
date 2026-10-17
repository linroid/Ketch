package com.linroid.ketch.engine

import com.linroid.ketch.api.Destination
import com.linroid.ketch.api.DownloadConfig
import com.linroid.ketch.api.DownloadRequest
import com.linroid.ketch.api.DownloadState
import com.linroid.ketch.api.Segment
import com.linroid.ketch.core.Ketch
import com.linroid.ketch.core.KetchDispatchers
import com.linroid.ketch.core.engine.DownloadCoordinator
import com.linroid.ketch.core.engine.SourceResolver
import com.linroid.ketch.core.file.DefaultFileNameResolver
import com.linroid.ketch.core.task.AtomicSaver
import com.linroid.ketch.core.task.TaskHandle
import com.linroid.ketch.core.task.TaskRecord
import com.linroid.ketch.core.task.TaskState
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Clock

/**
 * A cancel or remove never waits for the queue, so it can land while the queue starts the task.
 * These tests hold a save of the start (the queue's or the coordinator's) open while the cancel
 * comes, then let it finish: the task must stay canceled, run no more and free its slot.
 */
class CancelRaceTest {
  private val folder = "/tmp/ketch-cancel-race/"

  private fun request(vararg ids: String) = DownloadRequest(
    url = "pick:files",
    destination = Destination(folder),
    selectedFileIds = ids.toSet(),
  )

  private fun TestScope.ketch(
    source: SelectableSource,
    store: RecordingTaskStore,
    maxConcurrent: Int = 4,
  ): Ketch {
    val dispatcher = StandardTestDispatcher(testScheduler)
    return Ketch(
      httpEngine = FakeHttpEngine(),
      taskStore = store,
      config = DownloadConfig(
        retryCount = 0,
        saveIntervalMs = 60_000,
        maxConcurrentDownloads = maxConcurrent,
      ),
      additionalSources = listOf(source),
      dispatchers = KetchDispatchers(dispatcher, dispatcher, dispatcher),
    )
  }

  /** Holds the first save of [store] that [matches] until [release] completes; [held] says so. */
  private class SaveGate(store: RecordingTaskStore, matches: (TaskRecord) -> Boolean) {
    val held = CompletableDeferred<Unit>()
    val release = CompletableDeferred<Unit>()

    init {
      store.onSave = { record ->
        if (matches(record) && !held.isCompleted) {
          held.complete(Unit)
          release.await()
        }
      }
    }
  }

  @Test
  fun resume_canceledWhileTheCoordinatorSavesItsStart_staysCanceled() = runTest {
    val source = SelectableSource()
    val store = RecordingTaskStore()
    val ketch = ketch(source, store)
    try {
      val task = ketch.download(request("0"))
      runCurrent()
      task.pause()
      runCurrent()
      assertIs<DownloadState.Paused>(task.state.value)
      // The resume's own save, which comes before its execution.
      val gate = SaveGate(store) { it.state == TaskState.DOWNLOADING }
      val resuming = launch { task.resume() }
      runCurrent()
      assertTrue(gate.held.isCompleted)

      val canceling = launch { task.cancel() }
      runCurrent()
      gate.release.complete(Unit)
      resuming.join()
      canceling.join()
      runCurrent()
      // An execution still running would report progress and complete.
      source.runs.value.forEach { it.finish.complete(Unit) }
      runCurrent()

      assertIs<DownloadState.Canceled>(task.state.value)
      assertEquals(TaskState.CANCELED, store.load(task.taskId)?.state)
    } finally {
      ketch.close()
    }
  }

  @Test
  fun resume_canceledWhileTheQueueSavesIt_staysCanceledAndFreesItsSlot() = runTest {
    val source = SelectableSource()
    val store = RecordingTaskStore()
    val ketch = ketch(source, store, maxConcurrent = 1)
    try {
      val task = ketch.download(request("0"))
      runCurrent()
      task.pause()
      runCurrent()
      // The queue's save as it takes the task.
      val gate = SaveGate(store) { it.state == TaskState.QUEUED }
      val resuming = launch { task.resume() }
      runCurrent()
      assertTrue(gate.held.isCompleted)

      val canceling = launch { task.cancel() }
      runCurrent()
      gate.release.complete(Unit)
      resuming.join()
      canceling.join()
      runCurrent()

      assertIs<DownloadState.Canceled>(task.state.value)
      assertEquals(TaskState.CANCELED, store.load(task.taskId)?.state)
      assertEquals(1, source.runs.value.size)

      // The only slot is free again.
      val next = ketch.download(request("1"))
      runCurrent()
      assertIs<DownloadState.Downloading>(next.state.value)
      assertEquals(next.taskId, source.runs.value.last().context.taskId)
    } finally {
      ketch.close()
    }
  }

  @Test
  fun resume_removedWhileTheCoordinatorSavesItsStart_leavesNoRecord() = runTest {
    val source = SelectableSource()
    val store = RecordingTaskStore()
    val ketch = ketch(source, store)
    try {
      val task = ketch.download(request("0"))
      runCurrent()
      task.pause()
      runCurrent()
      val gate = SaveGate(store) { it.state == TaskState.DOWNLOADING }
      val resuming = launch { task.resume() }
      runCurrent()
      assertTrue(gate.held.isCompleted)

      val removing = launch { task.remove(deleteFiles = false) }
      runCurrent()
      gate.release.complete(Unit)
      resuming.join()
      removing.join()
      runCurrent()
      // An execution still running would save its record again as it completes.
      source.runs.value.forEach { it.finish.complete(Unit) }
      runCurrent()

      assertTrue(ketch.tasks.value.none { it.taskId == task.taskId })
      assertNull(store.load(task.taskId))
    } finally {
      ketch.close()
    }
  }

  @Test
  fun pause_canceledWhileItSaves_staysCanceled() = runTest {
    val source = SelectableSource()
    val store = RecordingTaskStore()
    val ketch = ketch(source, store, maxConcurrent = 1)
    try {
      ketch.download(request("0"))
      val waiting = ketch.download(request("1"))
      runCurrent()
      assertIs<DownloadState.Queued>(waiting.state.value)
      val gate = SaveGate(store) { it.taskId == waiting.taskId && it.state == TaskState.PAUSED }
      val pausing = launch { waiting.pause() }
      runCurrent()
      assertTrue(gate.held.isCompleted)

      val canceling = launch { waiting.cancel() }
      runCurrent()
      gate.release.complete(Unit)
      pausing.join()
      canceling.join()
      runCurrent()

      assertIs<DownloadState.Canceled>(waiting.state.value)
      assertEquals(TaskState.CANCELED, store.load(waiting.taskId)?.state)
    } finally {
      ketch.close()
    }
  }

  @Test
  fun start_whileACancelStopsTheTask_isRefused() = runTest {
    val source = SelectableSource()
    val dispatcher = StandardTestDispatcher(testScheduler)
    val coordinator = DownloadCoordinator(
      SourceResolver(listOf(source)),
      { DownloadConfig(retryCount = 0, saveIntervalMs = 60_000) },
      DefaultFileNameResolver(),
      dispatchers = KetchDispatchers(dispatcher, dispatcher, dispatcher),
    )
    val handle = handle(request("0"))
    try {
      coordinator.start(handle)
      runCurrent()
      assertEquals(1, source.runs.value.size)
      // The stopping execution's final save waits, so the cancel is still stopping it.
      val gate = CompletableDeferred<Unit>()
      source.saveGate = gate
      val canceling = launch { coordinator.cancel(handle, deletePartialFile = true) }
      runCurrent()
      assertTrue(canceling.isActive)

      coordinator.start(handle)
      runCurrent()
      gate.complete(Unit)
      canceling.join()
      runCurrent()

      assertEquals(1, source.runs.value.size)
      assertIs<DownloadState.Canceled>(handle.mutableState.value)
      assertEquals(TaskState.CANCELED, handle.record.value.state)
    } finally {
      coordinator.close()
    }
  }

  private fun handle(request: DownloadRequest): TaskHandle {
    val now = Clock.System.now()
    return object : TaskHandle {
      override val taskId = "canceling"
      override val request get() = record.value.request
      override val createdAt = now
      override val mutableState = MutableStateFlow<DownloadState>(DownloadState.Queued)
      override val mutableSegments = MutableStateFlow<List<Segment>>(emptyList())
      override val mutableQueuePosition = MutableStateFlow<Int?>(null)
      override val controlLock = Mutex()
      override val record = AtomicSaver(
        TaskRecord(taskId, request, state = TaskState.QUEUED, createdAt = now, updatedAt = now)
      ) {}
    }
  }
}
