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
    private val connection = object : TorrentConnection {
      override val remote = PeerEndpoint("127.0.0.1", 1)
      override suspend fun readExactly(size: Int): ByteArray = input.readByteArray(size.toLong())
      override suspend fun write(bytes: ByteArray) {
        if (failWrite) throw okio.IOException("Partial request write")
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
