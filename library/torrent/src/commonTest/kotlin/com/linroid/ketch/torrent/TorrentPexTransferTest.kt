package com.linroid.ketch.torrent

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import okio.FileSystem
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals

class TorrentPexTransferTest {
  @Test
  fun pexDiscoveredPeer_completesMissingPayload() = runTest {
    withContext(Dispatchers.Default) {
      withTimeout(15_000) {
        coroutineScope {
          val bytes = byteArrayOf(1, 2, 3, 4)
          val metadata = TorrentMetadata.fromBencode(Bencode.encode(mapOf("info" to mapOf(
            "name" to "data", "length" to 4L, "piece length" to 4L,
            "pieces" to sha1Digest(bytes)
          ))))
          val root = FileSystem.SYSTEM_TEMPORARY_DIRECTORY /
            "ketch-pex-${InfoHash.fromBytes(torrentRandomBytes(20)).hex}"
          val store = TorrentPieceStore(metadata, root / "data", emptySet(), "test")
          val network = createTorrentNetwork()
          val first = network.listen(PeerEndpoint("127.0.0.1", 0))
          val second = network.listen(PeerEndpoint("127.0.0.1", 0))
          val budget = TorrentBufferBudget(2 * 1024 * 1024)
          val servers = listOf(first, second).mapIndexed { index, listener ->
            launch {
              val connection = listener.accept()
              try {
                val wire = PeerWire(connection, metadata)
                wire.handshake(PeerHandshake(metadata.infoHash, torrentRandomBytes(20), true, false))
                wire.send(PeerMessage.Extended(0, Bencode.encode(mapOf("m" to mapOf("ut_pex" to 7L)))))
                wire.send(PeerMessage.Bitfield(byteArrayOf(if (index == 0) 0 else 128.toByte())))
                wire.send(PeerMessage.Control(PeerMessage.Signal.UNCHOKE))
                if (index == 0) {
                  wire.send(PeerMessage.Extended(2, Bencode.encode(mapOf(
                    "added" to DhtCodec.compactEndpoint(second.local)
                  ))))
                }
                while (true) {
                  val message = wire.read()
                  if (message is PeerMessage.Request) {
                    assertEquals(1, index)
                    wire.send(PeerMessage.Piece(0, 0, bytes))
                  }
                }
              } catch (_: okio.IOException) {
                // Session completion closes these peers.
              } finally {
                connection.close()
              }
            }
          }
          val peers = Channel<PeerEndpoint>(1)
          peers.send(first.local)
          peers.close()
          try {
            TorrentSwarm(store, network, budget, allowLocalDiscovery = true).run(peers)
            assertContentEquals(bytes, torrentFileSystem.read(root / "data") { readByteArray() })
            assertEquals(0, budget.allocated)
          } finally {
            servers.forEach { it.cancelAndJoin() }
            network.close()
            torrentFileSystem.deleteRecursively(root, mustExist = false)
          }
        }
      }
    }
  }
}
