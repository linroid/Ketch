package com.linroid.ketch.app.theme

import androidx.compose.ui.unit.dp
import kotlin.test.Test
import kotlin.test.assertEquals

class WindowChromeTest {
  private val chrome = WindowChrome(top = 28.dp, leading = 72.dp)

  @Test
  fun zoomed_larger_coversLessOfTheContentAcrossAndKeepsTheTitleZone() {
    assertEquals(WindowChrome(top = 28.dp, leading = 36.dp), chrome.zoomed(2f))
  }

  @Test
  fun zoomed_smaller_growsBothToStillCoverTheControls() {
    assertEquals(WindowChrome(top = 56.dp, leading = 144.dp), chrome.zoomed(0.5f))
  }

  @Test
  fun zoomed_noChrome_staysNone() {
    assertEquals(WindowChrome.None, WindowChrome.None.zoomed(1.5f))
  }
}
