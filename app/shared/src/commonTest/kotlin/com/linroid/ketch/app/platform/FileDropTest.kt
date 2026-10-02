package com.linroid.ketch.app.platform

import com.linroid.ketch.app.FakeKetchApi
import com.linroid.ketch.app.instance.InstanceFactory
import com.linroid.ketch.app.instance.InstanceManager
import com.linroid.ketch.app.state.AppState
import com.linroid.ketch.app.state.IntakeRequest
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class FileDropTest {

  @Test
  fun droppedLinkList_text_readsAsTextFile() = runTest {
    val list = droppedLinkList("https://example.com/a.iso")

    assertEquals(DROPPED_TEXT_NAME, list.name)
    assertTrue(list.name.endsWith(".txt"))
    assertContentEquals("https://example.com/a.iso".encodeToByteArray(), list.readBytes(25))
    assertFailsWith<IllegalArgumentException> { list.readBytes(24) }
  }

  @Test
  fun droppedLinkList_droppedOnTheApp_opensTheAddSheetWithTheText() = runTest {
    val manager = InstanceManager(InstanceFactory(embeddedFactory = { FakeKetchApi() }))
    try {
      val state = AppState(manager, backgroundScope)
      val text = "Ubuntu\nhttps://releases.ubuntu.com/ubuntu.iso"

      state.addDroppedFiles(listOf(droppedLinkList(text)))
      runCurrent()

      assertEquals(IntakeRequest(text = text), state.intakeRequest)
      assertTrue(state.showAddDialog)
      assertTrue(state.messages.history.value.isEmpty())
    } finally {
      manager.close()
    }
  }

  @Test
  fun uriListEntries_uriList_dropsCommentsAndBlankLines() {
    val list = "# Ubuntu\r\nhttps://a.org/u.iso\r\n\r\n  magnet:?xt=urn:btih:abc  \r\n"

    assertEquals(
      listOf("https://a.org/u.iso", "magnet:?xt=urn:btih:abc"),
      uriListEntries(list),
    )
  }
}
