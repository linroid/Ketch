package com.linroid.ketch.torrent.v2

import com.linroid.ketch.torrent.TorrentV2Fixture
import com.linroid.ketch.torrent.crypto.sha256Digest
import com.linroid.ketch.torrent.crypto.torrentRandomBytes
import com.linroid.ketch.torrent.engine.TorrentBufferBudget
import com.linroid.ketch.torrent.metainfo.Bencode
import com.linroid.ketch.torrent.metainfo.InfoHash
import com.linroid.ketch.torrent.metainfo.TorrentContentLayout
import com.linroid.ketch.torrent.metainfo.TorrentV2Document
import com.linroid.ketch.torrent.metainfo.TorrentV2Info
import com.linroid.ketch.torrent.net.PeerEndpoint
import com.linroid.ketch.torrent.net.TorrentConnection
import com.linroid.ketch.torrent.peer.PeerMessage
import com.linroid.ketch.torrent.peer.PeerWire
import com.linroid.ketch.torrent.peer.pieceBitfield
import com.linroid.ketch.torrent.storage.TorrentV2PieceStore
import com.linroid.ketch.torrent.storage.torrentFileSystem
import com.linroid.ketch.torrent.v2.peer.PeerBlockExchange
import com.linroid.ketch.torrent.v2.peer.PeerHashExchange
import com.linroid.ketch.torrent.v2.peer.PeerHashTransport
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import okio.Buffer
import okio.FileSystem
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class TorrentV2PieceSchedulerTest {
  private val bytes = ByteArray(16_387) { it.toByte() }
  private val root = sha256Digest(sha256Digest(bytes.copyOfRange(0, 16_384)) +
    sha256Digest(bytes.copyOfRange(16_384, bytes.size)))
  private val document = TorrentV2Document.parse(Bencode.encode(mapOf("info" to mapOf(
    "meta version" to 2L, "piece length" to 32_768L, "file tree" to mapOf(
      "a" to mapOf("" to mapOf("length" to bytes.size.toLong(), "pieces root" to root)),
      "b" to mapOf("" to mapOf("length" to 1L, "pieces root" to sha256Digest(byteArrayOf(9)))))
  ), "piece layers" to emptyMap<String, Any>())))
  private val layout = TorrentContentLayout.from(document.info)

  private class Peer(
    budget: TorrentBufferBudget,
    layout: TorrentContentLayout,
    maxPending: Int = 1,
    private val pieces: Int = 2,
  ) {
    var failWrite = false
    val input = Buffer()
    val output = Buffer()
    private val connection = object : TorrentConnection {
      override val remote = PeerEndpoint("127.0.0.1", 1)
      override suspend fun readExactly(size: Int): ByteArray = input.readByteArray(size.toLong())
      override suspend fun write(bytes: ByteArray) {
        if (failWrite) throw okio.IOException("Partial request write")
        output.write(bytes)
      }
      override fun close() = Unit
    }
    val transport = PeerHashTransport(connection, PeerHashExchange(budget, { null }), budget,
      pieceCount = pieces)
    // Fast (BEP 6), so the peer may reject a request.
    val exchange = PeerBlockExchange(layout, transport, budget, maxPending = maxPending,
      fast = true)
    suspend fun receive(message: PeerMessage): PeerBlockExchange.Response? {
      input.write(PeerWire.encode(message, pieceCount = pieces))
      val frame = transport.read()
      return try { exchange.receive(frame) } finally { frame.close() }
    }
    suspend fun ready() {
      receive(PeerMessage.Bitfield(pieceBitfield(BooleanArray(pieces) { true })))
      receive(PeerMessage.Control(PeerMessage.Signal.UNCHOKE))
    }

    /** Asks the peer for what [plan] reserved and binds the ticket, as a peer actor does. */
    suspend fun send(scheduler: TorrentV2PieceScheduler, plan: TorrentV2PieceScheduler.RequestPlan):
      PeerBlockExchange.Ticket {
      val ticket = assertNotNull(exchange.request(plan.request))
      assertTrue(scheduler.resolve(plan, ticket))
      return ticket
    }
  }

  @Test
  fun unavailableEvictionPreservesLiveRequestsAndCompletedAssemblies() = runTest {
    val buffers = TorrentBufferBudget(200_000)
    val state = TorrentBufferBudget(2_000_000)
    val scheduler = assertNotNull(TorrentV2PieceScheduler.create(layout, emptySet(),
      BooleanArray(2), buffers, state, maxActive = 1))
    val peer = Peer(buffers, layout)
    try {
      peer.ready()
      assertTrue(scheduler.begin(0))
      val ticket = assertNotNull(scheduler.requestNext(peer.exchange))
      assertEquals(0, scheduler.evictUnavailable { false })
      assertFalse(scheduler.begin(1))
      val request = ticket.request
      assertTrue(scheduler.receive(peer.exchange, assertIs<PeerBlockExchange.Response.Block>(
        peer.receive(PeerMessage.Piece(0, request.begin,
          bytes.copyOfRange(request.begin, request.begin + request.length))))))
      assertEquals(1, scheduler.evictUnavailable { false })
      assertTrue(scheduler.begin(1))
      assertNotNull(scheduler.requestNext(peer.exchange))
      assertTrue(scheduler.receive(peer.exchange, assertIs<PeerBlockExchange.Response.Block>(
        peer.receive(PeerMessage.Piece(1, 0, byteArrayOf(9))))))
      assertEquals(0, scheduler.evictUnavailable { false })
      assertEquals(1, scheduler.activeCount)
    } finally {
      scheduler.removePeer(peer.exchange)
      scheduler.close()
    }
    assertEquals(0, buffers.allocated)
    assertEquals(0, state.allocated)
  }

  @Test
  fun reservationsDoNotWriteAndUnsentAcknowledgementsCannotReleaseNewerPlans() = runTest {
    val buffers = TorrentBufferBudget(200_000)
    val state = TorrentBufferBudget(2_000_000)
    val scheduler = assertNotNull(TorrentV2PieceScheduler.create(layout, emptySet(),
      BooleanArray(2), buffers, state))
    val first = Peer(buffers, layout)
    val second = Peer(buffers, layout)
    try {
      assertTrue(scheduler.begin(0))
      assertNull(scheduler.planNext(first.exchange) { false })
      val a = assertNotNull(scheduler.planNext(first.exchange) { true })
      val b = assertNotNull(scheduler.planNext(second.exchange) { true })
      assertEquals(0, a.request.begin)
      assertEquals(16_384, b.request.begin)
      assertNull(scheduler.planNext(second.exchange) { true })
      assertEquals(0L, first.output.size)
      assertEquals(0L, second.output.size)
      assertTrue(scheduler.resolve(a, null))
      val retry = assertNotNull(scheduler.planNext(second.exchange) { true })
      assertEquals(a.request, retry.request)
      assertFalse(scheduler.resolve(a, null))
      assertNull(scheduler.planNext(first.exchange) { true })
      assertTrue(scheduler.resolve(retry, null))
    } finally {
      scheduler.removePeer(first.exchange)
      scheduler.removePeer(second.exchange)
      scheduler.close()
    }
    assertEquals(0, buffers.allocated)
    assertEquals(0, state.allocated)
  }

  @Test
  fun acknowledgementsRequireTheIssuingPeerAndDetachedPlansCannotBindReplacementTickets() = runTest {
    val buffers = TorrentBufferBudget(200_000)
    val state = TorrentBufferBudget(2_000_000)
    val scheduler = assertNotNull(TorrentV2PieceScheduler.create(layout, emptySet(),
      BooleanArray(2), buffers, state))
    val first = Peer(buffers, layout)
    val second = Peer(buffers, layout)
    try {
      first.ready()
      second.ready()
      assertTrue(scheduler.begin(1))
      val old = assertNotNull(scheduler.planNext(first.exchange) { true })
      val wrong = assertNotNull(second.exchange.request(old.request))
      assertFailsWith<IllegalArgumentException> { scheduler.resolve(old, wrong) }
      val ticket = assertNotNull(first.exchange.request(old.request))
      assertTrue(scheduler.resolve(old, ticket))
      assertFalse(scheduler.resolve(old, null))
      first.exchange.close()
      scheduler.detachPeer(first.exchange)
      val replacement = assertNotNull(scheduler.planNext(second.exchange) { true })
      assertFalse(scheduler.resolve(old, ticket))
      assertTrue(scheduler.resolve(replacement, wrong))
      assertFalse(scheduler.resolve(replacement, wrong))
      val response = assertIs<PeerBlockExchange.Response.Block>(second.receive(
        PeerMessage.Piece(1, 0, byteArrayOf(9))))
      assertTrue(scheduler.receive(second.exchange, response))
      scheduler.close()
      assertFalse(scheduler.resolve(replacement, wrong))
    } finally {
      scheduler.removePeer(first.exchange)
      scheduler.removePeer(second.exchange)
      scheduler.close()
    }
    assertEquals(0, buffers.allocated)
    assertEquals(0, state.allocated)
  }

  @Test
  fun twoPeersFillDifferentBlocksAndOnlyTheMatchingCommitPublishesVerifiedState() = runTest {
    val buffers = TorrentBufferBudget(200_000)
    val state = TorrentBufferBudget(2_000_000)
    val scheduler = assertNotNull(TorrentV2PieceScheduler.create(layout, emptySet(),
      BooleanArray(2), buffers, state, maxActive = 1))
    val first = Peer(buffers, layout)
    val second = Peer(buffers, layout)
    val path = FileSystem.SYSTEM_TEMPORARY_DIRECTORY /
      "ketch-scheduler-${InfoHash.fromBytes(torrentRandomBytes(20)).hex}"
    val store = TorrentV2PieceStore(document, path, emptySet(), "test", buffers, Semaphore(1))
    try {
      store.initialize()
      first.ready()
      second.ready()
      assertTrue(scheduler.begin(0))
      assertFalse(scheduler.begin(1))
      val a = assertNotNull(scheduler.requestNext(first.exchange))
      assertNull(scheduler.requestNext(first.exchange))
      val b = assertNotNull(scheduler.requestNext(second.exchange))
      assertEquals(0, a.request.begin)
      assertEquals(16_384, b.request.begin)
      for ((peer, ticket) in listOf(second to b, first to a)) {
        val request = ticket.request
        val response = assertIs<PeerBlockExchange.Response.Block>(peer.receive(
          PeerMessage.Piece(0, request.begin,
            bytes.copyOfRange(request.begin, request.begin + request.length))))
        assertTrue(scheduler.receive(peer.exchange, response))
      }
      assertFalse(scheduler.isVerified(0))
      TorrentV2CommitWorker.run(store) { worker ->
        assertEquals(1, scheduler.submitReady(worker))
        assertFalse(scheduler.begin(0))
        assertFalse(scheduler.completed(TorrentV2CommitWorker.Completion.Committed(
          TorrentV2CommitWorker.Ticket(0), true)))
        assertFalse(scheduler.isVerified(0))
        val completion = worker.completions.receive()
        assertTrue(scheduler.completed(completion))
        assertTrue(scheduler.isVerified(0))
        assertFalse(scheduler.completed(completion))
        assertEquals(0, scheduler.activeCount)
        assertTrue(scheduler.begin(1))
      }
      assertEquals(bytes.size.toLong(), torrentFileSystem.metadata(path / "a").size)
    } finally {
      scheduler.removePeer(first.exchange)
      scheduler.removePeer(second.exchange)
      scheduler.close()
      store.cleanup()
    }
    assertEquals(0, buffers.allocated)
    assertEquals(0, state.allocated)
  }

  @Test
  fun departingPeerReleasesAssignmentsAndItsLateDeliveryCannotDisplaceTheReplacement() = runTest {
    val buffers = TorrentBufferBudget(200_000)
    val state = TorrentBufferBudget(2_000_000)
    val scheduler = assertNotNull(TorrentV2PieceScheduler.create(layout, emptySet(),
      BooleanArray(2), buffers, state))
    val first = Peer(buffers, layout)
    val second = Peer(buffers, layout)
    try {
      first.ready()
      second.ready()
      assertTrue(scheduler.begin(1))
      val old = assertNotNull(scheduler.requestNext(first.exchange))
      scheduler.removePeer(first.exchange)
      val replacement = assertNotNull(scheduler.requestNext(second.exchange))
      assertEquals(old.request, replacement.request)
      val late = PeerBlockExchange.Response.Block(old, byteArrayOf(9),
        assertNotNull(buffers.reserve(512)))
      val before = buffers.allocated
      assertFalse(scheduler.receive(first.exchange, late))
      assertEquals(before - 512, buffers.allocated)
      val rejected = assertIs<PeerBlockExchange.Response.Rejected>(second.receive(
        PeerMessage.Reject(1, 0, 1)))
      assertTrue(scheduler.receive(second.exchange, rejected))
      assertNotNull(scheduler.requestNext(second.exchange))
    } finally {
      scheduler.removePeer(first.exchange)
      scheduler.removePeer(second.exchange)
      scheduler.close()
    }
    assertEquals(0, buffers.allocated)
    assertEquals(0, state.allocated)
  }

  @Test
  fun completedAssembliesSurviveQueuePressureAndCorruptCommitsBecomeRetryable() = runTest {
    val buffers = TorrentBufferBudget(200_000)
    val state = TorrentBufferBudget(2_000_000)
    val scheduler = assertNotNull(TorrentV2PieceScheduler.create(layout, emptySet(),
      BooleanArray(2), buffers, state))
    val peer = Peer(buffers, layout)
    val path = FileSystem.SYSTEM_TEMPORARY_DIRECTORY /
      "ketch-scheduler-queue-${InfoHash.fromBytes(torrentRandomBytes(20)).hex}"
    val store = TorrentV2PieceStore(document, path, emptySet(), "test", buffers, Semaphore(1))
    suspend fun fill(corrupt: Boolean) {
      while (true) {
        val ticket = scheduler.requestNext(peer.exchange) ?: break
        val request = ticket.request
        val payload = if (request.index == 0) bytes.copyOfRange(request.begin,
          request.begin + request.length) else byteArrayOf(if (corrupt) 8 else 9)
        val response = assertIs<PeerBlockExchange.Response.Block>(peer.receive(
          PeerMessage.Piece(request.index, request.begin, payload)))
        assertTrue(scheduler.receive(peer.exchange, response))
      }
    }
    try {
      store.initialize()
      peer.ready()
      assertTrue(scheduler.begin(0))
      assertTrue(scheduler.begin(1))
      fill(corrupt = true)
      TorrentV2CommitWorker.run(
        store = store,
        dispatcher = StandardTestDispatcher(testScheduler),
      ) { worker ->
        assertEquals(1, scheduler.submitReady(worker))
        assertEquals(2, scheduler.activeCount)
        assertNull(scheduler.requestNext(peer.exchange))
        assertTrue(scheduler.completed(worker.completions.receive()))
        assertTrue(scheduler.isVerified(0))
        assertEquals(1, scheduler.submitReady(worker))
        assertTrue(scheduler.completed(worker.completions.receive()))
        assertFalse(scheduler.isVerified(1))
        assertTrue(scheduler.begin(1))
        fill(corrupt = false)
        assertEquals(1, scheduler.submitReady(worker))
        assertTrue(scheduler.completed(worker.completions.receive()))
        assertTrue(scheduler.isVerified(1))
        assertTrue(store.completed())
      }
    } finally {
      scheduler.removePeer(peer.exchange)
      scheduler.close()
      store.cleanup()
    }
    assertEquals(0, buffers.allocated)
    assertEquals(0, state.allocated)
  }

  @Test
  fun requestWriteFailureReleasesEarlierAssignmentsForAnotherPeer() = runTest {
    val buffers = TorrentBufferBudget(200_000)
    val state = TorrentBufferBudget(2_000_000)
    val scheduler = assertNotNull(TorrentV2PieceScheduler.create(layout, emptySet(),
      BooleanArray(2), buffers, state))
    val failing = Peer(buffers, layout, maxPending = 2)
    val replacement = Peer(buffers, layout)
    try {
      failing.ready()
      replacement.ready()
      assertTrue(scheduler.begin(0))
      val first = assertNotNull(scheduler.requestNext(failing.exchange))
      failing.failWrite = true
      assertFailsWith<okio.IOException> { scheduler.requestNext(failing.exchange) }
      assertEquals(0, failing.exchange.pendingCount)
      val retry = assertNotNull(scheduler.requestNext(replacement.exchange))
      assertEquals(first.request, retry.request)
    } finally {
      scheduler.removePeer(failing.exchange)
      scheduler.removePeer(replacement.exchange)
      scheduler.close()
    }
    assertEquals(0, buffers.allocated)
    assertEquals(0, state.allocated)
  }

  @Test
  fun raritySelectionFeedsAdmittedPiecesAndSkipsActiveOrUnavailableCandidates() = runTest {
    val buffers = TorrentBufferBudget(200_000)
    val state = TorrentBufferBudget(2_000_000)
    val scheduler = assertNotNull(TorrentV2PieceScheduler.create(layout, emptySet(),
      BooleanArray(2), buffers, state))
    val picker = assertNotNull(TorrentV2RarityPicker.create<PeerBlockExchange>(2, state))
    val peer = Peer(buffers, layout)
    val other = Peer(buffers, layout)
    try {
      peer.ready()
      assertTrue(picker.update(peer.exchange, byteArrayOf(192.toByte())))
      assertTrue(picker.update(other.exchange, byteArrayOf(128.toByte())))
      assertTrue(scheduler.beginNext(peer.exchange, picker))
      assertFalse(scheduler.canBegin(1))
      assertTrue(scheduler.canBegin(0))
      assertEquals(1, assertNotNull(scheduler.requestNext(peer.exchange)).request.index)
      assertFalse(scheduler.beginNext(peer.exchange, picker))
      assertTrue(scheduler.receive(peer.exchange, assertIs<PeerBlockExchange.Response.Rejected>(
        peer.receive(PeerMessage.Reject(1, 0, 1)))))
      assertTrue(scheduler.beginNext(peer.exchange, picker))
      assertEquals(2, scheduler.activeCount)
    } finally {
      picker.close()
      scheduler.removePeer(peer.exchange)
      scheduler.removePeer(other.exchange)
      scheduler.close()
    }
    assertEquals(0, buffers.allocated)
    assertEquals(0, state.allocated)
  }

  @Test
  fun smallPieceAdmissionScalesToManyActivePeersWithoutChargingMaximumSizedPieces() {
    val tree = (0 until 128).associate { index ->
      index.toString().padStart(3, '0') to mapOf("" to mapOf("length" to 1L,
        "pieces root" to sha256Digest(byteArrayOf(1))))
    }
    val info = TorrentV2Info.parse(Bencode.encode(mapOf("meta version" to 2L,
      "piece length" to 16_384L, "file tree" to tree)))
    val buffers = TorrentBufferBudget(100_000)
    val state = TorrentBufferBudget(100_000)
    val scheduler = assertNotNull(TorrentV2PieceScheduler.create(TorrentContentLayout.from(info),
      emptySet(), BooleanArray(128), buffers, state, maxActive = 128))
    try {
      repeat(128) { assertTrue(scheduler.begin(it)) }
      assertEquals(128, scheduler.activeCount)
    } finally { scheduler.close() }
    assertEquals(0, buffers.allocated)
    assertEquals(0, state.allocated)
  }

  @Test
  fun interestStartsWhileChokedAndStopsWhenSelectedPiecesAreVerified() = runTest {
    val buffers = TorrentBufferBudget(200_000)
    val state = TorrentBufferBudget(2_000_000)
    val scheduler = assertNotNull(TorrentV2PieceScheduler.create(layout, setOf("1"),
      BooleanArray(2), buffers, state))
    val picker = assertNotNull(TorrentV2RarityPicker.create<PeerBlockExchange>(2, state))
    val peer = Peer(buffers, layout)
    try {
      peer.receive(PeerMessage.Bitfield(byteArrayOf(192.toByte())))
      picker.update(peer.exchange, byteArrayOf(192.toByte()))
      assertTrue(scheduler.needsPeer(peer.exchange))
      assertFalse(scheduler.beginNext(peer.exchange, picker))
      assertNull(scheduler.requestNext(peer.exchange))
      assertTrue(peer.exchange.localInterested)
      val size = peer.output.readInt()
      assertEquals(PeerMessage.Control(PeerMessage.Signal.INTERESTED),
        PeerWire.decode(peer.output.readByteArray(size.toLong())))
      peer.receive(PeerMessage.Control(PeerMessage.Signal.UNCHOKE))
      assertTrue(scheduler.beginNext(peer.exchange, picker))
      assertEquals(1, assertNotNull(scheduler.requestNext(peer.exchange)).request.index)
      val requestSize = peer.output.readInt()
      val sent = PeerWire.decode(peer.output.readByteArray(requestSize.toLong()))
      assertIs<PeerMessage.Request>(sent)
      assertEquals(0L, peer.output.size)
    } finally {
      scheduler.removePeer(peer.exchange)
      picker.close()
      scheduler.close()
    }
    val finished = assertNotNull(TorrentV2PieceScheduler.create(layout, setOf("1"),
      booleanArrayOf(false, true), buffers, state))
    val next = Peer(buffers, layout)
    try {
      next.ready()
      assertTrue(next.exchange.setInterested(true))
      assertFalse(finished.needsPeer(next.exchange))
      assertTrue(finished.updateInterest(next.exchange))
      assertFalse(next.exchange.localInterested)
    } finally {
      finished.removePeer(next.exchange)
      finished.close()
    }
    assertEquals(0, buffers.allocated)
    assertEquals(0, state.allocated)
  }

  @Test
  fun selectionVerifiedSnapshotsAndBothBudgetsGatePieceAdmission() {
    val buffers = TorrentBufferBudget(1024)
    val state = TorrentBufferBudget(2_000_000)
    assertNull(TorrentV2PieceScheduler.create(layout, emptySet(), BooleanArray(2), buffers,
      TorrentBufferBudget(1)))
    val snapshot = booleanArrayOf(true, false)
    val scheduler = assertNotNull(TorrentV2PieceScheduler.create(layout, setOf("1"), snapshot,
      buffers, state))
    snapshot[0] = false
    try {
      assertTrue(scheduler.isVerified(0))
      assertFalse(scheduler.begin(0))
      assertTrue(scheduler.begin(1))
      assertFalse(scheduler.begin(1))
    } finally { scheduler.close() }
    val all = assertNotNull(TorrentV2PieceScheduler.create(layout, emptySet(), BooleanArray(2),
      buffers, state))
    try { assertFalse(all.begin(0)) } finally { all.close() }
    assertEquals(0, buffers.allocated)
    assertEquals(0, state.allocated)
  }

  /** A scheduler over a and b, both wanted, and a ready peer; leak checks follow [block]. */
  private fun retargeting(
    block: suspend (TorrentV2PieceScheduler, Peer, TorrentBufferBudget) -> Unit,
  ) = runTest {
    val buffers = TorrentBufferBudget(200_000)
    val state = TorrentBufferBudget(2_000_000)
    val scheduler = assertNotNull(TorrentV2PieceScheduler.create(layout, emptySet(),
      BooleanArray(2), buffers, state))
    val peer = Peer(buffers, layout)
    try {
      peer.ready()
      block(scheduler, peer, buffers)
    } finally {
      scheduler.removePeer(peer.exchange)
      scheduler.close()
    }
    assertEquals(0, buffers.allocated)
    assertEquals(0, state.allocated)
  }

  /** Delivers every block [scheduler] asks [peer] for; b's byte is [last]. */
  private suspend fun fill(scheduler: TorrentV2PieceScheduler, peer: Peer, last: Byte = 9) {
    while (true) {
      val ticket = scheduler.requestNext(peer.exchange) ?: break
      val request = ticket.request
      val payload = if (request.index == 0) bytes.copyOfRange(request.begin,
        request.begin + request.length) else byteArrayOf(last)
      assertTrue(scheduler.receive(peer.exchange, assertIs<PeerBlockExchange.Response.Block>(
        peer.receive(PeerMessage.Piece(request.index, request.begin, payload)))))
    }
  }

  @Test
  fun retarget_evictsUnassignedUnwantedAssemblies() = retargeting { scheduler, _, buffers ->
    assertTrue(scheduler.begin(0))
    assertTrue(scheduler.begin(1))
    val held = buffers.allocated
    val change = scheduler.retarget(setOf("0"), BooleanArray(2))
    // b's assembly had no request out, so it goes at once with its buffer.
    assertEquals(1, change.evicted)
    assertContentEquals(intArrayOf(1), change.lost)
    assertTrue(change.gained.isEmpty())
    assertTrue(buffers.allocated < held)
    assertEquals(1, scheduler.activeCount)
    assertFalse(scheduler.isWanted(1))
    assertFalse(scheduler.canBegin(1))
    assertFalse(scheduler.isNeeded(1))
    assertFalse(scheduler.completed())
    // Wanted again, it is needed again.
    assertContentEquals(intArrayOf(1), scheduler.retarget(emptySet(), BooleanArray(2)).gained)
    assertTrue(scheduler.canBegin(1))
  }

  @Test
  fun retarget_keepsAssignedAssemblyUntilDrained() = retargeting { scheduler, peer, _ ->
    assertTrue(scheduler.begin(1))
    val ticket = assertNotNull(scheduler.requestNext(peer.exchange))
    assertEquals(0, scheduler.retarget(setOf("0"), BooleanArray(2)).evicted)
    assertEquals(1, scheduler.activeCount)
    // Nothing more is asked for it while its request is out.
    assertNull(scheduler.planNext(peer.exchange) { true })
    assertTrue(scheduler.receive(peer.exchange, assertIs<PeerBlockExchange.Response.Block>(
      peer.receive(PeerMessage.Piece(1, ticket.request.begin, byteArrayOf(9))))))
    assertEquals(1, scheduler.evictUnwanted())
    assertEquals(0, scheduler.activeCount)
  }

  @Test
  fun submitReady_skipsUnwantedCompleteAssemblies() = retargeting { scheduler, peer, _ ->
    val path = FileSystem.SYSTEM_TEMPORARY_DIRECTORY /
      "ketch-scheduler-unwanted-${InfoHash.fromBytes(torrentRandomBytes(20)).hex}"
    val store = TorrentV2PieceStore(document, path, emptySet(), "test",
      TorrentBufferBudget(200_000), Semaphore(1))
    try {
      store.initialize()
      assertTrue(scheduler.begin(1))
      fill(scheduler, peer)
      scheduler.retarget(setOf("0"), BooleanArray(2))
      TorrentV2CommitWorker.run(store) { worker ->
        // b is complete but no longer wanted: nothing is written for it.
        assertEquals(0, scheduler.submitReady(worker))
        assertEquals(0, scheduler.activeCount)
      }
      assertEquals(0L, torrentFileSystem.metadata(path / "b").size)
      assertFalse(store.verifiedPieces()[1])
    } finally {
      store.cleanup()
    }
  }

  @Test
  fun completed_discardedCommit_keepsRemaining() = retargeting { scheduler, peer, _ ->
    val path = FileSystem.SYSTEM_TEMPORARY_DIRECTORY /
      "ketch-scheduler-discarded-${InfoHash.fromBytes(torrentRandomBytes(20)).hex}"
    // The store already dropped b, which the scheduler still wants until it hears of it.
    val store = TorrentV2PieceStore(document, path, setOf("0"), "test",
      TorrentBufferBudget(200_000), Semaphore(1))
    try {
      store.initialize()
      assertTrue(scheduler.begin(1))
      fill(scheduler, peer)
      TorrentV2CommitWorker.run(store) { worker ->
        assertEquals(1, scheduler.submitReady(worker))
        val completion = worker.completions.receive()
        assertIs<TorrentV2CommitWorker.Completion.Discarded>(completion)
        assertTrue(scheduler.completed(completion))
      }
      assertFalse(scheduler.isVerified(1))
      assertTrue(scheduler.isNeeded(1))
      assertFalse(scheduler.completed())
      // Retryable, once the scheduler wants it at all.
      assertTrue(scheduler.canBegin(1))
      assertFalse(torrentFileSystem.exists(path / "b"))
    } finally {
      store.cleanup()
    }
  }

  @Test
  fun retarget_newlyVerifiedStorePieces_areReported() = retargeting { scheduler, _, _ ->
    val change = scheduler.retarget(emptySet(), booleanArrayOf(false, true))
    assertContentEquals(intArrayOf(1), change.verified)
    assertContentEquals(intArrayOf(1), change.lost)
    assertTrue(scheduler.isVerified(1))
    scheduler.revoke(1)
    assertFalse(scheduler.isVerified(1))
    assertTrue(scheduler.isNeeded(1))
  }

  @Test
  fun claimWebSeed_takesFreePiecesThenDuplicatesAssembledOnesInTheEndgame() {
    val buffers = TorrentBufferBudget(1024 * 1024)
    val state = TorrentBufferBudget(1024 * 1024)
    val scheduler = assertNotNull(TorrentV2PieceScheduler.create(layout, emptySet(),
      BooleanArray(2), buffers, state, maxActive = 4))
    // A peer assembles piece 0, so a web seed starts with the free piece 1.
    assertTrue(scheduler.begin(0))
    val first = assertNotNull(scheduler.claimWebSeed())
    assertEquals(1, first.index)
    assertEquals(layout.v2Piece(1).length.toInt(), first.bytes.size)
    // Nothing is free: the piece the peer assembles is fetched too, but by one seed only.
    val second = assertNotNull(scheduler.claimWebSeed())
    assertEquals(0, second.index)
    assertNull(scheduler.claimWebSeed())
    scheduler.webSeedDone(0, verified = true)
    second.lease.close()
    assertTrue(scheduler.isVerified(0))
    // The peer's assembly of a piece the seed stored goes.
    assertEquals(1, scheduler.evictUnwanted())
    scheduler.webSeedDone(1, verified = false)
    first.lease.close()
    assertFalse(scheduler.completed())
    val again = assertNotNull(scheduler.claimWebSeed())
    assertEquals(1, again.index)
    again.lease.close()
    scheduler.close()
    assertEquals(0, buffers.allocated)
  }

  @Test
  fun claimWebSeedRun_extendsOverFreePiecesWithinItsShare() {
    // Pieces 0 to 7 lie in "a", 8 and 9 in "b".
    val fixture = TorrentV2Fixture.build(listOf("a" to 8 * 32_768, "b" to 2 * 32_768))
    val buffers = TorrentBufferBudget(4 * 1024 * 1024)
    val state = TorrentBufferBudget(1024 * 1024)
    val scheduler = assertNotNull(TorrentV2PieceScheduler.create(fixture.layout, emptySet(),
      BooleanArray(10), buffers, state, maxActive = 64))
    // A peer assembles piece 5: the first run stops before it.
    assertTrue(scheduler.begin(5))
    val first = scheduler.claimWebSeedRun(fetchers = 1)
    assertEquals(listOf(0, 1, 2, 3, 4), first.map { it.index })
    // Two fetchers share the four free pieces left.
    val second = scheduler.claimWebSeedRun(fetchers = 2)
    assertEquals(listOf(6, 7), second.map { it.index })
    // A run follows the pieces into the next file, up to its limit.
    val third = scheduler.claimWebSeedRun(fetchers = 1, maxPieces = 1)
    assertEquals(listOf(8), third.map { it.index })
    val fourth = scheduler.claimWebSeedRun(fetchers = 1)
    assertEquals(listOf(9), fourth.map { it.index })
    // Nothing is free: the endgame duplicate of the peer's piece is never extended.
    val duplicate = scheduler.claimWebSeedRun(fetchers = 1)
    assertEquals(listOf(5), duplicate.map { it.index })
    assertTrue(scheduler.claimWebSeedRun(fetchers = 1).isEmpty())
    for (claim in first + second + third + fourth + duplicate) {
      scheduler.webSeedDone(claim.index, verified = claim.index != 3)
      claim.lease.close()
    }
    // A piece given back is free again, alone between verified ones.
    val again = scheduler.claimWebSeedRun(fetchers = 1)
    assertEquals(listOf(3), again.map { it.index })
    again.single().lease.close()
    scheduler.close()
    assertEquals(0, buffers.allocated)
  }

  @Test
  fun claimWebSeedRun_laterPiecesLeaveHalfTheBuffersFree() {
    val fixture = TorrentV2Fixture.build(listOf("a" to 8 * 32_768))
    // Room for eight claims of a piece and its overhead: a run takes four.
    val buffers = TorrentBufferBudget(8 * (32_768 + 512))
    val state = TorrentBufferBudget(1024 * 1024)
    val scheduler = assertNotNull(TorrentV2PieceScheduler.create(fixture.layout, emptySet(),
      BooleanArray(8), buffers, state, maxActive = 64))
    val run = scheduler.claimWebSeedRun(fetchers = 1)
    assertEquals(listOf(0, 1, 2, 3), run.map { it.index })
    // The next run starts on what is left, then extends no further.
    assertEquals(listOf(4), scheduler.claimWebSeedRun(fetchers = 1).map { it.index })
    scheduler.close()
  }

  @Test
  fun lateBlock_isAskedOfAnotherPeer_andTheFirstCopyCancelsTheOther() = runTest {
    var now = 0L
    val buffers = TorrentBufferBudget(200_000)
    val state = TorrentBufferBudget(2_000_000)
    val scheduler = assertNotNull(TorrentV2PieceScheduler.create(layout, setOf("1"),
      BooleanArray(2), buffers, state, nowMs = { now }))
    val slow = Peer(buffers, layout)
    val fast = Peer(buffers, layout)
    try {
      slow.ready()
      fast.ready()
      assertTrue(scheduler.begin(1))
      val plan = assertNotNull(scheduler.planNext(slow.exchange, lateAfterMs = 1_000) { true })
      val ticket = slow.send(scheduler, plan)
      // On time, the block is the slow peer's alone.
      assertNull(scheduler.planDuplicate(fast.exchange, endgame = false) { true })
      assertEquals(0, scheduler.lateCount())
      now = 1_500
      assertEquals(1, scheduler.lateCount())
      // Late, another peer asks for it too, and nobody a third time.
      val copy = assertNotNull(scheduler.planDuplicate(fast.exchange, endgame = false) { true })
      assertEquals(plan.request, copy.request)
      assertNull(scheduler.planDuplicate(fast.exchange, endgame = false) { true })
      fast.send(scheduler, copy)
      // The copy arrives first: it is stored, and the slow peer is told to cancel.
      assertTrue(scheduler.receive(fast.exchange, assertIs<PeerBlockExchange.Response.Block>(
        fast.receive(PeerMessage.Piece(1, 0, byteArrayOf(9))))))
      val cancel = scheduler.takeCancels().single()
      assertSame(slow.exchange, cancel.peer)
      assertSame(ticket, cancel.ticket)
      assertTrue(scheduler.takeCancels().isEmpty())
      // Its own copy, already on its way, is discarded rather than stored twice.
      assertTrue(scheduler.receive(slow.exchange, assertIs<PeerBlockExchange.Response.Block>(
        slow.receive(PeerMessage.Piece(1, 0, byteArrayOf(9))))))
      assertEquals(0, scheduler.lateCount())
      assertNull(scheduler.planNext(slow.exchange) { true })
      assertNull(scheduler.planDuplicate(slow.exchange, endgame = true) { true })
    } finally {
      scheduler.removePeer(slow.exchange)
      scheduler.removePeer(fast.exchange)
      scheduler.close()
    }
    assertEquals(0, buffers.allocated)
    assertEquals(0, state.allocated)
  }

  @Test
  fun discardedPayload_countsLostCopiesCorruptPiecesAndEvictions() = runTest {
    var now = 0L
    var discarded = 0L
    val buffers = TorrentBufferBudget(200_000)
    val state = TorrentBufferBudget(2_000_000)
    val scheduler = assertNotNull(TorrentV2PieceScheduler.create(layout, emptySet(),
      BooleanArray(2), buffers, state, nowMs = { now }, onDiscard = { discarded += it }))
    val slow = Peer(buffers, layout)
    val fast = Peer(buffers, layout)
    val path = FileSystem.SYSTEM_TEMPORARY_DIRECTORY /
      "ketch-scheduler-${InfoHash.fromBytes(torrentRandomBytes(20)).hex}"
    val store = TorrentV2PieceStore(document, path, emptySet(), "test", buffers, Semaphore(1))
    try {
      store.initialize()
      slow.ready()
      fast.ready()
      assertTrue(scheduler.begin(1))
      val plan = assertNotNull(scheduler.planNext(slow.exchange, lateAfterMs = 1_000) { true })
      slow.send(scheduler, plan)
      now = 1_500
      fast.send(scheduler, assertNotNull(
        scheduler.planDuplicate(fast.exchange, endgame = false) { true }))
      // The copy that comes first is kept, wrong as it is; the other is thrown away.
      for (peer in listOf(fast, slow)) {
        assertTrue(scheduler.receive(peer.exchange, assertIs<PeerBlockExchange.Response.Block>(
          peer.receive(PeerMessage.Piece(1, 0, byteArrayOf(8))))))
      }
      assertEquals(1L, discarded)
      // The piece fails its hash check: every block of it is fetched again.
      TorrentV2CommitWorker.run(store) { worker ->
        assertEquals(1, scheduler.submitReady(worker))
        val completion = assertIs<TorrentV2CommitWorker.Completion.Committed>(
          worker.completions.receive())
        assertFalse(completion.verified)
        assertTrue(scheduler.completed(completion))
      }
      assertEquals(2L, discarded)
      // A partial piece nobody has any more gives up the blocks it holds.
      assertTrue(scheduler.begin(0))
      val ticket = assertNotNull(scheduler.requestNext(slow.exchange))
      val request = ticket.request
      assertTrue(scheduler.receive(slow.exchange, assertIs<PeerBlockExchange.Response.Block>(
        slow.receive(PeerMessage.Piece(0, request.begin,
          bytes.copyOfRange(request.begin, request.begin + request.length))))))
      assertEquals(1, scheduler.evictUnavailable { false })
      assertEquals(2L + request.length, discarded)
    } finally {
      scheduler.removePeer(slow.exchange)
      scheduler.removePeer(fast.exchange)
      scheduler.close()
      store.cleanup()
    }
    assertEquals(0, buffers.allocated)
    assertEquals(0, state.allocated)
  }

  @Test
  fun endgame_duplicatesOutstandingBlocksAtOnce_andARejectedFirstRequestLeavesItToTheCopy() =
    runTest {
      val buffers = TorrentBufferBudget(200_000)
      val state = TorrentBufferBudget(2_000_000)
      val scheduler = assertNotNull(TorrentV2PieceScheduler.create(layout, emptySet(),
        BooleanArray(2), buffers, state))
      val first = Peer(buffers, layout, maxPending = 2)
      val second = Peer(buffers, layout, maxPending = 2)
      try {
        first.ready()
        second.ready()
        assertTrue(scheduler.begin(0))
        // Piece 1 is free and some peer has it: no endgame yet.
        assertFalse(scheduler.endgame { true })
        // Nobody has it: every piece left that can be fetched is.
        assertTrue(scheduler.endgame { it != 1 })
        val plan = assertNotNull(scheduler.planNext(first.exchange, 60_000) { it == 0 })
        first.send(scheduler, plan)
        // Not late, yet in the endgame another peer asks for it at once.
        assertNull(scheduler.planDuplicate(second.exchange, endgame = false) { it == 0 })
        val copy = assertNotNull(scheduler.planDuplicate(second.exchange, endgame = true) {
          it == 0
        })
        assertEquals(plan.request, copy.request)
        second.send(scheduler, copy)
        // The first peer rejects it: the block stays with the copy, not free for a third.
        assertTrue(scheduler.receive(first.exchange, assertIs<PeerBlockExchange.Response.Rejected>(
          first.receive(PeerMessage.Reject(0, 0, 16_384)))))
        val other = assertNotNull(scheduler.planNext(first.exchange) { it == 0 })
        assertEquals(16_384, other.request.begin)
        assertTrue(scheduler.resolve(other, null))
        assertTrue(scheduler.receive(second.exchange, assertIs<PeerBlockExchange.Response.Block>(
          second.receive(PeerMessage.Piece(0, 0, bytes.copyOfRange(0, 16_384))))))
        assertTrue(scheduler.takeCancels().isEmpty())
      } finally {
        scheduler.removePeer(first.exchange)
        scheduler.removePeer(second.exchange)
        scheduler.close()
      }
      assertEquals(0, buffers.allocated)
      assertEquals(0, state.allocated)
    }

  @Test
  fun duplicates_areBoundedPerPeer_andAPeerThatLeavesHandsItsBlocksToTheirCopies() = runTest {
    var now = 0L
    // Ten pieces of two blocks each.
    val fixture = TorrentV2Fixture.build(listOf("a" to 10 * 32_768))
    val buffers = TorrentBufferBudget(4 * 1024 * 1024)
    val state = TorrentBufferBudget(4 * 1024 * 1024)
    val scheduler = assertNotNull(TorrentV2PieceScheduler.create(fixture.layout, emptySet(),
      BooleanArray(10), buffers, state, maxActive = 16, nowMs = { now }))
    val slow = Peer(buffers, fixture.layout, maxPending = 32, pieces = 10)
    val fast = Peer(buffers, fixture.layout, maxPending = 32, pieces = 10)
    try {
      slow.ready()
      fast.ready()
      repeat(10) { assertTrue(scheduler.begin(it)) }
      while (true) {
        val plan = scheduler.planNext(slow.exchange, lateAfterMs = 1_000) { true } ?: break
        slow.send(scheduler, plan)
      }
      now = 2_000
      assertEquals(20, scheduler.lateCount())
      val copies = generateSequence {
        scheduler.planDuplicate(fast.exchange, endgame = false) { true }
      }.toList()
      assertEquals(TorrentV2PieceScheduler.MAX_PEER_DUPLICATES, copies.size)
      copies.forEach { fast.send(scheduler, it) }
      // The slow peer leaves: what was duplicated stays with the copies, the rest is free.
      scheduler.removePeer(slow.exchange)
      val left = generateSequence { scheduler.planNext(fast.exchange) { true } }.toList()
      assertEquals(20 - copies.size, left.size)
      assertTrue(left.none { plan -> copies.any { it.request == plan.request } })
      left.forEach { assertTrue(scheduler.resolve(it, null)) }
    } finally {
      scheduler.removePeer(slow.exchange)
      scheduler.removePeer(fast.exchange)
      scheduler.close()
    }
    assertEquals(0, buffers.allocated)
    assertEquals(0, state.allocated)
  }

  @Test
  fun rescue_letsPeersBeginAPieceAWebSeedFetches() = runTest {
    val buffers = TorrentBufferBudget(1024 * 1024)
    val state = TorrentBufferBudget(1024 * 1024)
    val scheduler = assertNotNull(TorrentV2PieceScheduler.create(layout, emptySet(),
      BooleanArray(2), buffers, state, maxActive = 4))
    val peer = Peer(buffers, layout)
    try {
      peer.ready()
      val claim = assertNotNull(scheduler.claimWebSeed())
      assertEquals(0, claim.index)
      // Piece 1 is free; once nobody has it, the seed's piece is all that can be fetched.
      assertFalse(scheduler.endgame { true })
      assertTrue(scheduler.endgame { it == 0 })
      // Busy with the seed: peers never begin it, except to rescue it.
      assertFalse(scheduler.canBegin(0))
      assertTrue(scheduler.canRescue(0))
      assertFalse(scheduler.canRescue(1))
      assertTrue(scheduler.rescue(0))
      assertFalse(scheduler.canRescue(0))
      val plan = assertNotNull(scheduler.planNext(peer.exchange) { it == 0 })
      assertEquals(0, plan.request.index)
      peer.send(scheduler, plan)
      // The seed verifies it first; the peer's assembly goes once its block is answered.
      scheduler.webSeedDone(0, verified = true)
      claim.lease.close()
      assertTrue(scheduler.isVerified(0))
      assertEquals(0, scheduler.evictUnwanted())
      assertTrue(scheduler.receive(peer.exchange, assertIs<PeerBlockExchange.Response.Block>(
        peer.receive(PeerMessage.Piece(0, 0, bytes.copyOfRange(0, 16_384))))))
      assertEquals(1, scheduler.evictUnwanted())
    } finally {
      scheduler.removePeer(peer.exchange)
      scheduler.close()
    }
    assertEquals(0, buffers.allocated)
  }

  @Test
  fun missing_countsWantedPiecesNoSourceHasAndNamesTheirFiles() {
    val buffers = TorrentBufferBudget(200_000)
    val state = TorrentBufferBudget(2_000_000)
    val scheduler = assertNotNull(TorrentV2PieceScheduler.create(layout, emptySet(),
      booleanArrayOf(false, false), buffers, state))
    try {
      val none = scheduler.missing(limit = 10, maxFiles = 32) { true }
      assertEquals(0, none.count)
      assertTrue(none.fileIds.isEmpty())
      // Nobody has b's piece.
      val missing = scheduler.missing(limit = 10, maxFiles = 32) { it == 0 }
      assertEquals(1, missing.count)
      assertEquals(listOf("1"), missing.fileIds)
      // Counted whatever the limit, which bounds only the files looked up.
      val bounded = scheduler.missing(limit = 0, maxFiles = 32) { false }
      assertEquals(2, bounded.count)
      assertTrue(bounded.fileIds.isEmpty())
    } finally { scheduler.close() }
  }

  @Test
  fun lateAfter_isTwiceThePipelinesTimeAtThePeersRate() {
    assertEquals(TorrentV2PieceScheduler.DEFAULT_LATE_MS,
      TorrentV2PieceScheduler.lateAfterMs(pipeline = 32, rate = 0))
    // 32 blocks of 16 KiB at 64 KiB/s take 8 s.
    assertEquals(16_000, TorrentV2PieceScheduler.lateAfterMs(pipeline = 32, rate = 65_536))
    assertEquals(TorrentV2PieceScheduler.MIN_LATE_MS,
      TorrentV2PieceScheduler.lateAfterMs(pipeline = 32, rate = 100_000_000))
    assertEquals(TorrentV2PieceScheduler.MAX_LATE_MS,
      TorrentV2PieceScheduler.lateAfterMs(pipeline = 32, rate = 1_000))
  }
}
