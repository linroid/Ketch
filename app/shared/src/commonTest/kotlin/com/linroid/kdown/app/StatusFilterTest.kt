package com.linroid.kdown.app

import com.linroid.kdown.api.DownloadProgress
import com.linroid.kdown.api.DownloadSchedule
import com.linroid.kdown.api.DownloadState
import com.linroid.kdown.api.KDownError
import com.linroid.kdown.app.state.StatusFilter
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class StatusFilterTest {

  private val allStates: List<DownloadState> = listOf(
    DownloadState.Idle,
    DownloadState.Scheduled(DownloadSchedule.Immediate),
    DownloadState.Queued,
    DownloadState.Pending,
    DownloadState.Downloading(
      DownloadProgress(
        downloadedBytes = 100,
        totalBytes = 1000,
        bytesPerSecond = 50,
      )
    ),
    DownloadState.Paused(
      DownloadProgress(
        downloadedBytes = 100,
        totalBytes = 1000,
      )
    ),
    DownloadState.Completed("/path/to/file"),
    DownloadState.Failed(KDownError.Network()),
    DownloadState.Canceled
  )

  // -----------------------------------------------------------
  // StatusFilter.All
  // -----------------------------------------------------------

  @Test
  fun all_matchesEveryState() {
    allStates.forEach { state ->
      assertTrue(
        StatusFilter.All.matches(state),
        "All should match $state"
      )
    }
  }

  // -----------------------------------------------------------
  // StatusFilter.Downloading
  // -----------------------------------------------------------

  @Test
  fun downloading_matchesDownloadingState() {
    val state = DownloadState.Downloading(
      DownloadProgress(50, 100, 10)
    )
    assertTrue(StatusFilter.Downloading.matches(state))
  }

  @Test
  fun downloading_matchesPendingState() {
    assertTrue(
      StatusFilter.Downloading.matches(DownloadState.Pending)
    )
  }

  @Test
  fun downloading_matchesQueuedState() {
    assertTrue(
      StatusFilter.Downloading.matches(DownloadState.Queued)
    )
  }

  @Test
  fun downloading_matchesScheduledState() {
    val state = DownloadState.Scheduled(
      DownloadSchedule.Immediate
    )
    assertTrue(StatusFilter.Downloading.matches(state))
  }

  @Test
  fun downloading_rejectsPaused() {
    val state = DownloadState.Paused(
      DownloadProgress(50, 100)
    )
    assertFalse(StatusFilter.Downloading.matches(state))
  }

  @Test
  fun downloading_rejectsCompleted() {
    assertFalse(
      StatusFilter.Downloading.matches(
        DownloadState.Completed("/file")
      )
    )
  }

  @Test
  fun downloading_rejectsFailed() {
    assertFalse(
      StatusFilter.Downloading.matches(
        DownloadState.Failed(KDownError.Network())
      )
    )
  }

  @Test
  fun downloading_rejectsCanceled() {
    assertFalse(
      StatusFilter.Downloading.matches(DownloadState.Canceled)
    )
  }

  @Test
  fun downloading_matchesIdleState() {
    assertTrue(
      StatusFilter.Downloading.matches(DownloadState.Idle)
    )
  }

  // -----------------------------------------------------------
  // StatusFilter.Paused
  // -----------------------------------------------------------

  @Test
  fun paused_matchesPausedState() {
    val state = DownloadState.Paused(
      DownloadProgress(50, 100)
    )
    assertTrue(StatusFilter.Paused.matches(state))
  }

  @Test
  fun paused_rejectsDownloading() {
    assertFalse(
      StatusFilter.Paused.matches(
        DownloadState.Downloading(
          DownloadProgress(50, 100, 10)
        )
      )
    )
  }

  @Test
  fun paused_rejectsCompleted() {
    assertFalse(
      StatusFilter.Paused.matches(
        DownloadState.Completed("/file")
      )
    )
  }

  @Test
  fun paused_rejectsFailed() {
    assertFalse(
      StatusFilter.Paused.matches(
        DownloadState.Failed(KDownError.Network())
      )
    )
  }

  @Test
  fun paused_rejectsCanceled() {
    assertFalse(
      StatusFilter.Paused.matches(DownloadState.Canceled)
    )
  }

  @Test
  fun paused_rejectsIdle() {
    assertFalse(
      StatusFilter.Paused.matches(DownloadState.Idle)
    )
  }

  @Test
  fun paused_rejectsPending() {
    assertFalse(
      StatusFilter.Paused.matches(DownloadState.Pending)
    )
  }

  @Test
  fun paused_rejectsQueued() {
    assertFalse(
      StatusFilter.Paused.matches(DownloadState.Queued)
    )
  }

  // -----------------------------------------------------------
  // StatusFilter.Completed
  // -----------------------------------------------------------

  @Test
  fun completed_matchesCompletedState() {
    assertTrue(
      StatusFilter.Completed.matches(
        DownloadState.Completed("/path/to/file.zip")
      )
    )
  }

  @Test
  fun completed_rejectsDownloading() {
    assertFalse(
      StatusFilter.Completed.matches(
        DownloadState.Downloading(
          DownloadProgress(50, 100, 10)
        )
      )
    )
  }

  @Test
  fun completed_rejectsPaused() {
    assertFalse(
      StatusFilter.Completed.matches(
        DownloadState.Paused(DownloadProgress(50, 100))
      )
    )
  }

  @Test
  fun completed_rejectsFailed() {
    assertFalse(
      StatusFilter.Completed.matches(
        DownloadState.Failed(KDownError.Network())
      )
    )
  }

  @Test
  fun completed_rejectsCanceled() {
    assertFalse(
      StatusFilter.Completed.matches(DownloadState.Canceled)
    )
  }

  @Test
  fun completed_rejectsIdle() {
    assertFalse(
      StatusFilter.Completed.matches(DownloadState.Idle)
    )
  }

  // -----------------------------------------------------------
  // StatusFilter.Failed
  // -----------------------------------------------------------

  @Test
  fun failed_matchesFailedState() {
    assertTrue(
      StatusFilter.Failed.matches(
        DownloadState.Failed(KDownError.Network())
      )
    )
  }

  @Test
  fun failed_matchesCanceledState() {
    assertTrue(
      StatusFilter.Failed.matches(DownloadState.Canceled)
    )
  }

  @Test
  fun failed_matchesFailedWithDifferentErrors() {
    assertTrue(
      StatusFilter.Failed.matches(
        DownloadState.Failed(KDownError.Disk())
      )
    )
    assertTrue(
      StatusFilter.Failed.matches(
        DownloadState.Failed(KDownError.Http(404, "Not Found"))
      )
    )
    assertTrue(
      StatusFilter.Failed.matches(
        DownloadState.Failed(KDownError.Unknown())
      )
    )
  }

  @Test
  fun failed_rejectsDownloading() {
    assertFalse(
      StatusFilter.Failed.matches(
        DownloadState.Downloading(
          DownloadProgress(50, 100, 10)
        )
      )
    )
  }

  @Test
  fun failed_rejectsPaused() {
    assertFalse(
      StatusFilter.Failed.matches(
        DownloadState.Paused(DownloadProgress(50, 100))
      )
    )
  }

  @Test
  fun failed_rejectsCompleted() {
    assertFalse(
      StatusFilter.Failed.matches(
        DownloadState.Completed("/file")
      )
    )
  }

  @Test
  fun failed_rejectsIdle() {
    assertFalse(
      StatusFilter.Failed.matches(DownloadState.Idle)
    )
  }

  @Test
  fun failed_rejectsPending() {
    assertFalse(
      StatusFilter.Failed.matches(DownloadState.Pending)
    )
  }

  @Test
  fun failed_rejectsQueued() {
    assertFalse(
      StatusFilter.Failed.matches(DownloadState.Queued)
    )
  }

  // -----------------------------------------------------------
  // Cross-filter coverage: each state matched by exactly one
  // non-All filter
  // -----------------------------------------------------------

  @Test
  fun eachState_matchedByExactlyOneNonAllFilter() {
    val nonAllFilters = StatusFilter.entries.filter {
      it != StatusFilter.All
    }
    allStates.forEach { state ->
      val matchingFilters = nonAllFilters.filter {
        it.matches(state)
      }
      assertTrue(
        matchingFilters.size == 1,
        "State $state should match exactly one filter, " +
          "but matched: $matchingFilters"
      )
    }
  }
}
