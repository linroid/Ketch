package com.linroid.ketch.torrent.utp

import okio.Buffer
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class UtpPacketTest {
  @Test
  fun encodeDecode_roundTripsEveryField() {
    val packet = UtpPacket(UtpType.DATA, 0xfffe, -5, 123_456, 0xffffffffL, 0xffff, 7,
      selectiveAck = byteArrayOf(1, 0, 0, 0x80.toByte()), payload = byteArrayOf(9, 8, 7))
    val bytes = packet.encode()
    // Type 0, version 1; the selective ack extension; 20 header bytes, 6 extension, 3 payload.
    assertEquals(0x01, bytes[0].toInt())
    assertEquals(1, bytes[1].toInt())
    assertEquals(29, bytes.size)
    val decoded = assertNotNull(UtpPacket.decode(bytes))
    assertEquals(UtpType.DATA, decoded.type)
    assertEquals(0xfffe, decoded.connectionId)
    assertEquals(-5, decoded.timestamp)
    assertEquals(123_456, decoded.timestampDiff)
    assertEquals(0xffffffffL, decoded.window)
    assertEquals(0xffff, decoded.seq)
    assertEquals(7, decoded.ack)
    assertContentEquals(byteArrayOf(1, 0, 0, 0x80.toByte()), decoded.selectiveAck)
    assertContentEquals(byteArrayOf(9, 8, 7), decoded.payload)
  }

  @Test
  fun decode_skipsUnknownExtensions() {
    val header = UtpPacket(UtpType.STATE, 1, 0, 0, 100, 2, 3).encode()
    // Extension 2 (unknown, 3 bytes) chains to the selective ack.
    val bytes = Buffer().write(header, 0, 1).writeByte(2).write(header, 2, 18)
      .writeByte(1).writeByte(3).write(byteArrayOf(1, 2, 3))
      .writeByte(0).writeByte(4).write(byteArrayOf(4, 0, 0, 0))
      .write(byteArrayOf(42)).readByteArray()
    val decoded = assertNotNull(UtpPacket.decode(bytes))
    assertContentEquals(byteArrayOf(4, 0, 0, 0), decoded.selectiveAck)
    assertContentEquals(byteArrayOf(42), decoded.payload)
  }

  @Test
  fun decode_rejectsWhatIsNotUtp() {
    // A DHT message starts with 'd'.
    assertNull(UtpPacket.decode("d1:ad2:id20:aaaaaaaaaaaaaaaaaaaae1:q4:ping1:t2:aa1:y1:qe"
      .encodeToByteArray()))
    assertNull(UtpPacket.decode(ByteArray(19) { 0x41 }))
    val version2 = UtpPacket(UtpType.SYN, 1, 0, 0, 0, 1, 0).encode().also { it[0] = 0x42 }
    assertNull(UtpPacket.decode(version2))
    val truncated = UtpPacket(UtpType.STATE, 1, 0, 0, 0, 1, 0,
      selectiveAck = ByteArray(4)).encode().copyOf(23)
    assertNull(UtpPacket.decode(truncated))
    assertFalse(UtpPacket.looksLikeUtp("d1:a".encodeToByteArray()))
    assertTrue(UtpPacket.looksLikeUtp(UtpPacket(UtpType.SYN, 1, 0, 0, 0, 1, 0).encode()))
  }

  @Test
  fun sequenceArithmetic_wraps() {
    assertTrue(utpAfter(0, 0xffff))
    assertTrue(utpAfter(5, 0xfff0))
    assertFalse(utpAfter(0xffff, 0))
    assertFalse(utpAfter(7, 7))
    assertEquals(0, utpSeq(0xffff))
    assertEquals(0xffff, utpSeq(0, -1))
  }
}
