package com.linroid.ketch.engine

import com.linroid.ketch.api.Destination
import com.linroid.ketch.api.DownloadConfig
import com.linroid.ketch.api.DownloadRequest
import com.linroid.ketch.api.DownloadSchedule
import com.linroid.ketch.api.DownloadState
import com.linroid.ketch.api.DownloadTask
import com.linroid.ketch.api.KetchError
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
import kotlinx.coroutines.test.UnconfinedTestDispatcher
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
  fun selectFiles_runningTaskAfterASave_plansFromTheLatestProgress() = runTest {
    val source = SelectableSource().apply { reportsOnChange = false }
    val store = RecordingTaskStore()
    val ketch = ketch(source, store, DownloadConfig(retryCount = 0, saveIntervalMs = 1_000))
    try {
      val task = ketch.download(request("0", "1"))
      runCurrent()
      val context = source.runs.value.single().context
      context.segments.value = listOf(Segment(0, 0, 99, 30), Segment(1, 100, 299))
      context.onProgress(30, 300)
      advanceTimeBy(1_001)
      runCurrent()
      assertEquals(30, store.saves.last().segments?.sumOf { it.downloadedBytes })
      // The download moved on since the record was saved.
      context.segments.value = listOf(Segment(0, 0, 99, 80), Segment(1, 100, 299, 50))
      context.onProgress(130, 300)

      task.selectFiles(setOf("0", "1", "2"))
      runCurrent()

      val downloading = assertIs<DownloadState.Downloading>(task.state.value)
      assertEquals(130, downloading.progress.downloadedBytes)
      assertEquals(600, downloading.progress.totalBytes)
      val saved = assertNotNull(store.load(task.taskId))
      assertEquals(listOf(80L, 50L, 0L), saved.segments?.map { it.downloadedBytes })
      source.runs.value.single().finish.complete(Unit)
      runCurrent()
    } finally {
      ketch.close()
    }
  }

  @Test
  fun selectFiles_runningTask_showsTheNewProgressBeforeTheNewRequest() = runTest {
    // Clients follow a task's request and its state apart: the request must not come first, with
    // the old selection's progress.
    val source = SelectableSource().apply { reportsOnChange = false }
    val store = RecordingTaskStore()
    val ketch = ketch(source, store)
    try {
      val task = ketch.download(request("0", "1"))
      runCurrent()
      val run = source.runs.value.single()
      run.context.segments.value = listOf(Segment(0, 0, 99, 80), Segment(1, 100, 299, 50))
      run.context.onProgress(130, 300)
      var shown: DownloadState? = null
      store.onSave = { if (it.request.selectedFileIds.size == 3) shown = task.state.value }

      task.selectFiles(setOf("0", "1", "2"))
      runCurrent()

      val downloading = assertIs<DownloadState.Downloading>(shown)
      assertEquals(130, downloading.progress.downloadedBytes)
      assertEquals(600, downloading.progress.totalBytes)
      run.finish.complete(Unit)
      runCurrent()
    } finally {
      ketch.close()
    }
  }

  @Test
  fun selectFiles_runningTaskReportsTheOldFilesDuringTheSave_showsTheNewProgress() = runTest {
    val source = SelectableSource().apply { reportsOnChange = false }
    val store = RecordingTaskStore()
    val ketch = ketch(source, store)
    try {
      val task = ketch.download(request("0", "1"))
      runCurrent()
      val run = source.runs.value.single()
      run.context.segments.value = listOf(Segment(0, 0, 99, 80), Segment(1, 100, 299, 50))
      run.context.onProgress(130, 300)
      var published: DownloadState? = null
      store.onSave = {
        if (it.request.selectedFileIds.size == 3) {
          // The transfer has not seen the new selection yet; the request is published next.
          run.context.onProgress(130, 300)
          published = task.state.value
        }
      }

      task.selectFiles(setOf("0", "1", "2"))
      runCurrent()

      assertEquals(600, assertIs<DownloadState.Downloading>(published).progress.totalBytes)
      assertEquals(600, assertIs<DownloadState.Downloading>(task.state.value).progress.totalBytes)
      // Delivered, the selection is what the transfer reports from then on.
      run.context.onProgress(140, 600)
      val reported = assertIs<DownloadState.Downloading>(task.state.value)
      assertEquals(140, reported.progress.downloadedBytes)
      store.onSave = {}
      run.finish.complete(Unit)
      runCurrent()
    } finally {
      ketch.close()
    }
  }

  @Test
  fun selectFiles_runningTaskSaveFails_showsTheOldProgressAgain() = runTest {
    val source = SelectableSource().apply { reportsOnChange = false }
    val store = RecordingTaskStore()
    val ketch = ketch(source, store)
    try {
      val task = ketch.download(request("0", "1"))
      runCurrent()
      val run = source.runs.value.single()
      run.context.segments.value = listOf(Segment(0, 0, 99, 80), Segment(1, 100, 299, 50))
      run.context.onProgress(130, 300)
      store.onSave = { check(it.request.selectedFileIds.size != 3) { "Disk full" } }

      assertFailsWith<IllegalStateException> { task.selectFiles(setOf("0", "1", "2")) }
      runCurrent()

      val downloading = assertIs<DownloadState.Downloading>(task.state.value)
      assertEquals(130, downloading.progress.downloadedBytes)
      assertEquals(300, downloading.progress.totalBytes)
      assertEquals(setOf("0", "1"), task.request.selectedFileIds)
      assertTrue(run.applied.isEmpty())
      // The transfer's reports show again.
      run.context.onProgress(140, 300)
      val reported = assertIs<DownloadState.Downloading>(task.state.value)
      assertEquals(140, reported.progress.downloadedBytes)
      store.onSave = {}
      run.finish.complete(Unit)
      runCurrent()
    } finally {
      ketch.close()
    }
  }

  @Test
  fun selectFiles_startedTaskFirstReportsDuringTheSave_showsThePlanDownloading() = runTest {
    val source = SelectableSource().apply {
      reportsOnStart = false
      reportsOnChange = false
    }
    val store = RecordingTaskStore()
    val ketch = ketch(source, store)
    try {
      val task = ketch.download(request("0", "1"))
      runCurrent()
      val run = source.runs.value.single()
      assertEquals(DownloadState.Queued, task.state.value)
      run.context.segments.value = listOf(Segment(0, 0, 99, 80), Segment(1, 100, 299, 50))
      store.onSave = {
        // The run's only report, as a torrent waiting for an engine slot sends.
        if (it.request.selectedFileIds.size == 3) run.context.onProgress(130, 300)
      }

      task.selectFiles(setOf("0", "1", "2"))

      val downloading = assertIs<DownloadState.Downloading>(task.state.value)
      assertEquals(130, downloading.progress.downloadedBytes)
      assertEquals(600, downloading.progress.totalBytes)
      store.onSave = {}
      run.finish.complete(Unit)
      runCurrent()
    } finally {
      ketch.close()
    }
  }

  @Test
  fun selectFiles_startedTaskFirstReportsDuringAFailedSave_showsTheReport() = runTest {
    val source = SelectableSource().apply {
      reportsOnStart = false
      reportsOnChange = false
    }
    val store = RecordingTaskStore()
    val ketch = ketch(source, store)
    try {
      val task = ketch.download(request("0", "1"))
      runCurrent()
      val run = source.runs.value.single()
      assertEquals(DownloadState.Queued, task.state.value)
      store.onSave = {
        if (it.request.selectedFileIds.size == 3) {
          run.context.onProgress(130, 300)
          error("Disk full")
        }
      }

      assertFailsWith<IllegalStateException> { task.selectFiles(setOf("0", "1", "2")) }

      val downloading = assertIs<DownloadState.Downloading>(task.state.value)
      assertEquals(130, downloading.progress.downloadedBytes)
      assertEquals(300, downloading.progress.totalBytes)
      store.onSave = {}
      run.finish.complete(Unit)
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

  /**
   * What a client following [task]'s request and state apart sees, as one frame per value either
   * takes: the latest request's selection with the latest state.
   */
  private fun TestScope.follow(task: DownloadTask): List<Pair<Set<String>, DownloadState>> {
    val frames = mutableListOf<Pair<Set<String>, DownloadState>>()
    var selection = task.request.selectedFileIds
    var state = task.state.value
    val dispatcher = UnconfinedTestDispatcher(testScheduler)
    backgroundScope.launch(dispatcher) {
      task.requestState.collect {
        selection = it.selectedFileIds
        frames += selection to state
      }
    }
    backgroundScope.launch(dispatcher) {
      task.state.collect {
        state = it
        frames += selection to state
      }
    }
    return frames
  }

  /** Every state [task] shows from now on, in order. */
  private fun TestScope.statesOf(task: DownloadTask): List<DownloadState> {
    val states = mutableListOf<DownloadState>()
    backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
      task.state.collect { states += it }
    }
    return states
  }

  /** The size [state] shows, if it shows one. */
  private fun sizeOf(state: DownloadState): Long? = when (state) {
    is DownloadState.Completed -> state.totalBytes
    is DownloadState.Downloading -> state.progress.totalBytes
    is DownloadState.Paused -> state.progress.totalBytes
    else -> null
  }

  @Test
  fun selectFiles_completedShrink_neverShowsTheNewFilesWithTheOldSize() = runTest {
    val source = SelectableSource()
    val ketch = ketch(source)
    try {
      val task = ketch.download(request("0", "1"))
      runCurrent()
      source.runs.value.single().finish.complete(Unit)
      runCurrent()
      assertEquals(300, assertIs<DownloadState.Completed>(task.state.value).totalBytes)
      val frames = follow(task)

      task.selectFiles(setOf("1"))
      runCurrent()

      assertEquals(setOf("1") to 200L, frames.last().let { (ids, state) -> ids to sizeOf(state) })
      assertTrue(frames.none { (ids, state) -> ids == setOf("1") && sizeOf(state) == 300L })
    } finally {
      ketch.close()
    }
  }

  @Test
  fun selectFiles_completedExpand_neverShowsTheNewFilesWithTheOldSize() = runTest {
    val source = SelectableSource()
    val ketch = ketch(source)
    try {
      val task = ketch.download(request("0"))
      runCurrent()
      source.runs.value.single().finish.complete(Unit)
      runCurrent()
      assertEquals(100, assertIs<DownloadState.Completed>(task.state.value).totalBytes)
      val frames = follow(task)

      task.selectFiles(setOf("0", "1"))
      runCurrent()

      assertEquals(setOf("0", "1") to 300L,
        frames.last().let { (ids, state) -> ids to sizeOf(state) })
      assertTrue(frames.none { (ids, state) -> ids == setOf("0", "1") && sizeOf(state) == 100L })
      source.runs.value.last().finish.complete(Unit)
      runCurrent()
    } finally {
      ketch.close()
    }
  }

  @Test
  fun selectFiles_pausedTask_neverShowsTheNewFilesWithTheOldSize() = runTest {
    val source = SelectableSource()
    val ketch = ketch(source)
    try {
      val task = ketch.download(request("0"))
      runCurrent()
      task.pause()
      runCurrent()
      assertEquals(100, assertIs<DownloadState.Paused>(task.state.value).progress.totalBytes)
      val frames = follow(task)

      task.selectFiles(setOf("0", "1"))
      runCurrent()

      assertEquals(setOf("0", "1") to 300L,
        frames.last().let { (ids, state) -> ids to sizeOf(state) })
      assertTrue(frames.none { (ids, state) -> ids == setOf("0", "1") && sizeOf(state) == 100L })
    } finally {
      ketch.close()
    }
  }

  @Test
  fun selectFiles_pausedTaskSaveFails_showsTheOldProgressAgain() = runTest {
    val source = SelectableSource()
    val store = RecordingTaskStore()
    val ketch = ketch(source, store)
    try {
      val task = ketch.download(request("0"))
      runCurrent()
      task.pause()
      runCurrent()
      val paused = assertIs<DownloadState.Paused>(task.state.value)
      val segments = task.segments.value
      store.onSave = { check(it.request.selectedFileIds.size != 2) { "Disk full" } }

      assertFailsWith<IllegalStateException> { task.selectFiles(setOf("0", "1")) }

      assertEquals(paused, task.state.value)
      assertEquals(segments, task.segments.value)
      assertEquals(setOf("0"), task.request.selectedFileIds)
    } finally {
      ketch.close()
    }
  }

  @Test
  fun selectFiles_completedExpandSaveFails_staysCompleted() = runTest {
    val source = SelectableSource()
    val store = RecordingTaskStore()
    val ketch = ketch(source, store)
    try {
      val task = ketch.download(request("0"))
      runCurrent()
      source.runs.value.single().finish.complete(Unit)
      runCurrent()
      val completed = assertIs<DownloadState.Completed>(task.state.value)
      val states = statesOf(task)
      store.onSave = { check(it.request.selectedFileIds.size != 2) { "Disk full" } }

      assertFailsWith<IllegalStateException> { task.selectFiles(setOf("0", "1")) }
      runCurrent()

      assertEquals(completed, task.state.value)
      // Never queued then completed again, which followers such as hooks take for a new finish.
      assertTrue(states.all { it is DownloadState.Completed }, "$states")
      assertEquals(TaskState.COMPLETED, store.load(task.taskId)?.state)
      assertEquals(1, source.runs.value.size)
    } finally {
      ketch.close()
    }
  }

  @Test
  fun selectFiles_awaitingTaskSaveFails_keepsWaitingThroughout() = runTest {
    val source = SelectableSource()
    val store = RecordingTaskStore()
    val ketch = ketch(source, store)
    try {
      val task = ketch.download(request().copy(awaitFileSelection = true))
      runCurrent()
      val waiting = assertIs<DownloadState.Paused>(task.state.value)
      assertEquals(PauseReason.AwaitingFileSelection, waiting.reason)
      val states = statesOf(task)
      store.onSave = { check(it.request.selectedFileIds.isEmpty()) { "Disk full" } }

      assertFailsWith<IllegalStateException> { task.selectFiles(setOf("1")) }
      runCurrent()

      assertEquals(waiting, task.state.value)
      assertTrue(
        states.all { it is DownloadState.Paused && it.reason == PauseReason.AwaitingFileSelection },
        "$states",
      )
      assertTrue(source.runs.value.isEmpty())
    } finally {
      ketch.close()
    }
  }

  @Test
  fun selectFiles_completedExpand_ignoresCancelWhileSaving() = runTest {
    val source = SelectableSource()
    val store = RecordingTaskStore()
    val ketch = ketch(source, store)
    try {
      val task = ketch.download(request("0"))
      runCurrent()
      source.runs.value.single().finish.complete(Unit)
      runCurrent()
      val states = statesOf(task)
      val saving = CompletableDeferred<Unit>()
      val release = CompletableDeferred<Unit>()
      store.onSave = {
        if (it.request.selectedFileIds.size == 2 && !release.isCompleted) {
          saving.complete(Unit)
          release.await()
        }
      }
      val selecting = launch { task.selectFiles(setOf("0", "1")) }
      runCurrent()
      assertTrue(saving.isCompleted)

      val canceling = launch { task.cancel() }
      runCurrent()
      release.complete(Unit)
      selecting.join()
      canceling.join()
      runCurrent()

      // A completed task ignores cancel, also while its reopening selection is saved.
      assertTrue(states.none { it is DownloadState.Canceled }, "$states")
      assertIs<DownloadState.Downloading>(task.state.value)
      assertEquals(2, source.runs.value.size)
      source.runs.value.last().finish.complete(Unit)
      runCurrent()
    } finally {
      ketch.close()
    }
  }

  @Test
  fun selectFiles_awaitingTaskCanceledWhileSaving_staysCanceled() = runTest {
    val source = SelectableSource()
    val store = RecordingTaskStore()
    val ketch = ketch(source, store)
    try {
      val task = ketch.download(request().copy(awaitFileSelection = true))
      runCurrent()
      val waiting = assertIs<DownloadState.Paused>(task.state.value)
      assertEquals(PauseReason.AwaitingFileSelection, waiting.reason)
      val saving = CompletableDeferred<Unit>()
      val release = CompletableDeferred<Unit>()
      store.onSave = {
        if (it.request.selectedFileIds.isNotEmpty() && !release.isCompleted) {
          saving.complete(Unit)
          release.await()
        }
      }
      val selecting = launch { task.selectFiles(setOf("1")) }
      runCurrent()
      assertTrue(saving.isCompleted)

      val canceling = launch { task.cancel() }
      runCurrent()
      release.complete(Unit)
      selecting.join()
      canceling.join()
      runCurrent()

      assertIs<DownloadState.Canceled>(task.state.value)
      assertEquals(TaskState.CANCELED, store.load(task.taskId)?.state)
      assertTrue(source.runs.value.isEmpty())
    } finally {
      ketch.close()
    }
  }

  @Test
  fun selectFiles_awaitingTaskCanceledWhilePlanning_staysCanceled() = runTest {
    val source = SelectableSource()
    val store = RecordingTaskStore()
    val ketch = ketch(source, store)
    try {
      val task = ketch.download(request().copy(awaitFileSelection = true))
      runCurrent()
      assertIs<DownloadState.Paused>(task.state.value)
      val gate = CompletableDeferred<Unit>()
      source.planGate = gate
      val selecting = launch { task.selectFiles(setOf("1")) }
      runCurrent()

      task.cancel()
      runCurrent()
      gate.complete(Unit)
      selecting.join()
      runCurrent()

      assertIs<DownloadState.Canceled>(task.state.value)
      // Saved after the cancel, the selection keeps the record canceled, so no restart starts it.
      assertEquals(TaskState.CANCELED, store.load(task.taskId)?.state)
      assertTrue(source.runs.value.isEmpty())
    } finally {
      ketch.close()
    }
  }

  @Test
  fun selectFiles_completedExpandRemovedWhileSaving_staysRemoved() = runTest {
    val source = SelectableSource()
    val store = RecordingTaskStore()
    val ketch = ketch(source, store)
    try {
      val task = ketch.download(request("0"))
      runCurrent()
      source.runs.value.single().finish.complete(Unit)
      runCurrent()
      assertIs<DownloadState.Completed>(task.state.value)
      val saving = CompletableDeferred<Unit>()
      val release = CompletableDeferred<Unit>()
      store.onSave = {
        if (it.request.selectedFileIds.size == 2 && !release.isCompleted) {
          saving.complete(Unit)
          release.await()
        }
      }
      val selecting = launch { task.selectFiles(setOf("0", "1")) }
      runCurrent()
      assertTrue(saving.isCompleted)

      val removing = launch { task.remove(deleteFiles = true) }
      runCurrent()
      release.complete(Unit)
      selecting.join()
      removing.join()
      runCurrent()

      assertTrue(ketch.tasks.value.none { it.taskId == task.taskId })
      assertNull(store.load(task.taskId))
      assertEquals(1, source.runs.value.size)
    } finally {
      ketch.close()
    }
  }

  @Test
  fun download_fileItsResolutionDoesNotList_isRefusedNamingIt() = runTest {
    val source = SelectableSource()
    val store = RecordingTaskStore()
    val ketch = ketch(source, store)
    try {
      val resolved = source.resolved("pick:files", "client")

      val error = assertFailsWith<IllegalArgumentException> {
        ketch.download(request("0", "7").copy(resolvedSource = resolved))
      }

      assertEquals("Unknown file ID: 7", error.message)
      assertTrue(ketch.tasks.value.isEmpty())
      assertTrue(store.saves.isEmpty())
    } finally {
      ketch.close()
    }
  }

  @Test
  fun download_resolutionListsNoFiles_isRefusedNamingTheSelection() = runTest {
    val source = SelectableSource()
    val store = RecordingTaskStore()
    val ketch = ketch(source, store)
    try {
      // An ordinary file: its resolution lists no files to choose.
      val resolved = source.resolved("pick:files", "client").copy(files = emptyList())

      val error = assertFailsWith<IllegalArgumentException> {
        ketch.download(request("0").copy(resolvedSource = resolved))
      }

      assertEquals("Unknown file ID: 0", error.message)
      assertTrue(ketch.tasks.value.isEmpty())
      assertTrue(store.saves.isEmpty())
    } finally {
      ketch.close()
    }
  }

  @Test
  fun freshStart_unknownFile_failsWithASourceErrorNamingIt() = runTest {
    val source = SelectableSource()
    val ketch = ketch(source)
    try {
      val task = ketch.download(request("0", "7"))
      runCurrent()

      val failed = assertIs<DownloadState.Failed>(task.state.value)
      val error = assertIs<KetchError.SourceError>(failed.error)
      assertEquals(SelectableSource.TYPE, error.sourceType)
      assertEquals("Unknown file ID: 7", error.detail)
      assertTrue(source.runs.value.isEmpty())
    } finally {
      ketch.close()
    }
  }

  @Test
  fun freshStart_sourceRefusesAListedFile_failsWithItsReason() = runTest {
    val source = SelectableSource()
    val ketch = ketch(source)
    try {
      // The client's resolution lists a fourth file, which the source does not have.
      val listed = SelectableSource(sizes = listOf(100, 200, 300, 400))
        .resolved("pick:files", "client")
      val task = ketch.download(request("3").copy(resolvedSource = listed))
      runCurrent()

      val failed = assertIs<DownloadState.Failed>(task.state.value)
      val error = assertIs<KetchError.SourceError>(failed.error)
      assertEquals("Unknown file id", error.detail)
      assertTrue(source.runs.value.isEmpty())
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
  fun selectFiles_queueStartsTheTaskWhilePlanning_runsTheSavedSelection() = runTest {
    val source = SelectableSource()
    val ketch = ketch(
      source,
      config = DownloadConfig(retryCount = 0, saveIntervalMs = 60_000, maxConcurrentDownloads = 1),
    )
    try {
      val other = ketch.download(request("0"))
      runCurrent()
      val client = source.resolved("pick:files", "client")
      val task = ketch.download(request("0", "1").copy(resolvedSource = client))
      runCurrent()
      assertEquals(DownloadState.Queued, task.state.value)
      val gate = CompletableDeferred<Unit>()
      source.planGate = gate
      val selecting = async { task.selectFiles(setOf("0", "1", "2")) }
      runCurrent()

      // The slot frees while the selection is planned: the queue starts the task, whose
      // execution is built from the request it had then.
      source.runs.value.single().finish.complete(Unit)
      runCurrent()
      assertIs<DownloadState.Completed>(other.state.value)
      gate.complete(Unit)
      runCurrent()
      selecting.await()

      val run = source.runs.value.last()
      assertEquals(2, source.runs.value.size)
      val followed = (listOf(run.initial) + run.applied).last()
      assertEquals(setOf("0", "1", "2"), followed.fileIds)
      run.finish.complete(Unit)
      runCurrent()
      assertEquals(600, assertIs<DownloadState.Completed>(task.state.value).totalBytes)
      assertEquals(2, source.runs.value.size)
    } finally {
      ketch.close()
    }
  }

  @Test
  fun selectFiles_cancelledWhileTheCoordinatorIsBusy_deliversTheSavedSelection() = runTest {
    val source = SelectableSource()
    val store = RecordingTaskStore()
    val ketch = ketch(source, store)
    try {
      val task = ketch.download(request("0", "1"))
      val other = ketch.download(request("0"))
      runCurrent()
      val run = source.runs.value.single { it.context.taskId == task.taskId }
      // Another task's pause holds the coordinator, which every task shares, while it saves.
      val pausing = CompletableDeferred<Unit>()
      val paused = CompletableDeferred<Unit>()
      store.onSave = { record ->
        if (record.taskId == other.taskId && record.state == TaskState.PAUSED) {
          pausing.complete(Unit)
          paused.await()
        } else if (record.request.selectedFileIds.size == 3 && !pausing.isCompleted) {
          launch { other.pause() }
          pausing.await()
        }
      }
      val selecting = launch { task.selectFiles(setOf("0", "1", "2")) }
      runCurrent()
      // Saved, the selection waits for the coordinator to deliver it, and its caller goes away.
      assertEquals(setOf("0", "1", "2"), task.request.selectedFileIds)
      assertTrue(run.applied.isEmpty())
      selecting.cancel()
      runCurrent()

      paused.complete(Unit)
      runCurrent()

      assertEquals(setOf("0", "1", "2"), run.applied.single().fileIds)
      store.onSave = {}
      run.finish.complete(Unit)
      runCurrent()
      assertEquals(600, assertIs<DownloadState.Completed>(task.state.value).totalBytes)
    } finally {
      ketch.close()
    }
  }

  @Test
  fun selectFiles_cancelledWhileTheQueueIsBusy_reopensTheCompletedTask() = runTest {
    val source = SelectableSource()
    val store = RecordingTaskStore()
    val ketch = ketch(source, store)
    try {
      val task = ketch.download(request("0"))
      val other = ketch.download(request("0"))
      runCurrent()
      source.runs.value.single { it.context.taskId == task.taskId }.finish.complete(Unit)
      other.pause()
      runCurrent()
      assertIs<DownloadState.Completed>(task.state.value)
      assertIs<DownloadState.Paused>(other.state.value)
      // Another task's resume holds the queue, which every task shares, while it saves.
      val resuming = CompletableDeferred<Unit>()
      val resumed = CompletableDeferred<Unit>()
      store.onSave = { record ->
        if (record.taskId == other.taskId && record.state == TaskState.QUEUED) {
          resuming.complete(Unit)
          resumed.await()
        } else if (record.taskId == task.taskId && record.request.selectedFileIds.size == 2 &&
          !resuming.isCompleted
        ) {
          launch { other.resume() }
          resuming.await()
        }
      }
      val selecting = launch { task.selectFiles(setOf("0", "1")) }
      runCurrent()
      // Saved, the selection waits for the queue to start the task, and its caller goes away.
      assertEquals(setOf("0", "1"), task.request.selectedFileIds)
      assertEquals(TaskState.QUEUED, store.load(task.taskId)?.state)
      assertEquals(1, source.runs.value.count { it.context.taskId == task.taskId })
      selecting.cancel()
      runCurrent()

      store.onSave = {}
      resumed.complete(Unit)
      runCurrent()

      val runs = source.runs.value.filter { it.context.taskId == task.taskId }
      assertEquals(2, runs.size)
      val run = runs.last()
      assertTrue(run.resumed)
      assertEquals(setOf("0", "1"), run.initial.fileIds)
      assertIs<DownloadState.Downloading>(task.state.value)
      run.finish.complete(Unit)
      runCurrent()
      assertEquals(300, assertIs<DownloadState.Completed>(task.state.value).totalBytes)
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
