package com.linroid.ketch.app

import com.linroid.ketch.api.DownloadProgress
import com.linroid.ketch.api.DownloadSchedule
import com.linroid.ketch.api.DownloadState
import com.linroid.ketch.api.KetchError
import com.linroid.ketch.app.state.StatusFilter
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.minutes

class StatusFilterTest {

  private val scheduled = DownloadState.Scheduled(DownloadSchedule.AfterDelay(30.minutes))
  private val queued = DownloadState.Queued
  private val downloading = DownloadState.Downloading(
    DownloadProgress(downloadedBytes = 100, totalBytes = 1000, bytesPerSecond = 50),
  )
  private val paused = DownloadState.Paused(
    DownloadProgress(downloadedBytes = 100, totalBytes = 1000),
  )
  private val completed = DownloadState.Completed("/path/to/file", totalBytes = 1000)
  private val failed = DownloadState.Failed(KetchError.Network())
  private val canceled = DownloadState.Canceled

  private val allStates: List<DownloadState> =
    listOf(scheduled, queued, downloading, paused, completed, failed, canceled)

  @Test
  fun matches_all_matchesEveryState() {
    assertMatchesOnly(StatusFilter.All, allStates)
  }

  @Test
  fun matches_downloading_matchesOnlyDownloading() {
    assertMatchesOnly(StatusFilter.Downloading, listOf(downloading))
  }

  @Test
  fun matches_waiting_matchesQueuedAndScheduled() {
    assertMatchesOnly(StatusFilter.Waiting, listOf(queued, scheduled))
  }

  @Test
  fun matches_paused_matchesOnlyPaused() {
    assertMatchesOnly(StatusFilter.Paused, listOf(paused))
  }

  @Test
  fun matches_done_matchesOnlyCompleted() {
    assertMatchesOnly(StatusFilter.Done, listOf(completed))
  }

  @Test
  fun matches_failed_matchesFailedAndCanceled() {
    assertMatchesOnly(StatusFilter.Failed, listOf(failed, canceled))
  }

  @Test
  fun matches_everyState_belongsToExactlyOneTabBesidesAll() {
    val tabs = StatusFilter.entries - StatusFilter.All
    allStates.forEach { state ->
      assertEquals(1, tabs.count { it.matches(state) }, "tabs matching $state")
    }
  }

  private fun assertMatchesOnly(filter: StatusFilter, expected: List<DownloadState>) {
    allStates.forEach { state ->
      assertEquals(state in expected, filter.matches(state), "$filter matches $state")
    }
  }
}
