package com.linroid.ketch.torrent

import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import okio.Buffer
import okio.FileSystem
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
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
      pieceCount = 2)
    val exchange = PeerBlockExchange(layout, transport, budget, maxPending = maxPending)
    suspend fun receive(message: PeerMessage): PeerBlockExchange.Response? {
      input.write(PeerWire.encode(message, pieceCount = 2))
      val frame = transport.read()
      return try { exchange.receive(frame) } finally { frame.close() }
    }
    suspend fun ready() {
      receive(PeerMessage.Bitfield(byteArrayOf(192.toByte())))
      receive(PeerMessage.Control(PeerMessage.Signal.UNCHOKE))
    }
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
}
