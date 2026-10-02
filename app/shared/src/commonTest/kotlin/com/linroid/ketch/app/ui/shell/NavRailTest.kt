package com.linroid.ketch.app.ui.shell

import com.linroid.ketch.api.DownloadProgress
import com.linroid.ketch.api.DownloadState
import com.linroid.ketch.app.state.ListFixtures
import com.linroid.ketch.app.state.TaskRow
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class NavRailTest {
  private fun row(id: String, state: DownloadState): TaskRow = ListFixtures.row(id, state)

  @Test
  fun aggregateProgress_downloadingTasks_weighsThemBySize() {
    val rows = listOf(
      row("a", DownloadState.Downloading(DownloadProgress(25, 100))),
      row("b", DownloadState.Downloading(DownloadProgress(275, 300))),
      row("c", DownloadState.Queued)
    )

    assertEquals(0.75f, aggregateProgress(rows))
  }

  @Test
  fun aggregateProgress_unknownSizes_leavesThemOut() {
    val rows = listOf(
      row("a", DownloadState.Downloading(DownloadProgress(50, 0))),
      row("b", DownloadState.Downloading(DownloadProgress(10, 40)))
    )

    assertEquals(0.25f, aggregateProgress(rows))
  }

  @Test
  fun aggregateProgress_nothingDownloading_isNull() {
    assertNull(aggregateProgress(listOf(row("a", DownloadState.Queued))))
    assertNull(aggregateProgress(emptyList()))
  }
}
