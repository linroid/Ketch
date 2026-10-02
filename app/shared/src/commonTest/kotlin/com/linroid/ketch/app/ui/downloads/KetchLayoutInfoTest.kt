package com.linroid.ketch.app.ui.downloads

import androidx.compose.ui.unit.dp
import kotlin.test.Test
import kotlin.test.assertEquals

class KetchLayoutInfoTest {

  @Test
  fun of_widthsAroundTheBreakpoints_pickTheTier() {
    val tiers = listOf(360, 599, 600, 1023, 1024, 1440).map { KetchLayoutInfo.of(it.dp).tier }

    assertEquals(
      listOf(
        LayoutTier.Compact,
        LayoutTier.Compact,
        LayoutTier.Medium,
        LayoutTier.Medium,
        LayoutTier.Expanded,
        LayoutTier.Expanded
      ),
      tiers
    )
  }
}
