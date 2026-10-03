package com.linroid.ketch.app.ui.downloads

import com.linroid.ketch.app.i18n.load
import com.linroid.ketch.app.state.GroupBy
import com.linroid.ketch.app.state.ListArrangement
import com.linroid.ketch.app.state.SortKey
import com.linroid.ketch.app.theme.lightKetchColors
import kotlinx.coroutines.test.runTest
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
  fun reasonText_failure_startsWithTheErrorTitleInTheFailedColor() {
    val colors = lightKetchColors()

    val text = reasonText("detail", "Access denied (403)", "the link may have expired", colors)

    assertEquals("Access denied (403) · the link may have expired", text.text)
    assertEquals(colors.status.failed.color, text.spanStyles.single().item.color)
  }

  @Test
  fun reasonText_noFailure_isTheDetail() {
    assertEquals(
      "Waiting to start",
      reasonText("Waiting to start", null, null, lightKetchColors()).text
    )
  }

  @Test
  fun clearFinishedLabel_noneFinished_dropsTheCount() = runTest {
    assertEquals("Clear 4 finished", clearFinishedLabel(4).load())
    assertEquals("Clear finished", clearFinishedLabel(0).load())
  }

  @Test
  fun columnTitle_abbreviatedHeader_isSpelledOutInMenus() = runTest {
    assertEquals("Conn.", TableColumn.Connections.label.load())
    assertEquals("Connections", TableColumn.Connections.title.load())
    assertEquals("Left", TableColumn.Left.title.load())
  }
}
