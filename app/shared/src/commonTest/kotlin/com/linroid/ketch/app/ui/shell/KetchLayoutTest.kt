package com.linroid.ketch.app.ui.shell

import androidx.compose.ui.unit.dp
import com.linroid.ketch.app.ui.downloads.LayoutTier
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class KetchLayoutTest {

  @Test
  fun of_phoneWidth_usesThePhoneShellFullBleed() {
    val layout = KetchLayout.of(360.dp)

    assertEquals(ShellNavigation.Phone, layout.navigation)
    assertTrue(layout.fullBleed)
    assertEquals(360.dp, layout.cardWidth)
  }

  @Test
  fun of_mediumWidths_useTheRailAndKeepTheCardFrom840() {
    val narrow = KetchLayout.of(600.dp)
    val wide = KetchLayout.of(840.dp)

    assertEquals(ShellNavigation.Rail, narrow.navigation)
    assertTrue(narrow.fullBleed)
    assertEquals(528.dp, narrow.cardWidth)
    assertEquals(ShellNavigation.Rail, wide.navigation)
    assertFalse(wide.fullBleed)
    assertEquals(760.dp, wide.cardWidth)
  }

  @Test
  fun of_defaultDesktopWindow_docksTheInspectorBesideTheSidebar() {
    val layout = KetchLayout.of(1280.dp)

    assertEquals(LayoutTier.Expanded, layout.tier)
    assertEquals(ShellNavigation.Sidebar, layout.navigation)
    assertEquals(1052.dp, layout.cardWidth)
  }

  @Test
  fun of_smallDesktopWindow_keepsTheSidebar() {
    val layout = KetchLayout.of(1024.dp)

    assertEquals(ShellNavigation.Sidebar, layout.navigation)
    assertEquals(796.dp, layout.cardWidth)
  }

  @Test
  fun of_collapsedSidebar_usesTheRailOnWideWindows() {
    val layout = KetchLayout.of(1280.dp, sidebarCollapsed = true)

    assertEquals(ShellNavigation.Rail, layout.navigation)
    assertEquals(1200.dp, layout.cardWidth)
    assertEquals(ShellNavigation.Phone, KetchLayout.of(390.dp, sidebarCollapsed = true).navigation)
  }
}
