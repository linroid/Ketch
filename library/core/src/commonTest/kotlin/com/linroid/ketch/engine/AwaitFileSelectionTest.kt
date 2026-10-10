package com.linroid.ketch.engine

import com.linroid.ketch.api.Destination
import com.linroid.ketch.api.DownloadConfig
import com.linroid.ketch.api.DownloadProgress
import com.linroid.ketch.api.DownloadRequest
import com.linroid.ketch.api.DownloadState
import com.linroid.ketch.api.PauseReason
import com.linroid.ketch.api.ResolvedSource
import com.linroid.ketch.api.Segment
import com.linroid.ketch.core.Ketch
import com.linroid.ketch.core.KetchDispatchers
import com.linroid.ketch.core.engine.SourceResumeState
import com.linroid.ketch.core.task.TaskRecord
import com.linroid.ketch.core.task.TaskState
import com.linroid.ketch.core.task.TaskStore
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Clock

class AwaitFileSelectionTest {
  private val folder = "/tmp/ketch-await-files/"

  private fun request(
    url: String = "pick:files",
    selected: Set<String> = emptySet(),
    await: Boolean = true,
    destination: String = folder,
  ) = DownloadRequest(
    url = url,
    destination = Destination(destination),
    selectedFileIds = selected,
    awaitFileSelection = await,
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

  private val awaiting = { state: DownloadState ->
    state is DownloadState.Paused && state.reason == PauseReason.AwaitingFileSelection
  }

  @Test
  fun awaitFlag_severalFiles_parksAndFreesTheSlot() = runTest {
    val source = SelectableSource()
    val store = RecordingTaskStore()
    val ketch = ketch(source, store, DownloadConfig(maxConcurrentDownloads = 1, retryCount = 0))
    try {
      val parked = ketch.download(request())
      val next = ketch.download(request(url = "pick:next", selected = setOf("0"), await = false))
      assertEquals(1, next.queuePosition.value)

      runCurrent()

      assertEquals(
        DownloadState.Paused(DownloadProgress(0, 600), PauseReason.AwaitingFileSelection),
        parked.state.value
      )
      assertNull(parked.queuePosition.value)
      val record = assertNotNull(store.load(parked.taskId))
      assertEquals(TaskState.PAUSED, record.state)
      assertEquals(SelectableSource.TYPE, record.sourceType)
      assertNotNull(record.sourceResumeState)
      assertNull(record.outputPath)
      assertNull(record.segments)
      assertEquals(600, record.totalBytes)
      val kept = assertNotNull(record.request.resolvedSource)
      assertEquals(3, kept.files.size)
      assertTrue(ResolvedSource.METAINFO_KEY in kept.metadata)
      // Its slot went to the next task.
      assertEquals(next.taskId, source.runs.value.single().context.taskId)
      assertIs<DownloadState.Downloading>(next.state.value)
    } finally {
      ketch.close()
    }
  }

  @Test
  fun awaitFlag_singleFile_downloadsWithoutWaiting() = runTest {
    val source = SelectableSource(sizes = listOf(100))
    val ketch = ketch(source)
    try {
      val task = ketch.download(request())
      runCurrent()

      assertIs<DownloadState.Downloading>(task.state.value)
      assertEquals(1, source.runs.value.size)
    } finally {
      ketch.close()
    }
  }

  @Test
  fun parkedTask_selectFiles_startsFromStoredStateWithoutResolving() = runTest {
    val source = SelectableSource()
    val store = RecordingTaskStore()
    val ketch = ketch(source, store)
    try {
      val task = ketch.download(request())
      runCurrent()
      assertTrue(awaiting(task.state.value))
      assertEquals(1, source.resolves)

      task.selectFiles(setOf("1"))

      assertEquals(DownloadState.Queued, task.state.value)
      runCurrent()
      val run = source.runs.value.single()
      assertFalse(run.resumed)
      assertEquals("stored", run.context.preResolved?.metadata?.get(SelectableSource.MARKER))
      assertEquals(setOf("1"), run.initial.fileIds)
      assertEquals(1, source.resolves)
      assertEquals(1, source.storedResolves)
      val record = assertNotNull(store.load(task.taskId))
      assertEquals(TaskState.DOWNLOADING, record.state)
      assertEquals(200, record.totalBytes)
      assertNotNull(record.outputPath)
      assertEquals(1, record.control?.selectionGeneration)
      assertEquals(200, assertIs<DownloadState.Downloading>(task.state.value).progress.totalBytes)
    } finally {
      ketch.close()
    }
  }

  @Test
  fun parkedTask_resume_selectsEveryFile() = runTest {
    val source = SelectableSource()
    val store = RecordingTaskStore()
    val ketch = ketch(source, store)
    try {
      val task = ketch.download(request())
      runCurrent()
      assertTrue(awaiting(task.state.value))

      task.resume(Destination("/elsewhere/"))
      runCurrent()

      val run = source.runs.value.single()
      assertEquals(setOf("0", "1", "2"), run.initial.fileIds)
      assertEquals(setOf("0", "1", "2"), task.request.selectedFileIds)
      assertEquals(600, store.load(task.taskId)?.totalBytes)
      assertEquals(folder + "pick", run.context.outputPath)
    } finally {
      ketch.close()
    }
  }

  @Test
  fun parkedTask_pause_staysAwaiting() = runTest {
    val source = SelectableSource()
    val ketch = ketch(source)
    try {
      val task = ketch.download(request())
      runCurrent()

      task.pause()
      runCurrent()

      assertTrue(awaiting(task.state.value))
      assertTrue(source.runs.value.isEmpty())
    } finally {
      ketch.close()
    }
  }

  @Test
  fun parkedTask_restart_restoresAwaitingReason() = runTest {
    val source = SelectableSource()
    val store = RecordingTaskStore()
    val first = ketch(source, store)
    val taskId = try {
      val task = first.download(request())
      runCurrent()
      assertTrue(awaiting(task.state.value))
      task.taskId
    } finally {
      first.close()
    }

    val next = ketch(source, store)
    try {
      next.start()
      runCurrent()

      val task = next.tasks.value.single { it.taskId == taskId }
      assertEquals(
        DownloadState.Paused(DownloadProgress(0, 600), PauseReason.AwaitingFileSelection),
        task.state.value
      )
      assertTrue(source.runs.value.isEmpty())
      assertEquals(1, source.resolves)
    } finally {
      next.close()
    }
  }

  @Test
  fun userPauseDuringLookup_staysUserPause() = runTest {
    val source = SelectableSource().apply { resolveGate = CompletableDeferred() }
    val store = RecordingTaskStore()
    val first = ketch(source, store)
    val taskId = try {
      val task = first.download(request())
      runCurrent()
      assertEquals(DownloadState.Queued, task.state.value)

      task.pause()
      runCurrent()

      val paused = assertIs<DownloadState.Paused>(task.state.value)
      assertEquals(PauseReason.User, paused.reason)
      assertNull(store.load(task.taskId)?.sourceType)
      task.taskId
    } finally {
      first.close()
    }

    val next = ketch(source, store)
    try {
      next.start()
      runCurrent()
      val paused = assertIs<DownloadState.Paused>(next.tasks.value.single().state.value)
      assertEquals(taskId, next.tasks.value.single().taskId)
      assertEquals(PauseReason.User, paused.reason)
    } finally {
      next.close()
    }
  }

  @Test
  fun parkedTask_reservesNoOutputPath() = runTest {
    val source = SelectableSource()
    val folder = "/tmp/ketch-await-reservations/"
    val ketch = ketch(source)
    try {
      val parked = ketch.download(request(destination = folder))
      runCurrent()
      assertTrue(awaiting(parked.state.value))
      assertNull(parked.outputPath)

      // Another download of the same name takes the name the waiting one would have used.
      ketch.download(request(selected = setOf("0"), await = false, destination = folder))
      runCurrent()

      assertEquals(folder + "pick", source.runs.value.single().context.outputPath)
    } finally {
      ketch.close()
    }
  }

  @Test
  fun executeFresh_storedState_preferredOverClientResolvedSource() = runTest {
    val source = SelectableSource()
    val store = RecordingTaskStore()
    val now = Clock.System.now()
    val client = source.resolved("pick:files", "client")
    store.save(
      TaskRecord(
        taskId = "stored",
        request = request(selected = setOf("0"), await = false).copy(resolvedSource = client),
        state = TaskState.QUEUED,
        sourceType = SelectableSource.TYPE,
        sourceResumeState = SourceResumeState(SelectableSource.TYPE, "stored:pick:files"),
        createdAt = now,
        updatedAt = now,
      )
    )
    val ketch = ketch(source, store)
    try {
      ketch.start()
      runCurrent()

      val run = source.runs.value.single()
      assertFalse(run.resumed)
      assertEquals("stored", run.context.preResolved?.metadata?.get(SelectableSource.MARKER))
      assertEquals(0, source.resolves)
    } finally {
      ketch.close()
    }
  }

  @Test
  fun executeFresh_downloading_stripsMetainfoFromTheRequest() = runTest {
    val source = SelectableSource()
    val store = RecordingTaskStore()
    val ketch = ketch(source, store)
    try {
      val task = ketch.download(request(selected = setOf("0"), await = false))
      runCurrent()

      val record = assertNotNull(store.load(task.taskId))
      assertEquals(TaskState.DOWNLOADING, record.state)
      val kept = assertNotNull(record.request.resolvedSource)
      assertFalse(ResolvedSource.METAINFO_KEY in kept.metadata)
      assertEquals(3, kept.files.size)
      assertEquals(record.request, task.request)
      assertNotNull(record.sourceResumeState)
      val run = source.runs.value.single()
      assertTrue(ResolvedSource.METAINFO_KEY in assertNotNull(run.context.preResolved).metadata)
    } finally {
      ketch.close()
    }
  }

  @Test
  fun executeFresh_planSelection_totalComesFromSourceNotClientSizes() = runTest {
    val source = SelectableSource()
    val store = RecordingTaskStore()
    val ketch = ketch(source, store)
    try {
      val client = source.resolved("pick:files", "client") { 1L }
      val task = ketch.download(
        request(selected = setOf("0", "1"), await = false).copy(resolvedSource = client)
      )
      runCurrent()

      assertEquals(300, store.load(task.taskId)?.totalBytes)
      assertEquals(300, assertIs<DownloadState.Downloading>(task.state.value).progress.totalBytes)
    } finally {
      ketch.close()
    }
  }

  @Test
  fun executeResume_requestWithoutResolvedSource_restoresTheFileList() = runTest {
    val source = SelectableSource()
    val store = RecordingTaskStore()
    val now = Clock.System.now()
    store.save(
      TaskRecord(
        taskId = "added-without-preview",
        request = request(selected = setOf("0"), await = false),
        outputPath = folder + "pick",
        state = TaskState.DOWNLOADING,
        totalBytes = 100,
        segments = listOf(Segment(0, 0, 99, 10)),
        sourceType = SelectableSource.TYPE,
        sourceResumeState = SourceResumeState(SelectableSource.TYPE, "stored:pick:files"),
        createdAt = now,
        updatedAt = now,
      )
    )
    val ketch = ketch(source, store)
    try {
      ketch.start()
      runCurrent()

      val run = source.runs.value.single()
      assertTrue(run.resumed)
      val task = ketch.tasks.value.single()
      val restored = assertNotNull(task.request.resolvedSource)
      assertEquals(listOf("file0", "file1", "file2"), restored.files.map { it.name })
      assertFalse(ResolvedSource.METAINFO_KEY in restored.metadata)
      assertEquals(restored, store.load(task.taskId)?.request?.resolvedSource)
      assertEquals(restored, run.context.request.resolvedSource)
    } finally {
      ketch.close()
    }
  }
}
