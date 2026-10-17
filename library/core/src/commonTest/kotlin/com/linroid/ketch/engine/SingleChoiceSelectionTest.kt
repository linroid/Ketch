package com.linroid.ketch.engine

import com.linroid.ketch.api.Destination
import com.linroid.ketch.api.DownloadConfig
import com.linroid.ketch.api.DownloadProgress
import com.linroid.ketch.api.DownloadRequest
import com.linroid.ketch.api.DownloadState
import com.linroid.ketch.api.FileSelectionMode
import com.linroid.ketch.api.KetchError
import com.linroid.ketch.api.PauseReason
import com.linroid.ketch.core.Ketch
import com.linroid.ketch.core.KetchDispatchers
import com.linroid.ketch.core.engine.SINGLE_CHOICE_MESSAGE
import com.linroid.ketch.core.task.TaskStore
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** File selection of a source whose files are variants, one of which downloads. */
class SingleChoiceSelectionTest {
  private val folder = "/tmp/ketch-single-choice/"

  private fun request(
    vararg ids: String,
    await: Boolean = false,
  ) = DownloadRequest(
    url = "pick:variants",
    destination = Destination(folder),
    selectedFileIds = ids.toSet(),
    awaitFileSelection = await,
  )

  private fun variants() = SelectableSource(mode = FileSelectionMode.SINGLE)

  private fun TestScope.ketch(
    source: SelectableSource,
    store: TaskStore = RecordingTaskStore(),
  ): Ketch {
    val dispatcher = StandardTestDispatcher(testScheduler)
    return Ketch(
      httpEngine = FakeHttpEngine(),
      taskStore = store,
      config = DownloadConfig(retryCount = 0, saveIntervalMs = 60_000),
      additionalSources = listOf(source),
      dispatchers = KetchDispatchers(dispatcher, dispatcher, dispatcher),
    )
  }

  @Test
  fun selectFiles_runningSingleChoice_switchesToTheOtherVariant() = runTest {
    val source = variants()
    val ketch = ketch(source)
    try {
      val task = ketch.download(request("0"))
      runCurrent()

      task.selectFiles(setOf("1"))
      runCurrent()

      val run = source.runs.value.single()
      assertEquals(listOf(setOf("1")), run.applied.map { it.fileIds })
      assertEquals(1, run.context.acknowledgedSelection.value)
      assertEquals(setOf("1"), task.request.selectedFileIds)
      assertEquals(200, assertIs<DownloadState.Downloading>(task.state.value).progress.totalBytes)
      run.finish.complete(Unit)
      runCurrent()
      assertEquals(200, assertIs<DownloadState.Completed>(task.state.value).totalBytes)
    } finally {
      ketch.close()
    }
  }

  @Test
  fun selectFiles_twoVariants_rejectedBeforePlanning() = runTest {
    val source = variants()
    val ketch = ketch(source)
    try {
      val task = ketch.download(request("0"))
      runCurrent()
      val planned = source.planned.size

      assertFailsWith<IllegalArgumentException> { task.selectFiles(setOf("0", "1")) }

      assertEquals(planned, source.planned.size)
      assertEquals(setOf("0"), task.request.selectedFileIds)
      assertTrue(source.runs.value.single().applied.isEmpty())
      source.runs.value.single().finish.complete(Unit)
      runCurrent()
    } finally {
      ketch.close()
    }
  }

  @Test
  fun download_twoVariantsOfAResolvedSingleChoice_isRejected() = runTest {
    val source = variants()
    val ketch = ketch(source)
    try {
      val resolved = source.resolved("pick:variants", "client")
      assertFailsWith<IllegalArgumentException> {
        ketch.download(request("0", "1").copy(resolvedSource = resolved))
      }
      assertTrue(ketch.tasks.value.isEmpty())
    } finally {
      ketch.close()
    }
  }

  @Test
  fun freshStart_twoVariantsSelected_fails() = runTest {
    val source = variants()
    val ketch = ketch(source)
    try {
      val task = ketch.download(request("0", "1"))
      runCurrent()

      val failed = assertIs<DownloadState.Failed>(task.state.value)
      assertEquals(SINGLE_CHOICE_MESSAGE, assertIs<KetchError.SourceError>(failed.error).detail)
      assertTrue(source.runs.value.isEmpty())
    } finally {
      ketch.close()
    }
  }

  @Test
  fun awaitFlag_severalVariants_parksThenStartsWithTheChosenOne() = runTest {
    val source = variants()
    val store = RecordingTaskStore()
    val ketch = ketch(source, store)
    try {
      val task = ketch.download(request(await = true))
      runCurrent()
      assertEquals(
        DownloadState.Paused(DownloadProgress(0, 600), PauseReason.AwaitingFileSelection),
        task.state.value
      )
      assertTrue(source.runs.value.isEmpty())

      task.selectFiles(setOf("2"))
      runCurrent()

      val run = source.runs.value.single()
      assertEquals(setOf("2"), run.initial.fileIds)
      assertEquals(300, assertNotNull(store.load(task.taskId)).totalBytes)
      assertIs<DownloadState.Downloading>(task.state.value)
      run.finish.complete(Unit)
      runCurrent()
    } finally {
      ketch.close()
    }
  }

  @Test
  fun parkedSingleChoice_resume_choosesTheFirstVariant() = runTest {
    val source = variants()
    val ketch = ketch(source)
    try {
      val task = ketch.download(request(await = true))
      runCurrent()
      assertIs<DownloadState.Paused>(task.state.value)

      task.resume()
      runCurrent()

      // Chosen in place of every file, then sized again when the download starts.
      assertEquals(setOf<Set<String>?>(setOf("0")), source.planned.toSet())
      val run = source.runs.value.single()
      assertEquals(setOf("0"), run.initial.fileIds)
      assertEquals(setOf("0"), task.request.selectedFileIds)
      assertEquals(100, assertIs<DownloadState.Downloading>(task.state.value).progress.totalBytes)
      run.finish.complete(Unit)
      runCurrent()
    } finally {
      ketch.close()
    }
  }

  @Test
  fun selectFiles_singleChoiceNotAppliedLive_rerunsWithTheNewVariant() = runTest {
    val source = variants().apply { live = false }
    val ketch = ketch(source)
    try {
      val task = ketch.download(request("0"))
      runCurrent()
      task.selectFiles(setOf("1"))
      runCurrent()
      val first = source.runs.value.single()

      first.finish.complete(Unit)
      runCurrent()

      assertEquals(2, source.runs.value.size)
      val second = source.runs.value.last()
      assertTrue(second.resumed)
      assertEquals(setOf("1"), second.initial.fileIds)
      second.finish.complete(Unit)
      runCurrent()
      assertEquals(200, assertIs<DownloadState.Completed>(task.state.value).totalBytes)
    } finally {
      ketch.close()
    }
  }
}
