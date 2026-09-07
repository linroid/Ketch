package com.linroid.ketch.torrent

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
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
import kotlin.test.assertFalse

class TorrentUploadTest {
  @Test
  fun disabled_neverUnchokesOrServesRequests() = runTest { transfer(TorrentUploadPolicy.DISABLED) }

  @Test
  fun whileDownloading_servesVerifiedBytesThroughUploadLimiter() = runTest {
    transfer(TorrentUploadPolicy.WHILE_DOWNLOADING)
  }

  @Test
  fun seedAfterCompletion_keepsSessionUntilCanceledAndReleasesResources() = runTest {
    transfer(TorrentUploadPolicy.SEED_AFTER_COMPLETION)
  }

  private suspend fun transfer(policy: TorrentUploadPolicy) = withContext(Dispatchers.Default) {
    withTimeout(15_000) {
      coroutineScope {
        val first = byteArrayOf(1, 2, 3, 4)
        val second = byteArrayOf(5, 6, 7)
        val metadata = TorrentMetadata.fromBencode(Bencode.encode(mapOf("info" to mapOf(
          "name" to "data", "length" to 7L, "piece length" to 4L,
          "pieces" to (sha1Digest(first) + sha1Digest(second))
        ))))
        val root = FileSystem.SYSTEM_TEMPORARY_DIRECTORY /
          "ketch-upload-${InfoHash.fromBytes(torrentRandomBytes(20)).hex}"
        val store = TorrentPieceStore(metadata, root / "data", emptySet(), "test")
        store.initialize()
        store.commit(0, first)
        val network = createTorrentNetwork()
        val listener = network.listen(PeerEndpoint("127.0.0.1", 0))
        val budget = TorrentBufferBudget(1024 * 1024)
        val completed = CompletableDeferred<Unit>()
        var uploaded = 0
        val server = launch {
          val connection = listener.accept()
          try {
            val wire = PeerWire(connection, metadata)
            wire.handshake(PeerHandshake(metadata.infoHash, torrentRandomBytes(20), false, false))
            wire.send(PeerMessage.Bitfield(pieceBitfield(booleanArrayOf(false, true))))
            wire.send(PeerMessage.Control(PeerMessage.Signal.UNCHOKE))
            wire.send(PeerMessage.Control(PeerMessage.Signal.INTERESTED))
            if (policy == TorrentUploadPolicy.DISABLED) wire.send(PeerMessage.Request(0, 0, 4))
            var downloadRequest = false
            var servedUpload = false
            var delivered = false
            while (true) {
              when (val message = wire.read()) {
                is PeerMessage.Control -> if (message.signal == PeerMessage.Signal.UNCHOKE) {
                  assertFalse(policy == TorrentUploadPolicy.DISABLED)
                  wire.send(PeerMessage.Request(0, 0, 4))
                }
                is PeerMessage.Request -> {
                  assertEquals(PeerMessage.Request(1, 0, 3), message)
                  downloadRequest = true
                }
                is PeerMessage.Piece -> {
                  assertContentEquals(first, message.bytes)
                  assertEquals(4, uploaded)
                  servedUpload = true
                }
                else -> Unit
              }
              if (!delivered && downloadRequest &&
                (servedUpload || policy == TorrentUploadPolicy.DISABLED)) {
                wire.send(PeerMessage.Piece(1, 0, second))
                delivered = true
              }
            }
          } catch (_: okio.IOException) {
            // Download completion/cancellation closes the connection.
          } finally {
            connection.close()
          }
        }
        val peers = Channel<PeerEndpoint>(1)
        peers.send(listener.local)
        peers.close()
        val download = async {
          TorrentSwarm(store, network, budget, uploadPolicy = policy,
            uploadPayload = { uploaded += it }, onCompleted = { completed.complete(Unit) }).run(peers)
        }
        try {
          completed.await()
          if (policy == TorrentUploadPolicy.SEED_AFTER_COMPLETION) {
            assertFalse(download.isCompleted)
            download.cancelAndJoin()
          } else download.await()
          assertEquals(if (policy == TorrentUploadPolicy.DISABLED) 0 else 4, uploaded)
          assertEquals(0, budget.allocated)
          assertContentEquals(first + second,
            torrentFileSystem.read(root / "data") { readByteArray() })
        } finally {
          download.cancelAndJoin()
          server.cancelAndJoin()
          network.close()
          torrentFileSystem.deleteRecursively(root, mustExist = false)
        }
      }
    }
  }
}
