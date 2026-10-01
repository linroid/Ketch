package com.linroid.ketch.app.util

import com.linroid.ketch.api.DownloadProgress
import com.linroid.ketch.api.DownloadState
import com.linroid.ketch.api.SpeedLimit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

class DownloadSummaryTest {
  @Test
  fun transferSummary_completed_showsSizeTimeAndAverageSpeed() {
    val state = DownloadState.Completed("/file", totalBytes = 10_485_760, downloadTime = 4.seconds)

    assertEquals(listOf("10.0 MB", "took 4s", "avg 2.5 MB/s"), transferSummary(state))
  }

  @Test
  fun transferSummary_completedUnderASecond_showsLessThanOneSecond() {
    val state = DownloadState.Completed("/file", 2048, downloadTime = 500.milliseconds)

    assertEquals(listOf("2.0 KB", "took <1s", "avg 4.0 KB/s"), transferSummary(state))
  }

  @Test
  fun transferSummary_completedEmptyFileInstantly_omitsAverageSpeed() {
    val state = DownloadState.Completed("/file", totalBytes = 0, downloadTime = Duration.ZERO)

    assertEquals(listOf("0 B", "took <1s"), transferSummary(state))
  }

  @Test
  fun transferSummary_completedWithoutDownloadTime_showsSizeOnly() {
    val state = DownloadState.Completed("/file", totalBytes = 1024)

    assertEquals(listOf("1.0 KB"), transferSummary(state))
  }

  @Test
  fun transferSummary_completedWithoutStats_isEmpty() {
    assertEquals(emptyList(), transferSummary(DownloadState.Completed("/file")))
  }

  @Test
  fun transferSummary_downloading_showsDownloadedOfTotal() {
    val state = DownloadState.Downloading(DownloadProgress(1024, 4096, 512))

    assertEquals(listOf("1.0 KB / 4.0 KB"), transferSummary(state))
  }

  @Test
  fun transferSummary_pausedWithUnknownTotal_showsDownloadedOnly() {
    val state = DownloadState.Paused(DownloadProgress(2048, 0))

    assertEquals(listOf("2.0 KB"), transferSummary(state))
  }

  @Test
  fun transferSummary_nothingDownloadedYet_isEmpty() {
    val state = DownloadState.Downloading(DownloadProgress(0, 0))

    assertEquals(emptyList(), transferSummary(state))
  }

  @Test
  fun speedSummary_downloading_showsSpeedAndTimeLeft() {
    val state = DownloadState.Downloading(DownloadProgress(1024, 4096, 1024))

    assertEquals(listOf("1.0 KB/s", "3s left"), speedSummary(state))
  }

  @Test
  fun speedSummary_unknownTotal_omitsTimeLeft() {
    val state = DownloadState.Downloading(DownloadProgress(1024, 0, 1024))

    assertEquals(listOf("1.0 KB/s"), speedSummary(state))
  }

  @Test
  fun speedSummary_noSpeedYet_showsPlaceholder() {
    val state = DownloadState.Downloading(DownloadProgress(0, 4096, 0))

    assertEquals(listOf("--"), speedSummary(state))
  }

  @Test
  fun speedSummary_notDownloading_isEmpty() {
    val state = DownloadState.Paused(DownloadProgress(1024, 4096, 1024))

    assertEquals(emptyList(), speedSummary(state))
  }

  @Test
  fun speedLimitLabel_limitedDownload_showsLimit() {
    val state = DownloadState.Downloading(DownloadProgress(0, 4096, 1024))

    assertEquals("limit 2.0 KB/s", speedLimitLabel(state, SpeedLimit.of(2048)))
  }

  @Test
  fun speedLimitLabel_unlimitedOrNotRunning_isNull() {
    val downloading = DownloadState.Downloading(DownloadProgress(0, 4096, 1024))
    val paused = DownloadState.Paused(DownloadProgress(0, 4096))

    assertNull(speedLimitLabel(downloading, SpeedLimit.Unlimited))
    assertNull(speedLimitLabel(paused, SpeedLimit.of(2048)))
  }
}
