package com.linroid.ketch.app.ui.list

import com.linroid.ketch.api.DownloadProgress
import com.linroid.ketch.api.DownloadState
import com.linroid.ketch.app.i18n.load
import com.linroid.ketch.app.state.ListFixtures
import com.linroid.ketch.app.theme.lightKetchColors
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

class DownloadListRowTest {
  private val colors = lightKetchColors()

  @Test
  fun secondLineParts_downloadingOnTouch_showsSizeSpeedAndTimeLeft() = runTest {
    val state = DownloadState.Downloading(DownloadProgress(1L shl 30, 4L shl 30, 1L shl 20))
    val row = ListFixtures.row("iso", state)

    val parts = secondLineParts(row, touch = true)

    assertEquals(listOf("1.00 of 4.00 GB", "1.0 MB/s", "51m 12s"), parts.map { it.text.load() })
    assertEquals(listOf(true, true, true), parts.map { it.unbroken })
  }

  @Test
  fun secondLineParts_queued_isTheReasonThatMayWrap() = runTest {
    val row = ListFixtures.row("queued", DownloadState.Queued)

    val part = secondLineParts(row, touch = false).single()

    assertEquals(row.content.detail.load(), part.text.load())
    assertEquals(false, part.unbroken)
  }

  @Test
  fun secondLine_failure_leadsWithTheErrorTitleInTheFailedColor() {
    val text = secondLine(
      "Access denied (403)",
      "the link may have expired",
      listOf("1 KB"),
      colors
    )

    assertEquals("Access denied (403) · the link may have expired · 1 KB", text.text)
    assertEquals(colors.status.failed.color, text.spanStyles.single().item.color)
  }
}
