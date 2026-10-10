package com.linroid.ketch.torrent

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.SendChannel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import okio.ByteString.Companion.toByteString
import okio.FileSystem
import okio.IOException
import okio.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class KotlinTorrentEngineRoutingTest {
  private val bytes = byteArrayOf(1, 2, 3, 4)

  private fun root(): Path = (FileSystem.SYSTEM_TEMPORARY_DIRECTORY /
    "ketch-routing-${InfoHash.fromBytes(torrentRandomBytes(20)).hex}")
    .also { torrentFileSystem.createDirectories(it) }

  /** A v1 torrent whose payload is not on disk, so its owner keeps downloading. */
  private fun v1(name: String = "file"): TorrentMetadata =
    TorrentMetadata.fromBencode(Bencode.encode(mapOf("info" to mapOf(
      "name" to name, "length" to 4L, "piece length" to 4L, "pieces" to sha1Digest(bytes)))))

  private fun document(hybrid: Boolean = false): TorrentV2Document {
    val info = mutableMapOf<String, Any>("name" to "pack", "meta version" to 2L,
      "piece length" to 16_384L, "file tree" to mapOf("a" to mapOf("" to mapOf(
        "length" to 4L, "pieces root" to sha256Digest(bytes)))))
    if (hybrid) {
      info["files"] = listOf(mapOf("path" to listOf("a"), "length" to 4L))
      info["pieces"] = sha1Digest(bytes)
    }
    return TorrentV2Document.parse(Bencode.encode(mapOf("info" to info,
      "piece layers" to emptyMap<String, Any>())))
  }

  /** The v1 adapter of a hybrid's raw info, as a v1 task would hold it. */
  private fun legacy(hybrid: TorrentV2Document) = TorrentMetadata(
    checkNotNull(hybrid.identity.v1), "a", 16_384, 4,
    listOf(TorrentMetadata.TorrentFile(0, "a", 4)),
    infoBytes = hybrid.info.rawInfo.toByteArray(), pieceHashes = sha1Digest(bytes))

  private fun spec(
    document: TorrentV2Document,
    output: Path,
    discover: suspend (SendChannel<PeerEndpoint>) -> Unit = {},
  ) = TorrentV2TaskSpec("v2", document, output.toString(),
    privacy = TorrentDiscoveryPrivacy.PUBLIC, discover = discover)

  private fun handshake(hash: InfoHash, peerId: ByteArray = torrentRandomBytes(20)) =
    PeerWire.encodeHandshake(PeerHandshake(hash, peerId, extensions = false, dht = false))

  private suspend fun assertLeakFree(engine: KotlinTorrentEngine) {
    engine.stop()
    assertEquals(0, engine.admittedSessionBytes)
    assertEquals(0, engine.allocatedExchangeBytes)
    assertTrue(engine.incomingTags().isEmpty())
  }

  @Test
  fun v1IncomingStillRoutesThroughDispatch() = runTest {
    withContext(Dispatchers.Default) {
      withTimeout(15_000) {
        val root = root()
        val metadata = v1()
        val engine = KotlinTorrentEngine(TorrentConfig(dhtEnabled = false))
        val remote = createTorrentNetwork()
        try {
          engine.start()
          val session = engine.addTask(TorrentTaskSpec("v1", metadata,
            (root / "file").toString(), emptySet()))
          session.resume()
          session.state.first { it == TorrentSessionState.DOWNLOADING }
          assertEquals(setOf(metadata.infoHash.toBytes().toByteString()), engine.incomingTags())
          val connection = remote.connect(PeerEndpoint("127.0.0.1", engine.listenPort))
          try {
            val local = torrentRandomBytes(20)
            val header = handshake(metadata.infoHash, local)
            // The engine reads the whole handshake before it routes, however it arrives.
            connection.write(header.copyOf(7))
            delay(50)
            connection.write(header.copyOfRange(7, 68))
            val reply = PeerWire.decodeHandshake(connection.readExactly(68), metadata.infoHash)
            assertFalse(reply.peerId.contentEquals(local))
          } finally { connection.close() }
        } finally {
          remote.close()
          assertLeakFree(engine)
          torrentFileSystem.deleteRecursively(root, mustExist = false)
        }
      }
    }
  }

  @Test
  fun unknownTagIsClosedWithoutReply() = runTest {
    withContext(Dispatchers.Default) {
      withTimeout(15_000) {
        val root = root()
        val engine = KotlinTorrentEngine(TorrentConfig(dhtEnabled = false))
        val remote = createTorrentNetwork()
        try {
          engine.start()
          // A v2 owner that was never resumed admits no incoming peers; it refuses too.
          val document = document()
          engine.addV2Task(spec(document, root / "payload"))
          val unknown = InfoHash.fromBytes(torrentRandomBytes(20))
          val tags = listOf(handshake(unknown),
            handshake(InfoHash.fromBytes(document.info.hash.wireBytes())))
          for (header in tags) {
            val connection = remote.connect(PeerEndpoint("127.0.0.1", engine.listenPort))
            try {
              connection.write(header)
              val failure = assertNotNull(runCatching {
                withTimeout(5_000) { connection.readExactly(1) }
              }.exceptionOrNull(), "The engine answered a handshake it cannot route")
              assertFalse(failure is TimeoutCancellationException, "The connection stayed open")
            } finally { connection.close() }
          }
        } finally {
          remote.close()
          assertLeakFree(engine)
          torrentFileSystem.deleteRecursively(root, mustExist = false)
        }
      }
    }
  }

  @Test
  fun v2OwnerOutlivesBodyUntilRemovedByV2Hex() = runTest {
    withContext(Dispatchers.Default) {
      withTimeout(15_000) {
        val root = root()
        val output = root / "payload"
        val document = document()
        val engine = KotlinTorrentEngine(TorrentConfig(dhtEnabled = false, maxActiveTorrents = 1))
        val discovering = CompletableDeferred<Unit>()
        try {
          engine.start()
          val session = engine.addV2Task(spec(document, output) {
            discovering.complete(Unit)
            awaitCancellation()
          })
          // The call returned, yet the owner stays registered, routed and charged.
          assertEquals(setOf(document.info.hash.wireBytes().toByteString()),
            engine.incomingTags())
          assertFalse(engine.hasFreeSlot())
          assertTrue(engine.admittedSessionBytes > 0)
          session.resume()
          discovering.await()
          assertEquals(TorrentSessionState.DOWNLOADING, session.state.value)
          assertFailsWith<IllegalStateException> {
            engine.addV2Task(spec(document, root / "other"))
          }
          engine.removeTorrent(document.info.hash.hex, deleteFiles = false)
          assertEquals(TorrentSessionState.STOPPED, session.state.value)
          assertTrue(engine.incomingTags().isEmpty())
          assertTrue(engine.hasFreeSlot())
          assertEquals(0, engine.admittedSessionBytes)
          // The hash and the output are free again.
          engine.withV2Download("again", document, output.toString(), discover = {}) {
            assertFalse(engine.hasFreeSlot())
          }
        } finally {
          assertLeakFree(engine)
          torrentFileSystem.deleteRecursively(root, mustExist = false)
        }
      }
    }
  }

  @Test
  fun hybridOwnerIsRemovedByItsV1Hex() = runTest {
    withContext(Dispatchers.Default) {
      withTimeout(15_000) {
        val root = root()
        val hybrid = document(hybrid = true)
        val v1 = checkNotNull(hybrid.identity.v1)
        val engine = KotlinTorrentEngine(TorrentConfig(dhtEnabled = false))
        try {
          engine.start()
          val session = engine.addV2Task(spec(hybrid, root / "payload"))
          assertEquals(setOf(v1.toBytes().toByteString(),
            hybrid.info.hash.wireBytes().toByteString()), engine.incomingTags())
          engine.removeTorrent(v1.hex, deleteFiles = false)
          assertEquals(TorrentSessionState.STOPPED, session.state.value)
          assertTrue(engine.incomingTags().isEmpty())
          assertEquals(0, engine.admittedSessionBytes)
          // The v1 hash is free for a v1 owner again, and its own hash removes that one.
          engine.addTask(TorrentTaskSpec("legacy", legacy(hybrid), (root / "legacy").toString(),
            emptySet()))
          assertEquals(setOf(v1.toBytes().toByteString()), engine.incomingTags())
          engine.removeTorrent(v1.hex, deleteFiles = false)
          assertEquals(0, engine.admittedSessionBytes)
        } finally {
          assertLeakFree(engine)
          torrentFileSystem.deleteRecursively(root, mustExist = false)
        }
      }
    }
  }

  @Test
  fun closingOwnerKeepsTagsReservedButFreesSlot() = runTest {
    withContext(Dispatchers.Default) {
      withTimeout(15_000) {
        val root = root()
        val hybrid = document(hybrid = true)
        val engine = KotlinTorrentEngine(TorrentConfig(dhtEnabled = false, maxActiveTorrents = 1))
        val entered = CompletableDeferred<Unit>()
        val cleaning = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        try {
          engine.start()
          val session = engine.addV2Task(spec(hybrid, root / "payload") {
            entered.complete(Unit)
            try { awaitCancellation() } finally {
              withContext(NonCancellable) { cleaning.complete(Unit); release.await() }
            }
          })
          session.resume()
          entered.await()
          val removal = async { engine.removeTorrent(hybrid.info.hash.hex, deleteFiles = false) }
          cleaning.await()
          assertFalse(removal.isCompleted)
          assertTrue(engine.hasFreeSlot())
          assertTrue(engine.incomingTags().isEmpty())
          // Both hashes stay claimed while the closing owner still holds its files.
          assertFailsWith<IllegalStateException> {
            engine.addV2Task(spec(hybrid, root / "other"))
          }
          assertFailsWith<IllegalStateException> {
            engine.addTask(TorrentTaskSpec("legacy", legacy(hybrid), (root / "legacy").toString(),
              emptySet()))
          }
          // The freed slot takes another torrent at once.
          val other = v1("other")
          engine.addTask(TorrentTaskSpec("other", other, (root / "other").toString(), emptySet()))
          assertFalse(engine.hasFreeSlot())
          release.complete(Unit)
          removal.await()
          assertEquals(TorrentSessionState.STOPPED, session.state.value)
          engine.removeTorrent(other.infoHash.hex, deleteFiles = false)
          engine.withV2Download("again", hybrid, (root / "payload").toString(), discover = {}) {}
          assertEquals(0, engine.admittedSessionBytes)
        } finally {
          release.complete(Unit)
          assertLeakFree(engine)
          torrentFileSystem.deleteRecursively(root, mustExist = false)
        }
      }
    }
  }

  @Test
  fun stopClosesV2OwnersBeforeReturningAdmission() = runTest {
    withContext(Dispatchers.Default) {
      withTimeout(15_000) {
        val root = root()
        val engine = KotlinTorrentEngine(TorrentConfig(dhtEnabled = false))
        val entered = CompletableDeferred<Unit>()
        val joined = CompletableDeferred<Unit>()
        try {
          engine.start()
          val session = engine.addV2Task(spec(document(), root / "payload") {
            entered.complete(Unit)
            try { awaitCancellation() } finally { joined.complete(Unit) }
          })
          session.resume()
          entered.await()
          assertTrue(engine.admittedSessionBytes > 0)
          engine.stop()
          // No caller removed the owner: stopping the engine closed it and joined discovery.
          assertTrue(joined.isCompleted)
          assertEquals(TorrentSessionState.STOPPED, session.state.value)
          assertNull(session.saveResumeData())
          assertFailsWith<IllegalStateException> { session.resume() }
        } finally {
          assertLeakFree(engine)
          torrentFileSystem.deleteRecursively(root, mustExist = false)
        }
      }
    }
  }

  @Test
  fun cancelledAcceptLoopKeepsHandshakeInFlight() = runTest {
    withContext(Dispatchers.Default) {
      withTimeout(15_000) {
        val root = root()
        val metadata = v1()
        val toEngine = Pipe()
        val fromEngine = Pipe()
        val peer = MemoryConnection(PeerEndpoint("127.0.0.1", 40_000), toEngine, fromEngine)
        val offered = CompletableDeferred<Unit>()
        val loopEnded = CompletableDeferred<Unit>()
        var accepts = 0
        val network = object : TorrentNetwork {
          override suspend fun connect(remote: PeerEndpoint): TorrentConnection = error("Unused")
          override suspend fun bindUdp(local: PeerEndpoint): TorrentDatagramSocket = error("Unused")
          override suspend fun listen(local: PeerEndpoint): TorrentListener {
            if (local.host == "::") throw IOException("No second listener")
            return object : TorrentListener {
              override val local = PeerEndpoint("127.0.0.1", 6881)
              override suspend fun accept(): TorrentConnection {
                if (accepts++ == 0) {
                  offered.await()
                  return peer
                }
                // Whoever owns the loop stops it, as replacing a listener would.
                throw CancellationException("Listener replaced")
              }
              override fun close() { loopEnded.complete(Unit) }
            }
          }
          override fun close() = Unit
        }
        val engine = KotlinTorrentEngine(TorrentConfig(dhtEnabled = false), rawNetwork = network)
        try {
          engine.start()
          val session = engine.addTask(TorrentTaskSpec("v1", metadata,
            (root / "file").toString(), emptySet()))
          session.resume()
          session.state.first { it == TorrentSessionState.DOWNLOADING }
          val local = torrentRandomBytes(20)
          val header = handshake(metadata.infoHash, local)
          toEngine.write(header.copyOf(20))
          offered.complete(Unit)
          // The loop ends with the first 20 bytes read; the handshake is still in flight.
          loopEnded.await()
          toEngine.write(header.copyOfRange(20, 68))
          val reply = PeerWire.decodeHandshake(fromEngine.read(68), metadata.infoHash)
          assertFalse(reply.peerId.contentEquals(local))
        } finally {
          peer.close()
          assertLeakFree(engine)
          torrentFileSystem.deleteRecursively(root, mustExist = false)
        }
      }
    }
  }

  /** One direction of an in-memory socket. */
  private class Pipe {
    private val chunks = Channel<ByteArray>(Channel.UNLIMITED)
    private var pending = ByteArray(0)

    suspend fun read(size: Int): ByteArray {
      while (pending.size < size) {
        pending += chunks.receiveCatching().getOrNull() ?: throw IOException("Closed")
      }
      return pending.copyOf(size).also { pending = pending.copyOfRange(size, pending.size) }
    }

    fun write(bytes: ByteArray) {
      if (chunks.trySend(bytes.copyOf()).isFailure) throw IOException("Closed")
    }

    fun close() { chunks.close() }
  }

  private class MemoryConnection(
    override val remote: PeerEndpoint,
    private val input: Pipe,
    private val output: Pipe,
  ) : TorrentConnection {
    override suspend fun readExactly(size: Int): ByteArray = input.read(size)
    override suspend fun write(bytes: ByteArray) = output.write(bytes)
    override fun close() {
      input.close()
      output.close()
    }
  }
}
