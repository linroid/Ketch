package com.linroid.ketch.torrent

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import okio.ByteString.Companion.toByteString
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull

class PeerIdentityConnectionTest {
  @Test
  fun negotiatesV2BeforeExchangingAuthenticatedHashesOverTcp() = runTest {
    withContext(Dispatchers.Default) {
      withTimeout(10_000) {
        val hashes = (sha256Digest(ByteArray(16_384)) + sha256Digest(byteArrayOf(1)))
          .toByteString()
        val root = sha256Digest(hashes.toByteArray()).toByteString()
        val info = mapOf("meta version" to 2L, "piece length" to 16_384L,
          "file tree" to mapOf("file" to mapOf("" to mapOf(
            "length" to 16_385L, "pieces root" to root))))
        val document = TorrentV2Document.parse(Bencode.encode(mapOf(
          "info" to info, "piece layers" to mapOf(root to hashes))))
        val handshake = PeerIdentityHandshake(document.identity)
        val clientId = ByteArray(20) { 1 }.toByteString()
        val serverId = ByteArray(20) { 2 }.toByteString()
        val handshakes = TorrentBufferBudget(32_768)
        val frames = TorrentBufferBudget(65_536)
        val hashBudget = TorrentBufferBudget(32_768)
        val network = createTorrentNetwork()
        val listener = network.listen(PeerEndpoint("127.0.0.1", 0))
        val selector = PeerHashSelector(root, 0, 0, 2, 0)
        val server = async {
          val connection = listener.accept()
          try {
            val route = handshake.respond(connection, serverId, handshakes)
            assertEquals(PeerIdentityHandshake.Mode.V2, route.mode)
            assertEquals(document.identity, route.identity)
            val wire = PeerWire(connection, pieceCount = 2)
            val request = assertIs<PeerMessage.Unknown>(wire.read())
            assertEquals(PeerHashMessage.Request(selector), PeerHashWire.decode(request))
            wire.send(PeerHashWire.encode(PeerHashMessage.Hashes(selector, hashes)))
          } finally { connection.close() }
        }
        var transport: PeerHashTransport? = null
        try {
          val connection = network.connect(listener.local)
          val route = handshake.initiate(connection, PeerIdentityHandshake.Mode.V2, clientId,
            handshakes, expectedPeerId = serverId)
          assertEquals(PeerIdentityHandshake.Mode.V2, route.mode)
          assertEquals(document.identity, route.identity)
          val exchange = PeerHashExchange(hashBudget, { requested ->
            document.info.files.firstOrNull { it.piecesRoot == requested }?.length
          })
          val client = PeerHashTransport(connection, exchange, frames, pieceCount = 2)
          transport = client
          assertNotNull(client.request(selector))
          val frame = client.read()
          try {
            val verified = assertIs<PeerHashTransport.Event.Verified>(client.accept(frame)).result
            try { assertEquals(hashes, verified.hashes) } finally { verified.close() }
          } finally { frame.close() }
          server.await()
          assertEquals(0, handshakes.allocated)
          assertEquals(0, frames.allocated)
          assertEquals(0, hashBudget.allocated)
        } finally {
          transport?.close()
          listener.close()
          network.close()
          server.cancel()
        }
      }
    }
  }
}
