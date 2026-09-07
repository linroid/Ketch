package com.linroid.ketch.torrent

import kotlinx.coroutines.CancellationException
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
import kotlin.test.assertTrue

class TorrentSwarmTest {
  @Test
  fun complementaryPeers_withOutOfOrderBlocks_completeAndReleaseMemory() = runTest {
    withContext(Dispatchers.Default) {
      withTimeout(15_000) {
        coroutineScope {
          val payload = ByteArray(131_073) { (it * 17 + 9).toByte() }
          val hashes = payload.asList().chunked(65_536).fold(ByteArray(0)) { result, chunk ->
            result + sha1Digest(chunk.toByteArray())
          }
          val metadata = TorrentMetadata.fromBencode(Bencode.encode(mapOf("info" to mapOf(
            "name" to "data", "length" to payload.size.toLong(), "piece length" to 65_536L,
            "pieces" to hashes
          ))))
          val root = FileSystem.SYSTEM_TEMPORARY_DIRECTORY /
            "ketch-swarm-${InfoHash.fromBytes(torrentRandomBytes(20)).hex}"
          val store = TorrentPieceStore(metadata, root / "data", emptySet(), "swarm")
          val network = createTorrentNetwork()
          val budget = TorrentBufferBudget(2 * 1024 * 1024)
          val peers = Channel<PeerEndpoint>(2)
          val servers = (0..1).map { number ->
            val listener = network.listen(PeerEndpoint("127.0.0.1", 0))
            peers.send(listener.local)
            launch {
              val connection = listener.accept()
              try {
                val wire = PeerWire(connection, metadata)
                wire.handshake(PeerHandshake(metadata.infoHash, torrentRandomBytes(20), false, false))
                val available = BooleanArray(3) { it % 2 == number }
                wire.send(PeerMessage.Bitfield(pieceBitfield(available)))
                wire.send(PeerMessage.Control(PeerMessage.Signal.UNCHOKE))
                val requests = mutableListOf<PeerMessage.Request>()
                while (true) {
                  val message = wire.read()
                  if (message is PeerMessage.Request) {
                    assertTrue(available[message.index])
                    requests += message
                    val count = if (message.index == 2) 1 else 4
                    if (requests.size == count) {
                      for (request in requests.reversed()) {
                        val begin = request.index * 65_536 + request.begin
                        wire.send(PeerMessage.Piece(request.index, request.begin,
                          payload.copyOfRange(begin, begin + request.length)))
                      }
                      requests.clear()
                    }
                  }
                }
              } catch (_: okio.IOException) {
                // The completed downloader closes the connections.
              } catch (e: CancellationException) {
                throw e
              } finally {
                connection.close()
              }
            }
          }
          peers.close()
          try {
            TorrentSwarm(store, network, budget).run(peers)
            assertTrue(store.completed())
            assertContentEquals(payload, torrentFileSystem.read(root / "data") { readByteArray() })
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
