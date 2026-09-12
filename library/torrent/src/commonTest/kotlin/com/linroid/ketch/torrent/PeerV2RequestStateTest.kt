package com.linroid.ketch.torrent

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class PeerV2RequestStateTest {
  private val first = PeerMessage.Request(0, 0, 4)
  private val second = PeerMessage.Request(0, 4, 4)
  private fun state(maxPending: Int = 2) = PeerProtocolState(1, maxPending, explicitRejects = true)
    .also {
      it.received(PeerMessage.Bitfield(byteArrayOf(0x80.toByte())))
      it.received(PeerMessage.Control(PeerMessage.Signal.UNCHOKE))
    }

  @Test
  fun chokePreservesPendingResponsesUntilExplicitCompletionOrRejection() {
    val state = state()
    state.requested(first)
    state.requested(second)
    state.received(PeerMessage.Control(PeerMessage.Signal.CHOKE))
    assertEquals(setOf(first, second), state.requests)
    assertFailsWith<IllegalStateException> { state.requested(PeerMessage.Request(0, 8, 4)) }
    assertTrue(state.received(PeerMessage.Piece(0, 0, ByteArray(4))))
    assertFalse(state.received(PeerMessage.Reject(0, 4, 4)))
    assertTrue(state.requests.isEmpty())
    assertFailsWith<IllegalArgumentException> {
      state.received(PeerMessage.Piece(0, 0, ByteArray(4)))
    }
  }

  @Test
  fun canceledRequestsStillOccupyPipelineSlotsUntilTheirResponse() {
    val state = state(maxPending = 1)
    state.requested(first)
    state.cancel(first)
    assertEquals(setOf(first), state.requests)
    assertFailsWith<IllegalArgumentException> { state.requested(second) }
    assertFalse(state.received(PeerMessage.Piece(0, 0, ByteArray(4))))
    assertTrue(state.requests.isEmpty())
    assertFailsWith<IllegalArgumentException> {
      state.received(PeerMessage.Piece(0, 0, ByteArray(4)))
    }
    state.requested(second)
    state.cancel(second)
    assertFalse(state.received(PeerMessage.Reject(0, 4, 4)))
    assertTrue(state.requests.isEmpty())
  }

  @Test
  fun mismatchedRejectionsCannotReleaseAnOutstandingRequest() {
    val state = state()
    state.requested(first)
    assertFailsWith<IllegalArgumentException> { state.received(PeerMessage.Reject(0, 0, 3)) }
    assertEquals(setOf(first), state.requests)
    assertFalse(state.received(PeerMessage.Reject(0, 0, 4)))
    assertFailsWith<IllegalArgumentException> { state.received(PeerMessage.Reject(0, 0, 4)) }
  }

  @Test
  fun rejectUsesExactWireFieldsAndValidatesBlockBounds() {
    val encoded = PeerWire.encode(PeerMessage.Reject(1, 16_384, 4), pieceCount = 2)
    assertContentEquals(byteArrayOf(0, 0, 0, 13, 16,
      0, 0, 0, 1, 0, 0, 64, 0, 0, 0, 0, 4), encoded)
    assertEquals(PeerMessage.Reject(1, 16_384, 4),
      assertIs<PeerMessage.Reject>(PeerWire.decode(encoded.copyOfRange(4, encoded.size),
        pieceCount = 2)))
    for (message in listOf(PeerMessage.Reject(2, 0, 1), PeerMessage.Reject(0, -1, 1),
      PeerMessage.Reject(0, 0, 0), PeerMessage.Reject(0, 0, 16_385))) {
      assertFailsWith<IllegalArgumentException> { PeerWire.encode(message, pieceCount = 2) }
    }
    assertFailsWith<IllegalArgumentException> {
      PeerWire.decode(encoded.copyOfRange(4, encoded.size - 1), pieceCount = 2)
    }
  }

  @Test
  fun legacyChokeAndCanceledDuplicateBehaviorIsPreserved() {
    val legacy = PeerProtocolState(1)
    legacy.received(PeerMessage.Bitfield(byteArrayOf(0x80.toByte())))
    legacy.received(PeerMessage.Control(PeerMessage.Signal.UNCHOKE))
    legacy.requested(first)
    legacy.received(PeerMessage.Control(PeerMessage.Signal.CHOKE))
    assertTrue(legacy.requests.isEmpty())
    assertFalse(legacy.received(PeerMessage.Piece(0, 0, ByteArray(4))))
    assertTrue(legacy.received(PeerMessage.Reject(0, 0, 4)))
  }
}
