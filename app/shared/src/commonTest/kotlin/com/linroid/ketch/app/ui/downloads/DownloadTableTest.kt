package com.linroid.ketch.app.ui.downloads

import com.linroid.ketch.api.DownloadState
import com.linroid.ketch.api.KetchError
import com.linroid.ketch.app.state.GroupBy
import com.linroid.ketch.app.state.ListArrangement
import com.linroid.ketch.app.state.ListFixtures
import com.linroid.ketch.app.state.SortKey
import com.linroid.ketch.app.theme.lightKetchColors
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DownloadTableTest {
  @Test
  fun nextArrangement_threeClicks_sortReverseThenSmart() {
    val start = ListArrangement()

    val first = nextArrangement(start, SortKey.Speed)
    val second = nextArrangement(first, SortKey.Speed)
    val third = nextArrangement(second, SortKey.Speed)

    assertEquals(SortKey.Speed, first.sort)
    assertTrue(first.descending)
    assertEquals(SortKey.Speed, second.sort)
    assertFalse(second.descending)
    assertEquals(SortKey.Smart, third.sort)
  }

  @Test
  fun nextArrangement_otherColumn_keepsTheGrouping() {
    val grouped = ListArrangement(group = GroupBy.Status)

    val sorted = nextArrangement(grouped, SortKey.Name)

    assertEquals(GroupBy.Status, sorted.group)
    assertFalse(sorted.descending)
  }

  @Test
  fun reasonText_failedRow_startsWithTheErrorTitleInTheFailedColor() {
    val colors = lightKetchColors()
    val row = ListFixtures.row("weights", DownloadState.Failed(KetchError.Http(403, "Forbidden")))

    val text = reasonText(row, colors)

    val title = row.content.error!!.title
    assertTrue(text.text.startsWith(title))
    assertEquals(colors.status.failed.color, text.spanStyles.single().item.color)
  }

  @Test
  fun reasonText_queuedRow_isItsDetail() {
    val row = ListFixtures.row("queued", DownloadState.Queued)

    assertEquals(row.content.detail, reasonText(row, lightKetchColors()).text)
  }

  @Test
  fun clearFinishedLabel_noneFinished_dropsTheCount() {
    assertEquals("Clear 4 finished", clearFinishedLabel(4))
    assertEquals("Clear finished", clearFinishedLabel(0))
  }
}
