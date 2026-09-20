package com.linroid.ketch.torrent

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import okio.ByteString.Companion.toByteString
import okio.FileSystem
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs

class TorrentV2EngineRateTest {
  @Test
  fun engineAndSessionLimitsGateTcpRequestsAndCanBeRemovedWhileDownloading() = runTest {
    withContext(Dispatchers.Default) {
      withTimeout(15_000) {
        val bytes = ByteArray(32_768) { it.toByte() }
        val root = sha256Digest(sha256Digest(bytes.copyOfRange(0, 16_384)) +
          sha256Digest(bytes.copyOfRange(16_384, bytes.size)))
        val document = TorrentV2Document.parse(Bencode.encode(mapOf("info" to mapOf(
          "meta version" to 2L, "piece length" to 32_768L,
          "file tree" to mapOf("a" to mapOf("" to mapOf("length" to bytes.size.toLong(),
            "pieces root" to root)))
        ), "piece layers" to emptyMap<String, Any>())))
        val output = FileSystem.SYSTEM_TEMPORARY_DIRECTORY /
          "ketch-v2-rate-${InfoHash.fromBytes(torrentRandomBytes(20)).hex}"
        val network = createTorrentNetwork()
        val listener = network.listen(PeerEndpoint("127.0.0.1", 0))
        val peerBudget = TorrentBufferBudget(1024 * 1024)
        val firstReply = CompletableDeferred<Unit>()
        val secondRequest = CompletableDeferred<Unit>()
        val server = async {
          val connection = listener.accept()
          try {
            PeerIdentityHandshake(document.identity).respond(connection,
              ByteArray(20) { 2 }.toByteString(), peerBudget)
            val wire = PeerWire(connection, pieceCount = 1)
            wire.send(PeerMessage.Bitfield(byteArrayOf(128.toByte())))
            wire.send(PeerMessage.Control(PeerMessage.Signal.UNCHOKE))
            assertEquals(PeerMessage.Control(PeerMessage.Signal.INTERESTED), wire.read())
            repeat(2) { index ->
              val request = assertIs<PeerMessage.Request>(wire.read())
              assertEquals(index * 16_384, request.begin)
              if (index == 1) secondRequest.complete(Unit)
              wire.send(PeerMessage.Piece(0, request.begin,
                bytes.copyOfRange(request.begin, request.begin + request.length)))
              if (index == 0) firstReply.complete(Unit)
            }
          } finally { connection.close() }
        }
        val engine = KotlinTorrentEngine(TorrentConfig(dhtEnabled = false))
        try {
          engine.start()
          engine.setDownloadRateLimit(1)
          engine.withV2Download("rate", document, output.toString(),
            discover = { it.send(listener.local); awaitCancellation() }) { session ->
            session.setDownloadRateLimit(1)
            session.resume()
            firstReply.await()
            delay(100)
            assertFalse(secondRequest.isCompleted)
            engine.setDownloadRateLimit(0)
            delay(100)
            assertFalse(secondRequest.isCompleted)
            session.setDownloadRateLimit(0)
            session.state.first { it == TorrentSessionState.FINISHED }
            assertEquals(bytes.size.toLong(), session.verifiedBytes.value)
          }
          server.await()
        } finally {
          engine.stop()
          listener.close()
          network.close()
          server.cancelAndJoin()
          torrentFileSystem.deleteRecursively(output, mustExist = false)
        }
        assertEquals(0, peerBudget.allocated)
        assertEquals(0, engine.admittedSessionBytes)
      }
    }
  }
}
