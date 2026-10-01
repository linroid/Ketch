package com.linroid.ketch.app.ui.toolbar

import com.linroid.ketch.api.DownloadProgress
import com.linroid.ketch.api.DownloadSchedule
import com.linroid.ketch.api.DownloadState
import com.linroid.ketch.api.KetchError
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes

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
  fun clearFinishedLabel_someFinished_namesTheCount() {
    assertEquals("Clear 12 finished", clearFinishedLabel(12))
  }

  @Test
  fun clearFinishedLabel_noneFinished_leavesTheCountOut() {
    assertEquals("Clear finished", clearFinishedLabel(0))
  }
}
