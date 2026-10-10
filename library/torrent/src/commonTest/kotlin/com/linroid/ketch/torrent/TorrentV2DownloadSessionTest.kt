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
import kotlin.concurrent.atomics.AtomicInt
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.concurrent.atomics.incrementAndFetch
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

@OptIn(ExperimentalAtomicApi::class)
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

  // Two files of one piece each: a ("0") is piece 0, b ("1") is piece 1.
  private val pair = TorrentV2Fixture.build(listOf("a" to 4, "b" to 6), pieceLength = 16_384)

  @Test
  fun changeSelection_downloading_keepsThePeerSocket() = runTest {
    fixture {
      val listener = network.listen(PeerEndpoint("127.0.0.1", 0))
      val accepted = AtomicInt(0)
      val firstRequest = CompletableDeferred<Unit>()
      val release = CompletableDeferred<Unit>()
      // A seed with both pieces that holds a back until the test releases it.
      val server = scope.launch {
        while (true) {
          val connection = listener.accept()
          accepted.incrementAndFetch()
          launch {
            try {
              PeerIdentityHandshake(pair.document.identity).respond(connection,
                ByteArray(20) { 2 }.toByteString(), buffers)
              val wire = PeerWire(connection, pieceCount = 2)
              wire.send(PeerMessage.Bitfield(byteArrayOf(192.toByte())))
              wire.send(PeerMessage.Control(PeerMessage.Signal.UNCHOKE))
              while (true) {
                val request = wire.read() as? PeerMessage.Request ?: continue
                if (request.index == 0) {
                  firstRequest.complete(Unit)
                  release.await()
                }
                wire.send(PeerMessage.Piece(request.index, request.begin,
                  pair.v2Piece(request.index)))
              }
            } catch (_: IOException) {
              // The session closed the connection.
            } finally { connection.close() }
          }
        }
      }
      val store = TorrentV2PieceStore(pair.document, output, setOf("0"), "owner", buffers,
        Semaphore(4))
      try {
        TorrentV2DownloadSession.run(pair.document, pair.layout, setOf("0"), store, network,
          peerId, buffers, state, maxPeers = 1,
          discover = { it.send(listener.local); awaitCancellation() }) { session ->
          session.resume()
          firstRequest.await()
          assertEquals(4L, session.totalBytes)
          // Taken by the running transfer, over the same connection.
          assertTrue(session.changeSelection(setOf("0", "1")))
          assertEquals(setOf("0", "1"), session.selectedFileIds)
          assertEquals(10L, session.totalBytes)
          release.complete(Unit)
          assertEquals(TorrentSessionState.FINISHED, session.state.first {
            it == TorrentSessionState.FINISHED || it == TorrentSessionState.STOPPED
          }, session.failure.value?.stackTraceToString())
          assertEquals(10L, session.verifiedBytes.value)
          // Finished, a change is only saved: the next resume downloads it.
          assertFalse(session.changeSelection(setOf("1")))
          assertEquals(6L, session.totalBytes)
        }
        assertEquals(1, accepted.load())
        assertContentEquals(pair.payloads[1], torrentFileSystem.read(output / "b") {
          readByteArray()
        })
      } finally {
        server.cancelAndJoin()
        listener.close()
        store.cleanup()
      }
    }
  }

  @Test
  fun resume_seedOnlyChangedPayload_stopsWithoutDiscovery() = runTest {
    fixture {
      var discoveries = 0
      val session = TorrentV2DownloadSession.open(scope, document, layout, emptySet(), store,
        TorrentV2Runtime(network, peerId, buffers, state,
          uploadPolicy = { TorrentUploadPolicy.SEED_AFTER_COMPLETION }),
        TorrentV2SessionOptions(maxPeers = 1, seedOnly = true, discovery = {
          discoveries++
          awaitCancellation()
        }))
      try {
        // The files of a completed torrent are gone: seeding would download them instead.
        session.resume()
        assertEquals(TorrentSessionState.STOPPED, session.state.first {
          it == TorrentSessionState.STOPPED || it == TorrentSessionState.SEEDING
        })
        assertIs<IncompleteSeedException>(session.failure.value)
        assertEquals(0, discoveries)
      } finally {
        withContext(NonCancellable) { session.close() }
      }
    }
  }
}
