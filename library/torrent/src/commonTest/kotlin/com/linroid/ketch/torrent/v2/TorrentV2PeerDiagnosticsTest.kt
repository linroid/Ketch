package com.linroid.ketch.torrent.v2

import com.linroid.ketch.torrent.crypto.sha256Digest
import com.linroid.ketch.torrent.crypto.torrentRandomBytes
import com.linroid.ketch.torrent.engine.TorrentBufferBudget
import com.linroid.ketch.torrent.engine.TorrentSessionState
import com.linroid.ketch.torrent.metainfo.Bencode
import com.linroid.ketch.torrent.metainfo.InfoHash
import com.linroid.ketch.torrent.metainfo.TorrentContentLayout
import com.linroid.ketch.torrent.metainfo.TorrentV2Document
import com.linroid.ketch.torrent.net.PeerEndpoint
import com.linroid.ketch.torrent.net.TorrentConnection
import com.linroid.ketch.torrent.net.createTorrentNetwork
import com.linroid.ketch.torrent.peer.PeerIdentityHandshake
import com.linroid.ketch.torrent.peer.PeerMessage
import com.linroid.ketch.torrent.peer.PeerOutcome
import com.linroid.ketch.torrent.peer.PeerOutcomeReport
import com.linroid.ketch.torrent.peer.PeerWire
import com.linroid.ketch.torrent.storage.TorrentV2PieceStore
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.SendChannel
import kotlinx.coroutines.delay
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
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The peer diagnostics a v2 session records, as `TorrentPeerDiagnosticsTest` does for v1. */
class TorrentV2PeerDiagnosticsTest {
  private val bytes = byteArrayOf(1, 2, 3, 4)
  private val document = TorrentV2Document.parse(Bencode.encode(mapOf(
    "info" to mapOf("name" to "pack", "meta version" to 2L, "piece length" to 16_384L,
      "file tree" to mapOf("a" to mapOf("" to mapOf("length" to 4L,
        "pieces root" to sha256Digest(bytes))))),
    "piece layers" to emptyMap<String, Any>()
  )))
  private val layout = TorrentContentLayout.from(document.info)
  private val peerId = ByteArray(20) { 1 }.toByteString()

  private class Fixture(val scope: CoroutineScope) {
    val output = FileSystem.SYSTEM_TEMPORARY_DIRECTORY /
      "ketch-v2-diagnostics-${InfoHash.fromBytes(torrentRandomBytes(20)).hex}"
    val buffers = TorrentBufferBudget(4 * 1024 * 1024)
    val state = TorrentBufferBudget(4 * 1024 * 1024)
    val network = createTorrentNetwork()
    /** Written by the session loop only; read once the session closed. */
    val reports = mutableListOf<PeerOutcomeReport>()
  }

  private suspend fun fixture(body: suspend Fixture.() -> Unit) = withContext(Dispatchers.Default) {
    withTimeout(15_000) {
      val fixture = Fixture(this)
      try { fixture.body() } finally {
        withContext(NonCancellable) { fixture.network.close() }
      }
    }
  }

  private suspend fun Fixture.session(
    onReport: (PeerOutcomeReport) -> Unit,
    discover: suspend (SendChannel<PeerEndpoint>) -> Unit,
    body: suspend (TorrentV2DownloadSession) -> Unit,
  ) {
    val store = TorrentV2PieceStore(document, output, emptySet(), "diagnostics", buffers,
      Semaphore(4))
    val session = TorrentV2DownloadSession.open(scope, document, layout, emptySet(), store,
      TorrentV2Runtime(network, peerId, buffers, state, peerOutcomes = {
        reports += it
        onReport(it)
      }),
      TorrentV2SessionOptions(maxPeers = 4, discovery = { it.relay(discover = discover) }))
    try { body(session) } finally {
      withContext(NonCancellable) {
        session.close()
        store.cleanup()
      }
    }
  }

  @Test
  fun dials_recordFurthestStageAndOutcome() = runTest {
    fixture {
      // A seed that answers once the other two dials are recorded.
      val seed = network.listen(PeerEndpoint("127.0.0.1", 0))
      // Accepts and closes at once, as peers requiring encryption close a plaintext handshake.
      val closer = network.listen(PeerEndpoint("127.0.0.1", 0))
      // Nobody listens there any more.
      val refused = network.listen(PeerEndpoint("127.0.0.1", 0)).let {
        it.close()
        it.local
      }
      val othersFailed = CompletableDeferred<Unit>()
      val seeding = scope.launch {
        val connection = seed.accept()
        try {
          PeerIdentityHandshake(document.identity).respond(connection,
            ByteArray(20) { 2 }.toByteString(), buffers)
          val wire = PeerWire(connection, pieceCount = 1)
          wire.send(PeerMessage.Bitfield(byteArrayOf(128.toByte())))
          othersFailed.await()
          wire.send(PeerMessage.Control(PeerMessage.Signal.UNCHOKE))
          while (true) {
            val request = wire.read() as? PeerMessage.Request ?: continue
            wire.send(PeerMessage.Piece(request.index, request.begin, bytes))
          }
        } catch (_: IOException) {
          // The session closed the connection.
        } finally { connection.close() }
      }
      val closing = scope.launch {
        while (true) closer.accept().close()
      }
      try {
        session(
          onReport = {
            if (reports.any { it.endpoint == closer.local } &&
              reports.any { it.endpoint == refused }) othersFailed.complete(Unit)
          },
          discover = { peers ->
            peers.send(refused)
            peers.send(closer.local)
            peers.send(seed.local)
            awaitCancellation()
          },
        ) { session ->
          session.resume()
          assertEquals(TorrentSessionState.FINISHED, session.state.first {
            it == TorrentSessionState.FINISHED || it == TorrentSessionState.STOPPED
          }, session.failure.value?.stackTraceToString())
          session.pause()
        }
      } finally {
        seeding.cancelAndJoin()
        closing.cancelAndJoin()
        seed.close()
        closer.close()
      }

      val received = reports.single { it.endpoint == seed.local }
      assertEquals(PeerOutcome.RECEIVED, received.outcome)
      assertFalse(received.incoming)
      assertEquals(PeerIdentityHandshake.Mode.V2, received.mode)
      assertFalse(received.encrypted)
      assertFalse(received.utp)
      val closed = reports.filter { it.endpoint == closer.local }
      assertTrue(closed.isNotEmpty())
      assertTrue(closed.all { it.outcome == PeerOutcome.HANDSHAKE_CLOSED }, "$closed")
      assertTrue(closed.all { it.mode == PeerIdentityHandshake.Mode.V2 })
      val unreachable = reports.filter { it.endpoint == refused }
      assertTrue(unreachable.isNotEmpty())
      assertTrue(unreachable.all {
        it.outcome == PeerOutcome.REFUSED || it.outcome == PeerOutcome.CONNECT_FAILED
      }, "$unreachable")
    }
  }

  @Test
  fun incoming_failedHandshake_isRecordedAsIncoming() = runTest {
    fixture {
      val source = PeerEndpoint("10.0.0.9", 51413)
      val connection = object : TorrentConnection {
        override val remote = source
        override val encrypted = true
        override suspend fun readExactly(size: Int): ByteArray =
          throw IOException("Connection reset by peer")
        override suspend fun write(bytes: ByteArray) = Unit
        override fun close() = Unit
      }
      val recorded = CompletableDeferred<PeerOutcomeReport>()
      session(
        onReport = { if (it.incoming) recorded.complete(it) },
        discover = { awaitCancellation() },
      ) { session ->
        session.resume()
        // The transfer accepts peers once it runs.
        while (!session.accept(connection)) delay(10)
        val report = recorded.await()
        assertEquals(source, report.endpoint)
        assertEquals(PeerOutcome.HANDSHAKE_CLOSED, report.outcome)
        assertTrue(report.encrypted)
        assertNull(report.mode)
        session.pause()
      }
      assertEquals(listOf(source), reports.map { it.endpoint })
    }
  }
}
