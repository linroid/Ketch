package com.linroid.ketch.torrent

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import okio.ByteString.Companion.toByteString
import okio.FileSystem
import okio.IOException
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

class TorrentV2DownloadSessionTest {
  private val bytes = byteArrayOf(1, 2, 3, 4)
  private val document = TorrentV2Document.parse(Bencode.encode(mapOf(
    "info" to mapOf("name" to "pack", "meta version" to 2L, "piece length" to 16_384L,
      "file tree" to mapOf("a" to mapOf("" to mapOf("length" to 4L,
        "pieces root" to sha256Digest(bytes))))),
    "piece layers" to emptyMap<String, Any>()
  )))
  private val layout = TorrentContentLayout.from(document.info)
  private val peerId = ByteArray(20) { 1 }.toByteString()

  private inner class Fixture(val scope: CoroutineScope) {
    val output = FileSystem.SYSTEM_TEMPORARY_DIRECTORY /
      "ketch-v2-owner-${InfoHash.fromBytes(torrentRandomBytes(20)).hex}"
    val buffers = TorrentBufferBudget(4 * 1024 * 1024)
    val state = TorrentBufferBudget(4 * 1024 * 1024)
    val network = createTorrentNetwork()
    val store = TorrentV2PieceStore(document, output, emptySet(), "owner", buffers, Semaphore(4))

    suspend fun <T> run(
      discover: suspend (kotlinx.coroutines.channels.SendChannel<PeerEndpoint>) -> Unit,
      body: suspend (TorrentV2DownloadSession) -> T,
    ): T = TorrentV2DownloadSession.run(document, layout, emptySet(), store, network, peerId,
      buffers, state, maxPeers = 1, discover = discover, body = body)
  }

  private suspend fun fixture(body: suspend Fixture.() -> Unit) = withContext(Dispatchers.Default) {
    withTimeout(15_000) {
      val fixture = Fixture(this)
      try { fixture.body() } finally {
        withContext(NonCancellable) {
          fixture.network.close()
          fixture.store.cleanup()
        }
      }
      assertEquals(0, fixture.buffers.allocated)
      assertEquals(0, fixture.state.allocated)
    }
  }

  @Test
  fun resumedDownloadRechecksModifiedPayloadAndPublishesOnlyVerifiedBytes() = runTest {
    fixture {
      val listener = network.listen(PeerEndpoint("127.0.0.1", 0))
      val secondRequest = CompletableDeferred<Unit>()
      val release = CompletableDeferred<Unit>()
      val server = scope.async {
        repeat(2) { round ->
          val connection = listener.accept()
          try {
            PeerIdentityHandshake(document.identity).respond(connection,
              ByteArray(20) { 2 }.toByteString(), buffers)
            val wire = PeerWire(connection, pieceCount = 1)
            wire.send(PeerMessage.Bitfield(byteArrayOf(128.toByte())))
            wire.send(PeerMessage.Control(PeerMessage.Signal.UNCHOKE))
            assertEquals(PeerMessage.Control(PeerMessage.Signal.INTERESTED), wire.read())
            val request = assertIs<PeerMessage.Request>(wire.read())
            if (round == 1) { secondRequest.complete(Unit); release.await() }
            wire.send(PeerMessage.Piece(request.index, request.begin, bytes))
          } finally { connection.close() }
        }
      }
      try {
        run(discover = { it.send(listener.local); awaitCancellation() }) { session ->
          session.resume()
          session.state.first { it == TorrentSessionState.FINISHED }
          assertEquals(4L, session.verifiedBytes.value)
          session.pause()
          torrentFileSystem.write(output / "a") { write(ByteArray(4)) }
          session.resume()
          secondRequest.await()
          assertEquals(0L, session.verifiedBytes.value)
          release.complete(Unit)
          session.state.first { it == TorrentSessionState.FINISHED }
          assertEquals(4L, session.verifiedBytes.value)
          assertContentEquals(bytes, torrentFileSystem.read(output / "a") { readByteArray() })
        }
        server.await()
      } finally { listener.close(); server.cancelAndJoin() }
    }
  }

  @Test
  fun pauseWaitsForDiscoveryCleanupAndResumeCreatesANewLifetime() = runTest {
    fixture {
      val entered = CompletableDeferred<Unit>()
      val cleaning = CompletableDeferred<Unit>()
      val release = CompletableDeferred<Unit>()
      val restarted = CompletableDeferred<Unit>()
      var starts = 0
      run(discover = {
        starts++
        if (starts == 2) restarted.complete(Unit)
        entered.complete(Unit)
        try { awaitCancellation() } finally {
          withContext(NonCancellable) { cleaning.complete(Unit); release.await() }
        }
      }) { session ->
        val retained = state.allocated
        session.resume()
        entered.await()
        val pause = scope.launch { session.pause() }
        cleaning.await()
        assertFalse(pause.isCompleted)
        assertTrue(state.allocated >= retained)
        release.complete(Unit)
        pause.join()
        assertEquals(TorrentSessionState.PAUSED, session.state.value)
        assertEquals(retained, state.allocated)
        assertEquals(0, buffers.allocated)
        session.resume()
        restarted.await()
        session.pause()
        assertEquals(2, starts)
      }
    }
  }

  @Test
  fun discoveryFailureStopsSessionAndCanBeRetried() = runTest {
    fixture {
      var attempts = 0
      run(discover = { attempts++; throw IOException("Discovery failed") }) { session ->
        repeat(2) {
          session.resume()
          session.state.first { it == TorrentSessionState.STOPPED }
          assertIs<IOException>(session.failure.value)
          assertEquals(0L, session.verifiedBytes.value)
        }
        assertEquals(2, attempts)
      }
    }
  }

  @Test
  fun ownerExitJoinsDiscoveryBeforeReturningAdmissionAndClosingTheStore() = runTest {
    fixture {
      val ready = CompletableDeferred<TorrentV2DownloadSession>()
      val entered = CompletableDeferred<Unit>()
      val finish = CompletableDeferred<Unit>()
      val cleaning = CompletableDeferred<Unit>()
      val release = CompletableDeferred<Unit>()
      val owner = scope.async {
        run(discover = {
          entered.complete(Unit)
          try { awaitCancellation() } finally {
            withContext(NonCancellable) { cleaning.complete(Unit); release.await() }
          }
        }) { session -> ready.complete(session); finish.await() }
      }
      val session = ready.await()
      session.resume()
      entered.await()
      finish.complete(Unit)
      cleaning.await()
      assertFalse(owner.isCompleted)
      assertTrue(state.allocated > 0)
      release.complete(Unit)
      owner.await()
      assertEquals(0, state.allocated)
      assertEquals(0, buffers.allocated)
      assertEquals(TorrentSessionState.STOPPED, session.state.value)
      assertFailsWith<IllegalStateException> { session.resume() }
      assertFailsWith<IllegalStateException> { store.initialize() }
    }
  }

  @Test
  fun rejectsHybridLayoutThatChangesSelectedV1FileIdsBeforeIo() = runTest {
    val hybrid = TorrentV2Document.parse(Bencode.encode(mapOf("info" to mapOf(
      "name" to "hybrid", "meta version" to 2L, "piece length" to 16_384L,
      "file tree" to mapOf(
        "a" to mapOf("" to mapOf("length" to 4L, "pieces root" to sha256Digest(bytes))),
        "b" to mapOf("" to mapOf("length" to 4L, "pieces root" to sha256Digest(bytes)))),
      "files" to listOf(mapOf("path" to listOf("a"), "length" to 4L),
        mapOf("attr" to "p", "length" to 16_380L),
        mapOf("path" to listOf("b"), "length" to 4L)),
      "pieces" to (sha1Digest(bytes + ByteArray(16_380)) + sha1Digest(bytes))
    ), "piece layers" to emptyMap<String, Any>())))
    val output = FileSystem.SYSTEM_TEMPORARY_DIRECTORY /
      "ketch-layout-bind-${InfoHash.fromBytes(torrentRandomBytes(20)).hex}"
    val buffers = TorrentBufferBudget(1024 * 1024)
    val state = TorrentBufferBudget(1024 * 1024)
    val network = createTorrentNetwork()
    val store = TorrentV2PieceStore(hybrid, output, setOf("2"), "hybrid", buffers, Semaphore(1))
    try {
      assertFailsWith<IllegalArgumentException> {
        TorrentV2DownloadSession.run(hybrid, TorrentContentLayout.from(hybrid.info), setOf("2"),
          store, network, peerId, buffers, state, discover = { error("Unexpected discovery") }) {
          error("Accepted a layout with the wrong file IDs")
        }
      }
      assertFalse(torrentFileSystem.exists(output))
      assertEquals(0, state.allocated)
      TorrentV2DownloadSession.run(hybrid, TorrentContentLayout.from(hybrid.info, hybrid.hybrid),
        setOf("2"), store, network, peerId, buffers, state, discover = {}) {}
    } finally { network.close(); store.cleanup() }
    assertEquals(0, state.allocated)
  }

  @Test
  fun selectionMismatchRejectsBeforeCreatingFilesOrStartingDiscovery() = runTest {
    fixture {
      assertFailsWith<IllegalArgumentException> {
        TorrentV2DownloadSession.run(document, layout, setOf("unknown"), store, network, peerId,
          buffers, state, discover = { error("Unexpected discovery") }) {
          error("Unexpected session")
        }
      }
      assertFalse(torrentFileSystem.exists(output))
    }
  }
}
