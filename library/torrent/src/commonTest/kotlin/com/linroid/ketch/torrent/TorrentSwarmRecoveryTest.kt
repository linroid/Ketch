package com.linroid.ketch.torrent

import kotlinx.coroutines.CompletableDeferred
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

class TorrentSwarmRecoveryTest {
  @Test
  fun corruptPeer_isDroppedAndPieceReassigned() = runTest { recover(corrupt = true) }

  @Test
  fun disconnectedPeer_isReassignedWithoutLeakingBuffers() = runTest { recover(corrupt = false) }

  @Test
  fun endgame_fasterDuplicateCancelsSlowOutstandingRequest() = runTest {
    recover(corrupt = false, endgame = true)
  }

  private suspend fun recover(corrupt: Boolean, endgame: Boolean = false) = withContext(Dispatchers.Default) {
    withTimeout(15_000) {
      coroutineScope {
        val payload = byteArrayOf(1, 2, 3, 4)
        val metadata = TorrentMetadata.fromBencode(Bencode.encode(mapOf("info" to mapOf(
          "name" to "data", "length" to 4L, "piece length" to 4L,
          "pieces" to sha1Digest(payload)
        ))))
        val root = FileSystem.SYSTEM_TEMPORARY_DIRECTORY /
          "ketch-recover-${InfoHash.fromBytes(torrentRandomBytes(20)).hex}"
        val store = TorrentPieceStore(metadata, root / "data", emptySet(), "test")
        val network = createTorrentNetwork()
        val peers = Channel<PeerEndpoint>(2)
        val badAttempted = CompletableDeferred<Unit>()
        val canceled = CompletableDeferred<Unit>()
        val budget = TorrentBufferBudget(2 * 1024 * 1024)
        val servers = (0..1).map { number ->
          val listener = network.listen(PeerEndpoint("127.0.0.1", 0))
          peers.send(listener.local)
          launch {
            val connection = listener.accept()
            try {
              val wire = PeerWire(connection, metadata)
              wire.handshake(PeerHandshake(metadata.infoHash, torrentRandomBytes(20), false, false))
              if (number == 1) badAttempted.await()
              wire.send(PeerMessage.Bitfield(byteArrayOf(128.toByte())))
              wire.send(PeerMessage.Control(PeerMessage.Signal.UNCHOKE))
              while (true) {
                val message = wire.read()
                if (message is PeerMessage.Request) {
                  if (number == 0) {
                    if (corrupt) wire.send(PeerMessage.Piece(0, 0, byteArrayOf(4, 3, 2, 1)))
                    badAttempted.complete(Unit)
                    if (endgame) {
                      while (true) {
                        val next = wire.read()
                        if (next is PeerMessage.Cancel) {
                          assertEquals(PeerMessage.Cancel(0, 0, 4), next)
                          canceled.complete(Unit)
                          break
                        }
                      }
                    }
                  } else wire.send(PeerMessage.Piece(0, 0, payload))
                  break
                }
              }
            } finally {
              connection.close()
              listener.close()
            }
          }
        }
        peers.close()
        try {
          TorrentSwarm(store, network, budget).run(peers)
          assertContentEquals(payload, torrentFileSystem.read(root / "data") { readByteArray() })
          assertEquals(0, budget.allocated)
          if (endgame) canceled.await()
        } finally {
          servers.forEach { it.cancelAndJoin() }
          network.close()
          torrentFileSystem.deleteRecursively(root, mustExist = false)
        }
      }
    }
  }
}
