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
  fun matches_eachTab_matchesItsStates() {
    val tabs = mapOf(
      StatusFilter.All to allStates,
      StatusFilter.Downloading to listOf(downloading),
      StatusFilter.Waiting to listOf(queued, scheduled),
      StatusFilter.Paused to listOf(paused),
      StatusFilter.Done to listOf(completed),
      StatusFilter.Failed to listOf(failed, canceled),
    )
    for ((filter, expected) in tabs) {
      allStates.forEach { state ->
        assertEquals(state in expected, filter.matches(state), "$filter matches $state")
      }
    }
  }

  @Test
  fun matches_everyState_belongsToExactlyOneTabBesidesAll() {
    val tabs = StatusFilter.entries - StatusFilter.All
    allStates.forEach { state ->
      assertEquals(1, tabs.count { it.matches(state) }, "tabs matching $state")
    }
  }

  @Test
  fun counts_everyTab_matchesItsDefinition() {
    val states = allStates + listOf(downloading, queued, canceled)

    val counts = StatusFilter.counts(states)

    StatusFilter.entries.forEach { filter ->
      assertEquals(states.count { filter.matches(it) }, counts[filter], "count of $filter")
    }
    assertEquals(states.size, counts.filterKeys { it != StatusFilter.All }.values.sum())
  }
}
