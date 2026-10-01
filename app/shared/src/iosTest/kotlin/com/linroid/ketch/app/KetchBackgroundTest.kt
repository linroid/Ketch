package com.linroid.ketch.app

import com.linroid.ketch.api.DownloadState
import com.linroid.ketch.app.state.RecordingKetchApi
import com.linroid.ketch.app.state.RecordingTask
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class KetchBackgroundTest {

  private val downloading = DownloadState.Downloading(RecordingTask.PROGRESS)
  private val paused = DownloadState.Paused(RecordingTask.PROGRESS)

  @Test
  fun pauseActive_queuedBehindDownload_pausesQueuedBeforeItCanStart() = runTest {
    val api = RecordingKetchApi(maxActive = 1)
    val running = api.add(downloading)
    val waiting = api.add(DownloadState.Queued)
    val states = mutableListOf<DownloadState>()
    backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
      waiting.state.collect { states += it }
    }

    val ids = KetchBackground.pauseActive(api)

    assertEquals(listOf(running.taskId, waiting.taskId), ids)
    assertEquals(listOf(DownloadState.Queued, paused), states)
  }

  @Test
  fun pauseActive_finishedAndPausedTasks_leavesThemAlone() = runTest {
    val api = RecordingKetchApi()
    val done = api.add(DownloadState.Completed("/downloads/a.bin"))
    val held = api.add(paused)

    val ids = KetchBackground.pauseActive(api)

    assertEquals(emptyList(), ids)
    assertTrue(done.calls.isEmpty() && held.calls.isEmpty())
  }

  @Test
  fun pauseActive_failingPause_skipsTaskAndReportsTheRest() = runTest {
    val api = RecordingKetchApi()
    val stuck = api.add(downloading).apply { failure = IllegalStateException("closed") }
    val running = api.add(downloading)
    val reported = mutableListOf<List<String>>()

    val ids = KetchBackground.pauseActive(api) { reported += it }

    assertEquals(listOf(running.taskId), ids)
    assertEquals(listOf(listOf(running.taskId)), reported)
    assertEquals(listOf("pause"), stuck.calls)
  }

  @Test
  fun resumePaused_tasksChangedMeanwhile_resumesOnlyThoseStillPaused() = runTest {
    val api = RecordingKetchApi()
    val first = api.add(paused)
    val finished = api.add(DownloadState.Completed("/downloads/b.bin"))
    val second = api.add(paused)

    val resumed = KetchBackground.resumePaused(
      api,
      listOf(second.taskId, "removed", finished.taskId, first.taskId),
    )

    assertEquals(2, resumed)
    assertEquals(listOf("resume"), first.calls)
    assertEquals(listOf("resume"), second.calls)
    assertTrue(finished.calls.isEmpty())
  }
}
