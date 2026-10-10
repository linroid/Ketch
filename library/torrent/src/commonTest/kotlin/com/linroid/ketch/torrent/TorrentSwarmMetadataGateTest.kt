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
import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

/** With uploads off a v1 swarm keeps its info dictionary to itself, whatever peers ask. */
@OptIn(ExperimentalAtomicApi::class)
class TorrentSwarmMetadataGateTest {
  @Test
  fun disabledUploadRejectsMetadataRequests() = runTest {
    withContext(Dispatchers.Default) {
      withTimeout(15_000) {
        coroutineScope {
          val bytes = byteArrayOf(1, 2, 3, 4)
          val metadata = TorrentMetadata.fromBencode(Bencode.encode(mapOf("info" to mapOf(
            "name" to "data", "length" to 4L, "piece length" to 4L,
            "pieces" to sha1Digest(bytes)))))
          val root = FileSystem.SYSTEM_TEMPORARY_DIRECTORY /
            "ketch-metadata-gate-${InfoHash.fromBytes(torrentRandomBytes(20)).hex}"
          // Incomplete, so the swarm keeps the peer whatever the policy.
          val store = TorrentPieceStore(metadata, root / "data", emptySet(), "test")
          val network = createTorrentNetwork()
          val listener = network.listen(PeerEndpoint("127.0.0.1", 0))
          val budget = TorrentBufferBudget(1024 * 1024)
          val policy = AtomicReference(TorrentUploadPolicy.DISABLED)
          val peers = Channel<PeerEndpoint>(1)
          peers.send(listener.local)
          peers.close()
          val swarm = launch {
            TorrentSwarm(store, network, budget, uploadPolicy = { policy.load() }).run(peers)
          }
          val connection = listener.accept()
          try {
            val wire = PeerWire(connection, metadata)
            wire.handshake(PeerHandshake(metadata.infoHash, torrentRandomBytes(20), true, false))
            // The handshake neither offers the info dictionary nor tells its size.
            val ours = assertIs<PeerMessage.Extended>(wire.read())
            val handshake = Bencode.parse(ours.payload)
            assertNull(handshake["m"]?.get("ut_metadata"))
            assertNull(handshake["metadata_size"])
            wire.send(PeerMessage.Extended(0, Bencode.encode(mapOf(
              "m" to mapOf("ut_metadata" to 3L)))))
            /** Asks for the first block anyway, and returns the answer's type and payload. */
            suspend fun request(): Pair<Long, ByteArray> {
              wire.send(TorrentMetadataExchange.metadataMessage(PeerExtensions.METADATA, 0, 0))
              while (true) {
                val message = wire.read()
                if (message !is PeerMessage.Extended || message.id != 3) continue
                val header = Bencode.parsePrefix(message.payload)
                return requireNotNull(header["msg_type"]?.integer) to
                  message.payload.copyOfRange(header.end, message.payload.size)
              }
            }
            assertEquals(2L, request().first)
            // While uploads are on it is served, however the connection started.
            policy.store(TorrentUploadPolicy.WHILE_DOWNLOADING)
            val (type, info) = request()
            assertEquals(1L, type)
            assertContentEquals(metadata.infoBytes, info)
            // Switched off again, the next request is rejected at once.
            policy.store(TorrentUploadPolicy.DISABLED)
            assertEquals(2L, request().first)
          } finally {
            // Stopped first: without its only peer the swarm would give up and fail.
            swarm.cancelAndJoin()
            connection.close()
            network.close()
            torrentFileSystem.deleteRecursively(root, mustExist = false)
          }
          assertEquals(0, budget.allocated)
        }
      }
    }
  }
}
