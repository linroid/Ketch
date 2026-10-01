package com.linroid.ketch.app.ui.list

import com.linroid.ketch.api.DownloadProgress
import com.linroid.ketch.api.DownloadState
import com.linroid.ketch.api.KetchError
import com.linroid.ketch.app.state.ListFixtures
import com.linroid.ketch.app.theme.lightKetchColors
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DownloadListRowTest {
  private val colors = lightKetchColors()

  @Test
  fun secondLine_downloadingOnTouch_showsSizeSpeedAndTimeLeft() {
    val state = DownloadState.Downloading(DownloadProgress(1L shl 30, 4L shl 30, 1L shl 20))
    val row = ListFixtures.row("iso", state)

    val text = secondLine(row, touch = true, colors).text

    assertEquals("1.00 of 4.00 GB · 1.0 MB/s · 51m 12s", text)
  }

  @Test
  fun secondLine_failed_leadsWithTheErrorTitle() {
    val row = ListFixtures.row("weights", DownloadState.Failed(KetchError.Http(403, "Forbidden")))

    val text = secondLine(row, touch = false, colors)

    assertTrue(text.text.startsWith(row.content.error!!.title))
    assertEquals(colors.status.failed.color, text.spanStyles.single().item.color)
  }

  @Test
  fun secondLine_queued_isTheReason() {
    val row = ListFixtures.row("queued", DownloadState.Queued)

    assertEquals(row.content.detail, secondLine(row, touch = false, colors).text)
  }
}
