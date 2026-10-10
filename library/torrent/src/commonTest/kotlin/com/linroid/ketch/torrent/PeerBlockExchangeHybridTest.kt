package com.linroid.ketch.torrent

import kotlinx.coroutines.test.runTest
import okio.Buffer
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** A hybrid's v1 route requests canonical v1 blocks and strips the padding they carry. */
class PeerBlockExchangeHybridTest {
  // Pieces of 32 KiB: a fills piece 0 and 7232 bytes of piece 1, b 5 bytes of piece 2, and c,
  // the last file, 20000 bytes of the short last piece 3.
  private val fixture = TorrentV2Fixture.build(listOf("a" to 40_000, "b" to 5, "c" to 20_000),
    hybrid = true)

  private class Connection : TorrentConnection {
    override val remote = PeerEndpoint("127.0.0.1", 1)
    val incoming = Buffer()
    val outgoing = Buffer()
    var closed = false
    override suspend fun readExactly(size: Int): ByteArray = incoming.readByteArray(size.toLong())
    override suspend fun write(bytes: ByteArray) { outgoing.write(bytes) }
    override fun close() { closed = true }
  }

  private inner class Exchange(mode: PeerIdentityHandshake.Mode) {
    val connection = Connection()
    val frames = TorrentBufferBudget(1_000_000)
    val blocks = TorrentBufferBudget(1_000_000)
    private val transport = PeerHashTransport(connection, PeerHashExchange(frames, { null }),
      frames, pieceCount = 4, hashMessages = mode == PeerIdentityHandshake.Mode.V2)
    val exchange = PeerBlockExchange(fixture.layout, transport, blocks, mode = mode)

    suspend fun receive(message: PeerMessage): PeerBlockExchange.Response? {
      connection.incoming.write(PeerWire.encode(message, pieceCount = 4))
      val frame = transport.read()
      return try { exchange.receive(frame) } finally { frame.close() }
    }

    suspend fun ready() {
      receive(PeerMessage.Bitfield(byteArrayOf(0xf0.toByte())))
      receive(PeerMessage.Control(PeerMessage.Signal.UNCHOKE))
    }

    /** Every request frame written so far, in order. */
    fun sent(): List<PeerMessage> = buildList {
      while (!connection.outgoing.exhausted()) {
        val size = connection.outgoing.readInt()
        add(PeerWire.decode(connection.outgoing.readByteArray(size.toLong()), pieceCount = 4))
      }
    }
  }

  @Test
  fun v1ModeRequestsCanonicalTailBlock() = runTest {
    val peer = Exchange(PeerIdentityHandshake.Mode.V1)
    peer.ready()
    val full = PeerMessage.Request(0, 16_384, 16_384)
    val tail = PeerMessage.Request(1, 0, 7_232)
    val tiny = PeerMessage.Request(2, 0, 5)
    val last = PeerMessage.Request(3, 16_384, 3_616)
    val tickets = listOf(full, tail, tiny, last).map { assertNotNull(peer.exchange.request(it)) }
    // File tails inside a v1 piece go out as the canonical 16 KiB blocks that cover the padding;
    // whole blocks, and the torrent's own short end, go out as they are.
    val wire = listOf(full, PeerMessage.Request(1, 0, 16_384), PeerMessage.Request(2, 0, 16_384),
      last)
    assertEquals(wire, peer.sent())
    assertEquals(listOf(full, tail, tiny, last), tickets.map { it.request })
    assertEquals(wire.sumOf { it.length * 2 + 256 }, peer.blocks.allocated)
    // The same scheduler block is not requested twice, and a cancel names the wire block.
    assertEquals(null, peer.exchange.request(tail))
    assertTrue(peer.exchange.cancel(tickets[1]))
    assertEquals(listOf(PeerMessage.Cancel(1, 0, 16_384)), peer.sent())
    peer.exchange.close()
    assertEquals(0, peer.blocks.allocated)
  }

  @Test
  fun v1ModeStripsZeroPadding() = runTest {
    val peer = Exchange(PeerIdentityHandshake.Mode.V1)
    peer.ready()
    val tail = PeerMessage.Request(1, 0, 7_232)
    val ticket = assertNotNull(peer.exchange.request(tail))
    val block = assertIs<PeerBlockExchange.Response.Block>(peer.receive(
      PeerMessage.Piece(1, 0, fixture.v1Piece(1).copyOf(16_384))))
    assertSame(ticket, block.ticket)
    // Only the file's own bytes reach the scheduler and the store.
    assertContentEquals(fixture.payloads[0].copyOfRange(32_768, 40_000), block.bytes)
    block.close()
    peer.exchange.close()
    assertEquals(0, peer.blocks.allocated)
  }

  @Test
  fun nonzeroPaddingFailsThePeer() = runTest {
    val peer = Exchange(PeerIdentityHandshake.Mode.V1)
    peer.ready()
    assertNotNull(peer.exchange.request(PeerMessage.Request(2, 0, 5)))
    val bytes = fixture.v1Piece(2).copyOf(16_384)
    bytes[16_000] = 1
    val failure = assertFailsWith<IllegalArgumentException> {
      peer.receive(PeerMessage.Piece(2, 0, bytes))
    }
    assertEquals("Nonzero hybrid padding", failure.message)
    // The violation tears the connection down and returns the block's credit.
    assertTrue(peer.connection.closed)
    assertEquals(0, peer.blocks.allocated)
  }

  @Test
  fun v2ModeKeepsFileTailBound() = runTest {
    val peer = Exchange(PeerIdentityHandshake.Mode.V2)
    peer.ready()
    // A v2 peer serves v2 pieces, which end at the file's tail.
    assertFailsWith<IllegalArgumentException> {
      peer.exchange.request(PeerMessage.Request(1, 0, 16_384))
    }
    val tail = PeerMessage.Request(1, 0, 7_232)
    assertNotNull(peer.exchange.request(tail))
    assertEquals(listOf(tail), peer.sent())
    val block = assertIs<PeerBlockExchange.Response.Block>(peer.receive(
      PeerMessage.Piece(1, 0, fixture.payloads[0].copyOfRange(32_768, 40_000))))
    assertEquals(7_232, block.bytes.size)
    block.close()
    peer.exchange.close()
    assertEquals(0, peer.blocks.allocated)
  }
}
