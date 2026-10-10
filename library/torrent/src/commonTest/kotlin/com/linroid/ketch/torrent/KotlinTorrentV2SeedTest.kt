package com.linroid.ketch.torrent

import com.linroid.ketch.core.engine.HttpEngine
import com.linroid.ketch.core.engine.ServerInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.channels.SendChannel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import okio.ByteString.Companion.toByteString
import okio.FileSystem
import okio.Path
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** A v2 owner as a seed: starting complete, policy changes, removal and its own upload limit. */
class KotlinTorrentV2SeedTest {
  // Pieces of 32 KiB: a spans pieces 0 and 1, b is piece 2, c is piece 3.
  private val fixture = TorrentV2Fixture.build(listOf("a" to 40_000, "b" to 5, "c" to 20_000))
  private val pieceCount = fixture.layout.pieceCount.toInt()
  private val handshakes = TorrentBufferBudget(65_536)
  private val tracker = "http://tracker.test/announce"

  /** One announce the fake tracker saw. */
  private data class Announce(val event: String?, val left: Long)

  /** An HTTP tracker that records announces and returns no peers. */
  private class Tracker : HttpEngine {
    val announces = MutableStateFlow<List<Announce>>(emptyList())

    override suspend fun head(url: String, headers: Map<String, String>): ServerInfo =
      error("Unused")

    override suspend fun download(
      url: String,
      range: LongRange?,
      headers: Map<String, String>,
      onData: suspend (ByteArray) -> Unit,
    ) {
      val event = url.substringAfter("&event=", "").substringBefore('&').ifEmpty { null }
      val left = url.substringAfter("&left=").substringBefore('&').toLong()
      announces.update { it + Announce(event, left) }
      onData(Bencode.encode(mapOf("interval" to 3600L, "peers" to ByteArray(0))))
    }

    override fun close() = Unit
  }

  private fun root(): Path = (FileSystem.SYSTEM_TEMPORARY_DIRECTORY /
    "ketch-v2-seed-${InfoHash.fromBytes(torrentRandomBytes(20)).hex}")
    .also { torrentFileSystem.createDirectories(it) }

  /**
   * Runs [body] with a started engine on real loopback, whose upload cap all torrents share is
   * [uploadRateLimit], then checks for leaks.
   */
  private fun engine(
    policy: TorrentUploadPolicy,
    http: Tracker = Tracker(),
    uploadRateLimit: Long = 0,
    body: suspend (KotlinTorrentEngine, Path) -> Unit,
  ) = runTest {
    withContext(Dispatchers.Default) {
      withTimeout(30_000) {
        val root = root()
        val engine = KotlinTorrentEngine(TorrentConfig(dhtEnabled = false,
          uploadPolicy = policy, uploadRateLimit = uploadRateLimit), http = TorrentHttp(http),
          listenHost = "127.0.0.1")
        try {
          engine.start()
          body(engine, root)
          assertEquals(0, engine.admittedSessionBytes)
        } finally {
          engine.stop()
          torrentFileSystem.deleteRecursively(root, mustExist = false)
        }
        assertEquals(0, engine.admittedSessionBytes)
        assertEquals(0, engine.allocatedExchangeBytes)
      }
    }
  }

  /** Adds an owner whose payload is already on disk, adopted through its checkpoint. */
  private suspend fun seeded(
    engine: KotlinTorrentEngine,
    root: Path,
    trackers: List<List<String>> = emptyList(),
    discover: (suspend (SendChannel<PeerEndpoint>) -> Unit)? = null,
  ): TorrentV2DownloadSession {
    val output = root / "payload"
    val checkpoint = fixture.preseed(output, "seed")
    return engine.addV2Task(TorrentV2TaskSpec("seed", fixture.document, output.toString(),
      checkpoint = checkpoint, trackerTiers = trackers, privacy = TorrentDiscoveryPrivacy.PUBLIC,
      discover = discover))
  }

  private suspend fun TorrentV2DownloadSession.settled(): TorrentSessionState = state.first {
    it == TorrentSessionState.SEEDING || it == TorrentSessionState.FINISHED ||
      it == TorrentSessionState.STOPPED
  }

  /** A raw peer dialing the engine: it knows the v2 swarm and has nothing. */
  private suspend fun dial(network: TorrentNetwork, engine: KotlinTorrentEngine): PeerWire {
    val connection = network.connect(PeerEndpoint("127.0.0.1", engine.listenPort))
    PeerIdentityHandshake(fixture.document.identity).initiate(connection,
      PeerIdentityHandshake.Mode.V2, torrentRandomBytes(20).toByteString(), handshakes)
    return PeerWire(connection, pieceCount = pieceCount)
  }

  private suspend fun PeerWire.readUntil(match: (PeerMessage) -> Boolean): PeerMessage {
    while (true) {
      val message = read()
      if (match(message)) return message
    }
  }

  private suspend fun PeerWire.unchoked() {
    send(PeerMessage.Control(PeerMessage.Signal.INTERESTED))
    readUntil { it == PeerMessage.Control(PeerMessage.Signal.UNCHOKE) }
  }

  private suspend fun PeerWire.block(index: Int, begin: Int): PeerMessage.Piece {
    send(PeerMessage.Request(index, begin, minOf(16_384, fixture.v2Piece(index).size - begin)))
    return assertIs<PeerMessage.Piece>(readUntil { it is PeerMessage.Piece })
  }

  /** The peer's connection ends without another message; a timeout means it stayed open. */
  private suspend fun assertClosed(wire: PeerWire) {
    val failure = runCatching { withTimeout(5_000) { wire.readUntil { false } } }
      .exceptionOrNull()
    assertNotNull(failure)
    assertFalse(failure is TimeoutCancellationException, "The owner kept the connection open")
  }

  @Test
  fun startsCompleteAndSeedsWithDiscoveryWhenPolicyIsSeed() {
    val http = Tracker()
    engine(TorrentUploadPolicy.SEED_AFTER_COMPLETION, http) { engine, root ->
      val session = seeded(engine, root, listOf(listOf(tracker)))
      session.resume()
      assertEquals(TorrentSessionState.SEEDING, session.settled(),
        session.failure.value?.stackTraceToString())
      // Seeding joins the swarm: the tracker hears a start with nothing left to download.
      assertEquals(Announce("started", 0), http.announces.first { it.isNotEmpty() }.first())
      val network = createTorrentNetwork()
      try {
        val peer = dial(network, engine)
        assertContentEquals(pieceBitfield(BooleanArray(pieceCount) { true }),
          assertIs<PeerMessage.Bitfield>(peer.read()).bytes)
        peer.unchoked()
        assertContentEquals(fixture.v2Piece(3).copyOfRange(0, 16_384), peer.block(3, 0).bytes)
        engine.removeTorrent(fixture.document.info.hash.hex)
        assertClosed(peer)
      } finally { network.close() }
      assertEquals(TorrentSessionState.STOPPED, session.state.value)
      // Removal announces the stop.
      assertEquals("stopped", http.announces.value.last().event)
      assertTrue(http.announces.value.none { it.left != 0L })
    }
  }

  @Test
  fun startsCompleteAndFinishesWithoutDiscoveryWhenDisabled() {
    val http = Tracker()
    engine(TorrentUploadPolicy.DISABLED, http) { engine, root ->
      val session = seeded(engine, root, listOf(listOf(tracker)))
      session.resume()
      assertEquals(TorrentSessionState.FINISHED, session.settled())
      // Without seeding a complete owner never joins the swarm.
      assertTrue(http.announces.value.isEmpty())
      engine.removeTorrent(fixture.document.info.hash.hex)
      assertTrue(http.announces.value.isEmpty())
    }
  }

  @Test
  fun whileDownloadingStopsUploadingAtCompletion() =
    engine(TorrentUploadPolicy.WHILE_DOWNLOADING) { engine, root ->
      val session = engine.addV2Task(TorrentV2TaskSpec("leech", fixture.document,
        (root / "payload").toString(), privacy = TorrentDiscoveryPrivacy.PUBLIC,
        discover = { awaitCancellation() }))
      session.resume()
      session.state.first { it == TorrentSessionState.DOWNLOADING }
      val network = createTorrentNetwork()
      try { coroutineScope {
        // A seed that has piece 0 only, for now, and serves what it is asked.
        val seed = dial(network, engine)
        seed.send(PeerMessage.Bitfield(pieceBitfield(BooleanArray(pieceCount) { it == 0 })))
        seed.send(PeerMessage.Control(PeerMessage.Signal.UNCHOKE))
        suspend fun serve(until: (PeerMessage) -> Boolean) {
          while (true) {
            val message = seed.read()
            if (until(message)) return
            if (message !is PeerMessage.Request) continue
            seed.send(PeerMessage.Piece(message.index, message.begin, fixture.v2Piece(
              message.index).copyOfRange(message.begin, message.begin + message.length)))
          }
        }
        // The owner tells the seed it verified piece 0, as it tells every peer.
        serve { it == PeerMessage.Have(0) }
        // While downloading, a leecher is unchoked and served the verified piece.
        val leecher = dial(network, engine)
        assertContentEquals(pieceBitfield(BooleanArray(pieceCount) { it == 0 }),
          assertIs<PeerMessage.Bitfield>(leecher.read()).bytes)
        leecher.unchoked()
        assertContentEquals(fixture.v2Piece(0).copyOfRange(0, 16_384), leecher.block(0, 0).bytes)
        while (session.uploadedBytes() < 16_384) delay(10)
        // The seed offers the rest; once complete the owner finishes instead of seeding.
        for (index in 1 until pieceCount) seed.send(PeerMessage.Have(index))
        val finished = async { runCatching { serve { false } } }
        assertEquals(TorrentSessionState.FINISHED, session.settled(),
          session.failure.value?.stackTraceToString())
        assertClosed(leecher)
        finished.await()
        assertEquals(16_384L, session.uploadedBytes())
        for ((index, name) in fixture.names.withIndex()) {
          assertContentEquals(fixture.payloads[index],
            torrentFileSystem.read(root / "payload" / name) { readByteArray() })
        }
      } } finally { network.close() }
      engine.removeTorrent(fixture.document.info.hash.hex)
    }

  @Test
  fun enginePolicyChangeFinishesV2Seeder() =
    engine(TorrentUploadPolicy.SEED_AFTER_COMPLETION) { engine, root ->
      val session = seeded(engine, root)
      session.resume()
      assertEquals(TorrentSessionState.SEEDING, session.settled())
      val network = createTorrentNetwork()
      try {
        val peer = dial(network, engine)
        peer.read()
        peer.unchoked()
        engine.setUploadPolicy(TorrentUploadPolicy.WHILE_DOWNLOADING)
        // Neither policy but seeding keeps a complete owner going.
        assertEquals(TorrentSessionState.FINISHED, session.state.first {
          it == TorrentSessionState.FINISHED || it == TorrentSessionState.STOPPED
        })
        assertClosed(peer)
      } finally { network.close() }
      // A later start follows the new policy.
      session.resume()
      assertEquals(TorrentSessionState.FINISHED, session.settled())
      engine.removeTorrent(fixture.document.info.hash.hex)
    }

  @Test
  fun removeByV2HexStopsSeederBeforeCleanup() =
    engine(TorrentUploadPolicy.SEED_AFTER_COMPLETION) { engine, root ->
      val session = seeded(engine, root)
      session.resume()
      assertEquals(TorrentSessionState.SEEDING, session.settled())
      val network = createTorrentNetwork()
      try {
        val peer = dial(network, engine)
        peer.read()
        peer.unchoked()
        assertEquals(fixture.v2Piece(2).size, peer.block(2, 0).bytes.size)
        // Removal stops the seed and only then deletes what the owner created.
        engine.removeTorrent(fixture.document.info.hash.hex, deleteFiles = true)
        assertEquals(TorrentSessionState.STOPPED, session.state.value)
        assertClosed(peer)
      } finally { network.close() }
      assertFalse(torrentFileSystem.exists(root / "payload"))
      assertEquals(emptySet(), engine.incomingTags())
    }

  @Test
  fun sessionUploadLimitAppliesToV2Seeder() =
    engine(TorrentUploadPolicy.SEED_AFTER_COMPLETION) { engine, root ->
      val session = seeded(engine, root)
      session.resume()
      assertEquals(TorrentSessionState.SEEDING, session.settled())
      // One byte a second: the bucket's first block is all the peer gets for now.
      session.setUploadRateLimit(1)
      val network = createTorrentNetwork()
      try {
        val peer = dial(network, engine)
        peer.read()
        peer.unchoked()
        assertEquals(16_384, peer.block(0, 0).bytes.size)
        peer.send(PeerMessage.Request(0, 16_384, 16_384))
        delay(500)
        assertEquals(16_384L, session.uploadedBytes())
        // Lifting it lets the held block go.
        session.setUploadRateLimit(0)
        val piece = assertIs<PeerMessage.Piece>(peer.readUntil { it is PeerMessage.Piece })
        assertEquals(16_384, piece.begin)
        while (session.uploadedBytes() < 32_768) delay(10)
        assertEquals(32_768L, session.uploadedBytes())
      } finally { network.close() }
      engine.removeTorrent(fixture.document.info.hash.hex)
    }

  @Test
  fun engineUploadLimitAppliesToV2Seeder() =
    // The cap all torrents share, a byte a second; the owner's own bucket stays unlimited.
    engine(TorrentUploadPolicy.SEED_AFTER_COMPLETION, uploadRateLimit = 1) { engine, root ->
      val session = seeded(engine, root)
      session.resume()
      assertEquals(TorrentSessionState.SEEDING, session.settled())
      val network = createTorrentNetwork()
      try {
        val peer = dial(network, engine)
        peer.read()
        peer.unchoked()
        // The engine's bucket starts with one block, all the peer gets for now.
        assertEquals(16_384, peer.block(0, 0).bytes.size)
        peer.send(PeerMessage.Request(0, 16_384, 16_384))
        delay(500)
        assertEquals(16_384L, session.uploadedBytes())
        // Lifting the engine's cap lets the held block go.
        engine.setUploadRateLimit(0)
        val piece = assertIs<PeerMessage.Piece>(peer.readUntil { it is PeerMessage.Piece })
        assertEquals(16_384, piece.begin)
        assertContentEquals(fixture.v2Piece(0).copyOfRange(16_384, 32_768), piece.bytes)
        while (session.uploadedBytes() < 32_768) delay(10)
      } finally { network.close() }
      engine.removeTorrent(fixture.document.info.hash.hex)
    }

  @Test
  fun engineUploadLimitHoldsBackBlockProofReads() =
    engine(TorrentUploadPolicy.SEED_AFTER_COMPLETION, uploadRateLimit = 1) { engine, root ->
      blockProofReads(engine, root) { engine.setUploadRateLimit(0) }
    }

  @Test
  fun sessionUploadLimitHoldsBackBlockProofReads() =
    // The engine's cap stays unlimited; only the owner's own bucket, a byte a second, holds back.
    engine(TorrentUploadPolicy.SEED_AFTER_COMPLETION) { engine, root ->
      blockProofReads(engine, root, sessionLimit = 1) { it.setUploadRateLimit(0) }
    }

  /**
   * A seeder proves block hashes of two pieces, each read back from disk, under an upload limit
   * whose bucket holds one block, the owner's own when [sessionLimit] is set: reading the first
   * piece spends more than it held, so the second waits until [lift] lifts the limit.
   */
  private suspend fun blockProofReads(
    engine: KotlinTorrentEngine,
    root: Path,
    sessionLimit: Long = 0,
    lift: (TorrentV2DownloadSession) -> Unit,
  ) {
    val session = seeded(engine, root)
    session.setUploadRateLimit(sessionLimit)
    session.resume()
    assertEquals(TorrentSessionState.SEEDING, session.settled())
    val network = createTorrentNetwork()
    try {
      val peer = dial(network, engine)
      peer.read()
      peer.unchoked()
      suspend fun ask(selector: PeerHashSelector): PeerHashMessage {
        peer.send(PeerHashWire.encode(PeerHashMessage.Request(selector)))
        val answer = peer.readUntil { it is PeerMessage.Unknown && it.id in 22..23 }
        return checkNotNull(PeerHashWire.decode(answer as PeerMessage.Unknown))
      }
      // Block hashes of piece 0, and of file c, which fits piece 3: each read back from disk.
      val first = PeerHashSelector(checkNotNull(fixture.document.info.files[0].piecesRoot),
        0, 0, 2, 1)
      val other = PeerHashSelector(checkNotNull(fixture.document.info.files[2].piecesRoot),
        0, 0, 2, 0)
      assertIs<PeerHashMessage.Hashes>(ask(first))
      // Reading piece 0 spent more than the bucket held: piece 3 waits for it.
      assertEquals(PeerHashMessage.Reject(other), ask(other))
      lift(session)
      assertIs<PeerHashMessage.Hashes>(ask(other))
    } finally { network.close() }
    engine.removeTorrent(fixture.document.info.hash.hex)
  }
}
