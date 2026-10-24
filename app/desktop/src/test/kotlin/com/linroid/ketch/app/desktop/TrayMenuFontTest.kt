package com.linroid.ketch.app.desktop

import kotlin.test.Test
import kotlin.test.assertEquals

class TrayMenuFontTest {
  @Test
  fun trayMenuFont_zoom_growsFromTheSizeOfWindowsMenusToTheNearestPixel() {
    assertEquals(12, trayMenuFont(1f).size)
    assertEquals(13, trayMenuFont(1.1f).size)
    assertEquals(18, trayMenuFont(1.5f).size)
    assertEquals(21, trayMenuFont(1.75f).size)
  }
}
