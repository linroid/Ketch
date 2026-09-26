package com.linroid.ketch.app

import com.linroid.ketch.app.instance.InstanceFactory
import com.linroid.ketch.app.instance.InstanceManager
import com.linroid.ketch.app.state.AppState
import com.linroid.ketch.app.state.IncomingDownload
import com.linroid.ketch.app.state.IncomingDownloads
import com.linroid.ketch.app.state.MAX_TORRENT_FILE_BYTES
import com.linroid.ketch.app.state.torrentFileDownload
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runCurrent
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
  private val first = IncomingDownload.Ready("a.torrent", "data:a")
  private val second = IncomingDownload.Ready("b.torrent", "data:b")

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
  fun `opened files stay pending until completed`() {
    val incoming = IncomingDownloads()
    incoming.offer(first)
    incoming.offer(second)
    incoming.offer(first)

    assertEquals(listOf(first, second), incoming.pending.value)
    incoming.complete(first)
    assertEquals(listOf(second), incoming.pending.value)
  }

  @Test
  fun `unreadable files are delivered once`() = runTest {
    val incoming = IncomingDownloads()
    incoming.offerUnreadable("b.torrent", Exception("Permission denied"))

    assertEquals(
      IncomingDownload.Failed("b.torrent", "Permission denied"),
      incoming.failures.first(),
    )
  }

  @Test
  fun `opened files are shown one after another`() = runTest {
    val incoming = IncomingDownloads()
    withManager { manager ->
      val state = AppState(manager, backgroundScope, incoming = incoming)
      incoming.offer(first)
      incoming.offer(second)
      runCurrent()

      assertTrue(state.showAddDialog)
      assertEquals(first, state.openedDownload)
      state.closeAddDialog()
      runCurrent()
      assertTrue(state.showAddDialog)
      assertEquals(second, state.openedDownload)
      state.closeAddDialog()
      runCurrent()
      assertFalse(state.showAddDialog)
      assertNull(state.openedDownload)
      assertEquals(emptyList(), incoming.pending.value)
    }
  }

  @Test
  fun `an opened file shows again in a recreated UI`() = runTest {
    val incoming = IncomingDownloads()
    withManager { manager ->
      AppState(manager, backgroundScope, incoming = incoming)
      incoming.offer(first)
      runCurrent()

      // Android recreates the activity, and with it the UI state, on rotation.
      val recreated = AppState(manager, backgroundScope, incoming = incoming)
      runCurrent()
      assertTrue(recreated.showAddDialog)
      assertEquals(first, recreated.openedDownload)
    }
  }

  @Test
  fun `opened files wait for a backend`() = runTest {
    val incoming = IncomingDownloads()
    withManager(embedded = false) { manager ->
      val state = AppState(manager, backgroundScope, incoming = incoming)
      incoming.offer(first)
      runCurrent()

      assertFalse(state.showAddDialog)
      assertTrue(state.showAddRemoteDialog)
      assertEquals(listOf(first), incoming.pending.value)
    }
  }

  @Test
  fun `unreadable files are reported without opening the dialog`() = runTest {
    val incoming = IncomingDownloads()
    withManager { manager ->
      val state = AppState(manager, backgroundScope, incoming = incoming)
      incoming.offer(IncomingDownload.Failed("a.torrent", "The file is empty"))
      runCurrent()

      assertFalse(state.showAddDialog)
      assertEquals("Couldn't open a.torrent: The file is empty", state.errorMessage)
    }
  }

  private inline fun withManager(embedded: Boolean = true, block: (InstanceManager) -> Unit) {
    val factory = if (embedded) {
      InstanceFactory(embeddedFactory = { FakeKetchApi("Core") })
    } else {
      InstanceFactory()
    }
    val manager = InstanceManager(factory)
    try {
      block(manager)
    } finally {
      manager.close()
    }
  }
}
