package com.linroid.ketch.app

import com.linroid.ketch.api.DownloadState
import com.linroid.ketch.app.state.PendingOps
import com.linroid.ketch.app.state.RecordingKetchApi
import com.linroid.ketch.app.state.RecordingTask
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class KetchBackgroundTest {

  private val downloading = DownloadState.Downloading(RecordingTask.PROGRESS)
  private val paused = DownloadState.Paused(RecordingTask.PROGRESS)
  private val saved = MemoryIds()
  private val notices = mutableListOf<Int>()
  private var withdrawn = 0

  private fun pauser(
    api: RecordingKetchApi,
    scope: CoroutineScope,
    commitPending: () -> Job = { Job().apply { complete() } },
  ) = BackgroundPauser(
    api = api,
    scope = scope,
    saved = saved,
    commitPending = commitPending,
    onPaused = { notices += it },
    onResumed = { withdrawn++ },
  )

  @Test
  fun enterBackground_queuedBehindDownload_pausesQueuedBeforeItCanStart() = runTest {
    val api = RecordingKetchApi(maxActive = 1)
    val running = api.add(downloading)
    val waiting = api.add(DownloadState.Queued)
    val states = mutableListOf<DownloadState>()
    backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
      waiting.state.collect { states += it }
    }

    pauser(api, this).enterBackground()?.join()

    assertEquals(listOf(running.taskId, waiting.taskId), saved.ids)
    assertEquals(listOf(DownloadState.Queued, paused), states)
    assertEquals(listOf(2), notices)
  }

  @Test
  fun enterBackground_finishedAndPausedTasks_returnsNull() = runTest {
    val api = RecordingKetchApi()
    val done = api.add(DownloadState.Completed("/downloads/a.bin"))
    val held = api.add(paused)

    val work = pauser(api, this).enterBackground()

    assertNull(work)
    assertTrue(done.calls.isEmpty() && held.calls.isEmpty())
    assertTrue(notices.isEmpty())
  }

  @Test
  fun enterBackground_failingPause_remembersOnlyPausedTasks() = runTest {
    val api = RecordingKetchApi()
    val stuck = api.add(downloading).apply { failure = IllegalStateException("closed") }
    val running = api.add(downloading)

    pauser(api, this).enterBackground()?.join()

    assertEquals(listOf(running.taskId), saved.ids)
    assertEquals(listOf("pause"), stuck.calls)
  }

  @Test
  fun enterBackground_pendingRemovalWithoutDownloads_waitsForItsCommit() = runTest {
    val api = RecordingKetchApi()
    val ops = PendingOps(backgroundScope)
    val removal = CompletableDeferred<Unit>()
    ops.register("Remove", commit = { removal.await() })

    val work = assertNotNull(pauser(api, this, commitPending = ops::flush).enterBackground())
    runCurrent()

    assertTrue(work.isActive)
    removal.complete(Unit)
    work.join()
    assertTrue(notices.isEmpty())
  }

  @Test
  fun keepRunningInBackground_pendingRemoval_commitsWithoutPausing() = runTest {
    val api = RecordingKetchApi()
    val running = api.add(downloading)
    val ops = PendingOps(backgroundScope)
    val removal = CompletableDeferred<Unit>()
    ops.register("Remove", commit = { removal.await() })
    val pauser = pauser(api, this, commitPending = ops::flush)

    val work = assertNotNull(pauser.keepRunningInBackground())
    runCurrent()

    assertTrue(work.isActive)
    removal.complete(Unit)
    work.join()
    assertTrue(running.calls.isEmpty())
    assertTrue(saved.ids.isEmpty())
    assertTrue(notices.isEmpty())
  }

  @Test
  fun keepRunningInBackground_nothingPending_returnsNull() = runTest {
    val api = RecordingKetchApi()
    val running = api.add(downloading)

    assertNull(pauser(api, this).keepRunningInBackground())
    assertTrue(running.calls.isEmpty())
  }

  @Test
  fun enterForeground_afterBackground_resumesTasksStillPaused() = runTest {
    val api = RecordingKetchApi()
    val first = api.add(downloading)
    val removed = api.add(downloading)
    val pauser = pauser(api, this)
    pauser.enterBackground()?.join()
    api.removeTask(removed)

    pauser.enterForeground().join()

    assertEquals(listOf("pause", "resume"), first.calls)
    assertEquals(emptyList(), saved.ids)
    assertEquals(1, withdrawn)
  }

  @Test
  fun enterForeground_whilePausing_resumesOncePausingFinished() = runTest {
    val api = RecordingKetchApi()
    val task = api.add(downloading)
    val pauser = pauser(api, this)
    pauser.enterBackground()

    pauser.enterForeground().join()

    assertEquals(listOf("pause", "resume"), task.calls)
    assertEquals(emptyList(), saved.ids)
  }

  @Test
  fun enterBackground_beforeResumeRan_keepsTasksPausedAndRemembered() = runTest {
    val api = RecordingKetchApi()
    val task = api.add(downloading)
    val pauser = pauser(api, this)
    pauser.enterBackground()?.join()
    pauser.enterForeground()

    pauser.enterBackground()?.join()
    runCurrent()

    assertEquals(listOf("pause"), task.calls)
    assertEquals(listOf(task.taskId), saved.ids)
    assertEquals(listOf(1, 1), notices)
    assertEquals(0, withdrawn)
  }

  @Test
  fun enterForeground_afterRelaunch_resumesOnceTasksAreRestored() = runTest {
    val api = RecordingKetchApi()
    saved.ids = listOf("t1")
    val resuming = pauser(api, this).enterForeground()
    runCurrent()

    val task = api.add(paused)
    resuming.join()

    assertEquals("t1", task.taskId)
    assertEquals(listOf("resume"), task.calls)
    assertEquals(emptyList(), saved.ids)
  }

  private class MemoryIds : PausedTaskIds {
    override var ids: List<String> = emptyList()
  }
}
