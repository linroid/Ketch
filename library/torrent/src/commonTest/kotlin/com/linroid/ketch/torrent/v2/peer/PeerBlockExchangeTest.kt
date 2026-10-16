package com.linroid.ketch.torrent.v2.peer

import com.linroid.ketch.torrent.engine.TorrentBufferBudget
import com.linroid.ketch.torrent.metainfo.Bencode
import com.linroid.ketch.torrent.metainfo.TorrentContentLayout
import com.linroid.ketch.torrent.metainfo.TorrentV2Info
import com.linroid.ketch.torrent.net.PeerEndpoint
import com.linroid.ketch.torrent.net.TorrentConnection
import com.linroid.ketch.torrent.peer.PeerMessage
import com.linroid.ketch.torrent.peer.PeerWire
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.runTest
import okio.Buffer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class PeerBlockExchangeTest {
  private class Connection : TorrentConnection {
    override val remote = PeerEndpoint("127.0.0.1", 1)
    val incoming = Buffer()
    val outgoing = Buffer()
    var closed = false
    var writing: suspend () -> Unit = {}
    override suspend fun readExactly(size: Int): ByteArray = incoming.readByteArray(size.toLong())
    override suspend fun write(bytes: ByteArray) { outgoing.write(bytes); writing() }
    override fun close() { closed = true }
  }

  private class Fixture(capacity: Int = 4096, pipeline: Int = 2, fast: Boolean = false) {
    val connection = Connection()
    val frames = TorrentBufferBudget(100_000)
    val blocks = TorrentBufferBudget(capacity)
    var now = 0L
    private val info = TorrentV2Info.parse(Bencode.encode(mapOf("meta version" to 2L,
      "piece length" to 16_384L, "file tree" to mapOf(
        "a" to mapOf("" to mapOf("length" to 3L, "pieces root" to ByteArray(32))),
        "b" to mapOf("" to mapOf("length" to 5L, "pieces root" to ByteArray(32)))))))
    val transport = PeerHashTransport(connection, PeerHashExchange(frames, { null }), frames,
      pieceCount = 2)
    val exchange = PeerBlockExchange(TorrentContentLayout.from(info), transport, blocks,
      maxPending = pipeline, timeoutMs = 10, clock = { now }, fast = fast,
      fastRejectGraceMs = 5)
    suspend fun receive(message: PeerMessage): PeerBlockExchange.Response? {
      connection.incoming.write(PeerWire.encode(message, pieceCount = 2))
      val frame = transport.read()
      return try { exchange.receive(frame) } finally { frame.close() }
    }
    suspend fun ready() {
      receive(PeerMessage.Bitfield(byteArrayOf(192.toByte())))
      receive(PeerMessage.Control(PeerMessage.Signal.UNCHOKE))
    }
  }

  @Test
  fun boundsRequestsByActualFileTailsAndAdmitsBeforeSending() = runTest {
    val f = Fixture(capacity = 262)
    try {
      assertNull(f.exchange.request(PeerMessage.Request(0, 0, 3)))
      f.ready()
      assertFailsWith<IllegalArgumentException> {
        f.exchange.request(PeerMessage.Request(0, 0, 4))
      }
      assertFailsWith<IllegalArgumentException> {
        f.exchange.request(PeerMessage.Request(1, 4, 2))
      }
      assertEquals(0L, f.connection.outgoing.size)
      val request = PeerMessage.Request(0, 0, 3)
      assertNotNull(f.exchange.request(request))
      val written = f.connection.outgoing.size
      assertNull(f.exchange.request(request))
      assertNull(f.exchange.request(PeerMessage.Request(1, 0, 5)))
      assertEquals(written, f.connection.outgoing.size)
      assertEquals(262, f.blocks.allocated)
    } finally { f.exchange.close() }
    assertEquals(0, f.blocks.allocated)
  }

  @Test
  fun choke_dropsOutstandingRequestsAsRejectedAndIgnoresLateBlocks() = runTest {
    val f = Fixture(pipeline = 1)
    f.ready()
    val request = PeerMessage.Request(0, 0, 3)
    val ticket = assertNotNull(f.exchange.request(request))
    f.now = 4
    // Without the Fast extension a choking peer silently discards our requests (BEP 3).
    assertNull(f.receive(PeerMessage.Control(PeerMessage.Signal.CHOKE)))
    val dropped = f.exchange.takeDropped()
    assertEquals(1, dropped.size)
    assertSame(ticket, dropped.single().ticket)
    assertTrue(f.exchange.takeDropped().isEmpty())
    assertEquals(0, f.exchange.pendingCount)
    assertNull(f.exchange.nextDeadlineMs())
    assertEquals(0, f.blocks.allocated)
    // A block already in flight when the peer choked is discarded, not a protocol violation.
    assertNull(f.receive(PeerMessage.Piece(0, 0, byteArrayOf(1, 2, 3))))
    assertFalse(f.connection.closed)
    f.now = 100
    assertFalse(f.exchange.expire())
    f.receive(PeerMessage.Control(PeerMessage.Signal.UNCHOKE))
    val replacement = assertNotNull(f.exchange.request(request))
    assertTrue(f.exchange.cancel(replacement))
    // Canceled, it is forgotten at once: its late block is ignored.
    assertEquals(0, f.exchange.pendingCount)
    assertNull(f.receive(PeerMessage.Piece(0, 0, byteArrayOf(1, 2, 3))))
    assertFalse(f.connection.closed)
    f.exchange.close()
    assertEquals(0, f.blocks.allocated)
  }

  @Test
  fun cancel_forgetsTheRequestOnceWritten_soItsLateAnswerIsNoViolation() = runTest {
    val f = Fixture(fast = true)
    f.ready()
    val ticket = assertNotNull(f.exchange.request(PeerMessage.Request(0, 0, 3)))
    val other = assertNotNull(f.exchange.request(PeerMessage.Request(1, 0, 5)))
    val sent = f.connection.outgoing.size
    assertTrue(f.exchange.cancel(ticket))
    f.connection.outgoing.skip(sent)
    assertEquals(PeerMessage.Cancel(0, 0, 3), PeerWire.decode(
      f.connection.outgoing.readByteArray(f.connection.outgoing.readInt().toLong()),
      pieceCount = 2))
    // Its credit is back, and nothing for it is pending any more.
    assertEquals(1, f.exchange.pendingCount)
    assertEquals(5 * 2 + 256, f.blocks.allocated)
    assertFalse(f.exchange.cancel(ticket))
    // The peer had already queued it, then answers our CANCEL with a reject (BEP 6): both are
    // ignored, and the request still out is served as usual.
    assertNull(f.receive(PeerMessage.Piece(0, 0, byteArrayOf(1, 2, 3))))
    assertNull(f.receive(PeerMessage.Reject(0, 0, 3)))
    assertSame(other, assertIs<PeerBlockExchange.Response.Block>(
      f.receive(PeerMessage.Piece(1, 0, byteArrayOf(1, 2, 3, 4, 5)))).also { it.close() }.ticket)
    assertFalse(f.connection.closed)
    f.exchange.close()
    assertEquals(0, f.blocks.allocated)
  }

  @Test
  fun slowPeer_answeringNow_andThen_keepsItsRequestsUntilSilentForTheTimeout() = runTest {
    val f = Fixture()
    f.ready()
    val first = assertNotNull(f.exchange.request(PeerMessage.Request(0, 0, 3)))
    assertNotNull(f.exchange.request(PeerMessage.Request(1, 0, 5)))
    // Past the timeout of its oldest request, the peer answers it: the other request goes on.
    f.now = 8
    assertSame(first, assertIs<PeerBlockExchange.Response.Block>(
      f.receive(PeerMessage.Piece(0, 0, byteArrayOf(1, 2, 3)))).also { it.close() }.ticket)
    f.now = 15
    assertFalse(f.exchange.expire())
    assertEquals(3L, f.exchange.nextDeadlineMs())
    // Silent for the timeout since its last answer, it is closed.
    f.now = 18
    assertTrue(f.exchange.expire())
    assertTrue(f.connection.closed)
    assertEquals(0, f.blocks.allocated)
  }

  @Test
  fun requestThePeerNeverAnswers_expiresAtItsMaximumAgeWhateverElseItServes() = runTest {
    val f = Fixture()
    f.ready()
    // Piece 1's request is never answered; piece 0's are, one after another.
    assertNotNull(f.exchange.request(PeerMessage.Request(1, 0, 5)))
    val maxAge = 10L * PeerBlockExchange.MAX_AGE_TIMEOUTS
    while (f.now + 8 < maxAge) {
      assertNotNull(f.exchange.request(PeerMessage.Request(0, 0, 3)))
      f.now += 8
      assertFalse(f.exchange.expire())
      assertIs<PeerBlockExchange.Response.Block>(
        f.receive(PeerMessage.Piece(0, 0, byteArrayOf(1, 2, 3)))).close()
    }
    f.now = maxAge
    assertTrue(f.exchange.expire())
    assertTrue(f.connection.closed)
    assertEquals(0, f.blocks.allocated)
  }

  @Test
  fun fastChoke_keepsRequestsUntilRejectedThenReleasesTheRestAfterGrace() = runTest {
    val f = Fixture(fast = true)
    f.ready()
    val head = assertNotNull(f.exchange.request(PeerMessage.Request(0, 0, 3)))
    val tail = assertNotNull(f.exchange.request(PeerMessage.Request(1, 0, 5)))
    f.now = 1
    // A Fast peer rejects what it will not serve; nothing is dropped by the CHOKE itself.
    assertNull(f.receive(PeerMessage.Control(PeerMessage.Signal.CHOKE)))
    assertTrue(f.exchange.takeDropped().isEmpty())
    assertEquals(2, f.exchange.pendingCount)
    assertSame(tail, assertIs<PeerBlockExchange.Response.Rejected>(
      f.receive(PeerMessage.Reject(1, 0, 5))).ticket)
    f.now = 3
    // Repeating the CHOKE keeps the grace clock running from the first one.
    assertNull(f.receive(PeerMessage.Control(PeerMessage.Signal.CHOKE)))
    assertEquals(3L, f.exchange.nextReleaseMs())
    assertFalse(f.exchange.releaseChoked())
    f.now = 6
    val sent = f.connection.outgoing.size
    assertTrue(f.exchange.releaseChoked())
    assertSame(head, f.exchange.takeDropped().single().ticket)
    assertEquals(0, f.exchange.pendingCount)
    assertNull(f.exchange.nextReleaseMs())
    // The unanswered request is canceled on the wire.
    f.connection.outgoing.skip(sent)
    assertEquals(PeerMessage.Cancel(0, 0, 3), PeerWire.decode(
      f.connection.outgoing.readByteArray(f.connection.outgoing.readInt().toLong()),
      pieceCount = 2))
    // A late block or reject for it is ignored, not a protocol violation.
    assertNull(f.receive(PeerMessage.Piece(0, 0, byteArrayOf(1, 2, 3))))
    assertNull(f.receive(PeerMessage.Reject(0, 0, 3)))
    assertFalse(f.connection.closed)
    f.exchange.close()
    assertEquals(0, f.blocks.allocated)
  }

  @Test
  fun fastPeer_servesAllowedFastPiecesWhileChoking() = runTest {
    val f = Fixture(fast = true)
    try {
      f.receive(PeerMessage.HaveAll)
      f.receive(PeerMessage.AllowedFast(1))
      assertFalse(f.exchange.canRequest(0))
      assertTrue(f.exchange.canRequest(1))
      assertNull(f.exchange.request(PeerMessage.Request(0, 0, 3)))
      assertNotNull(f.exchange.request(PeerMessage.Request(1, 0, 5)))
      // Allowed fast, the request is not released when the peer chokes us again.
      f.receive(PeerMessage.Control(PeerMessage.Signal.UNCHOKE))
      f.receive(PeerMessage.Control(PeerMessage.Signal.CHOKE))
      assertNull(f.exchange.nextReleaseMs())
      f.now = 8
      assertFalse(f.exchange.releaseChoked())
      assertEquals(1, f.exchange.pendingCount)
    } finally { f.exchange.close() }
  }

  @Test
  fun fastMessageWithoutNegotiation_failsThePeer() = runTest {
    val f = Fixture()
    assertFailsWith<IllegalArgumentException> { f.receive(PeerMessage.HaveAll) }
    assertTrue(f.connection.closed)
  }

  @Test
  fun deliveredBlockCreditSurvivesFrameDispatchAndConnectionShutdown() = runTest {
    val f = Fixture()
    f.ready()
    val ticket = assertNotNull(f.exchange.request(PeerMessage.Request(1, 2, 3)))
    val block = assertIs<PeerBlockExchange.Response.Block>(
      f.receive(PeerMessage.Piece(1, 2, byteArrayOf(1, 2, 3))))
    assertSame(ticket, block.ticket)
    assertEquals(0, f.frames.allocated)
    assertEquals(0, f.exchange.pendingCount)
    f.exchange.close()
    assertTrue(f.blocks.allocated > 0)
    assertTrue(block.bytes.contentEquals(byteArrayOf(1, 2, 3)))
    block.close()
    block.close()
    assertEquals(0, f.blocks.allocated)
  }

  @Test
  fun expiryAndMismatchedOrLateResponsesCloseTheStreamAndReleaseEveryPendingBlock() = runTest {
    for (failure in 0..3) {
      val f = Fixture()
      f.ready()
      assertNotNull(f.exchange.request(PeerMessage.Request(0, 0, 3)))
      assertNotNull(f.exchange.request(PeerMessage.Request(1, 0, 5)))
      when (failure) {
        0 -> { f.now = 10; assertTrue(f.exchange.expire()) }
        1 -> { f.now = -1; assertTrue(f.exchange.expire()) }
        2 -> assertFailsWith<IllegalArgumentException> {
          f.receive(PeerMessage.Piece(0, 0, byteArrayOf(1, 2)))
        }
        else -> {
          f.now = 10
          assertFailsWith<IllegalStateException> {
            f.receive(PeerMessage.Piece(0, 0, byteArrayOf(1, 2, 3)))
          }
        }
      }
      assertTrue(f.connection.closed)
      assertEquals(0, f.blocks.allocated)
      assertEquals(0, f.frames.allocated)
      assertEquals(0, f.exchange.pendingCount)
    }
  }

  @Test
  fun framePressureLeavesLiveRequestsAndUnsentCancelsUnchanged() = runTest {
    val f = Fixture()
    f.ready()
    val first = assertNotNull(f.exchange.request(PeerMessage.Request(0, 0, 3)))
    val retained = f.blocks.allocated
    val written = f.connection.outgoing.size
    val occupied = assertNotNull(f.frames.reserve(f.frames.capacity))
    try {
      assertNull(f.exchange.request(PeerMessage.Request(1, 0, 5)))
      assertFalse(f.exchange.cancel(first))
      assertEquals(1, f.exchange.pendingCount)
      assertEquals(retained, f.blocks.allocated)
      assertEquals(written, f.connection.outgoing.size)
      assertFalse(f.connection.closed)
    } finally { occupied.close() }
    val delivered = assertIs<PeerBlockExchange.Response.Block>(
      f.receive(PeerMessage.Piece(0, 0, byteArrayOf(1, 2, 3))))
    delivered.close()
    val next = assertNotNull(f.exchange.request(PeerMessage.Request(1, 0, 5)))
    assertTrue(f.exchange.cancel(next))
    assertNull(f.receive(PeerMessage.Piece(1, 0, byteArrayOf(1, 2, 3, 4, 5))))
    f.exchange.close()
    assertEquals(0, f.blocks.allocated)
    assertEquals(0, f.frames.allocated)
  }

  @Test
  fun failedCancelWriteClosesTheStreamInsteadOfDroppingResponseOwnership() = runTest {
    val f = Fixture()
    f.ready()
    val ticket = assertNotNull(f.exchange.request(PeerMessage.Request(0, 0, 3)))
    f.connection.writing = { throw okio.IOException("Partial cancel frame") }
    assertFailsWith<okio.IOException> { f.exchange.cancel(ticket) }
    assertTrue(f.connection.closed)
    assertEquals(0, f.blocks.allocated)
    assertEquals(0, f.frames.allocated)
    assertEquals(0, f.exchange.pendingCount)
  }

  @Test
  fun interestIsAdmittedRetriedAndDeduplicatedWithoutDependingOnRemoteChokeState() = runTest {
    val f = Fixture()
    val occupied = assertNotNull(f.frames.reserve(f.frames.capacity))
    assertFalse(f.exchange.setInterested(true))
    assertFalse(f.exchange.localInterested)
    assertEquals(0L, f.connection.outgoing.size)
    occupied.close()
    assertTrue(f.exchange.setInterested(true))
    assertTrue(f.exchange.localInterested)
    val written = f.connection.outgoing.size
    assertTrue(f.exchange.setInterested(true))
    assertEquals(written, f.connection.outgoing.size)
    assertTrue(f.exchange.setInterested(false))
    for (signal in listOf(PeerMessage.Signal.INTERESTED, PeerMessage.Signal.NOT_INTERESTED)) {
      val size = f.connection.outgoing.readInt()
      assertEquals(PeerMessage.Control(signal),
        PeerWire.decode(f.connection.outgoing.readByteArray(size.toLong())))
    }
    f.exchange.close()
    assertEquals(0, f.frames.allocated)
  }

  @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
  @Test
  fun interestWritesCannotConcealPendingDeadlinesOrReportLateSuccess() = runTest {
    val f = Fixture()
    f.ready()
    assertNotNull(f.exchange.request(PeerMessage.Request(0, 0, 3)))
    f.now = 8
    f.connection.writing = { awaitCancellation() }
    assertFailsWith<kotlinx.coroutines.TimeoutCancellationException> {
      f.exchange.setInterested(true)
    }
    assertEquals(2L, testScheduler.currentTime)
    assertTrue(f.connection.closed)
    assertFalse(f.exchange.localInterested)
    assertEquals(0, f.frames.allocated)
    assertEquals(0, f.blocks.allocated)
    val late = Fixture()
    late.connection.writing = { late.now = 10 }
    assertFailsWith<IllegalStateException> { late.exchange.setInterested(true) }
    assertTrue(late.connection.closed)
    assertFalse(late.exchange.localInterested)
    assertEquals(0, late.frames.allocated)
  }

  @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
  @Test
  fun backpressuredWritesUseTheOldestPendingDeadlineAndLateWritesCannotReturnTickets() = runTest {
    val f = Fixture()
    f.ready()
    assertNotNull(f.exchange.request(PeerMessage.Request(0, 0, 3)))
    f.now = 8
    f.connection.writing = { awaitCancellation() }
    assertFailsWith<kotlinx.coroutines.TimeoutCancellationException> {
      f.exchange.request(PeerMessage.Request(1, 0, 5))
    }
    assertEquals(2L, testScheduler.currentTime)
    assertTrue(f.connection.closed)
    assertEquals(0, f.blocks.allocated)
    assertEquals(0, f.frames.allocated)

    val late = Fixture()
    late.ready()
    late.connection.writing = { late.now = 10 }
    assertFailsWith<IllegalStateException> {
      late.exchange.request(PeerMessage.Request(0, 0, 3))
    }
    assertTrue(late.connection.closed)
    assertEquals(0, late.blocks.allocated)
  }
}
