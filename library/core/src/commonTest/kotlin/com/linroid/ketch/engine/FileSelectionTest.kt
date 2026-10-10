package com.linroid.ketch.engine

import com.linroid.ketch.api.Destination
import com.linroid.ketch.api.DownloadConfig
import com.linroid.ketch.api.DownloadRequest
import com.linroid.ketch.api.DownloadSchedule
import com.linroid.ketch.api.DownloadState
import com.linroid.ketch.api.PauseReason
import com.linroid.ketch.api.Segment
import com.linroid.ketch.core.Ketch
import com.linroid.ketch.core.KetchDispatchers
import com.linroid.ketch.core.engine.DownloadCoordinator
import com.linroid.ketch.core.engine.SelectionDelivery
import com.linroid.ketch.core.engine.SourceResolver
import com.linroid.ketch.core.file.DefaultFileNameResolver
import com.linroid.ketch.core.task.AtomicSaver
import com.linroid.ketch.core.task.TaskHandle
import com.linroid.ketch.core.task.TaskRecord
import com.linroid.ketch.core.task.TaskState
import com.linroid.ketch.core.task.TaskStore
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Duration.Companion.hours

class FileSelectionTest {
  private val folder = "/tmp/ketch-file-selection/"

  private fun request(vararg ids: String) = DownloadRequest(
    url = "pick:files",
    destination = Destination(folder),
    selectedFileIds = ids.toSet(),
  )

  private fun TestScope.ketch(
    source: SelectableSource,
    store: TaskStore = RecordingTaskStore(),
    config: DownloadConfig = DownloadConfig(retryCount = 0, saveIntervalMs = 60_000),
  ): Ketch {
    val dispatcher = StandardTestDispatcher(testScheduler)
    return Ketch(
      httpEngine = FakeHttpEngine(),
      taskStore = store,
      config = config,
      additionalSources = listOf(source),
      dispatchers = KetchDispatchers(dispatcher, dispatcher, dispatcher),
    )
  }

  @Test
  fun selectFiles_pausedTask_savesSelectionTotalSegmentsAndGenerationInOneWrite() = runTest {
    val source = SelectableSource()
    val store = RecordingTaskStore()
    val ketch = ketch(source, store)
    try {
      val task = ketch.download(request("0"))
      runCurrent()
      assertIs<DownloadState.Downloading>(task.state.value)
      task.pause()
      runCurrent()
      assertIs<DownloadState.Paused>(task.state.value)
      val writes = store.saves.size

      task.selectFiles(setOf("0", "1"))

      assertEquals(writes + 1, store.saves.size)
      val saved = store.saves.last()
      assertEquals(setOf("0", "1"), saved.request.selectedFileIds)
      assertEquals(300, saved.totalBytes)
      assertEquals(listOf(0, 1), saved.segments?.map { it.index })
      assertEquals(1, saved.control?.selectionGeneration)
      assertEquals(TaskState.PAUSED, saved.state)
      val paused = assertIs<DownloadState.Paused>(task.state.value)
      assertEquals(300, paused.progress.totalBytes)
      assertEquals(PauseReason.User, paused.reason)
      assertEquals(saved.segments, task.segments.value)
      assertEquals(setOf("0", "1"), task.request.selectedFileIds)
      assertEquals(1, source.runs.value.size)
    } finally {
      ketch.close()
    }
  }

  @Test
  fun selectFiles_runningTask_deliversWithoutRestart() = runTest {
    val source = SelectableSource()
    val ketch = ketch(source)
    try {
      val task = ketch.download(request("0"))
      runCurrent()

      task.selectFiles(setOf("0", "1"))
      runCurrent()

      val run = source.runs.value.single()
      assertEquals(listOf(setOf("0", "1")), run.applied.map { it.fileIds })
      assertEquals(1, run.context.acknowledgedSelection.value)
      val downloading = assertIs<DownloadState.Downloading>(task.state.value)
      assertEquals(300, downloading.progress.totalBytes)

      run.finish.complete(Unit)
      runCurrent()

      val completed = assertIs<DownloadState.Completed>(task.state.value)
      assertEquals(300, completed.totalBytes)
      assertEquals(1, source.runs.value.size)
    } finally {
      ketch.close()
    }
  }

  @Test
  fun selectFiles_runningTaskWithoutProgress_publishesTheNewTotal() = runTest {
    // A transfer with no peers reports no progress, so the change must show at once.
    val source = SelectableSource().apply { reportsOnChange = false }
    val ketch = ketch(source)
    try {
      val task = ketch.download(request("0", "1"))
      runCurrent()
      assertEquals(300, assertIs<DownloadState.Downloading>(task.state.value).progress.totalBytes)

      task.selectFiles(setOf("1"))
      runCurrent()

      val downloading = assertIs<DownloadState.Downloading>(task.state.value)
      assertEquals(source.totalOf(setOf("1")), downloading.progress.totalBytes)
      source.runs.value.single().finish.complete(Unit)
      runCurrent()
    } finally {
      ketch.close()
    }
  }

  @Test
  fun selectFiles_unacknowledgedAtFinish_reruns() = runTest {
    val source = SelectableSource().apply { collects = false }
    val ketch = ketch(source)
    try {
      val task = ketch.download(request("0"))
      runCurrent()
      task.selectFiles(setOf("0", "1"))
      runCurrent()
      val first = source.runs.value.single()
      assertEquals(0, first.context.acknowledgedSelection.value)

      first.finish.complete(Unit)
      runCurrent()

      // Not complete: the selection the source never saw runs through resume.
      assertIs<DownloadState.Downloading>(task.state.value)
      val second = source.runs.value.last()
      assertEquals(2, source.runs.value.size)
      assertTrue(second.resumed)
      assertEquals(1, second.initial.revision)
      assertEquals(setOf("0", "1"), second.initial.fileIds)

      second.finish.complete(Unit)
      runCurrent()

      assertEquals(300, assertIs<DownloadState.Completed>(task.state.value).totalBytes)
    } finally {
      ketch.close()
    }
  }

  @Test
  fun selectFiles_appliedButNotLive_reruns() = runTest {
    val source = SelectableSource().apply { live = false }
    val ketch = ketch(source)
    try {
      val task = ketch.download(request("0"))
      runCurrent()
      task.selectFiles(setOf("0", "1"))
      runCurrent()
      val first = source.runs.value.single()
      // Applied to a run that could no longer deliver it, so it was not acknowledged.
      assertEquals(listOf(setOf("0", "1")), first.applied.map { it.fileIds })

      first.finish.complete(Unit)
      runCurrent()

      assertEquals(2, source.runs.value.size)
      val second = source.runs.value.last()
      assertTrue(second.resumed)
      assertEquals(setOf("0", "1"), second.initial.fileIds)
      second.finish.complete(Unit)
      runCurrent()
      assertEquals(300, assertIs<DownloadState.Completed>(task.state.value).totalBytes)
    } finally {
      ketch.close()
    }
  }

  @Test
  fun selectFiles_finishingExecution_joinsThenLandsCompleted() = runTest {
    val source = SelectableSource()
    val ketch = ketch(source)
    try {
      val task = ketch.download(request("0", "1"))
      runCurrent()
      val gate = CompletableDeferred<Unit>()
      source.saveGate = gate
      source.runs.value.single().finish.complete(Unit)
      runCurrent()
      // The source returned; the execution is saving its final state before completing.
      assertIs<DownloadState.Downloading>(task.state.value)

      val selecting = async { task.selectFiles(setOf("0")) }
      runCurrent()
      assertFalse(selecting.isCompleted)

      gate.complete(Unit)
      runCurrent()

      assertTrue(selecting.isCompleted)
      selecting.await()
      val completed = assertIs<DownloadState.Completed>(task.state.value)
      assertEquals(100, completed.totalBytes)
      assertEquals(1, source.runs.value.size)
    } finally {
      ketch.close()
    }
  }

  @Test
  fun selectFiles_completedExpand_reopensIntoTheSameFolder() = runTest {
    val source = SelectableSource()
    val store = RecordingTaskStore()
    val ketch = ketch(source, store)
    try {
      val task = ketch.download(request("0"))
      runCurrent()
      source.runs.value.single().finish.complete(Unit)
      runCurrent()
      val first = assertIs<DownloadState.Completed>(task.state.value)
      assertNotNull(first.completedAt)

      task.selectFiles(setOf("0", "1"))

      assertEquals(DownloadState.Queued, task.state.value)
      runCurrent()
      val reopened = assertNotNull(store.load(task.taskId))
      assertEquals(TaskState.DOWNLOADING, reopened.state)
      assertNull(reopened.completedAt)
      assertEquals(first.outputPath, reopened.outputPath)
      val second = source.runs.value.last()
      assertTrue(second.resumed)
      assertEquals(first.outputPath, second.context.outputPath)
      assertEquals(setOf("0", "1"), second.initial.fileIds)
      assertIs<DownloadState.Downloading>(task.state.value)

      second.finish.complete(Unit)
      runCurrent()

      val completed = assertIs<DownloadState.Completed>(task.state.value)
      assertEquals(first.outputPath, completed.outputPath)
      assertEquals(300, completed.totalBytes)
      assertNotNull(completed.completedAt)
      assertNotNull(store.load(task.taskId)?.completedAt)
      assertEquals(1, store.load(task.taskId)?.control?.selectionGeneration)
    } finally {
      ketch.close()
    }
  }

  @Test
  fun selectFiles_completedShrink_staysCompletedWithNewTotal() = runTest {
    val source = SelectableSource()
    val store = RecordingTaskStore()
    val ketch = ketch(source, store)
    try {
      val task = ketch.download(request("0", "1"))
      runCurrent()
      source.runs.value.single().finish.complete(Unit)
      runCurrent()
      val first = assertIs<DownloadState.Completed>(task.state.value)

      task.selectFiles(setOf("1"))
      runCurrent()

      val completed = assertIs<DownloadState.Completed>(task.state.value)
      assertEquals(200, completed.totalBytes)
      assertEquals(first.completedAt, completed.completedAt)
      val record = assertNotNull(store.load(task.taskId))
      assertEquals(TaskState.COMPLETED, record.state)
      assertEquals(200, record.totalBytes)
      assertEquals(setOf("1"), record.request.selectedFileIds)
      assertEquals(1, source.runs.value.size)
    } finally {
      ketch.close()
    }
  }

  @Test
  fun selectFiles_unknownId_failsBeforeSaving() = runTest {
    val source = SelectableSource()
    val store = RecordingTaskStore()
    val ketch = ketch(source, store)
    try {
      val task = ketch.download(request("0"))
      runCurrent()
      task.pause()
      runCurrent()
      val writes = store.saves.size

      assertFailsWith<IllegalArgumentException> { task.selectFiles(setOf("0", "9")) }

      assertEquals(writes, store.saves.size)
      assertEquals(setOf("0"), task.request.selectedFileIds)
    } finally {
      ketch.close()
    }
  }

  @Test
  fun selectFiles_emptySet_isRejected() = runTest {
    val source = SelectableSource()
    val store = RecordingTaskStore()
    val ketch = ketch(source, store)
    try {
      val task = ketch.download(request("0"))
      val writes = store.saves.size

      assertFailsWith<IllegalArgumentException> { task.selectFiles(emptySet()) }
      assertFailsWith<IllegalArgumentException> { task.selectFiles(setOf("x".repeat(129))) }

      assertEquals(writes, store.saves.size)
    } finally {
      ketch.close()
    }
  }

  @Test
  fun selectFiles_canceledTask_failsWithIllegalState() = runTest {
    val source = SelectableSource()
    val ketch = ketch(source)
    try {
      val task = ketch.download(request("0"))
      runCurrent()
      task.cancel()
      runCurrent()

      assertFailsWith<IllegalStateException> { task.selectFiles(setOf("1")) }
    } finally {
      ketch.close()
    }
  }

  @Test
  fun selectFiles_httpTask_isUnsupported() = runTest {
    val ketch = ketch(SelectableSource())
    try {
      val task = ketch.download(
        DownloadRequest(
          url = "https://example.com/file.zip",
          schedule = DownloadSchedule.AtTime(Clock.System.now() + 1.hours),
        )
      )

      assertFailsWith<UnsupportedOperationException> { task.selectFiles(setOf("0")) }
    } finally {
      ketch.close()
    }
  }

  @Test
  fun selectFiles_filesUnknown_failsWithIllegalState() = runTest {
    val source = SelectableSource().apply { resolveGate = CompletableDeferred() }
    val store = RecordingTaskStore()
    val ketch = ketch(source, store)
    try {
      val task = ketch.download(request())
      runCurrent()
      // Holding a slot while its metadata is looked up.
      assertEquals(DownloadState.Queued, task.state.value)
      val writes = store.saves.size

      assertFailsWith<IllegalStateException> { task.selectFiles(setOf("0")) }

      assertEquals(writes, store.saves.size)
    } finally {
      ketch.close()
    }
  }

  @Test
  fun selectFiles_sameSelection_writesNothing() = runTest {
    val source = SelectableSource()
    val store = RecordingTaskStore()
    val ketch = ketch(source, store)
    try {
      val task = ketch.download(request("0", "1"))
      runCurrent()
      task.pause()
      runCurrent()
      val writes = store.saves.size

      task.selectFiles(setOf("1", "0"))

      assertEquals(writes, store.saves.size)
      assertNull(store.load(task.taskId)?.control)
    } finally {
      ketch.close()
    }
  }

  @Test
  fun selectFiles_concurrentStart_contextSeesTheSavedSelection() = runTest {
    val source = SelectableSource()
    val ketch = ketch(source)
    try {
      val client = source.resolved("pick:files", "client")
      val task = ketch.download(request("0").copy(resolvedSource = client))
      // The execution holds a slot but has not run yet.

      task.selectFiles(setOf("0", "2"))
      runCurrent()

      val run = source.runs.value.single()
      assertEquals(setOf("0", "2"), run.context.request.selectedFileIds)
      assertEquals(setOf("0", "2"), run.initial.fileIds)
      assertEquals(1, run.initial.revision)
      assertEquals(1, run.context.acknowledgedSelection.value)
      assertTrue(run.applied.isEmpty())
      val downloading = assertIs<DownloadState.Downloading>(task.state.value)
      assertEquals(400, downloading.progress.totalBytes)
    } finally {
      ketch.close()
    }
  }

  @Test
  fun periodicSave_concurrentSelection_neverRevertsTheRequest() = runTest {
    val source = SelectableSource()
    val store = RecordingTaskStore()
    val ketch = ketch(source, store, DownloadConfig(retryCount = 0, saveIntervalMs = 1_000))
    try {
      val task = ketch.download(request("0"))
      runCurrent()
      val gate = CompletableDeferred<Unit>()
      source.saveGate = gate
      advanceTimeBy(1_001)
      runCurrent()
      // A periodic save took its snapshot and waits for the source's state.

      task.selectFiles(setOf("0", "1"))
      gate.complete(Unit)
      advanceTimeBy(2_001)
      runCurrent()

      val record = assertNotNull(store.load(task.taskId))
      assertEquals(setOf("0", "1"), record.request.selectedFileIds)
      assertEquals(300, record.totalBytes)
      assertEquals(1, record.control?.selectionGeneration)
      assertTrue(store.saves.last().request.selectedFileIds == setOf("0", "1"))
    } finally {
      ketch.close()
    }
  }

  /**
   * DownloadTask.setPriority holds the control lock while the queue can resume the task, which
   * joins its stopping execution. That execution, stopped with a selection pending, must end
   * without the lock: its completion check gives up when cancelled, and its final save never
   * takes it.
   */
  @Test
  fun setPriority_whileSelectionPending_doesNotDeadlock() = runTest {
    val source = SelectableSource().apply { collects = false }
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
      val first = source.runs.value.single()
      // Saved, then delivered, as Ketch does.
      val delivery = handle.controlLock.withLock {
        handle.record.update {
          it.copy(request = it.request.copy(selectedFileIds = setOf("0", "1")), totalBytes = 300)
        }
        coordinator.deliverSelection(handle.taskId, setOf("0", "1"), 300)
      }
      assertEquals(SelectionDelivery.DELIVERED, delivery)

      val proceed = CompletableDeferred<Unit>()
      val holder = launch {
        handle.controlLock.withLock {
          proceed.await()
          coordinator.pause(handle.taskId)
          coordinator.resume(handle)
        }
      }
      runCurrent()
      // The source returns with the selection unacknowledged: the execution waits for the lock
      // to decide whether to run again.
      first.finish.complete(Unit)
      runCurrent()
      val gate = CompletableDeferred<Unit>()
      source.saveGate = gate
      proceed.complete(Unit)
      runCurrent()
      // Pausing cancelled the waiting execution, which now saves its final state.
      assertFalse(holder.isCompleted)

      gate.complete(Unit)
      runCurrent()

      assertTrue(holder.isCompleted)
      val second = source.runs.value.last()
      assertEquals(2, source.runs.value.size)
      assertTrue(second.resumed)
      assertEquals(setOf("0", "1"), second.context.request.selectedFileIds)
    } finally {
      coordinator.close()
    }
  }

  private fun handle(request: DownloadRequest): TaskHandle {
    val now = Clock.System.now()
    return object : TaskHandle {
      override val taskId = "deadlock"
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
