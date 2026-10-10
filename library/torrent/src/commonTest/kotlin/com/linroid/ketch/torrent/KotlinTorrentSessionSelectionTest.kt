package com.linroid.ketch.torrent

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import okio.FileSystem
import okio.IOException
import okio.Path
import kotlin.concurrent.atomics.AtomicInt
import kotlin.concurrent.atomics.AtomicLong
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.concurrent.atomics.incrementAndFetch
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Three files of one 4-byte piece each, served by a loopback seeder through a counting network. */
@OptIn(ExperimentalAtomicApi::class)
class KotlinTorrentSessionSelectionTest {
  private val payloads = List(3) { file -> ByteArray(4) { (file * 4 + it + 1).toByte() } }
  private val metadata = TorrentMetadata.fromBencode(Bencode.encode(mapOf("info" to mapOf(
    "name" to "pack", "piece length" to 4L,
    "pieces" to payloads.fold(ByteArray(0)) { hashes, bytes -> hashes + sha1Digest(bytes) },
    "files" to List(3) { mapOf("length" to 4L, "path" to listOf("f$it")) }
  ))))

  /** Counts the connections a session opens; selection changes must open none. */
  private class CountingNetwork(private val delegate: TorrentNetwork) : TorrentNetwork by delegate {
    val connects = AtomicInt(0)
    override suspend fun connect(remote: PeerEndpoint): TorrentConnection =
      delegate.connect(remote).also { connects.incrementAndFetch() }
  }

  /** Serves every piece, each once its gate opens; requests are recorded per piece. */
  private inner class Seeder(val listener: TorrentListener) {
    val gates = List(3) { CompletableDeferred<Unit>() }
    val requested = List(3) { CompletableDeferred<Unit>() }
    val accepted = AtomicInt(0)

    fun open(vararg pieces: Int) = pieces.forEach { gates[it].complete(Unit) }

    fun start(scope: CoroutineScope): Job = scope.launch {
      while (isActive) {
        val connection = listener.accept()
        accepted.incrementAndFetch()
        launch {
          try {
            val wire = PeerWire(connection, metadata)
            wire.handshake(PeerHandshake(metadata.infoHash, torrentRandomBytes(20), false, false))
            wire.send(PeerMessage.Bitfield(byteArrayOf(0xE0.toByte())))
            wire.send(PeerMessage.Control(PeerMessage.Signal.UNCHOKE))
            while (true) {
              val message = wire.read()
              if (message is PeerMessage.Request) {
                requested[message.index].complete(Unit)
                gates[message.index].await()
                wire.send(PeerMessage.Piece(message.index, message.begin,
                  payloads[message.index].copyOfRange(message.begin,
                    message.begin + message.length)))
              }
            }
          } catch (_: IOException) {
            // The session closed the connection.
          } finally {
            connection.close()
          }
        }
      }
    }
  }

  private inner class Fixture(val scope: CoroutineScope, val network: CountingNetwork) {
    val root: Path = FileSystem.SYSTEM_TEMPORARY_DIRECTORY /
      "ketch-session-selection-${InfoHash.fromBytes(torrentRandomBytes(20)).hex}"
    val budget = TorrentBufferBudget(1024 * 1024)
    val discoveries = AtomicInt(0)
    lateinit var seeder: Seeder

    fun session(
      selected: Set<Int>,
      policy: TorrentUploadPolicy = TorrentUploadPolicy.DISABLED,
      seedOnly: Boolean = false,
      slots: Semaphore = Semaphore(32),
      nowMs: () -> Long = monotonicClock(),
      peer: () -> PeerEndpoint = { seeder.listener.local },
    ) = KotlinTorrentSession(
      TorrentPieceStore(metadata, root / "pack", selected, "selection", storageSlots = slots),
      network, budget, scope, uploadPolicy = { policy }, seedOnly = seedOnly, nowMs = nowMs,
      discover = { peers, _ ->
        discoveries.incrementAndFetch()
        peers.send(peer())
        awaitCancellation()
      },
    )

    fun file(index: Int): ByteArray = torrentFileSystem.read(root / "pack/f$index") {
      readByteArray()
    }
  }

  private fun fixture(block: suspend Fixture.() -> Unit) = runTest {
    withContext(Dispatchers.Default) {
      withTimeout(20_000) {
        coroutineScope {
          val transport = createTorrentNetwork()
          val fixture = Fixture(this, CountingNetwork(transport))
          fixture.seeder = Seeder(transport.listen(PeerEndpoint("127.0.0.1", 0)))
          val server = fixture.seeder.start(this)
          try {
            fixture.block()
          } finally {
            server.cancelAndJoin()
            transport.close()
            torrentFileSystem.deleteRecursively(fixture.root, mustExist = false)
          }
          assertEquals(0, fixture.budget.allocated)
        }
      }
    }
  }

  private suspend fun awaitState(
    session: KotlinTorrentSession,
    expected: TorrentSessionState,
  ) {
    val state = session.state.first { it == expected || it == TorrentSessionState.STOPPED }
    assertEquals(expected, state, session.failure.value?.stackTraceToString())
  }

  @Test
  fun changeSelection_downloading_keepsThePeerConnected() = fixture {
    val session = session(setOf(0))
    try {
      session.resume()
      seeder.requested[0].await()
      assertTrue(session.changeSelection(setOf("0", "1")))
      assertEquals(setOf("0", "1"), session.selectedFileIds)
      assertEquals(8L, session.totalBytes)
      seeder.open(0, 1, 2)
      awaitState(session, TorrentSessionState.FINISHED)
      assertContentEquals(payloads[0], file(0))
      assertContentEquals(payloads[1], file(1))
      assertEquals(1, network.connects.load())
      assertEquals(1, seeder.accepted.load())
      assertEquals(1, discoveries.load())
    } finally {
      session.close()
    }
  }

  @Test
  fun changeSelection_inFlightClaimDeselected_peerNotBanned() = fixture {
    val session = session(setOf(0, 1))
    try {
      session.resume()
      seeder.requested[0].await()
      // Piece 0 is in flight; its claim is dropped, and a late block is not held against the peer.
      assertTrue(session.changeSelection(setOf("1")))
      seeder.open(0, 1)
      awaitState(session, TorrentSessionState.FINISHED)
      assertContentEquals(payloads[1], file(1))
      assertEquals(4L, session.downloadedBytes.value)
      assertEquals(1, network.connects.load())
      assertEquals(1, seeder.accepted.load())
    } finally {
      session.close()
    }
  }

  @Test
  fun changeSelection_checking_restartsOnlyTheCheck() = fixture {
    // Holding the only storage slot keeps the session checking files, before any discovery.
    val slots = Semaphore(1)
    slots.acquire()
    val session = session(setOf(0), slots = slots)
    try {
      session.resume()
      session.state.first { it == TorrentSessionState.CHECKING_FILES }
      assertTrue(session.changeSelection(setOf("1", "2")))
      session.state.first { it == TorrentSessionState.CHECKING_FILES }
      assertEquals(0, discoveries.load())
      slots.release()
      seeder.open(0, 1, 2)
      awaitState(session, TorrentSessionState.FINISHED)
      assertFalse(torrentFileSystem.exists(root / "pack/f0"))
      assertContentEquals(payloads[2], file(2))
      assertEquals(1, discoveries.load())
      assertEquals(1, network.connects.load())
    } finally {
      session.close()
    }
  }

  @Test
  fun changeSelection_paused_appliesAtResumeAndReturnsFalse() = fixture {
    val session = session(setOf(0))
    try {
      session.resume()
      seeder.requested[0].await()
      session.pause()
      assertFalse(session.changeSelection(setOf("0", "1")))
      assertEquals(TorrentSessionState.PAUSED, session.state.value)
      seeder.open(0, 1, 2)
      session.resume()
      awaitState(session, TorrentSessionState.FINISHED)
      assertContentEquals(payloads[1], file(1))
    } finally {
      session.close()
    }
  }

  @Test
  fun changeSelection_swarmExited_returnsFalse() = fixture {
    seeder.open(0, 1, 2)
    val session = session(setOf(0))
    try {
      session.resume()
      awaitState(session, TorrentSessionState.FINISHED)
      assertFalse(session.changeSelection(setOf("0", "1")))
      assertEquals(TorrentSessionState.FINISHED, session.state.value)
      session.resume()
      // Still FINISHED from the first run until the new one checks: wait for the new bytes.
      session.downloadedBytes.first { it == 8L }
      awaitState(session, TorrentSessionState.FINISHED)
      assertContentEquals(payloads[1], file(1))
    } finally {
      session.close()
    }
  }

  @Test
  fun changeSelection_seedingExpand_downloadsThenSeedsAgain() = fixture {
    seeder.open(0)
    val session = session(setOf(0), TorrentUploadPolicy.SEED_AFTER_COMPLETION)
    try {
      session.resume()
      awaitState(session, TorrentSessionState.SEEDING)
      assertTrue(session.changeSelection(setOf("0", "1")))
      assertEquals(TorrentSessionState.DOWNLOADING, session.state.value)
      seeder.requested[1].await()
      seeder.open(1)
      awaitState(session, TorrentSessionState.SEEDING)
      assertContentEquals(payloads[1], file(1))
      assertEquals(8L, session.downloadedBytes.value)
      assertEquals(1, network.connects.load())
      assertEquals(1, discoveries.load())
    } finally {
      session.close()
    }
  }

  @Test
  fun changeSelection_seedingExpandAfterNinetySeconds_keepsPeers() = fixture {
    val now = AtomicLong(0)
    seeder.open(0)
    val session = session(setOf(0), TorrentUploadPolicy.SEED_AFTER_COMPLETION,
      nowMs = { now.load() })
    try {
      session.resume()
      awaitState(session, TorrentSessionState.SEEDING)
      // The seed got nothing from its peer for longer than a downloader tolerates.
      now.store(100_000)
      delay(300)
      assertTrue(session.changeSelection(setOf("0", "1")))
      seeder.requested[1].await()
      // Several worker passes while the peer holds the piece back: it is not evicted.
      delay(500)
      assertEquals(TorrentSessionState.DOWNLOADING, session.state.value)
      seeder.open(1)
      awaitState(session, TorrentSessionState.SEEDING)
      assertContentEquals(payloads[1], file(1))
      assertEquals(1, network.connects.load())
      assertEquals(1, seeder.accepted.load())
    } finally {
      session.close()
    }
  }

  @Test
  fun revokedDeselectedPiece_refusesTheRequestAndKeepsThePeer() = fixture {
    torrentFileSystem.createDirectories(root / "pack")
    payloads.forEachIndexed { index, bytes ->
      torrentFileSystem.write(root / "pack/f$index") { write(bytes) }
    }
    val leecher = network.listen(PeerEndpoint("127.0.0.1", 0))
    val session = session(setOf(0, 1, 2), TorrentUploadPolicy.SEED_AFTER_COMPLETION,
      peer = { leecher.local })
    try {
      session.resume()
      awaitState(session, TorrentSessionState.SEEDING)
      assertTrue(session.changeSelection(setOf("0")))
      torrentFileSystem.delete(root / "pack/f1")
      val connection = leecher.accept()
      try {
        val wire = PeerWire(connection, metadata)
        wire.handshake(PeerHandshake(metadata.infoHash, torrentRandomBytes(20), false, false))
        wire.send(PeerMessage.Bitfield(byteArrayOf(0)))
        wire.send(PeerMessage.Control(PeerMessage.Signal.INTERESTED))
        while (wire.read() != PeerMessage.Control(PeerMessage.Signal.UNCHOKE)) { }
        wire.send(PeerMessage.Request(1, 0, 4))
        // The piece is refused with a choke instead of payload, and the connection stays.
        while (true) {
          val message = wire.read()
          assertFalse(message is PeerMessage.Piece)
          if (message == PeerMessage.Control(PeerMessage.Signal.CHOKE)) break
        }
        wire.send(PeerMessage.KeepAlive)
        assertNull(withTimeoutOrNull(300) {
          while (true) {
            val message = wire.read()
            assertFalse(message is PeerMessage.Piece)
          }
        })
        assertEquals(TorrentSessionState.SEEDING, session.state.value)
        assertNull(session.failure.value)
        assertContentEquals(booleanArrayOf(true, false, true), session.verifiedPieces())
      } finally {
        connection.close()
      }
    } finally {
      session.close()
      leecher.close()
    }
  }

  @Test
  fun resume_seedOnlyChangedPayload_stopsWithoutDownloading() = fixture {
    torrentFileSystem.createDirectories(root / "pack")
    payloads.forEachIndexed { index, bytes ->
      torrentFileSystem.write(root / "pack/f$index") { write(bytes) }
    }
    torrentFileSystem.write(root / "pack/f2") { writeUtf8("bad!") }
    val changed = session(setOf(0, 2), TorrentUploadPolicy.SEED_AFTER_COMPLETION, seedOnly = true)
    try {
      changed.resume()
      changed.state.first { it == TorrentSessionState.STOPPED }
      assertIs<IncompleteSeedException>(changed.failure.value)
    } finally {
      changed.close()
    }
    assertEquals(0, discoveries.load())
    assertEquals(0, network.connects.load())
    // The same files, still complete for the selection, seed.
    val seed = session(setOf(0, 1), TorrentUploadPolicy.SEED_AFTER_COMPLETION, seedOnly = true)
    try {
      seed.resume()
      awaitState(seed, TorrentSessionState.SEEDING)
    } finally {
      seed.close()
    }
  }
}
