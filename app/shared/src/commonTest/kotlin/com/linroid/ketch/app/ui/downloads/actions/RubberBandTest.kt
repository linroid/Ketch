package com.linroid.ketch.app.ui.downloads.actions

import androidx.compose.foundation.lazy.LazyListItemInfo
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RubberBandTest {
  // Five 40 px rows from y = 0, then empty space down to y = 400.
  private val items = List(5) { FakeItem(it, offset = it * 40, size = 40) }

  @Test
  fun bandRange_upFromBelowTheLastRow_coversTheRowsCrossed() {
    val range = band(anchorY = 300f, pointerY = 90f)

    assertEquals(2..4, range)
  }

  @Test
  fun bandRange_downFromBelowTheLastRow_coversNothing() {
    assertTrue(band(anchorY = 300f, pointerY = 350f).isEmpty())
  }

  @Test
  fun bandRange_pointerInTheGapAboveTheRows_stopsAtTheFirstRowBelowIt() {
    val gapped = listOf(FakeItem(0, offset = 20, size = 40), FakeItem(1, offset = 60, size = 40))

    val range = bandRange(300f, above = 1, below = 2, pointerY = 5f, 0f, gapped, 0)

    assertEquals(0..1, range)
  }

  @Test
  fun bandRange_listScrolledUnderTheBand_keepsTheRowsAboveTheViewport() {
    // Rows 5..9 are on screen after scrolling 200 px; the band started at row 2's bottom.
    val scrolled = List(5) { FakeItem(it + 5, offset = it * 40, size = 40) }

    val range = bandRange(120f, above = 2, below = 3, pointerY = 50f, 200f, scrolled, 0)

    assertEquals(3..6, range)
  }

  private fun band(anchorY: Float, pointerY: Float): IntRange =
    bandRange(anchorY, above = 4, below = 5, pointerY, 0f, items, 0)

  private class FakeItem(
    override val index: Int,
    override val offset: Int,
    override val size: Int,
  ) : LazyListItemInfo {
    override val key: Any = index
  }
}
