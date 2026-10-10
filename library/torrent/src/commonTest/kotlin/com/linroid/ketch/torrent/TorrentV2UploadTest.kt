package com.linroid.ketch.torrent

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import okio.Buffer
import okio.ByteString.Companion.toByteString
import okio.FileHandle
import okio.FileSystem
import okio.ForwardingFileSystem
import okio.IOException
import okio.Path
import kotlin.concurrent.atomics.AtomicInt
import kotlin.concurrent.atomics.AtomicLong
import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * A swarm loop uploading verified pieces to peers over in-memory connections. Real time: reads
 * go to disk, and virtual time would expire idle peers meanwhile.
 */
@OptIn(ExperimentalAtomicApi::class)
class TorrentV2UploadTest {
  // Pieces of 32 KiB: a spans pieces 0 and 1 (7 232 bytes in 1), b is piece 2, c is piece 3.
  private val files = listOf("a" to 40_000, "b" to 5, "c" to 20_000)
  private val pure = TorrentV2Fixture.build(files)
  private val hybrid = TorrentV2Fixture.build(files, hybrid = true)

  private val interested = PeerMessage.Control(PeerMessage.Signal.INTERESTED)
  private val unchoke = PeerMessage.Control(PeerMessage.Signal.UNCHOKE)
  private val choke = PeerMessage.Control(PeerMessage.Signal.CHOKE)

  /** The far end of one connection: the test speaks for the peer and reads what we sent it. */
  private class Remote(
    private val pieceCount: Int,
    override val remote: PeerEndpoint,
  ) : TorrentConnection {
    private val input = Channel<ByteArray>(Channel.UNLIMITED)
    private val pending = Buffer()
    val received = Channel<PeerMessage>(Channel.UNLIMITED)
    val closed = CompletableDeferred<Unit>()

    override suspend fun readExactly(size: Int): ByteArray {
      while (pending.size < size) pending.write(input.receive())
      return pending.readByteArray(size.toLong())
    }

    override suspend fun write(bytes: ByteArray) {
      check(!closed.isCompleted)
      val buffer = Buffer().write(bytes)
      val size = buffer.readInt()
      received.trySend(PeerWire.decode(buffer.readByteArray(size.toLong()),
        pieceCount = pieceCount))
    }

    fun send(message: PeerMessage) {
      input.trySend(PeerWire.encode(message, pieceCount = pieceCount))
    }

    /** The next message we sent this peer that [match]es; others are skipped. */
    suspend fun next(match: (PeerMessage) -> Boolean = { it != PeerMessage.KeepAlive }):
      PeerMessage {
      while (true) {
        val message = received.receive()
        if (match(message)) return message
      }
    }

    suspend fun nextPiece(): PeerMessage.Piece =
      assertIs<PeerMessage.Piece>(next { it is PeerMessage.Piece })

    override fun close() {
      closed.complete(Unit)
      input.cancel()
    }
  }

  /**
   * One swarm loop over [fixture]'s storage and the peers the test connects. Upload reads are
   * charged to a partition of [uploadBytes] when given. We listen on [listenPort] as peers see
   * it, unknown when zero. The loop's clock runs with real time, plus what the test [skip]s.
   */
  private inner class Rig(
    val fixture: TorrentV2Fixture,
    private val onCompleted: suspend () -> Boolean,
    uploadBytes: Int? = null,
    private val listenPort: Int = 0,
  ) {
    val layout = fixture.layout
    val pieceCount = layout.pieceCount.toInt()
    val buffers = TorrentBufferBudget(4 * 1024 * 1024)
    val uploads = uploadBytes?.let { TorrentBufferBudget(it, buffers) } ?: buffers
    val state = TorrentBufferBudget(16 * 1024 * 1024)
    private val clock = monotonicClock()
    private val skipped = AtomicLong(0)
    val output = FileSystem.SYSTEM_TEMPORARY_DIRECTORY /
      "ketch-v2-upload-${InfoHash.fromBytes(torrentRandomBytes(20)).hex}"
    /** Pieces read back from disk: the store opens a file read-only only to read one. */
    val pieceReads = AtomicInt(0)
    private val files = object : ForwardingFileSystem(torrentFileSystem) {
      override fun openReadOnly(file: Path): FileHandle {
        pieceReads.addAndFetch(1)
        return super.openReadOnly(file)
      }
    }
    val store = TorrentV2PieceStore(fixture.document, output, emptySet(), "upload", buffers,
      Semaphore(2), files)
    val policy = AtomicReference(TorrentUploadPolicy.SEED_AFTER_COMPLETION)
    val global = TorrentRateLimiter()
    val session = TorrentRateLimiter()
    private val connections = Channel<PeerV2Connector.Connected>(Channel.UNLIMITED,
      onUndeliveredElement = { it.close() })
    val discovered = Channel<TorrentV2Discovered>()
    val dial = Channel<TorrentV2DialTarget>(4)
    val failures = Channel<PeerV2Dialer.Failure<TorrentV2DialTarget>>(4)
    private val controls = Channel<TorrentV2SessionLoop.Control>(8)
    /** Runs when a selection change leaves the complete loop with pieces to download. */
    var onIncomplete: suspend () -> Unit = {}
    private val remotes = mutableListOf<Remote>()
    private var port = 40_000
    private val network = object : TorrentNetwork {
      override suspend fun connect(remote: PeerEndpoint): TorrentConnection = error("Unused")
      override suspend fun listen(local: PeerEndpoint): TorrentListener = error("Unused")
      override suspend fun bindUdp(local: PeerEndpoint): TorrentDatagramSocket = error("Unused")
      override fun close() = Unit
    }

    /**
     * A peer that claims [peerId], handed to the loop as the responders would: one that dialed
     * us from [host], or the one the loop [dialed]. With [extensions], both sides set BEP 10.
     * Its frames, both ways, are charged to [frames].
     */
    fun connect(
      peerId: Int,
      mode: PeerIdentityHandshake.Mode = PeerIdentityHandshake.Mode.V2,
      host: String = "192.0.2.$peerId",
      extensions: Boolean = false,
      dialed: TorrentV2DialTarget? = null,
      frames: TorrentBufferBudget = buffers,
    ): Remote {
      val remote = Remote(pieceCount, dialed?.endpoint ?: PeerEndpoint(host, port++))
      remotes += remote
      val v2 = mode == PeerIdentityHandshake.Mode.V2
      val transport = PeerHashTransport(remote, PeerHashExchange(buffers, { null }), frames,
        pieceCount = pieceCount, hashMessages = v2)
      val blocks = PeerBlockExchange(layout, transport, buffers, mode = mode)
      val id = ByteArray(20) { peerId.toByte() }.toByteString()
      val route = PeerIdentityHandshake.Result(fixture.document.identity, mode, id,
        extensions = extensions, dht = false)
      val origin = if (dialed != null) PeerV2Origin.Outgoing(remote.remote)
        else PeerV2Origin.Incoming(remote.remote)
      val info = PeerInfo(id, mode, origin, extensions = extensions, link = remote)
      val admission = checkNotNull(state.reserve(1024))
      check(connections.trySend(PeerV2Connector.Connected(route, info, transport, blocks,
        admission, generation = dialed?.generation ?: 0)).isSuccess)
      return remote
    }

    /** A connected peer we unchoked: it saw our bitfield (we have something) and asked. */
    suspend fun unchoked(
      peerId: Int,
      mode: PeerIdentityHandshake.Mode = PeerIdentityHandshake.Mode.V2,
      host: String = "192.0.2.$peerId",
    ): Remote {
      val remote = connect(peerId, mode, host)
      assertIs<PeerMessage.Bitfield>(remote.next())
      remote.send(interested)
      assertEquals(unchoke, remote.next())
      return remote
    }

    fun policyChanged() {
      check(controls.trySend(TorrentV2SessionLoop.Control.PolicyChanged).isSuccess)
    }

    /** Tells the loop the store's selection changed; returns once the loop follows it. */
    suspend fun select() {
      val done = CompletableDeferred<Unit>()
      controls.send(TorrentV2SessionLoop.Control.Select(done))
      done.await()
    }

    /** Moves the loop's clock [ms] on and wakes it to look again. */
    fun skip(ms: Long) {
      skipped.addAndFetch(ms)
      policyChanged()
    }

    /** Runs the loop while [body] runs, then stops it; a failure of the loop is rethrown. */
    suspend fun run(body: suspend (Deferred<Unit>) -> Unit) = coroutineScope {
      val swarm = { serve: TorrentV2ServeWorker ->
        TorrentV2Swarm(fixture.document,
          TorrentV2Runtime(network, ByteArray(20) { 9 }.toByteString(), buffers, state,
            uploadRate = global, uploadPolicy = { policy.load() },
            listenPortFor = { listenPort }, uploadBuffers = uploads),
          restricted = false, waitForPeers = true, discovered = discovered, dial = dial,
          dialFailures = failures, controls = controls, connectionLimit = { 8 },
          generation = { 0 }, serve = serve, sessionUploadRate = session,
          onCompleted = onCompleted, onIncomplete = { onIncomplete() })
      }
      val loop = async {
        PeerV2Pool.run(state, maxPeers = 8) { pool ->
          TorrentV2CommitWorker.run(store) { worker ->
            TorrentV2ServeWorker.run(store, uploads = uploads) { serve ->
              TorrentV2SessionLoop.download(layout, store.selectedIds(), store, pool, worker,
                buffers,
                state, maxPeers = 8, connections = connections,
                nowMs = { clock() + skipped.load() }, swarm = swarm(serve))
            }
          }
        }
      }
      try { body(loop) } finally {
        loop.cancelAndJoin()
        connections.cancel()
      }
    }

    fun checkReleased() {
      assertTrue(remotes.all { it.closed.isCompleted })
      assertEquals(0, buffers.allocated)
      assertEquals(0, state.allocated)
    }

    suspend fun close() {
      connections.cancel()
      discovered.cancel()
      dial.cancel()
      failures.cancel()
      controls.cancel()
      store.cleanup()
    }
  }

  private fun upload(
    fixture: TorrentV2Fixture = pure,
    onCompleted: suspend () -> Boolean = { true },
    uploadBytes: Int? = null,
    listenPort: Int = 0,
    test: suspend Rig.() -> Unit,
  ) = runTest {
    withContext(Dispatchers.Default) {
      withTimeout(20_000) {
        val rig = Rig(fixture, onCompleted, uploadBytes, listenPort)
        try {
          rig.store.initialize()
          rig.test()
          rig.checkReleased()
        } finally {
          rig.close()
        }
      }
    }
  }

  private fun all(count: Int) = pieceBitfield(BooleanArray(count) { true })

  @Test
  fun unchokesInterestedPeerAndServesVerifiedBlock() = upload {
    pure.seed(store)
    run {
      val peer = connect(1)
      // A full bitfield comes first; the unchoke follows once the peer says it is interested.
      assertContentEquals(all(pieceCount), assertIs<PeerMessage.Bitfield>(peer.next()).bytes)
      peer.send(interested)
      assertEquals(unchoke, peer.next())
      peer.send(PeerMessage.Request(0, 16_384, 16_384))
      val piece = peer.nextPiece()
      assertEquals(0, piece.index)
      assertEquals(16_384, piece.begin)
      assertContentEquals(pure.v2Piece(0).copyOfRange(16_384, 32_768), piece.bytes)
      // Counted once the actor wrote it.
      while (store.uploadedBytes() < 16_384) delay(10)
      assertEquals(16_384L, store.uploadedBytes())
    }
  }

  @Test
  fun ignoresRequestsWhileChokedOrUnverified() = upload(
    onCompleted = { error("Never completes") },
  ) {
    pure.seed(store, listOf(0))
    policy.store(TorrentUploadPolicy.WHILE_DOWNLOADING)
    run {
      val peer = connect(1)
      assertContentEquals(pieceBitfield(booleanArrayOf(true, false, false, false)),
        assertIs<PeerMessage.Bitfield>(peer.next()).bytes)
      // Asked while choked: dropped, as BEP 3 drops requests a CHOKE crossed.
      peer.send(PeerMessage.Request(0, 0, 16_384))
      peer.send(interested)
      assertEquals(unchoke, peer.next())
      // Piece 3 is not verified, so there is nothing to send for it.
      peer.send(PeerMessage.Request(3, 0, 16_384))
      peer.send(PeerMessage.Request(0, 16_384, 16_384))
      val served = peer.nextPiece()
      assertEquals(0 to 16_384, served.index to served.begin)
      peer.send(PeerMessage.Request(0, 0, 1))
      // Had the choked request been queued, its block would have come first.
      val next = peer.nextPiece()
      assertEquals(Triple(0, 0, 1), Triple(next.index, next.begin, next.bytes.size))
    }
  }

  @Test
  fun requestPastProtocolPieceStopsAndBansPeer() = upload {
    pure.seed(store)
    run {
      val peer = unchoked(1)
      // Piece 3 is the last one, but a pure v2 piece is whole: this block ends past its 32 KiB.
      peer.send(PeerMessage.Request(3, 24_576, 16_384))
      peer.closed.await()
      assertNull(withTimeoutOrNull(100) { peer.next { it is PeerMessage.Piece } })
      delay(200)
      // Banned on its host: the same peer arriving from there again is closed unanswered.
      val again = connect(1)
      again.closed.await()
      assertTrue(again.received.tryReceive().isFailure)
      // Another peer is unaffected.
      val other = unchoked(2)
      other.send(PeerMessage.Request(3, 0, 16_384))
      assertContentEquals(pure.v2Piece(3).copyOfRange(0, 16_384), other.nextPiece().bytes)
    }
  }

  @Test
  fun v2RequestPastFileTailInsideProtocolPieceIsZeroFilled() = upload {
    pure.seed(store)
    run {
      val peer = unchoked(1)
      peer.send(PeerMessage.Request(1, 0, 16_384))
      peer.send(PeerMessage.Request(1, 16_384, 16_384))
      val first = peer.nextPiece()
      val second = peer.nextPiece()
      // Piece 1 holds a's last 7 232 bytes; the rest of its 32 KiB is alignment, served as zeros.
      assertEquals(7_232, pure.v2Piece(1).size)
      assertContentEquals(pure.v2Piece(1) + ByteArray(16_384 - 7_232), first.bytes)
      assertContentEquals(ByteArray(16_384), second.bytes)
    }
  }

  @Test
  fun v2LastPieceIsWholeWithZerosPastTheLastFile() = upload {
    pure.seed(store)
    run {
      val peer = unchoked(1)
      // libtorrent pads a pure v2 torrent's last file to a piece too, and asks for whole blocks.
      peer.send(PeerMessage.Request(3, 16_384, 16_384))
      val tail = pure.v2Piece(3).copyOfRange(16_384, 20_000)
      assertContentEquals(tail + ByteArray(16_384 - tail.size), peer.nextPiece().bytes)
    }
  }

  @Test
  fun v1RouteRequestIntoPaddingIsZeroFilled() = upload(hybrid) {
    hybrid.seed(store)
    val legacy = TorrentMetadata.fromBencode(hybrid.metainfo, allowHybrid = true)
    run {
      // A peer of the v1 swarm asks for v1 piece 1: a's tail, then BEP 47 padding.
      val peer = unchoked(1, PeerIdentityHandshake.Mode.V1)
      peer.send(PeerMessage.Request(1, 0, 16_384))
      peer.send(PeerMessage.Request(1, 16_384, 16_384))
      val bytes = peer.nextPiece().bytes + peer.nextPiece().bytes
      assertContentEquals(hybrid.v1Piece(1), bytes)
      assertContentEquals(legacy.pieceHashes.copyOfRange(20, 40), sha1Digest(bytes))
    }
  }

  @Test
  fun cancelRemovesQueuedRequest() = upload {
    pure.seed(store)
    // The shared bucket starts empty and refills a byte a second: requests wait in the queue.
    global.set(1)
    assertEquals(0L, global.requestDelay(16_384, TorrentRateLimiter()))
    run {
      val peer = unchoked(1)
      peer.send(PeerMessage.Request(0, 0, 16_384))
      peer.send(PeerMessage.Request(0, 16_384, 16_384))
      peer.send(PeerMessage.Cancel(0, 16_384, 16_384))
      delay(300)
      global.set(0)
      assertEquals(0, peer.nextPiece().begin)
      peer.send(PeerMessage.Request(2, 0, 5))
      // The canceled block never comes: the next one is the request after it.
      val next = peer.nextPiece()
      assertEquals(2 to 0, next.index to next.begin)
    }
  }

  @Test
  fun uploadRateLimitDelaysWithoutConsumingTokens() = upload {
    pure.seed(store)
    // The engine's bucket starts empty; the task's starts full, at one byte a second each.
    global.set(1)
    assertEquals(0L, global.requestDelay(16_384, TorrentRateLimiter()))
    session.set(1)
    run {
      val peer = unchoked(1)
      peer.send(PeerMessage.Request(0, 0, 16_384))
      assertNull(withTimeoutOrNull(300) { peer.next { it is PeerMessage.Piece } })
      // Held attempts took nothing from the task's bucket, which now pays for the block.
      global.set(0)
      assertEquals(0, peer.nextPiece().begin)
      // Its bucket is empty now, and holds the next block back by itself.
      peer.send(PeerMessage.Request(0, 16_384, 16_384))
      assertNull(withTimeoutOrNull(300) { peer.next { it is PeerMessage.Piece } })
      session.set(0)
      assertEquals(16_384, peer.nextPiece().begin)
    }
  }

  @Test
  fun refusedFrameRequeuesBlockUntilBudgetReturns() = upload {
    pure.seed(store)
    run {
      val peer = unchoked(1)
      // The first block puts piece 0 in the cache.
      peer.send(PeerMessage.Request(0, 0, 16_384))
      assertEquals(0, peer.nextPiece().begin)
      while (store.uploadedBytes() < 16_384) delay(10)
      // Room for the next block, but not for writing its frame: the actor drops it unsent.
      val hold = checkNotNull(buffers.reserve(buffers.capacity - buffers.allocated - 40_000))
      try {
        peer.send(PeerMessage.Request(0, 16_384, 16_384))
        assertNull(withTimeoutOrNull(600) { peer.next { it is PeerMessage.Piece } })
        assertEquals(16_384L, store.uploadedBytes())
      } finally { hold.close() }
      // It waited at the head of the queue and goes out, once, at its next retry 250 ms on.
      val piece = withTimeout(2_000) { peer.nextPiece() }
      assertEquals(16_384, piece.begin)
      assertContentEquals(pure.v2Piece(0).copyOfRange(16_384, 32_768), piece.bytes)
      assertNull(withTimeoutOrNull(300) { peer.next { it is PeerMessage.Piece } })
      while (store.uploadedBytes() < 32_768) delay(10)
      assertEquals(32_768L, store.uploadedBytes())
    }
  }

  @Test
  fun unreadablePieceHandsSlotOnAfterThreeRefusals() = upload(uploadBytes = 32_768) {
    pure.seed(store)
    // Something else holds part of the partition pieces are read into: piece 0 cannot be read.
    val hold = checkNotNull(uploads.reserve(1_024))
    try {
      run {
        // Four peers take every upload slot, and a fifth waits for one.
        val peer = unchoked(1)
        repeat(3) { unchoked(it + 2) }
        val waiting = connect(5)
        assertIs<PeerMessage.Bitfield>(waiting.next())
        waiting.send(interested)
        assertNull(withTimeoutOrNull(300) { waiting.next() })
        peer.send(PeerMessage.Request(0, 0, 16_384))
        // Its read is refused three times, 250 ms apart; then its slot goes to the waiting peer.
        assertEquals(choke, withTimeout(5_000) { peer.next() })
        assertEquals(unchoke, withTimeout(5_000) { waiting.next() })
        assertTrue(peer.received.tryReceive().isFailure)
        hold.close()
        // The next rechoke gives it a slot again, and its read now succeeds.
        skip(10_001)
        assertEquals(unchoke, withTimeout(5_000) { peer.next() })
        peer.send(PeerMessage.Request(0, 0, 16_384))
        assertContentEquals(pure.v2Piece(0).copyOfRange(0, 16_384),
          withTimeout(5_000) { peer.nextPiece() }.bytes)
        assertFalse(peer.closed.isCompleted)
      }
    } finally { hold.close() }
    assertEquals(0, uploads.allocated)
  }

  @Test
  fun revokedPieceStopsTheSession() = upload {
    pure.seed(store)
    // Piece 3 changes on disk after it was verified.
    torrentFileSystem.write(output / "c") { write(ByteArray(20_000)) }
    val failure = assertFailsWith<TorrentStorageException> {
      run { loop ->
        val peer = unchoked(1)
        peer.send(PeerMessage.Request(3, 0, 16_384))
        loop.await()
      }
    }
    assertTrue(failure.cause?.message.orEmpty().contains("piece 3"), "${failure.cause}")
    assertFalse(store.verifiedPieces()[3])
  }

  @Test
  fun emptyBitfieldIsNotSent() = upload(onCompleted = { error("Never completes") }) {
    run {
      // We have nothing yet: an interested peer hears its unchoke first, with no bitfield.
      val peer = connect(1)
      peer.send(interested)
      assertEquals(unchoke, peer.next())
    }
  }

  @Test
  fun haveReachesEveryPeerAfterCommit() = upload {
    run { coroutineScope {
      val leechers = listOf(connect(1), connect(2))
      val seed = connect(3)
      seed.send(PeerMessage.Bitfield(all(pieceCount)))
      seed.send(unchoke)
      // The seed answers what we ask from its copy.
      val answers = launch {
        while (true) {
          val request = seed.next { it is PeerMessage.Request } as PeerMessage.Request
          seed.send(PeerMessage.Piece(request.index, request.begin, pure.v2Piece(request.index)
            .copyOfRange(request.begin, request.begin + request.length)))
        }
      }
      try {
        for (leecher in leechers) {
          val haves = mutableSetOf<Int>()
          while (haves.size < pieceCount) {
            haves += (leecher.next { it is PeerMessage.Have } as PeerMessage.Have).index
          }
          assertEquals((0 until pieceCount).toSet(), haves)
        }
      } finally { answers.cancel() }
      assertTrue(store.completed())
    } }
  }

  @Test
  fun seedPeerIsDisconnectedWhenComplete() = upload {
    pure.seed(store)
    run {
      val leecher = connect(1)
      val seed = connect(2)
      seed.send(PeerMessage.Bitfield(all(pieceCount)))
      // Two seeds have nothing for each other.
      seed.closed.await()
      assertIs<PeerMessage.Bitfield>(leecher.next())
      leecher.send(interested)
      assertEquals(unchoke, leecher.next())
      assertFalse(leecher.closed.isCompleted)
    }
  }

  @Test
  fun seedWeDialedIsNotDialedAgain() = upload {
    pure.seed(store)
    run {
      val endpoint = PeerEndpoint("192.0.2.80", 6881)
      discovered.send(TorrentV2Discovered(endpoint, PeerTopic.V2, PeerOrigin.TRACKER))
      val seed = connect(80, dialed = dial.receive())
      assertIs<PeerMessage.Bitfield>(seed.next())
      seed.send(PeerMessage.Bitfield(all(pieceCount)))
      seed.closed.await()
      // Two seeds have nothing for each other, so it is not dialed back after any backoff.
      for (wait in listOf(5_001L, 15_001L, 30_001L)) {
        skip(wait)
        assertNull(withTimeoutOrNull(300) { dial.receive() })
      }
    }
  }

  @Test
  fun endpointThatFailedWhileDownloadingIsNotRetriedOnceComplete() = upload {
    // Piece 3 is missing; a seed brings it.
    pure.seed(store, listOf(0, 1, 2))
    run { coroutineScope {
      val endpoint = PeerEndpoint("192.0.2.90", 6881)
      discovered.send(TorrentV2Discovered(endpoint, PeerTopic.V2, PeerOrigin.TRACKER))
      val target = dial.receive()
      assertEquals(endpoint, target.endpoint)
      // Its dial fails while we download, so it backs off to be dialed again in five seconds. The
      // loop takes this failure long before the many events of the download below complete it.
      failures.send(PeerV2Dialer.Failure(target, IOException("Connection refused")))
      val seed = connect(3)
      seed.send(PeerMessage.Bitfield(all(pieceCount)))
      seed.send(unchoke)
      val answers = launch {
        while (true) {
          val request = seed.next { it is PeerMessage.Request } as PeerMessage.Request
          seed.send(PeerMessage.Piece(request.index, request.begin, pure.v2Piece(request.index)
            .copyOfRange(request.begin, request.begin + request.length)))
        }
      }
      // Complete, we and the seed have nothing for each other.
      try { seed.closed.await() } finally { answers.cancel() }
      assertTrue(store.completed())
      // A seed retries nobody: the endpoint is not dialed after any of its backoffs.
      for (wait in listOf(5_001L, 15_001L, 30_001L)) {
        skip(wait)
        assertNull(withTimeoutOrNull(300) { dial.receive() })
      }
    } }
  }

  @Test
  fun metadataAnswerDroppedUnsentGoesOutOnItsOwn() = upload(TorrentV2Fixture.build(listOf(
    "a".repeat(100) to 40_000, "b".repeat(100) to 5, "c".repeat(100) to 20_000))) {
    fixture.seed(store)
    val info = fixture.document.info.rawInfo
    // The peer's frames have credit of their own: room for everything but our answer.
    val cost = PeerWire.encodedSize(TorrentMetadataExchange.response(3, 0, info), pieceCount) *
      4 + 512
    val frames = TorrentBufferBudget(2 * cost)
    val hold = checkNotNull(frames.reserve(cost + 1))
    try {
      run {
        val peer = connect(1, extensions = true, frames = frames)
        assertIs<PeerMessage.Extended>(peer.next())
        assertIs<PeerMessage.Bitfield>(peer.next())
        peer.send(PeerMessage.Extended(0, Bencode.encode(mapOf(
          "m" to mapOf("ut_metadata" to 3L)))))
        peer.send(TorrentMetadataExchange.metadataMessage(PeerExtensions.METADATA, 0, 0))
        // The actor drops the answer unsent, and again at each retry while there is no room.
        assertNull(withTimeoutOrNull(600) { peer.next { it is PeerMessage.Extended } })
        hold.close()
        // Nothing else happens: the loop wakes for the next retry by itself and sends it.
        val answer = assertIs<PeerMessage.Extended>(withTimeout(2_000) {
          peer.next { it is PeerMessage.Extended }
        })
        assertEquals(3, answer.id)
        val header = Bencode.parsePrefix(answer.payload, PeerWire.MAX_FRAME_SIZE)
        assertEquals(1L, header["msg_type"]?.integer)
        assertEquals(info, answer.payload.copyOfRange(header.end, answer.payload.size)
          .toByteString())
      }
    } finally { hold.close() }
    assertEquals(0, frames.allocated)
  }

  @Test
  fun seedThatDialedUsIsNotDialedBack() = upload {
    pure.seed(store)
    run {
      val seed = connect(81, extensions = true)
      // It says where it listens (BEP 10 `p`), then that it has everything.
      seed.send(PeerMessage.Extended(0, Bencode.encode(mapOf(
        "m" to emptyMap<String, Long>(), "p" to 6881L))))
      seed.send(PeerMessage.Bitfield(all(pieceCount)))
      seed.closed.await()
      assertNull(withTimeoutOrNull(300) { dial.receive() })
      skip(5_001)
      assertNull(withTimeoutOrNull(300) { dial.receive() })
    }
  }

  @Test
  fun impostorGetsOnlyItsOwnHostBanned() = upload(onCompleted = { error("Never completes") }) {
    pure.seed(store, listOf(0, 1, 2))
    policy.store(TorrentUploadPolicy.WHILE_DOWNLOADING)
    run {
      // The loop runs once it dials what it hears of; its own state is fixed from then on.
      discovered.send(TorrentV2Discovered(PeerEndpoint("192.0.2.200", 6881), PeerTopic.V2,
        PeerOrigin.TRACKER))
      dial.receive()
      val idle = state.allocated
      // Another host claims peer 7's ID and breaks the protocol.
      val impostor = unchoked(7, host = "198.51.100.66")
      impostor.send(PeerMessage.Request(0, 24_576, 16_384))
      impostor.closed.await()
      // Its state comes back as the loop forgets it, which bans the claim on its host.
      while (state.allocated != idle) delay(10)
      // A peer ID is only a claim: peer 7 itself is still served from its own host.
      val real = unchoked(7)
      real.send(PeerMessage.Request(0, 0, 16_384))
      assertContentEquals(pure.v2Piece(0).copyOfRange(0, 16_384), real.nextPiece().bytes)
      // The impostor's host claiming that ID again is refused.
      val again = connect(7, host = "198.51.100.66")
      again.closed.await()
      assertTrue(again.received.tryReceive().isFailure)
    }
  }

  @Test
  fun impostorNeverTakesOverAnEndpointWeDialed() =
    upload(onCompleted = { error("Never completes") }) {
      pure.seed(store, listOf(0, 1, 2))
      policy.store(TorrentUploadPolicy.WHILE_DOWNLOADING)
      run {
        val endpoint = PeerEndpoint("192.0.2.70", 6881)
        discovered.send(TorrentV2Discovered(endpoint, PeerTopic.V2, PeerOrigin.TRACKER))
        // Its peer ID sorts above ours, so a connection it dialed would replace ours.
        val real = connect(0xff, dialed = dial.receive())
        assertIs<PeerMessage.Bitfield>(real.next())
        // Another host claiming that ID is another peer: it never takes our connection's place.
        val impostor = unchoked(0xff, host = "198.51.100.66")
        impostor.send(PeerMessage.Request(0, 24_576, 16_384))
        impostor.closed.await()
        assertFalse(real.closed.isCompleted)
        assertServed(real)
      }
    }

  @Test
  fun impostorThatArrivesFirstNeverLocksOutThePeer() =
    upload(onCompleted = { error("Never completes") }) {
      pure.seed(store, listOf(0, 1, 2))
      policy.store(TorrentUploadPolicy.WHILE_DOWNLOADING)
      run {
        // It claims peer 0xff's ID and behaves; a connection we dial to that ID would lose to it.
        val impostor = unchoked(0xff, host = "198.51.100.66")
        val endpoint = PeerEndpoint("192.0.2.70", 6881)
        discovered.send(TorrentV2Discovered(endpoint, PeerTopic.V2, PeerOrigin.TRACKER))
        val real = connect(0xff, dialed = dial.receive())
        assertIs<PeerMessage.Bitfield>(real.next())
        assertServed(real)
        assertFalse(real.closed.isCompleted)
        assertFalse(impostor.closed.isCompleted)
      }
    }

  @Test
  fun impostorWeDialedNeverDisplacesAPeerThatDialedUs() =
    upload(onCompleted = { error("Never completes") }) {
      pure.seed(store, listOf(0, 1, 2))
      policy.store(TorrentUploadPolicy.WHILE_DOWNLOADING)
      run {
        // Our peer ID sorts above peer 1's, so a connection we dialed would replace its own.
        val real = unchoked(1)
        val endpoint = PeerEndpoint("198.51.100.66", 6881)
        discovered.send(TorrentV2Discovered(endpoint, PeerTopic.V2, PeerOrigin.TRACKER))
        val impostor = connect(1, dialed = dial.receive())
        assertIs<PeerMessage.Bitfield>(impostor.next())
        assertServed(real, interest = false)
        assertFalse(real.closed.isCompleted)
        assertFalse(impostor.closed.isCompleted)
      }
    }

  @Test
  fun duplicateOnOneHostKeepsWhatTheLowerListenPortDialed() = duplicate(6_000, keepDialed = true)

  @Test
  fun duplicateOnOneHostKeepsWhatTheHigherListenPortAccepted() =
    duplicate(7_000, keepDialed = false)

  // Without ports to tell them apart, the higher peer ID's connection stays. Ours is 0x09s.
  @Test
  fun duplicateWithEqualPortsKeepsWhatTheHigherPeerIdDialed() =
    duplicate(6_881, keepDialed = false, peerId = 0xff)

  @Test
  fun duplicateWithOurPortUnknownKeepsWhatTheHigherPeerIdDialed() =
    duplicate(0, keepDialed = true, peerId = 0x01)

  /**
   * A peer that dialed us, listening on port 6881 of its host, which we then dial there too.
   * Both ends keep one socket: libtorrent keeps the one the side with the lower listen port
   * dialed, so we compare ours ([listenPort]) with 6881, or, without that, the peer IDs.
   */
  private fun duplicate(listenPort: Int, keepDialed: Boolean, peerId: Int = 0x05) = upload(
    onCompleted = { error("Never completes") }, listenPort = listenPort,
  ) {
    pure.seed(store, listOf(0, 1, 2))
    policy.store(TorrentUploadPolicy.WHILE_DOWNLOADING)
    run {
      val host = "192.0.2.5"
      val accepted = connect(peerId, host = host)
      assertIs<PeerMessage.Bitfield>(accepted.next())
      discovered.send(TorrentV2Discovered(PeerEndpoint(host, 6881), PeerTopic.V2,
        PeerOrigin.TRACKER))
      val dialed = connect(peerId, dialed = dial.receive())
      val (kept, closed) = if (keepDialed) dialed to accepted else accepted to dialed
      closed.closed.await()
      if (keepDialed) assertIs<PeerMessage.Bitfield>(dialed.next())
      assertServed(kept)
      assertFalse(kept.closed.isCompleted)
    }
  }

  /** [peer] is unchoked once it says it is interested, unless [interest] says it already did. */
  private suspend fun assertServed(peer: Remote, interest: Boolean = true) {
    if (interest) {
      peer.send(interested)
      assertEquals(unchoke, peer.next { it == unchoke })
    }
    peer.send(PeerMessage.Request(0, 0, 16_384))
    assertContentEquals(pure.v2Piece(0).copyOfRange(0, 16_384), peer.nextPiece().bytes)
  }

  @Test
  fun uploadReadsStayWithinTheirPartition() = upload(uploadBytes = 32_768) {
    pure.seed(store)
    run {
      val peer = unchoked(1)
      // One 32 KiB piece fills the partition: each read makes room by dropping the cached one,
      // so the partition holds exactly the piece read last.
      repeat(3) {
        for ((index, length) in listOf(0 to 16_384, 1 to 7_232)) {
          peer.send(PeerMessage.Request(index, 0, length))
          assertContentEquals(pure.v2Piece(index).copyOfRange(0, length), peer.nextPiece().bytes)
          assertEquals(pure.v2Piece(index).size, uploads.allocated)
        }
      }
    }
    assertEquals(0, uploads.allocated)
  }

  @Test
  fun peersOnDifferentPiecesTakeTurnsPieceByPiece() = upload(uploadBytes = 32_768) {
    pure.seed(store)
    // The engine's bucket starts empty: every request is queued before a block goes out.
    global.set(1)
    assertEquals(0L, global.requestDelay(16_384, TorrentRateLimiter()))
    run {
      // The cache holds one 32 KiB piece: one peer takes blocks of piece 0, the other of piece 3.
      val first = unchoked(1)
      val second = unchoked(2)
      val before = pieceReads.load()
      val zero = List(32) { PeerMessage.Request(0, it * 1_024, 1_024) }
      val three = List(20) { PeerMessage.Request(3, it * 1_024, 1_024) }
      zero.forEach(first::send)
      three.forEach(second::send)
      delay(300)
      global.set(0)
      for ((peer, requests) in listOf(first to zero, second to three)) {
        val piece = pure.v2Piece(requests.first().index).copyOf(32_768)
        for (request in requests) {
          val block = peer.nextPiece()
          assertEquals(request.index to request.begin, block.index to block.begin)
          assertContentEquals(piece.copyOfRange(request.begin, request.begin + 1_024),
            block.bytes)
        }
      }
      // Each piece was read once: no read took the piece another peer was still being served.
      assertEquals(2, pieceReads.load() - before)
    }
    assertEquals(0, uploads.allocated)
  }

  @Test
  fun disabledPolicyChokesUnchokedPeers() = upload(onCompleted = { error("Never completes") }) {
    pure.seed(store, listOf(0))
    run {
      val peer = unchoked(1)
      // Upload switched off: the peer is choked within one pass, and asking gets it nothing.
      policy.store(TorrentUploadPolicy.DISABLED)
      policyChanged()
      assertEquals(PeerMessage.Control(PeerMessage.Signal.CHOKE), peer.next())
      peer.send(PeerMessage.Request(0, 0, 16_384))
      assertNull(withTimeoutOrNull(300) { peer.next { it is PeerMessage.Piece } })
      // Switched on again, the waiting peer gets its slot back at once.
      policy.store(TorrentUploadPolicy.WHILE_DOWNLOADING)
      policyChanged()
      assertEquals(unchoke, peer.next())
    }
  }

  @Test
  fun revokedDeselectedPieceKeepsTheSession() = upload {
    pure.seed(store)
    // c, piece 3, is deselected and then changes on disk; peers still know we had it.
    store.changeSelection(setOf("0", "1"))
    torrentFileSystem.write(output / "c") { write(ByteArray(20_000)) }
    run { loop ->
      val peer = unchoked(1)
      peer.send(PeerMessage.Request(3, 0, 16_384))
      while (store.verifiedPieces()[3]) delay(10)
      // The piece is gone, not the session: the same peer is served the rest.
      peer.send(PeerMessage.Request(0, 0, 16_384))
      val piece = peer.nextPiece()
      assertEquals(0 to 0, piece.index to piece.begin)
      assertFalse(loop.isCompleted)
      assertFalse(peer.closed.isCompleted)
      assertTrue(store.completed())
    }
  }

  @Test
  fun revokedWantedPieceStillFailsTheSession() = upload {
    pure.seed(store)
    store.changeSelection(setOf("2"))
    torrentFileSystem.write(output / "c") { write(ByteArray(20_000)) }
    val failure = assertFailsWith<TorrentStorageException> {
      run { loop ->
        val peer = unchoked(1)
        peer.send(PeerMessage.Request(3, 0, 16_384))
        loop.await()
      }
    }
    assertTrue(failure.cause?.message.orEmpty().contains("piece 3"), "${failure.cause}")
  }

  @Test
  fun selectionExpandInSeedModeUploadsWhileDownloadingAgain() {
    val completions = AtomicInt(0)
    // The first completion seeds; the second, under WHILE_DOWNLOADING, ends the loop.
    upload(onCompleted = { completions.addAndFetch(1) == 1 }) {
      pure.seed(store, listOf(0, 1, 2))
      store.changeSelection(setOf("0", "1"))
      val incomplete = CompletableDeferred<Unit>()
      onIncomplete = { incomplete.complete(Unit) }
      run { loop -> coroutineScope {
        val leecher = unchoked(1)
        assertEquals(1, completions.load())
        store.changeSelection(setOf("0", "1", "2"))
        select()
        assertTrue(incomplete.isCompleted)
        // Downloading again, so uploading while downloading is allowed again.
        policy.store(TorrentUploadPolicy.WHILE_DOWNLOADING)
        policyChanged()
        assertServed(leecher, interest = false)
        // A seed has c, which we fetch now.
        val seed = connect(2)
        seed.send(PeerMessage.Bitfield(all(pieceCount)))
        seed.send(unchoke)
        val answers = launch {
          while (true) {
            val request = seed.next { it is PeerMessage.Request } as PeerMessage.Request
            seed.send(PeerMessage.Piece(request.index, request.begin, pure.v2Piece(request.index)
              .copyOfRange(request.begin, request.begin + request.length)))
          }
        }
        try { loop.await() } finally { answers.cancel() }
        assertEquals(2, completions.load())
        assertTrue(store.completed())
      } }
    }
  }
}
