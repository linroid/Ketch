package com.linroid.ketch.app.ui.intake

import com.linroid.ketch.app.ui.downloads.ClipboardLink
import com.linroid.ketch.config.ClipboardMode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class PasteAreaTest {

  private val found = ClipboardLink.Found(
    url = "https://releases.ubuntu.com/24.04/ubuntu-24.04-desktop-amd64.iso",
    label = "ubuntu-24.04-desktop-amd64.iso · releases.ubuntu.com",
    hash = "abc",
  )

  @Test
  fun clipboardOffer_linkReadSilently_namesTheFile() {
    val offer = clipboardOffer(ClipboardMode.Suggest, found)

    assertEquals(
      ClipboardOffer("Paste ubuntu-24.04-desktop-amd64.iso from clipboard", "abc"),
      offer,
    )
  }

  @Test
  fun clipboardOffer_short_leavesTheClipboardToTheGlyph() {
    val offer = clipboardOffer(ClipboardMode.Fill, found, short = true)

    assertEquals("Paste ubuntu-24.04-desktop-amd64.iso", offer?.label)
  }

  @Test
  fun clipboardOffer_clipboardOff_offersNothing() {
    assertNull(clipboardOffer(ClipboardMode.Off, found))
  }

  @Test
  fun clipboardOffer_linkOnlyGuessedWhileFilling_offersNothing() {
    // A sheet that fills itself from the clipboard already read what was there.
    assertNull(clipboardOffer(ClipboardMode.Fill, ClipboardLink.Maybe))
    assertNull(clipboardOffer(ClipboardMode.Suggest, ClipboardLink.None))
  }
}
