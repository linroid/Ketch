package com.linroid.ketch.torrent

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PeerFastExtensionTest {
  private val hash = InfoHash.fromBytes(ByteArray(20) { 3 })

  @Test
  fun handshake_fastBitRoundTripsInLastReservedByte() {
    val bytes = PeerWire.encodeHandshake(PeerHandshake(hash, ByteArray(20) { 7 }, true, true,
      fast = true))
    assertEquals(0x05, bytes[27].toInt())
    assertTrue(PeerWire.decodeHandshake(bytes, hash).fast)
    val plain = PeerWire.encodeHandshake(PeerHandshake(hash, ByteArray(20) { 7 }, true, true))
    assertFalse(PeerWire.decodeHandshake(plain, hash).fast)
  }

  @Test
  fun codec_roundTripsFastMessages() {
    val messages = listOf(PeerMessage.Suggest(3), PeerMessage.HaveAll, PeerMessage.HaveNone,
      PeerMessage.AllowedFast(9), PeerMessage.Reject(2, 16_384, 16_384))
    val ids = listOf(13, 14, 15, 17, 16)
    for ((message, id) in messages.zip(ids)) {
      val encoded = PeerWire.encode(message, pieceCount = 10)
      assertEquals(id, encoded[4].toInt())
      assertEquals(PeerWire.encodedSize(message, 10), encoded.size)
      assertEquals(message, PeerWire.decode(encoded.copyOfRange(4, encoded.size), pieceCount = 10))
    }
  }

  @Test
  fun codec_rejectsMalformedFastMessages() {
    // Have All with a body, Allowed Fast without its index, and an index past the torrent.
    assertFailsWith<IllegalArgumentException> { PeerWire.decode(byteArrayOf(14, 0)) }
    assertFailsWith<IllegalArgumentException> { PeerWire.decode(byteArrayOf(17, 0, 0)) }
    assertFailsWith<IllegalArgumentException> {
      PeerWire.decode(byteArrayOf(13, 0, 0, 0, 10), pieceCount = 10)
    }
  }

  @Test
  fun allowedFastSet_matchesBep6Vectors() {
    val address = byteArrayOf(80, 4, 4, 200.toByte())
    val infoHash = ByteArray(20) { 0xaa.toByte() }
    assertEquals(listOf(1059, 431, 808, 1217, 287, 376, 1188),
      allowedFastSet(address, infoHash, 1313, count = 7))
    assertEquals(listOf(1059, 431, 808, 1217, 287, 376, 1188, 353, 508),
      allowedFastSet(address, infoHash, 1313, count = 9))
  }

  @Test
  fun allowedFastSet_sharedWithinSlash24AndCappedByPieceCount() {
    val infoHash = ByteArray(20) { 0xaa.toByte() }
    assertEquals(allowedFastSet(byteArrayOf(80, 4, 4, 1), infoHash, 1313),
      allowedFastSet(byteArrayOf(80, 4, 4, 200.toByte()), infoHash, 1313))
    assertEquals(listOf(0, 1, 2), allowedFastSet(byteArrayOf(80, 4, 4, 1), infoHash, 3).sorted())
    assertEquals(emptyList(), allowedFastSet(byteArrayOf(80, 4, 4, 1), infoHash, 0))
  }

  @Test
  fun allowedFastAddress_acceptsIpv4AndMappedOnly() {
    assertContentEquals(byteArrayOf(10, 0, 0, 1), allowedFastAddress("10.0.0.1"))
    assertContentEquals(byteArrayOf(10, 0, 0, 1), allowedFastAddress("::ffff:10.0.0.1"))
    assertNull(allowedFastAddress("2001:db8::1"))
    assertNull(allowedFastAddress("example.org"))
  }

  @Test
  fun state_haveAllAndHaveNoneReplaceTheBitfieldOnce() {
    val all = PeerProtocolState(10, fast = true)
    all.received(PeerMessage.HaveAll)
    assertTrue((0 until 10).all(all::hasPiece))
    assertFailsWith<IllegalArgumentException> { all.received(PeerMessage.HaveNone) }
    val none = PeerProtocolState(10, fast = true)
    none.received(PeerMessage.HaveNone)
    assertFalse(none.hasPiece(0))
    assertFailsWith<IllegalArgumentException> {
      none.received(PeerMessage.Bitfield(pieceBitfield(BooleanArray(10))))
    }
    none.received(PeerMessage.Have(4))
    assertTrue(none.hasPiece(4))
  }

  @Test
  fun state_chokeKeepsRequestsUntilRejected() {
    val state = PeerProtocolState(2, fast = true)
    state.received(PeerMessage.HaveAll)
    state.received(PeerMessage.Control(PeerMessage.Signal.UNCHOKE))
    val request = PeerMessage.Request(0, 0, 16_384)
    state.requested(request)
    state.received(PeerMessage.Control(PeerMessage.Signal.CHOKE))
    assertEquals(setOf(request), state.requests)
    assertTrue(state.received(PeerMessage.Reject(0, 0, 16_384)))
    assertTrue(state.requests.isEmpty())
    // A second reject for the same block was never asked for.
    assertFailsWith<IllegalArgumentException> {
      state.received(PeerMessage.Reject(0, 0, 16_384))
    }
  }

  @Test
  fun state_lateRejectOfCanceledRequestIsIgnored() {
    val state = PeerProtocolState(2, fast = true)
    state.received(PeerMessage.HaveAll)
    state.received(PeerMessage.Control(PeerMessage.Signal.UNCHOKE))
    val request = PeerMessage.Request(1, 0, 16_384)
    state.requested(request)
    state.cancel(request)
    assertFalse(state.received(PeerMessage.Reject(1, 0, 16_384)))
  }

  @Test
  fun state_allowedFastPiecesAreRequestableWhileChoked() {
    val state = PeerProtocolState(4, fast = true)
    state.received(PeerMessage.HaveAll)
    state.received(PeerMessage.AllowedFast(2))
    assertTrue(state.canRequest(2))
    assertFalse(state.canRequest(1))
    state.requested(PeerMessage.Request(2, 0, 16_384))
    assertFailsWith<IllegalStateException> { state.requested(PeerMessage.Request(1, 0, 16_384)) }
  }

  @Test
  fun state_withoutFastIgnoresAllowedFast() {
    val state = PeerProtocolState(4)
    state.received(PeerMessage.AllowedFast(2))
    assertFalse(state.allowsAnyFast)
  }
}
