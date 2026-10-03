package com.linroid.ketch.app

import com.linroid.ketch.api.DownloadState
import com.linroid.ketch.app.i18n.verbatim
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
  private val api = RecordingKetchApi()

  private fun pauser(
    scope: CoroutineScope,
    commitPending: () -> Job = { Job().apply { complete() } },
    api: RecordingKetchApi = this.api,
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

    pauser(this, api = api).enterBackground()?.join()

    assertEquals(listOf(running.taskId, waiting.taskId), saved.ids)
    assertEquals(listOf(DownloadState.Queued, paused), states)
    assertEquals(listOf(2), notices)
  }

  @Test
  fun enterBackground_finishedAndPausedTasks_returnsNull() = runTest {
    val done = api.add(DownloadState.Completed("/downloads/a.bin"))
    val held = api.add(paused)

    val work = pauser(this).enterBackground()

    assertNull(work)
    assertTrue(done.calls.isEmpty() && held.calls.isEmpty())
    assertTrue(notices.isEmpty())
  }

  @Test
  fun enterBackground_failingPause_remembersOnlyPausedTasks() = runTest {
    val stuck = api.add(downloading).apply { failure = IllegalStateException("closed") }
    val running = api.add(downloading)

    pauser(this).enterBackground()?.join()

    assertEquals(listOf(running.taskId), saved.ids)
    assertEquals(listOf("pause"), stuck.calls)
  }

  @Test
  fun enterBackground_pendingRemovalWithoutDownloads_waitsForItsCommit() = runTest {
    val ops = PendingOps(backgroundScope)
    val removal = CompletableDeferred<Unit>()
    ops.register(verbatim("Undo remove"), commit = { removal.await() })

    val work = assertNotNull(pauser(this, commitPending = ops::flush).enterBackground())
    runCurrent()

    assertTrue(work.isActive)
    removal.complete(Unit)
    work.join()
    assertTrue(notices.isEmpty())
  }

  @Test
  fun keepRunningInBackground_pendingRemoval_commitsWithoutPausing() = runTest {
    val running = api.add(downloading)
    val ops = PendingOps(backgroundScope)
    val removal = CompletableDeferred<Unit>()
    ops.register(verbatim("Undo remove"), commit = { removal.await() })
    val pauser = pauser(this, commitPending = ops::flush)

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
    val running = api.add(downloading)

    assertNull(pauser(this).keepRunningInBackground())
    assertTrue(running.calls.isEmpty())
  }

  @Test
  fun enterForeground_afterBackground_resumesTasksStillPaused() = runTest {
    val first = api.add(downloading)
    val removed = api.add(downloading)
    val pauser = pauser(this)
    pauser.enterBackground()?.join()
    api.removeTask(removed)

    pauser.enterForeground().join()

    assertEquals(listOf("pause", "resume"), first.calls)
    assertEquals(emptyList(), saved.ids)
    assertEquals(1, withdrawn)
  }

  @Test
  fun enterForeground_whilePausing_resumesOncePausingFinished() = runTest {
    val task = api.add(downloading)
    val pauser = pauser(this)
    pauser.enterBackground()

    pauser.enterForeground().join()

    assertEquals(listOf("pause", "resume"), task.calls)
    assertEquals(emptyList(), saved.ids)
  }

  @Test
  fun enterBackground_beforeResumeRan_keepsTasksPausedAndRemembered() = runTest {
    val task = api.add(downloading)
    val pauser = pauser(this)
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
    saved.ids = listOf("t1")
    val resuming = pauser(this).enterForeground()
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
