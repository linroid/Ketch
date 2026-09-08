package com.linroid.ketch.torrent

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import okio.FileSystem
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TorrentSwarmReviewTest {
  @Test
  fun payloadLimiterWaitLongerThanBlockDeadlineStillCompletes() = runTest {
    withContext(Dispatchers.Default) {
      withTimeout(40_000) {
        coroutineScope {
          val root = FileSystem.SYSTEM_TEMPORARY_DIRECTORY /
            "ketch-slow-${InfoHash.fromBytes(torrentRandomBytes(20)).hex}"
          val metadata = metadata(byteArrayOf(1))
          val store = TorrentPieceStore(metadata, root / "file", emptySet(), "test")
          val network = createTorrentNetwork()
          val listener = network.listen(PeerEndpoint("127.0.0.1", 0))
          val server = launch {
            val connection = listener.accept()
            try {
              val wire = PeerWire(connection, metadata)
              wire.handshake(PeerHandshake(metadata.infoHash, torrentRandomBytes(20), false, false))
              wire.send(PeerMessage.Have(0))
              wire.send(PeerMessage.Control(PeerMessage.Signal.UNCHOKE))
              while (wire.read() !is PeerMessage.Request) { }
              wire.send(PeerMessage.Piece(0, 0, byteArrayOf(1)))
              awaitCancellation()
            } finally {
              connection.close()
            }
          }
          val peers = Channel<PeerEndpoint>(1)
          peers.send(listener.local)
          peers.close()
          val budget = TorrentBufferBudget(1024 * 1024)
          try {
            TorrentSwarm(store, network, budget, downloadPayload = { delay(21_000) }).run(peers)
            assertTrue(store.completed())
            assertEquals(0, budget.allocated)
          } finally {
            server.cancelAndJoin()
            network.close()
            torrentFileSystem.deleteRecursively(root, mustExist = false)
          }
        }
      }
    }
  }

  private fun metadata(bytes: ByteArray) = TorrentMetadata.fromBencode(Bencode.encode(
    mapOf("info" to mapOf("name" to "file", "length" to bytes.size.toLong(),
      "piece length" to 1L, "pieces" to if (bytes.isEmpty()) ByteArray(0) else sha1Digest(bytes)))
  ))

  @Test
  fun emptyAndVerifiedStoresCompleteWithMinimumBuffer() = runTest {
    for (bytes in listOf(ByteArray(0), byteArrayOf(1))) {
      val root = FileSystem.SYSTEM_TEMPORARY_DIRECTORY /
        "ketch-complete-${InfoHash.fromBytes(torrentRandomBytes(20)).hex}"
      val network = createTorrentNetwork()
      try {
        val store = TorrentPieceStore(metadata(bytes), root / "file", emptySet(), "test")
        store.initialize()
        if (bytes.isNotEmpty()) store.commit(0, bytes)
        val peers = Channel<PeerEndpoint>().also { it.close() }
        var completed = 0
        TorrentSwarm(store, network, TorrentBufferBudget(16384),
          onCompleted = { completed++ }).run(peers)
        assertEquals(1, completed)
        assertTrue(store.completed())
      } finally {
        network.close()
        torrentFileSystem.deleteRecursively(root, mustExist = false)
      }
    }
  }

  @Test
  fun waitingInterestedPeerGetsReleasedSlotWithoutAnotherInterestedMessage() = runTest {
    withContext(Dispatchers.Default) {
      withTimeout(15_000) {
        coroutineScope {
          val root = FileSystem.SYSTEM_TEMPORARY_DIRECTORY /
            "ketch-slots-${InfoHash.fromBytes(torrentRandomBytes(20)).hex}"
          val metadata = metadata(byteArrayOf(1))
          val store = TorrentPieceStore(metadata, root / "file", emptySet(), "test")
          store.initialize()
          store.commit(0, byteArrayOf(1))
          val network = createTorrentNetwork()
          val listeners = List(5) { network.listen(PeerEndpoint("127.0.0.1", 0)) }
          val unchoked = Channel<Int>(5)
          val release = List(5) { CompletableDeferred<Unit>() }
          val servers = listeners.mapIndexed { index, listener ->
            launch {
              val connection = listener.accept()
              try {
                val wire = PeerWire(connection, metadata)
                wire.handshake(PeerHandshake(metadata.infoHash, torrentRandomBytes(20), false, false))
                wire.send(PeerMessage.Control(PeerMessage.Signal.INTERESTED))
                while (wire.read() != PeerMessage.Control(PeerMessage.Signal.UNCHOKE)) { }
                unchoked.send(index)
                release[index].await()
                wire.send(PeerMessage.Control(PeerMessage.Signal.NOT_INTERESTED))
                awaitCancellation()
              } finally {
                connection.close()
              }
            }
          }
          val peers = Channel<PeerEndpoint>(5)
          listeners.forEach { peers.trySend(it.local) }
          peers.close()
          val budget = TorrentBufferBudget(2 * 1024 * 1024)
          val swarm = async {
            TorrentSwarm(store, network, budget,
              uploadPolicy = TorrentUploadPolicy.SEED_AFTER_COMPLETION).run(peers)
          }
          try {
            val first = List(4) { unchoked.receive() }
            assertTrue(unchoked.tryReceive().isFailure)
            release[first.first()].complete(Unit)
            val fifth = withTimeout(3_000) { unchoked.receive() }
            assertTrue(fifth !in first)
          } finally {
            swarm.cancelAndJoin()
            servers.forEach { it.cancelAndJoin() }
            network.close()
            torrentFileSystem.deleteRecursively(root, mustExist = false)
          }
          assertEquals(0, budget.allocated)
        }
      }
    }
  }
}
