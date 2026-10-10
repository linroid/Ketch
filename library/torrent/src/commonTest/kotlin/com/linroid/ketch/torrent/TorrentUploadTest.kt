package com.linroid.ketch.torrent

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import okio.FileSystem
import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalAtomicApi::class)
class TorrentUploadTest {
  private val choke = PeerMessage.Control(PeerMessage.Signal.CHOKE)
  private val unchoke = PeerMessage.Control(PeerMessage.Signal.UNCHOKE)

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

  @Test
  fun policyChangeToDisabledChokesV1PeerWithinOneTick() = runTest {
    withContext(Dispatchers.Default) {
      withTimeout(15_000) {
        coroutineScope {
          val first = byteArrayOf(1, 2, 3, 4)
          val metadata = TorrentMetadata.fromBencode(Bencode.encode(mapOf("info" to mapOf(
            "name" to "data", "length" to 7L, "piece length" to 4L,
            "pieces" to (sha1Digest(first) + sha1Digest(byteArrayOf(5, 6, 7)))
          ))))
          val root = FileSystem.SYSTEM_TEMPORARY_DIRECTORY /
            "ketch-upload-policy-${InfoHash.fromBytes(torrentRandomBytes(20)).hex}"
          // Half done, so the swarm keeps going whatever the policy.
          val store = TorrentPieceStore(metadata, root / "data", emptySet(), "test")
          store.initialize()
          store.commit(0, first)
          val network = createTorrentNetwork()
          val listener = network.listen(PeerEndpoint("127.0.0.1", 0))
          val budget = TorrentBufferBudget(1024 * 1024)
          val policy = AtomicReference(TorrentUploadPolicy.WHILE_DOWNLOADING)
          val peers = Channel<PeerEndpoint>(1)
          peers.send(listener.local)
          peers.close()
          val swarm = launch {
            TorrentSwarm(store, network, budget, uploadPolicy = { policy.load() }).run(peers)
          }
          val connection = listener.accept()
          try {
            val wire = PeerWire(connection, metadata)
            wire.handshake(PeerHandshake(metadata.infoHash, torrentRandomBytes(20), false, false))
            wire.send(PeerMessage.Control(PeerMessage.Signal.INTERESTED))
            while (wire.read() != PeerMessage.Control(PeerMessage.Signal.UNCHOKE)) { }
            policy.store(TorrentUploadPolicy.DISABLED)
            // The loop and the worker each pass every 100 ms; the rest is slack for slow hosts.
            withTimeout(2_000) {
              while (wire.read() != PeerMessage.Control(PeerMessage.Signal.CHOKE)) { }
            }
            // Requests after the choke are not served.
            wire.send(PeerMessage.Request(0, 0, 4))
            assertNull(withTimeoutOrNull(500) {
              while (wire.read() !is PeerMessage.Piece) { }
            })
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

  @Test
  fun enginePolicyChangeFinishesV1Seeder() = runTest {
    withContext(Dispatchers.Default) {
      withTimeout(15_000) {
        val bytes = byteArrayOf(1, 2, 3, 4)
        val metadata = TorrentMetadata.fromBencode(Bencode.encode(mapOf("info" to mapOf(
          "name" to "data", "length" to 4L, "piece length" to 4L, "pieces" to sha1Digest(bytes)
        ))))
        val root = FileSystem.SYSTEM_TEMPORARY_DIRECTORY /
          "ketch-upload-engine-${InfoHash.fromBytes(torrentRandomBytes(20)).hex}"
        torrentFileSystem.createDirectories(root)
        torrentFileSystem.write(root / "data") { write(bytes) }
        val engine = KotlinTorrentEngine(TorrentConfig(dhtEnabled = false,
          uploadPolicy = TorrentUploadPolicy.SEED_AFTER_COMPLETION))
        try {
          engine.start()
          val session = engine.addTask(TorrentTaskSpec("seed", metadata,
            (root / "data").toString(), emptySet()))
          session.resume()
          assertEquals(TorrentSessionState.SEEDING, session.state.first {
            it == TorrentSessionState.SEEDING || it == TorrentSessionState.STOPPED
          }, session.failure.value?.stackTraceToString())
          // Only seeding keeps a complete torrent going: the session reads the policy live.
          engine.setUploadPolicy(TorrentUploadPolicy.WHILE_DOWNLOADING)
          assertEquals(TorrentSessionState.FINISHED, session.state.first {
            it == TorrentSessionState.FINISHED || it == TorrentSessionState.STOPPED
          }, session.failure.value?.stackTraceToString())
          engine.removeTorrent(metadata.infoHash.hex)
          assertEquals(0, engine.admittedSessionBytes)
        } finally {
          engine.stop()
          torrentFileSystem.deleteRecursively(root, mustExist = false)
        }
        assertEquals(0, engine.allocatedExchangeBytes)
      }
    }
  }

  @Test
  fun uploadLimitNeverStopsTheWorkerReadingPieces() = runTest {
    withContext(Dispatchers.Default) {
      withTimeout(15_000) {
        coroutineScope {
          // Two pieces of two 16 KiB blocks: we have the first, the peer the second.
          val first = ByteArray(32_768) { it.toByte() }
          val second = ByteArray(32_768) { (it * 7).toByte() }
          val metadata = TorrentMetadata.fromBencode(Bencode.encode(mapOf("info" to mapOf(
            "name" to "data", "length" to 65_536L, "piece length" to 32_768L,
            "pieces" to (sha1Digest(first) + sha1Digest(second))
          ))))
          val root = FileSystem.SYSTEM_TEMPORARY_DIRECTORY /
            "ketch-upload-limit-${InfoHash.fromBytes(torrentRandomBytes(20)).hex}"
          val store = TorrentPieceStore(metadata, root / "data", emptySet(), "test")
          store.initialize()
          store.commit(0, first)
          val network = createTorrentNetwork()
          val listener = network.listen(PeerEndpoint("127.0.0.1", 0))
          val budget = TorrentBufferBudget(1024 * 1024)
          // A byte a second, shared by every torrent: one block now, the next one not for hours.
          val engineRate = TorrentRateLimiter(1)
          val completed = CompletableDeferred<Unit>()
          val uploads = Channel<PeerMessage.Piece>(Channel.UNLIMITED)
          val peers = Channel<PeerEndpoint>(1)
          peers.send(listener.local)
          peers.close()
          val swarm = async {
            TorrentSwarm(store, network, budget,
              uploadPolicy = { TorrentUploadPolicy.SEED_AFTER_COMPLETION },
              uploadRate = engineRate, onCompleted = { completed.complete(Unit) }).run(peers)
          }
          val connection = listener.accept()
          val peer = launch {
            try {
              val wire = PeerWire(connection, metadata)
              wire.handshake(PeerHandshake(metadata.infoHash, torrentRandomBytes(20), false,
                false))
              wire.send(PeerMessage.Bitfield(pieceBitfield(booleanArrayOf(false, true))))
              wire.send(PeerMessage.Control(PeerMessage.Signal.UNCHOKE))
              wire.send(PeerMessage.Control(PeerMessage.Signal.INTERESTED))
              suspend fun serve(request: PeerMessage.Request) = wire.send(PeerMessage.Piece(
                request.index, request.begin,
                second.copyOfRange(request.begin, request.begin + request.length)))
              // It answers our requests only once its own second request waits behind the limit.
              val asked = mutableListOf<PeerMessage.Request>()
              var serving = false
              while (true) {
                when (val message = wire.read()) {
                  // It asks for both blocks of our piece at once.
                  PeerMessage.Control(PeerMessage.Signal.UNCHOKE) -> {
                    wire.send(PeerMessage.Request(0, 0, 16_384))
                    wire.send(PeerMessage.Request(0, 16_384, 16_384))
                  }
                  is PeerMessage.Request -> if (serving) serve(message) else asked += message
                  is PeerMessage.Piece -> {
                    uploads.send(message)
                    if (!serving) {
                      serving = true
                      asked.forEach { serve(it) }
                    }
                  }
                  else -> Unit
                }
              }
            } catch (_: okio.IOException) {
              // The swarm closes its peers when the test ends.
            } finally {
              connection.close()
            }
          }
          try {
            // The held block does not keep the worker from reading the piece we asked for.
            completed.await()
            assertContentEquals(first.copyOfRange(0, 16_384), uploads.receive().bytes)
            assertNull(withTimeoutOrNull(300) { uploads.receive() })
            assertFalse(swarm.isCompleted)
            // Lifted, the held block goes out.
            engineRate.set(0)
            val held = uploads.receive()
            assertEquals(16_384, held.begin)
            assertContentEquals(first.copyOfRange(16_384, 32_768), held.bytes)
            assertContentEquals(first + second,
              torrentFileSystem.read(root / "data") { readByteArray() })
          } finally {
            swarm.cancelAndJoin()
            peer.cancelAndJoin()
            connection.close()
            network.close()
            torrentFileSystem.deleteRecursively(root, mustExist = false)
          }
          assertEquals(0, budget.allocated)
        }
      }
    }
  }

  @Test
  fun engineUploadLimitAppliesToV1Seeder() = runTest {
    withContext(Dispatchers.Default) {
      withTimeout(15_000) {
        val bytes = ByteArray(32_768) { (it * 3).toByte() }
        val metadata = TorrentMetadata.fromBencode(Bencode.encode(mapOf("info" to mapOf(
          "name" to "data", "length" to 32_768L, "piece length" to 32_768L,
          "pieces" to sha1Digest(bytes)
        ))))
        val root = FileSystem.SYSTEM_TEMPORARY_DIRECTORY /
          "ketch-upload-engine-limit-${InfoHash.fromBytes(torrentRandomBytes(20)).hex}"
        torrentFileSystem.createDirectories(root)
        torrentFileSystem.write(root / "data") { write(bytes) }
        // The engine's shared cap, a byte a second: its bucket holds one block to start with.
        val engine = KotlinTorrentEngine(TorrentConfig(dhtEnabled = false,
          uploadPolicy = TorrentUploadPolicy.SEED_AFTER_COMPLETION, uploadRateLimit = 1))
        val network = createTorrentNetwork()
        try {
          engine.start()
          val session = engine.addTask(TorrentTaskSpec("seed", metadata,
            (root / "data").toString(), emptySet()))
          session.resume()
          assertEquals(TorrentSessionState.SEEDING, session.state.first {
            it == TorrentSessionState.SEEDING || it == TorrentSessionState.STOPPED
          }, session.failure.value?.stackTraceToString())
          val connection = network.connect(PeerEndpoint("127.0.0.1", engine.listenPort))
          try {
            val wire = PeerWire(connection, metadata)
            wire.handshake(PeerHandshake(metadata.infoHash, torrentRandomBytes(20), false, false))
            wire.send(PeerMessage.Control(PeerMessage.Signal.INTERESTED))
            while (wire.read() != PeerMessage.Control(PeerMessage.Signal.UNCHOKE)) { }
            suspend fun piece(): PeerMessage.Piece {
              while (true) {
                val message = wire.read()
                if (message is PeerMessage.Piece) return message
              }
            }
            wire.send(PeerMessage.Request(0, 0, 16_384))
            assertEquals(0, piece().begin)
            wire.send(PeerMessage.Request(0, 16_384, 16_384))
            assertNull(withTimeoutOrNull(500) { piece() })
            assertEquals(16_384L, session.uploadedBytes)
            // The piece the held block waits on is charged to the engine's upload partition, so
            // seeding can never take more than half of what downloads need.
            assertEquals(32_768, engine.allocatedUploadBytes)
            // Lifting the engine's cap lets the held block go.
            engine.setUploadRateLimit(0)
            val held = piece()
            assertEquals(16_384, held.begin)
            assertContentEquals(bytes.copyOfRange(16_384, 32_768), held.bytes)
          } finally { connection.close() }
          engine.removeTorrent(metadata.infoHash.hex)
          assertEquals(0, engine.admittedSessionBytes)
        } finally {
          engine.stop()
          network.close()
          torrentFileSystem.deleteRecursively(root, mustExist = false)
        }
        assertEquals(0, engine.allocatedExchangeBytes)
      }
    }
  }

  @Test
  fun cancelRemovesHeldV1Request() = heldUploads {
    // The engine's bucket holds one block: the first request takes it, the second waits.
    wire.send(PeerMessage.Request(0, 0, 16_384))
    assertEquals(0, piece().begin)
    wire.send(PeerMessage.Request(0, 16_384, 16_384))
    wire.send(PeerMessage.Cancel(0, 16_384, 16_384))
    assertTrue(sync().isEmpty())
    engineRate.set(0)
    // Canceled, the held block never goes out: the next block is the one asked for after it.
    wire.send(PeerMessage.Request(0, 0, 16_384))
    assertEquals(0, piece().begin)
    assertTrue(sync().isEmpty())
  }

  @Test
  fun chokeDiscardsHeldV1Requests() = heldUploads {
    wire.send(PeerMessage.Request(0, 0, 16_384))
    assertEquals(0, piece().begin)
    wire.send(PeerMessage.Request(0, 16_384, 16_384))
    assertTrue(sync().isEmpty())
    policy.store(TorrentUploadPolicy.DISABLED)
    assertTrue(until { it == choke }.isEmpty())
    engineRate.set(0)
    policy.store(TorrentUploadPolicy.WHILE_DOWNLOADING)
    assertTrue(until { it == unchoke }.isEmpty())
    // Requests a CHOKE crossed are discarded (BEP 3): only what the peer asks again is served.
    assertTrue(sync().isEmpty())
    wire.send(PeerMessage.Request(0, 16_384, 16_384))
    assertEquals(16_384, piece().begin)
  }

  /**
   * A scripted peer we unchoked, of a v1 swarm that has the first of two 32 KiB pieces. Uploads
   * pass [engineRate], which starts at a byte a second, and follow [policy].
   */
  private class HeldUploads(
    val wire: PeerWire,
    val engineRate: TorrentRateLimiter,
    val policy: AtomicReference<TorrentUploadPolicy>,
  ) {
    /** Reads until [match]; the blocks that came first are returned. */
    suspend fun until(match: (PeerMessage) -> Boolean): List<PeerMessage.Piece> {
      val pieces = mutableListOf<PeerMessage.Piece>()
      while (true) {
        val message = wire.read()
        if (match(message)) return pieces
        if (message is PeerMessage.Piece) pieces += message
      }
    }

    suspend fun piece(): PeerMessage.Piece {
      while (true) {
        val message = wire.read()
        if (message is PeerMessage.Piece) return message
      }
    }

    /**
     * Waits until the worker handled what the peer sent so far, as it answers a metadata request
     * after it; the blocks it sent meanwhile are returned.
     */
    suspend fun sync(): List<PeerMessage.Piece> {
      wire.send(TorrentMetadataExchange.metadataMessage(PeerExtensions.METADATA, 0, 0))
      return until { it is PeerMessage.Extended && it.id == 3 }
    }
  }

  private fun heldUploads(test: suspend HeldUploads.() -> Unit) = runTest {
    withContext(Dispatchers.Default) {
      withTimeout(15_000) {
        val first = ByteArray(32_768) { it.toByte() }
        val second = ByteArray(32_768) { (it * 7).toByte() }
        val metadata = TorrentMetadata.fromBencode(Bencode.encode(mapOf("info" to mapOf(
          "name" to "data", "length" to 65_536L, "piece length" to 32_768L,
          "pieces" to (sha1Digest(first) + sha1Digest(second))
        ))))
        val root = FileSystem.SYSTEM_TEMPORARY_DIRECTORY /
          "ketch-upload-held-${InfoHash.fromBytes(torrentRandomBytes(20)).hex}"
        // Half done, so the swarm keeps going whatever the policy.
        val store = TorrentPieceStore(metadata, root / "data", emptySet(), "test")
        store.initialize()
        store.commit(0, first)
        val network = createTorrentNetwork()
        val listener = network.listen(PeerEndpoint("127.0.0.1", 0))
        val budget = TorrentBufferBudget(1024 * 1024)
        // A byte a second, shared by every torrent: one block now, the next one not for hours.
        val engineRate = TorrentRateLimiter(1)
        val policy = AtomicReference(TorrentUploadPolicy.WHILE_DOWNLOADING)
        val peers = Channel<PeerEndpoint>(1)
        peers.send(listener.local)
        peers.close()
        coroutineScope {
          val swarm = launch {
            TorrentSwarm(store, network, budget, uploadPolicy = { policy.load() },
              uploadRate = engineRate).run(peers)
          }
          val connection = listener.accept()
          try {
            val wire = PeerWire(connection, metadata)
            wire.handshake(PeerHandshake(metadata.infoHash, torrentRandomBytes(20), true, false))
            wire.send(PeerMessage.Extended(0, Bencode.encode(mapOf(
              "m" to mapOf("ut_metadata" to 3L)))))
            wire.send(PeerMessage.Control(PeerMessage.Signal.INTERESTED))
            val held = HeldUploads(wire, engineRate, policy)
            assertTrue(held.until { it == unchoke }.isEmpty())
            held.test()
          } finally {
            // Stopped first: without its only peer the swarm would give up and fail.
            swarm.cancelAndJoin()
            connection.close()
            network.close()
            torrentFileSystem.deleteRecursively(root, mustExist = false)
          }
        }
        assertEquals(0, budget.allocated)
      }
    }
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
        val metadataServed = CompletableDeferred<Unit>()
        var uploaded = 0
        val server = launch {
          val connection = listener.accept()
          try {
            val wire = PeerWire(connection, metadata)
            wire.handshake(PeerHandshake(metadata.infoHash, torrentRandomBytes(20),
              policy == TorrentUploadPolicy.SEED_AFTER_COMPLETION, false))
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
                is PeerMessage.Extended -> {
                  if (message.id == 0) {
                    wire.send(PeerMessage.Extended(0, Bencode.encode(mapOf(
                      "m" to mapOf("ut_metadata" to 7L)
                    ))))
                    wire.send(TorrentMetadataExchange.metadataMessage(1, 0, 0))
                  } else {
                    assertEquals(7, message.id)
                    val header = Bencode.parsePrefix(message.payload)
                    assertContentEquals(metadata.infoBytes,
                      message.payload.copyOfRange(header.end, message.payload.size))
                    metadataServed.complete(Unit)
                  }
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
          TorrentSwarm(store, network, budget, uploadPolicy = { policy },
            onUploaded = { uploaded += it }, onCompleted = { completed.complete(Unit) }).run(peers)
        }
        try {
          completed.await()
          if (policy == TorrentUploadPolicy.SEED_AFTER_COMPLETION) {
            metadataServed.await()
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
