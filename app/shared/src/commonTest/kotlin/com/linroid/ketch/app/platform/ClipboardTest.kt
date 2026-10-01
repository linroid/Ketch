package com.linroid.ketch.app.platform

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ClipboardTest {
  @Test
  fun holdsLink_linkInProse_returnsTrue() {
    assertTrue(holdsLink("Grab it from https://releases.ubuntu.com/24.04/ubuntu.iso today"))
  }

  @Test
  fun holdsLink_magnet_returnsTrue() {
    assertTrue(holdsLink("magnet:?xt=urn:btih:0123456789abcdef0123456789abcdef01234567"))
  }

  @Test
  fun holdsLink_plainWords_returnsFalse() {
    assertFalse(holdsLink("Meeting moved to Thursday afternoon"))
  }

  @Test
  fun holdsLink_linkPastScannedStart_returnsFalse() {
    val text = "word ".repeat(20_000) + "https://example.com/file.zip"

    assertFalse(holdsLink(text))
  }
}
