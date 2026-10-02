package com.linroid.ketch.app.ui.toolbar

import com.linroid.ketch.api.DownloadProgress
import com.linroid.ketch.api.DownloadSchedule
import com.linroid.ketch.api.DownloadState
import com.linroid.ketch.api.KetchError
import com.linroid.ketch.app.instance.InstanceFactory
import com.linroid.ketch.app.instance.InstanceManager
import com.linroid.ketch.app.state.AppController
import com.linroid.ketch.app.state.RecordingKetchApi
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

@OptIn(ExperimentalCoroutinesApi::class)
class BulkActionsTest {

  private val progress = DownloadProgress(downloadedBytes = 10, totalBytes = 100)
  private val downloading = DownloadState.Downloading(progress)
  private val paused = DownloadState.Paused(progress)
  private val scheduled = DownloadState.Scheduled(DownloadSchedule.AfterDelay(30.minutes))
  private val completed = DownloadState.Completed("/downloads/a.iso", totalBytes = 100)
  private val failed = DownloadState.Failed(KetchError.Network())

  @Test
  fun of_noTasks_disablesEverything() {
    assertEquals(BulkActions(), BulkActions.of(emptyList()))
  }

  @Test
  fun of_onlyQueuedTasks_canPause() {
    assertTrue(BulkActions.of(listOf(DownloadState.Queued)).canPause)
  }

  @Test
  fun of_downloadingTask_canPause() {
    assertTrue(BulkActions.of(listOf(downloading, completed)).canPause)
  }

  @Test
  fun of_scheduledAndPausedTasks_cannotPause() {
    assertFalse(BulkActions.of(listOf(scheduled, paused)).canPause)
  }

  @Test
  fun of_pausedTask_canResume() {
    val actions = BulkActions.of(listOf(paused))

    assertTrue(actions.canResume)
    assertFalse(BulkActions.of(listOf(downloading)).canResume)
  }

  @Test
  fun of_mixedStates_countsFailedAndFinishedTasks() {
    val states = listOf(failed, failed, DownloadState.Canceled, completed, downloading)

    val actions = BulkActions.of(states)

    assertEquals(2, actions.failed)
    assertEquals(1, actions.finished)
  }

  @Test
  fun of_afterClearFinished_countsNoneUntilUndone() = runTest {
    val api = RecordingKetchApi()
    val controller = AppController(
      instanceManager = InstanceManager(
        factory = InstanceFactory(deviceName = "This Mac", embeddedFactory = { api }),
      ),
      context = StandardTestDispatcher(testScheduler),
    )
    val state = controller.state
    val finished = listOf(api.add(completed), api.add(completed))
    val running = api.add(downloading)
    backgroundScope.launch { state.tasks.collect {} }
    runCurrent()
    fun bulk() = BulkActions.of(state.tasks.value.map { it.state.value })
    assertEquals(2, bulk().finished)

    state.clearCompleted()
    runCurrent()
    assertEquals(0, bulk().finished)
    assertEquals(listOf(running), state.tasks.value)

    advanceTimeBy(5.seconds)
    controller.messages.active.value.last().actions.single { it.label == "Undo" }.onClick()
    advanceTimeBy(5.seconds)
    runCurrent()
    assertEquals(2, bulk().finished)
    assertTrue(finished.all { it.calls.isEmpty() })
    controller.close()
  }

  @Test
  fun clearFinishedLabel_someFinished_namesTheCount() {
    assertEquals("Clear 12 finished", clearFinishedLabel(12))
  }

  @Test
  fun clearFinishedLabel_noneFinished_leavesTheCountOut() {
    assertEquals("Clear finished", clearFinishedLabel(0))
  }
}
