package com.linroid.ketch.app.ui.downloads

import com.linroid.ketch.app.components.AddButtonMode
import com.linroid.ketch.app.i18n.load
import com.linroid.ketch.app.util.urlHost
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

class AddButtonTest {
  private val found = ClipboardLink.Found(
    url = "https://releases.ubuntu.com/24.04.1/ubuntu-24.04.1-desktop-amd64.iso",
    label = "ubuntu-24.04.1-desktop-amd64.iso · releases.ubuntu.com",
    hash = "clip-1",
  )

  @Test
  fun addButtonMode_foundLink_offersItsFileNameAndSite() = runTest {
    val description = clipDescription(clipName(found.url), urlHost(found.url)).load()

    val mode = addButtonMode(found, dragging = false, over = false, description = description)

    assertEquals(
      AddButtonMode.Clip(
        name = "ubuntu-24.04.1-desktop-amd64.iso",
        description = "Download ubuntu-24.04.1-desktop-amd64.iso from releases.ubuntu.com",
        id = "clip-1",
      ),
      mode,
    )
  }

  @Test
  fun addButtonMode_linkOnlyProbablyThere_keepsThePlainButton() {
    assertEquals(AddButtonMode.Plain, addButtonMode(ClipboardLink.Maybe, false, false))
    assertEquals(AddButtonMode.Plain, addButtonMode(ClipboardLink.None, false, false))
  }

  @Test
  fun addButtonMode_dragging_becomesADropTargetOverACopiedLink() {
    assertEquals(AddButtonMode.Drop(over = false), addButtonMode(found, true, over = false))
    assertEquals(AddButtonMode.Drop(over = true), addButtonMode(ClipboardLink.None, true, true))
  }

  @Test
  fun clipDescription_hostIsTheName_leavesTheSiteOut() = runTest {
    assertEquals("Download example.com", clipDescription("example.com", "example.com").load())
    assertEquals("Download a.iso", clipDescription("a.iso", null).load())
  }
}
