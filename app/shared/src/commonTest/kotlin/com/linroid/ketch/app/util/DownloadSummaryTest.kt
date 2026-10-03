package com.linroid.ketch.app.util

import com.linroid.ketch.api.DownloadProgress
import com.linroid.ketch.api.DownloadState
import com.linroid.ketch.app.i18n.load
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

class DownloadSummaryTest {
  @Test
  fun transferSummary_completed_showsSizeTimeAndAverageSpeed() = runTest {
    val state = DownloadState.Completed("/file", totalBytes = 10_485_760, downloadTime = 4.seconds)

    assertEquals(listOf("10.0 MB", "took 4s", "avg 2.5 MB/s"), summary(state))
  }

  @Test
  fun transferSummary_completedUnderASecond_showsLessThanOneSecond() = runTest {
    val state = DownloadState.Completed("/file", 2048, downloadTime = 500.milliseconds)

    assertEquals(listOf("2.0 KB", "took <1s", "avg 4.0 KB/s"), summary(state))
  }

  @Test
  fun transferSummary_completedEmptyFileInstantly_omitsAverageSpeed() = runTest {
    val state = DownloadState.Completed("/file", totalBytes = 0, downloadTime = Duration.ZERO)

    assertEquals(listOf("0 B", "took <1s"), summary(state))
  }

  @Test
  fun transferSummary_completedWithoutDownloadTime_showsSizeOnly() = runTest {
    val state = DownloadState.Completed("/file", totalBytes = 1024)

    assertEquals(listOf("1.0 KB"), summary(state))
  }

  @Test
  fun transferSummary_completedWithoutStats_isEmpty() = runTest {
    assertEquals(emptyList(), summary(DownloadState.Completed("/file")))
  }

  @Test
  fun transferSummary_downloading_showsDownloadedOfTotal() = runTest {
    val state = DownloadState.Downloading(DownloadProgress(1024, 4096, 512))

    assertEquals(listOf("1.0 KB / 4.0 KB"), summary(state))
  }

  @Test
  fun transferSummary_pausedWithUnknownTotal_showsDownloadedOnly() = runTest {
    val state = DownloadState.Paused(DownloadProgress(2048, 0))

    assertEquals(listOf("2.0 KB"), summary(state))
  }

  @Test
  fun transferSummary_nothingDownloadedYet_isEmpty() = runTest {
    val state = DownloadState.Downloading(DownloadProgress(0, 0))

    assertEquals(emptyList(), summary(state))
  }

  private suspend fun summary(state: DownloadState): List<String> =
    transferSummary(state).map { it.load() }
}
