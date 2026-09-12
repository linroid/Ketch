package com.linroid.ketch.torrent

import kotlinx.coroutines.test.runTest
import okio.Buffer
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

class PeerFrameLimitsTest {
  private class Connection(val input: Buffer) : TorrentConnection {
    override val remote = PeerEndpoint("127.0.0.1", 1)
    val reads = mutableListOf<Int>()
    override suspend fun readExactly(size: Int): ByteArray {
      reads += size
      return input.readByteArray(size.toLong())
    }
    override suspend fun write(bytes: ByteArray) = Unit
    override fun close() = Unit
  }

  @Test
  fun desktopBitfieldsReachTheLastPieceWithExactSizeAndPadding() = runTest {
    for (count in listOf(999_999, 1_000_000)) {
      val bits = ByteArray((count + 7) / 8)
      bits[0] = 0x80.toByte()
      bits[bits.lastIndex] = if (count % 8 == 0) 1 else 2
      val encoded = PeerWire.encode(PeerMessage.Bitfield(bits), pieceCount = count)
      val connection = Connection(Buffer().write(encoded))
      val decoded = assertIs<PeerMessage.Bitfield>(PeerWire(connection, pieceCount = count).read())
      assertContentEquals(bits, decoded.bytes)
      val state = PeerProtocolState(count)
      state.received(decoded)
      assertTrue(state.hasPiece(0) && state.hasPiece(count - 1))
      assertEquals(listOf(4, 1, bits.size), connection.reads)
      assertFailsWith<IllegalArgumentException> {
        PeerWire.decode(encoded.copyOfRange(4, encoded.size))
      }
      assertFailsWith<IllegalArgumentException> {
        PeerWire.encode(PeerMessage.Have(count), pieceCount = count)
      }
      if (count % 8 != 0) {
        bits[bits.lastIndex] = 3
        assertFailsWith<IllegalArgumentException> {
          PeerWire.encode(PeerMessage.Bitfield(bits), pieceCount = count)
        }
      }
    }
    assertFailsWith<IllegalArgumentException> { PeerFrameLimits(1_000_001) }
  }

  @Test
  fun unrelatedOversizedFramesAndWrongBitfieldsFailBeforeBodyReads() = runTest {
    for ((size, id) in listOf(125_001 to 20, 125_001 to 22, 125_000 to 5)) {
      val connection = Connection(Buffer().writeInt(size).writeByte(id))
      assertFailsWith<IllegalArgumentException> {
        PeerWire(connection, pieceCount = 1_000_000).read()
      }
      assertEquals(listOf(4, 1), connection.reads)
    }
    assertFailsWith<IllegalArgumentException> {
      PeerWire.encode(PeerMessage.Unknown(24, ByteArray(100_000)), pieceCount = 1_000_000)
    }
    assertFailsWith<IllegalArgumentException> {
      PeerWire.encode(PeerMessage.Extended(0, ByteArray(100_000)), pieceCount = 1_000_000)
    }
  }

  @Test
  fun transportAdmitsAndRetainsTheEntireDesktopBitfield() = runTest {
    val bits = ByteArray(125_000)
    val encoded = PeerWire.encode(PeerMessage.Bitfield(bits), pieceCount = 1_000_000)
    val connection = Connection(Buffer().write(encoded))
    val budget = TorrentBufferBudget(600_000)
    val exchange = PeerHashExchange(TorrentBufferBudget(32_768), { null })
    val transport = PeerHashTransport(connection, exchange, budget, pieceCount = 1_000_000)
    val frame = transport.read()
    try {
      assertContentEquals(bits, assertIs<PeerMessage.Bitfield>(frame.message).bytes)
      assertTrue(budget.allocated >= 4 * 125_001)
    } finally { frame.close(); transport.close() }
    assertEquals(0, budget.allocated)
    val wrong = Connection(Buffer().writeInt(125_001).writeByte(22))
    val rejected = PeerHashTransport(wrong, exchange, budget, pieceCount = 1_000_000)
    assertFailsWith<IllegalArgumentException> { rejected.read() }
    assertEquals(listOf(4, 1), wrong.reads)
    assertEquals(0, budget.allocated)
    rejected.close()
  }
}
