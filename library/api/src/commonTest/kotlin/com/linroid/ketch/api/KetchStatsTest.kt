package com.linroid.ketch.api

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.time.Duration

class KetchStatsTest {
  @Test
  fun of_countsOnlyDownloadingTasks_andSizesSeparately() {
    val stats = KetchStats.of(
      listOf(
        DownloadState.Downloading(DownloadProgress(40, 100, 10)),
        // Overshoot never makes the remaining bytes negative.
        DownloadState.Downloading(DownloadProgress(120, 100, 5)),
        DownloadState.Downloading(DownloadProgress(7, 0, 3)),
        DownloadState.Paused(DownloadProgress(50, 100)),
        DownloadState.Queued,
      ),
    )

    assertEquals(KetchStats(18, 160, 200, 60, 3, 1), stats)
  }

  @Test
  fun taskInterest_refusesIntervalsThatAreNotPositive() {
    assertFailsWith<IllegalArgumentException> { TaskInterest(progressInterval = Duration.ZERO) }
    assertFailsWith<IllegalArgumentException> {
      TaskInterest(segmentsInterval = -Duration.INFINITE)
    }
  }
}
