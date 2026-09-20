package com.linroid.ketch.torrent

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class PeerAvailabilityTest {
  @Test
  fun packedBitsPreserveByteBoundariesAndHaveUpdatesWithoutAliasingInputOrSnapshots() {
    val state = PeerProtocolState(10)
    val bits = byteArrayOf(129.toByte(), 64)
    state.received(PeerMessage.Bitfield(bits))
    bits.fill(0)
    for (index in 0 until 10) {
      if (index in listOf(0, 7, 9)) assertTrue(state.hasPiece(index))
      else assertFalse(state.hasPiece(index))
    }
    state.received(PeerMessage.Have(8))
    val snapshot = state.availabilitySnapshot()
    assertContentEquals(booleanArrayOf(true, false, false, false, false, false, false, true,
      true, true), snapshot)
    snapshot.fill(false)
    assertTrue(state.hasPiece(0) && state.hasPiece(8) && state.hasPiece(9))
    assertFalse(state.hasPiece(-1))
    assertFalse(state.hasPiece(10))
  }

  @Test
  fun malformedSpareBitsCannotPoisonAvailabilityOrConsumeTheInitialBitfieldSlot() {
    val state = PeerProtocolState(9)
    assertFailsWith<IllegalArgumentException> {
      state.received(PeerMessage.Bitfield(byteArrayOf(0, 1)))
    }
    assertFalse(state.hasPiece(8))
    state.received(PeerMessage.Bitfield(byteArrayOf(0, 128.toByte())))
    assertTrue(state.hasPiece(8))
    assertFailsWith<IllegalArgumentException> { state.received(PeerMessage.Have(9)) }
    assertFailsWith<IllegalArgumentException> {
      state.received(PeerMessage.Bitfield(byteArrayOf(0, 0)))
    }
    val empty = PeerProtocolState(0)
    empty.received(PeerMessage.Bitfield(ByteArray(0)))
    assertFalse(empty.hasPiece(0))
    assertContentEquals(BooleanArray(0), empty.availabilitySnapshot())
  }

  @Test
  fun millionPieceAvailabilityUsesTheWireBitfieldAndSupportsItsLastIndex() {
    val state = PeerProtocolState(1_000_000, explicitRejects = true)
    val bits = ByteArray(125_000)
    bits[bits.lastIndex] = 1
    state.received(PeerMessage.Bitfield(bits))
    assertTrue(state.hasPiece(999_999))
    assertFalse(state.hasPiece(999_998))
    state.received(PeerMessage.Have(999_998))
    state.received(PeerMessage.Control(PeerMessage.Signal.UNCHOKE))
    state.requested(PeerMessage.Request(999_999, 0, 1))
    assertTrue(state.received(PeerMessage.Piece(999_999, 0, byteArrayOf(1))))
    assertTrue(state.hasPiece(999_998))
  }
}
