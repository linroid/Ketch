package com.linroid.ketch.torrent

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.Runnable
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import okio.Buffer
import okio.ByteString
import okio.ByteString.Companion.toByteString
import okio.FileSystem
import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.coroutines.CoroutineContext
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * A swarm loop answering BEP 52 hash requests over in-memory connections: which requests it
 * proves, which it rejects, and that v1 peers of a hybrid never hear of hashes. Real time, as
 * block proofs read pieces back from disk.
 */
@OptIn(ExperimentalAtomicApi::class)
class TorrentV2HashServingLoopTest {
  // Pieces of 32 KiB: a spans pieces 0 and 1 (three blocks, a tree two levels high, its piece
  // layer level 1), b is piece 2, c is piece 3.
  private val files = listOf("a" to 40_000, "b" to 5, "c" to 20_000)
  private val pure = TorrentV2Fixture.build(files)
  private val hybrid = TorrentV2Fixture.build(files, hybrid = true)

  private val interested = PeerMessage.Control(PeerMessage.Signal.INTERESTED)
  private val unchoke = PeerMessage.Control(PeerMessage.Signal.UNCHOKE)

  /** Holds every coroutine it is given until [open]. */
  private class Gate : CoroutineDispatcher() {
    private val opened = CompletableDeferred<Unit>()

    override fun dispatch(context: CoroutineContext, block: Runnable) {
      if (opened.isCompleted) Dispatchers.Default.dispatch(context, block)
      else opened.invokeOnCompletion { Dispatchers.Default.dispatch(context, block) }
    }

    fun open() { opened.complete(Unit) }
  }

  /** The far end of one connection: the test speaks for the peer and reads what we sent it. */
  private class Remote(private val pieceCount: Int, port: Int) : TorrentConnection {
    override val remote = PeerEndpoint("127.0.0.1", port)
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

    fun ask(selector: PeerHashSelector) =
      send(PeerHashWire.encode(PeerHashMessage.Request(selector)))

    /** The next message we sent this peer that [match]es; others are skipped. */
    suspend fun next(match: (PeerMessage) -> Boolean = { it != PeerMessage.KeepAlive }):
      PeerMessage {
      while (true) {
        val message = received.receive()
        if (match(message)) return message
      }
    }

    /** The next answer to a hash request: hashes or a reject. */
    suspend fun answer(): PeerHashMessage = checkNotNull(PeerHashWire.decode(
      next { it is PeerMessage.Unknown && it.id in 22..23 } as PeerMessage.Unknown))

    override fun close() {
      closed.complete(Unit)
      input.cancel()
    }
  }

  /** One swarm loop over [fixture]'s storage, serving proofs, and the peers the test connects. */
  private inner class Rig(val fixture: TorrentV2Fixture, uploadBytes: Int? = null) {
    val layout = fixture.layout
    val pieceCount = layout.pieceCount.toInt()
    val buffers = TorrentBufferBudget(4 * 1024 * 1024)
    /** What peers ask of us is charged here: a partition of [buffers] when [uploadBytes] is set. */
    val uploads = uploadBytes?.let { TorrentBufferBudget(it, buffers) } ?: buffers
    val state = TorrentBufferBudget(16 * 1024 * 1024)
    val output = FileSystem.SYSTEM_TEMPORARY_DIRECTORY /
      "ketch-v2-hashes-${InfoHash.fromBytes(torrentRandomBytes(20)).hex}"
    val store = TorrentV2PieceStore(fixture.document, output, emptySet(), "hashes", buffers,
      Semaphore(2))
    val policy = AtomicReference(TorrentUploadPolicy.SEED_AFTER_COMPLETION)
    /** The engine's and the session's upload buckets; block proofs read pieces at their cost. */
    val uploadRate = TorrentRateLimiter()
    val sessionUploadRate = TorrentRateLimiter()
    /** Bytes of the pieces read back for proofs, as the upload buckets were asked for them. */
    val proofReads = mutableListOf<Long>()
    private val connections = Channel<PeerV2Connector.Connected>(Channel.UNLIMITED,
      onUndeliveredElement = { it.close() })
    private val discovered = Channel<TorrentV2Discovered>()
    private val dial = Channel<TorrentV2DialTarget>(4)
    private val failures = Channel<PeerV2Dialer.Failure<TorrentV2DialTarget>>(4)
    private val controls = Channel<TorrentV2SessionLoop.Control>(8)
    private val remotes = mutableListOf<Remote>()
    private var port = 41_000
    private val network = object : TorrentNetwork {
      override suspend fun connect(remote: PeerEndpoint): TorrentConnection = error("Unused")
      override suspend fun listen(local: PeerEndpoint): TorrentListener = error("Unused")
      override suspend fun bindUdp(local: PeerEndpoint): TorrentDatagramSocket = error("Unused")
      override fun close() = Unit
    }

    fun root(file: Int): ByteString = checkNotNull(fixture.document.info.files[file].piecesRoot)

    /** A peer that dialed us as [peerId], handed to the loop as the responders would. */
    fun connect(
      peerId: Int,
      mode: PeerIdentityHandshake.Mode = PeerIdentityHandshake.Mode.V2,
    ): Remote {
      val remote = Remote(pieceCount, port++)
      remotes += remote
      val v2 = mode == PeerIdentityHandshake.Mode.V2
      val transport = PeerHashTransport(remote, PeerHashExchange(buffers, { null }), buffers,
        pieceCount = pieceCount, hashMessages = v2)
      val blocks = PeerBlockExchange(layout, transport, buffers, mode = mode)
      val id = ByteArray(20) { peerId.toByte() }.toByteString()
      val route = PeerIdentityHandshake.Result(fixture.document.identity, mode, id,
        extensions = false, dht = false)
      val info = PeerInfo(id, mode, PeerV2Origin.Incoming(remote.remote), extensions = false,
        link = remote)
      val admission = checkNotNull(state.reserve(1024))
      check(connections.trySend(PeerV2Connector.Connected(route, info, transport, blocks,
        admission)).isSuccess)
      return remote
    }

    fun policyChanged() {
      check(controls.trySend(TorrentV2SessionLoop.Control.PolicyChanged).isSuccess)
    }

    /**
     * Runs the loop while [body] runs, then stops it. The serve worker queues up to [capacity]
     * jobs and runs on [dispatcher].
     */
    suspend fun run(
      capacity: Int = 8,
      dispatcher: CoroutineDispatcher = Dispatchers.Default,
      body: suspend () -> Unit,
    ) = coroutineScope {
      // Wired as the session wires it.
      val server = TorrentV2HashServer(fixture.document, layout, state,
        canRead = { uploadRate.canCharge(sessionUploadRate) },
        chargeRead = { bytes ->
          uploadRate.charge(bytes, sessionUploadRate)
          proofReads += bytes
        })
      val loop = async {
        PeerV2Pool.run(state, maxPeers = 8) { pool ->
          TorrentV2CommitWorker.run(store) { worker ->
            TorrentV2ServeWorker.run(store, server, uploads, capacity, dispatcher) { serve ->
              TorrentV2SessionLoop.download(layout, emptySet(), store, pool, worker, buffers,
                state, maxPeers = 8, connections = connections, swarm = TorrentV2Swarm(
                  fixture.document,
                  TorrentV2Runtime(network, ByteArray(20) { 9 }.toByteString(), buffers, state,
                    uploadRate = uploadRate, uploadPolicy = { policy.load() }),
                  restricted = false, waitForPeers = true, discovered = discovered, dial = dial,
                  dialFailures = failures, controls = controls, connectionLimit = { 8 },
                  generation = { 0 }, serve = serve, sessionUploadRate = sessionUploadRate,
                  onCompleted = { true }))
            }
          }
        }
      }
      try { body() } finally {
        withContext(NonCancellable) {
          loop.cancelAndJoin()
          connections.cancel()
        }
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

  private fun serving(
    fixture: TorrentV2Fixture = pure,
    uploadBytes: Int? = null,
    test: suspend Rig.() -> Unit,
  ) = runTest {
    withContext(Dispatchers.Default) {
      withTimeout(20_000) {
        val rig = Rig(fixture, uploadBytes)
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

  @Test
  fun v1RouteNeverAnswersHashMessages() = serving(hybrid) {
    hybrid.seed(store)
    run {
      // A peer of the v1 swarm sends what would be a hash request on a v2 connection.
      val peer = connect(1, PeerIdentityHandshake.Mode.V1)
      assertIs<PeerMessage.Bitfield>(peer.next())
      peer.ask(PeerHashSelector(root(0), 0, 0, 2, 1))
      peer.send(interested)
      // The request means nothing here: the connection carries on, and no hash reply comes.
      val replies = mutableListOf<PeerMessage>()
      while (true) {
        val message = peer.next()
        if (message == unchoke) break
        replies += message
      }
      assertNull(withTimeoutOrNull(300) {
        peer.next { it is PeerMessage.Unknown && it.id in 21..23 }
      })
      assertTrue(replies.none { it is PeerMessage.Unknown }, "$replies")
      assertFalse(peer.closed.isCompleted)
    }
  }

  @Test
  fun requestBeyondTheTreeIsRejectedAndThePeerKept() = serving {
    run {
      val peer = connect(1)
      // libtorrent may ask for up to 8192 hashes at once; file a's tree is far narrower.
      val large = PeerHashSelector(root(0), 0, 0, 1024, 0)
      peer.ask(large)
      assertEquals(PeerHashMessage.Reject(large), peer.answer())
      // The peer did nothing wrong: its next request is proved.
      val layer = PeerHashSelector(root(0), 1, 0, 2, 0)
      peer.ask(layer)
      assertIs<PeerHashMessage.Hashes>(peer.answer())
      assertFalse(peer.closed.isCompleted)
    }
  }

  @Test
  fun proofsStayWithinTheUploadPartition() = serving(uploadBytes = 16_384) {
    // Something else, a cached upload piece say, holds all but 64 bytes of the partition.
    val hold = checkNotNull(uploads.reserve(16_384 - 64))
    try {
      run {
        val peer = connect(1)
        // File a's piece layer, which any peer may ask for: its answer needs a larger lease.
        val layer = PeerHashSelector(root(0), 1, 0, 2, 0)
        peer.ask(layer)
        assertEquals(PeerHashMessage.Reject(layer), peer.answer())
        // Only the partition said no: the frames downloads need had room to spare.
        assertTrue(buffers.capacity - buffers.allocated >= buffers.capacity / 2)
        hold.close()
        peer.ask(layer)
        val hashes = assertIs<PeerHashMessage.Hashes>(peer.answer())
        assertTrue(verifyPeerHashes(hashes, layer, 40_000, root(0)))
      }
    } finally { hold.close() }
    assertEquals(0, uploads.allocated)
  }

  @Test
  fun disabledPolicyRejects() = serving {
    // Nothing verified: piece layer proofs come from the metainfo alone.
    policy.store(TorrentUploadPolicy.DISABLED)
    run {
      val peer = connect(1)
      // File a's whole piece layer: a request any peer may make, here refused for the policy.
      val layer = PeerHashSelector(root(0), 1, 0, 2, 0)
      peer.ask(layer)
      assertEquals(PeerHashMessage.Reject(layer), peer.answer())
      // The same request is proved once uploading is allowed again.
      policy.store(TorrentUploadPolicy.WHILE_DOWNLOADING)
      policyChanged()
      peer.ask(layer)
      val hashes = assertIs<PeerHashMessage.Hashes>(peer.answer())
      assertTrue(verifyPeerHashes(hashes, layer, 40_000, root(0)))
    }
  }

  @Test
  fun blockLayerRequiresUnchoke() = serving {
    pure.seed(store)
    run {
      val peer = connect(1)
      assertIs<PeerMessage.Bitfield>(peer.next())
      // Block hashes of piece 0 with one uncle: read back from disk, so only for unchoked peers.
      val blocks = PeerHashSelector(root(0), 0, 0, 2, 1)
      peer.ask(blocks)
      assertEquals(PeerHashMessage.Reject(blocks), peer.answer())
      peer.send(interested)
      assertEquals(unchoke, peer.next())
      peer.ask(blocks)
      val hashes = assertIs<PeerHashMessage.Hashes>(peer.answer())
      assertTrue(verifyPeerHashes(hashes, blocks, 40_000, root(0)))
      val tree = TorrentV2Fixture.merkleNodes(pure.payloads[0])
      assertContentEquals(tree[0][0] + tree[0][1] + tree[1][1], hashes.hashes.toByteArray())
    }
  }

  @Test
  fun blockProofReadsWaitForTheUploadLimit() = serving {
    pure.seed(store)
    // A byte a second: the bucket's first block pays for one piece read, then it is in debt.
    uploadRate.set(1)
    run {
      val peer = connect(1)
      assertIs<PeerMessage.Bitfield>(peer.next())
      peer.send(interested)
      assertEquals(unchoke, peer.next())
      // Block hashes of piece 0, and of file c, which fits piece 3: each read back from disk.
      val first = PeerHashSelector(root(0), 0, 0, 2, 1)
      val other = PeerHashSelector(root(2), 0, 0, 2, 0)
      peer.ask(first)
      assertIs<PeerHashMessage.Hashes>(peer.answer())
      // The read left the bucket in debt: another piece is refused without reading it.
      peer.ask(other)
      assertEquals(PeerHashMessage.Reject(other), peer.answer())
      // Asking again for piece 0 reads nothing, so it costs nothing.
      repeat(3) {
        peer.ask(first)
        assertTrue(verifyPeerHashes(assertIs<PeerHashMessage.Hashes>(peer.answer()), first,
          40_000, root(0)))
      }
      assertEquals(listOf(32_768L), proofReads)
      // Lifted, the limit lets the other piece be read and proved.
      uploadRate.set(0)
      peer.ask(other)
      assertTrue(verifyPeerHashes(assertIs<PeerHashMessage.Hashes>(peer.answer()), other,
        20_000, root(2)))
      assertEquals(listOf(32_768L, 20_000L), proofReads)
      assertFalse(peer.closed.isCompleted)
    }
  }

  @Test
  fun revokedPieceInBlockProofStopsTheSession() = serving {
    pure.seed(store)
    // File c, piece 3, changes on disk after it was verified.
    torrentFileSystem.write(output / "c") { write(ByteArray(20_000)) }
    val failure = assertFailsWith<TorrentStorageException> {
      run {
        val peer = connect(1)
        assertIs<PeerMessage.Bitfield>(peer.next())
        peer.send(interested)
        assertEquals(unchoke, peer.next())
        // c fits one piece, so its hashes come from reading that piece back.
        peer.ask(PeerHashSelector(root(2), 0, 0, 2, 0))
        awaitCancellation()
      }
    }
    assertTrue(failure.cause?.message.orEmpty().contains("piece 3"), "${failure.cause}")
    assertFalse(store.verifiedPieces()[3])
  }

  @Test
  fun pendingCapRejectsExcess() = serving {
    val gate = Gate()
    // Opened before the loop stops, so the held worker can wind down.
    run(capacity = 64, dispatcher = gate) {
      try {
        val peer = connect(1)
        val layer = PeerHashSelector(root(0), 1, 0, 2, 0)
        // The worker is held, so the first requests stay pending while the rest arrive.
        repeat(MAX_PENDING_HASH_REQUESTS + 4) { peer.ask(layer) }
        repeat(4) { assertEquals(PeerHashMessage.Reject(layer), peer.answer()) }
        assertNull(withTimeoutOrNull(300) { peer.answer() })
        gate.open()
        // Every pending request is answered once the worker runs.
        repeat(MAX_PENDING_HASH_REQUESTS) {
          val hashes = assertIs<PeerHashMessage.Hashes>(peer.answer())
          assertTrue(verifyPeerHashes(hashes, layer, 40_000, root(0)))
        }
        // Answered requests no longer count: the next one is proved.
        peer.ask(layer)
        assertIs<PeerHashMessage.Hashes>(peer.answer())
      } finally { gate.open() }
    }
  }
}
