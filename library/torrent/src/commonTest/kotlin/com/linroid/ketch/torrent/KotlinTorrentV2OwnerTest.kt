package com.linroid.ketch.torrent

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import okio.ByteString.Companion.toByteString
import okio.FileSystem
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertIs
import kotlin.test.assertTrue

class KotlinTorrentV2OwnerTest {
  private val bytes = byteArrayOf(1, 2, 3, 4)
  private fun document(name: String = "pack", hybrid: Boolean = false): TorrentV2Document {
    val info = mutableMapOf<String, Any>("name" to name, "meta version" to 2L,
      "piece length" to 16_384L, "file tree" to mapOf("a" to mapOf("" to mapOf(
        "length" to 4L, "pieces root" to sha256Digest(bytes)))))
    if (hybrid) {
      info["files"] = listOf(mapOf("path" to listOf("a"), "length" to 4L))
      info["pieces"] = sha1Digest(bytes)
    }
    return TorrentV2Document.parse(Bencode.encode(mapOf("info" to info,
      "piece layers" to emptyMap<String, Any>())))
  }

  private fun output() = FileSystem.SYSTEM_TEMPORARY_DIRECTORY /
    "ketch-engine-v2-${InfoHash.fromBytes(torrentRandomBytes(20)).hex}"

  @Test
  fun serializedRecoveryUsesSessionBudgetBeyondTheMetadataExchangeLimit() = runTest {
    withContext(Dispatchers.Default) {
      withTimeout(30_000) {
        val root = output()
        torrentFileSystem.createDirectory(root)
        val destination = root / "payload"
        val tree = (0 until 640).associate { index ->
          ("file-${index.toString().padStart(4, '0')}-" + "x".repeat(100)) to
            mapOf("" to mapOf("length" to 0L))
        }
        val document = TorrentV2Document.parse(Bencode.encode(mapOf("info" to mapOf(
          "name" to "large-checkpoint", "meta version" to 2L, "piece length" to 16_384L,
          "file tree" to tree), "piece layers" to emptyMap<String, Any>())))
        val config = TorrentConfig(dhtEnabled = false, maxMetadataBytes = 96 * 1024,
          maxSessionStateBytes = 16 * 1024 * 1024)
        val first = KotlinTorrentEngine(config)
        val second = KotlinTorrentEngine(config)
        try {
          first.start()
          val encoded = first.withV2Download("large-restart", document, destination.toString(),
            discover = { error("Empty files must not discover peers") }) { session ->
            session.resume()
            assertEquals(TorrentSessionState.FINISHED, session.state.first {
              it == TorrentSessionState.FINISHED || it == TorrentSessionState.STOPPED
            })
            encodeBase64(assertNotNull(session.saveResumeData()))
          }
          first.stop()
          assertEquals(0, first.admittedSessionBytes)
          assertTrue(encoded.length * 12L + 4096 > config.metadataExchangeBytes,
            "Fixture must exercise recovery beyond the metadata exchange partition")
          second.start()
          second.withV2Download("large-restart", document, destination.toString(),
            checkpointEncoded = encoded, discover = { error("Recovery must not discover peers") },
          ) { session ->
            assertEquals(0L, session.verifiedBytes.value)
            session.resume()
            assertEquals(TorrentSessionState.FINISHED, session.state.first {
              it == TorrentSessionState.FINISHED || it == TorrentSessionState.STOPPED
            })
            assertEquals(640, session.fileProgress().size)
          }
          assertEquals(0, second.admittedSessionBytes)
        } finally {
          first.stop()
          second.stop()
          torrentFileSystem.deleteRecursively(root, mustExist = false)
        }
      }
    }
  }

  @Test
  fun restartRechecksCheckpointBeforeCompletionAndAgainAfterPause() = runTest {
    withContext(Dispatchers.Default) {
      withTimeout(15_000) {
        val root = output()
        torrentFileSystem.createDirectory(root)
        val destination = root / "payload"
        val document = document()
        val buffers = TorrentBufferBudget(1024 * 1024)
        val original = TorrentV2PieceStore(document, destination, emptySet(), "restart",
          buffers, Semaphore(1))
        val engine = KotlinTorrentEngine(TorrentConfig(dhtEnabled = false))
        try {
          original.initialize()
          assertTrue(original.commit(0, bytes))
          val catalog = TorrentContentCatalog(root / "catalog")
          val saved = original.checkpoint(catalog)
          TorrentCheckpointFile(root / "state", "restart", catalog).save(document, saved)
          original.close()
          val loaded = assertNotNull(TorrentCheckpointFile(root / "state", "restart",
            TorrentContentCatalog(root / "catalog")).load())
          val checkpoint = loaded.checkpoint
          val admission = TorrentBufferBudget(4 * 1024 * 1024)
          val config = TorrentConfig(dhtEnabled = false)
          val fresh = admitV2Session(document, 0, destination.toString().length, config, admission)
          val freshBytes = admission.allocated
          fresh.close()
          val recovery = admitV2Session(document, 0, destination.toString().length, config,
            admission, checkpoint)
          assertTrue(admission.allocated > freshBytes)
          recovery.close()
          val tight = TorrentBufferBudget(freshBytes)
          assertFailsWith<IllegalStateException> {
            admitV2Session(document, 0, destination.toString().length, config, tight, checkpoint)
          }
          assertEquals(0, tight.allocated)
          engine.start()
          val discovery = CompletableDeferred<Unit>()
          engine.withV2Download("restart", loaded.document, destination.toString(),
            checkpoint = checkpoint, discover = {
              discovery.complete(Unit)
              awaitCancellation()
            }) { session ->
            assertEquals(0L, session.verifiedBytes.value)
            session.resume()
            session.state.first { it == TorrentSessionState.FINISHED }
            assertEquals(4L, session.verifiedBytes.value)
            assertFalse(discovery.isCompleted)
            session.pause()
            torrentFileSystem.write(destination / "a") { write(ByteArray(4)) }
            session.resume()
            discovery.await()
            assertEquals(0L, session.verifiedBytes.value)
            session.pause()
          }
          assertEquals(0, engine.admittedSessionBytes)
        } finally {
          engine.stop()
          original.close()
          torrentFileSystem.deleteRecursively(root, mustExist = false)
        }
        assertEquals(0, buffers.allocated)
      }
    }
  }

  @Test
  fun rejectedCheckpointReleasesEngineClaimsWithoutExposingTheSession() = runTest {
    val root = output()
    torrentFileSystem.createDirectory(root)
    val destination = root / "payload"
    val document = document()
    val buffers = TorrentBufferBudget(1024 * 1024)
    val original = TorrentV2PieceStore(document, destination, emptySet(), "restart",
      buffers, Semaphore(1))
    val engine = KotlinTorrentEngine(TorrentConfig(dhtEnabled = false))
    try {
      original.initialize()
      assertTrue(original.commit(0, bytes))
      val checkpoint = original.checkpoint(TorrentContentCatalog(root / "catalog"))
      original.close()
      engine.start()
      assertFailsWith<IllegalArgumentException> {
        engine.withV2Download("wrong-task", document, destination.toString(),
          checkpoint = checkpoint, discover = { error("Unexpected discovery") }) {
          error("Invalid recovery exposed the session")
        }
      }
      assertEquals(0, engine.admittedSessionBytes)
      engine.withV2Download("restart", document, destination.toString(),
        checkpoint = checkpoint, discover = { error("Unexpected discovery") }) { session ->
        assertEquals(0L, session.verifiedBytes.value)
      }
      assertContentEquals(bytes, torrentFileSystem.read(destination / "a") { readByteArray() })
      assertEquals(0, engine.admittedSessionBytes)
    } finally {
      engine.stop()
      original.close()
      torrentFileSystem.deleteRecursively(root, mustExist = false)
    }
    assertEquals(0, buffers.allocated)
  }

  @Test
  fun engineDownloadsV2ThroughSharedNetworkAndReleasesOwnershipAfterCompletion() = runTest {
    transfer(hybrid = false)
  }

  @Test
  fun engineDownloadsSelectedHybridFileAfterPadding() = runTest { transfer(hybrid = true) }

  private suspend fun transfer(hybrid: Boolean) {
    withContext(Dispatchers.Default) {
      withTimeout(15_000) {
        val document = if (!hybrid) document() else TorrentV2Document.parse(Bencode.encode(mapOf(
          "info" to mapOf("name" to "hybrid", "meta version" to 2L, "piece length" to 16_384L,
            "file tree" to mapOf(
              "a" to mapOf("" to mapOf("length" to 4L, "pieces root" to sha256Digest(bytes))),
              "b" to mapOf("" to mapOf("length" to 4L, "pieces root" to sha256Digest(bytes)))),
            "files" to listOf(mapOf("path" to listOf("a"), "length" to 4L),
              mapOf("attr" to "p", "length" to 16_380L),
              mapOf("path" to listOf("b"), "length" to 4L)),
            "pieces" to (sha1Digest(bytes + ByteArray(16_380)) + sha1Digest(bytes))),
          "piece layers" to emptyMap<String, Any>()
        )))
        val output = output()
        val remote = createTorrentNetwork()
        val listener = remote.listen(PeerEndpoint("127.0.0.1", 0))
        val buffers = TorrentBufferBudget(1024 * 1024)
        val engine = KotlinTorrentEngine(TorrentConfig(dhtEnabled = false))
        val server = async {
          val connection = listener.accept()
          try {
            PeerIdentityHandshake(document.identity).respond(connection,
              ByteArray(20) { 2 }.toByteString(), buffers)
            val wire = PeerWire(connection, pieceCount = if (hybrid) 2 else 1)
            wire.send(PeerMessage.Bitfield(byteArrayOf(if (hybrid) 64 else 128.toByte())))
            wire.send(PeerMessage.Control(PeerMessage.Signal.UNCHOKE))
            assertEquals(PeerMessage.Control(PeerMessage.Signal.INTERESTED), wire.read())
            val request = assertIs<PeerMessage.Request>(wire.read())
            assertEquals(if (hybrid) 1 else 0, request.index)
            wire.send(PeerMessage.Piece(request.index, request.begin, bytes))
          } finally { connection.close() }
        }
        try {
          engine.start()
          engine.withV2Download("v2", document, output.toString(),
            selected = if (hybrid) setOf("2") else emptySet(),
            discover = { it.send(listener.local); awaitCancellation() }) { session ->
            assertTrue(engine.admittedSessionBytes > 0)
            session.resume()
            session.state.first { it == TorrentSessionState.FINISHED }
            assertEquals(4L, session.verifiedBytes.value)
            val file = output / if (hybrid) "b" else "a"
            assertContentEquals(bytes, torrentFileSystem.read(file) { readByteArray() })
            if (hybrid) assertFalse(torrentFileSystem.exists(output / "a"))
          }
          assertEquals(0, engine.admittedSessionBytes)
          server.await()
        } finally {
          engine.stop()
          listener.close()
          remote.close()
          server.cancelAndJoin()
          torrentFileSystem.deleteRecursively(output, mustExist = false)
        }
        assertEquals(0, buffers.allocated)
      }
    }
  }

  @Test
  fun engineShutdownJoinsV2DiscoveryBeforeReturningAdmission() = runTest {
    withContext(Dispatchers.Default) {
      withTimeout(15_000) {
        val engine = KotlinTorrentEngine(TorrentConfig(dhtEnabled = false))
        val output = output()
        val entered = CompletableDeferred<Unit>()
        val cleaning = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        engine.start()
        val owner = launch {
          engine.withV2Download("v2", document(), output.toString(), discover = {
            entered.complete(Unit)
            try { awaitCancellation() } finally {
              withContext(NonCancellable) { cleaning.complete(Unit); release.await() }
            }
          }) { it.resume(); awaitCancellation() }
        }
        try {
          entered.await()
          val stop = async { engine.stop() }
          cleaning.await()
          assertFalse(stop.isCompleted)
          assertTrue(engine.admittedSessionBytes > 0)
          release.complete(Unit)
          stop.await()
          owner.join()
          assertEquals(0, engine.admittedSessionBytes)
        } finally {
          release.complete(Unit)
          owner.cancelAndJoin()
          engine.stop()
          torrentFileSystem.deleteRecursively(output, mustExist = false)
        }
      }
    }
  }

  @Test
  fun callbacksCanRequestShutdownWithoutJoiningTheirOwnJob() = runTest {
    withContext(Dispatchers.Default) {
      withTimeout(15_000) {
        for (fromDiscovery in listOf(false, true)) {
          val engine = KotlinTorrentEngine(TorrentConfig(dhtEnabled = false))
          val output = output()
          val returned = CompletableDeferred<Unit>()
          engine.start()
          try {
            try {
              engine.withV2Download("v2", document(), output.toString(), discover = {
                engine.stop()
                returned.complete(Unit)
              }) { session ->
                if (fromDiscovery) {
                  session.resume()
                  awaitCancellation()
                } else {
                  engine.stop()
                  returned.complete(Unit)
                }
              }
            } catch (_: CancellationException) {
              currentCoroutineContext().ensureActive()
            }
            returned.await()
            engine.stop()
            assertFalse(engine.isRunning)
            assertEquals(0, engine.admittedSessionBytes)
            assertFailsWith<IllegalStateException> { engine.start() }
          } finally {
            engine.stop()
            torrentFileSystem.deleteRecursively(output, mustExist = false)
          }
        }
      }
    }
  }

  @Test
  fun duplicateIdentityAndOverlappingOutputsRejectBeforeDiscovery() = runTest {
    val engine = KotlinTorrentEngine(TorrentConfig(dhtEnabled = false))
    val output = output()
    try {
      engine.start()
      engine.withV2Download("first", document(), output.toString(), discover = {}) {
        val retained = engine.admittedSessionBytes
        // A legacy removal request cannot release the scoped v2 owner's output claim.
        engine.removeTorrent(document().info.hash.hex, deleteFiles = false)
        assertFailsWith<IllegalStateException> {
          engine.withV2Download("same", document(), (output.parent!! / "unused").toString(),
            discover = { error("Unexpected discovery") }) { error("Unexpected owner") }
        }
        assertFailsWith<IllegalStateException> {
          engine.withV2Download("overlap", document("other"), output.toString(),
            discover = { error("Unexpected discovery") }) { error("Unexpected owner") }
        }
        assertEquals(retained, engine.admittedSessionBytes)
        assertFalse(torrentFileSystem.exists(output))
      }
      engine.withV2Download("retry", document(), output.toString(), discover = {}) {}
      assertEquals(0, engine.admittedSessionBytes)
    } finally { engine.stop() }
  }

  @Test
  fun hybridIdentityCannotHaveIndependentV1AndV2Owners() = runTest {
    val engine = KotlinTorrentEngine(TorrentConfig(dhtEnabled = false))
    val hybrid = document(hybrid = true)
    // The engine accepts trusted metadata adapters; model a v1 adapter for the same raw info.
    val legacy = TorrentMetadata(checkNotNull(hybrid.identity.v1), "a", 16_384, 4,
      listOf(TorrentMetadata.TorrentFile(0, "a", 4)),
      infoBytes = hybrid.info.rawInfo.toByteArray(), pieceHashes = sha1Digest(bytes))
    val spec = TorrentTaskSpec("legacy", legacy, output().toString(), emptySet())
    try {
      engine.start()
      engine.withV2Download("v2", hybrid, output().toString(), discover = {}) {
        assertFailsWith<IllegalStateException> { engine.addTask(spec) }
      }
      engine.addTask(spec)
      val retained = engine.admittedSessionBytes
      assertFailsWith<IllegalStateException> {
        engine.withV2Download("v2", hybrid, output().toString(), discover = {}) {
          error("Unexpected owner")
        }
      }
      assertEquals(retained, engine.admittedSessionBytes)
      engine.removeTorrent(legacy.infoHash.hex, deleteFiles = false)
      assertEquals(0, engine.admittedSessionBytes)
    } finally { engine.stop() }
  }

  @Test
  fun activeTaskLimitIsSharedAcrossV1AndV2() = runTest {
    val engine = KotlinTorrentEngine(TorrentConfig(dhtEnabled = false, maxActiveTorrents = 1))
    val legacy = TorrentMetadata.fromBencode(Bencode.encode(mapOf("info" to mapOf(
      "name" to "legacy", "length" to 4L, "piece length" to 4L, "pieces" to sha1Digest(bytes)
    ))))
    val spec = TorrentTaskSpec("legacy", legacy, output().toString(), emptySet())
    try {
      engine.start()
      engine.withV2Download("v2", document(), output().toString(), discover = {}) {
        assertFailsWith<IllegalStateException> { engine.addTask(spec) }
      }
      engine.addTask(spec)
      assertFailsWith<IllegalStateException> {
        engine.withV2Download("v2", document(), output().toString(), discover = {}) {
          error("Unexpected owner")
        }
      }
    } finally { engine.stop() }
    assertEquals(0, engine.admittedSessionBytes)
  }

  @Test
  fun metadataAdmissionFailsBeforeStorageAndDoesNotLeakOwnership() = runTest {
    val engine = KotlinTorrentEngine(TorrentConfig(dhtEnabled = false, maxSessionStateBytes = 1))
    val output = output()
    try {
      engine.start()
      assertFailsWith<IllegalStateException> {
        engine.withV2Download("v2", document(), output.toString(), discover = {}) {
          error("Unexpected owner")
        }
      }
      assertFalse(torrentFileSystem.exists(output))
      assertEquals(0, engine.admittedSessionBytes)
    } finally { engine.stop() }
  }
}
