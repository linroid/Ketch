package com.linroid.ketch.torrent

import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.runTest
import okio.Buffer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
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

  private class Fixture(capacity: Int = 4096, pipeline: Int = 2) {
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
      maxPending = pipeline, timeoutMs = 10, clock = { now })
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
  fun chokeAndCancelRetainOwnershipUntilAResponseWithoutExtendingTheDeadline() = runTest {
    val f = Fixture(pipeline = 1)
    f.ready()
    val request = PeerMessage.Request(0, 0, 3)
    val ticket = assertNotNull(f.exchange.request(request))
    f.now = 4
    f.receive(PeerMessage.Control(PeerMessage.Signal.CHOKE))
    f.exchange.cancel(ticket)
    f.receive(PeerMessage.Control(PeerMessage.Signal.UNCHOKE))
    assertNull(f.exchange.request(PeerMessage.Request(1, 0, 5)))
    val written = f.connection.outgoing.size
    f.exchange.cancel(ticket)
    assertEquals(written, f.connection.outgoing.size)
    assertEquals(1, f.exchange.pendingCount)
    assertEquals(6L, f.exchange.nextDeadlineMs())
    assertTrue(f.blocks.allocated > 0)
    val discarded = assertIs<PeerBlockExchange.Response.Canceled>(
      f.receive(PeerMessage.Piece(0, 0, byteArrayOf(1, 2, 3))))
    assertSame(ticket, discarded.ticket)
    assertEquals(0, f.blocks.allocated)
    f.receive(PeerMessage.Control(PeerMessage.Signal.UNCHOKE))
    val replacement = assertNotNull(f.exchange.request(request))
    val before = f.connection.outgoing.size
    f.exchange.cancel(ticket)
    assertEquals(before, f.connection.outgoing.size)
    val rejected = assertIs<PeerBlockExchange.Response.Rejected>(
      f.receive(PeerMessage.Reject(0, 0, 3)))
    assertSame(replacement, rejected.ticket)
    f.exchange.close()
    assertEquals(0, f.blocks.allocated)
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
