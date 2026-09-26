package com.linroid.ketch.app

import com.linroid.ketch.app.instance.InstanceFactory
import com.linroid.ketch.app.instance.InstanceManager
import com.linroid.ketch.app.state.AppState
import com.linroid.ketch.app.state.IncomingDownload
import com.linroid.ketch.app.state.IncomingDownloads
import com.linroid.ketch.app.state.MAX_TORRENT_FILE_BYTES
import com.linroid.ketch.app.state.torrentFileDownload
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlin.io.encoding.Base64
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class IncomingDownloadsTest {
  private val prefix = "data:application/x-bittorrent;base64,"

  @Test
  fun `torrent files become inline data URLs`() {
    val bytes = "d4:infod4:name1:aee".encodeToByteArray()
    val download = assertIs<IncomingDownload.Ready>(torrentFileDownload("a.torrent", bytes))
    assertEquals("a.torrent", download.label)
    assertTrue(download.url.startsWith(prefix))
    assertContentEquals(bytes, Base64.Default.decode(download.url.removePrefix(prefix)))
  }

  @Test
  fun `empty and oversized torrent files are rejected`() {
    assertIs<IncomingDownload.Failed>(torrentFileDownload("empty.torrent", ByteArray(0)))
    assertIs<IncomingDownload.Ready>(
      torrentFileDownload("max.torrent", ByteArray(MAX_TORRENT_FILE_BYTES)),
    )
    val large = torrentFileDownload("large.torrent", ByteArray(MAX_TORRENT_FILE_BYTES + 1))
    assertEquals("The file is larger than 4 MiB", assertIs<IncomingDownload.Failed>(large).message)
  }

  @Test
  fun `downloads offered before the UI collects are kept in order`() = runTest {
    val incoming = IncomingDownloads()
    incoming.offer(IncomingDownload.Ready("a.torrent", "data:a"))
    incoming.offerUnreadable("b.torrent", Exception("Permission denied"))

    assertEquals(
      listOf(
        IncomingDownload.Ready("a.torrent", "data:a"),
        IncomingDownload.Failed("b.torrent", "Permission denied"),
      ),
      incoming.requests.take(2).toList(),
    )
  }

  @Test
  fun `opened files are shown one after another`() = withAppState { state ->
    val first = IncomingDownload.Ready("a.torrent", "data:a")
    val second = IncomingDownload.Ready("b.torrent", "data:b")
    state.openIncoming(first)
    state.openIncoming(second)
    state.openIncoming(first)

    assertTrue(state.showAddDialog)
    assertEquals(first, state.openedDownload)
    state.closeAddDialog()
    assertTrue(state.showAddDialog)
    assertEquals(second, state.openedDownload)
    state.closeAddDialog()
    assertFalse(state.showAddDialog)
    assertNull(state.openedDownload)
  }

  @Test
  fun `unreadable files are reported without opening the dialog`() = withAppState { state ->
    state.openIncoming(IncomingDownload.Failed("a.torrent", "The file is empty"))

    assertFalse(state.showAddDialog)
    assertEquals("Couldn't open a.torrent: The file is empty", state.errorMessage)
  }

  private fun withAppState(block: TestScope.(AppState) -> Unit) = runTest {
    val manager = InstanceManager(InstanceFactory(embeddedFactory = { FakeKetchApi("Core") }))
    try {
      block(AppState(manager, backgroundScope))
    } finally {
      manager.close()
    }
  }
}
