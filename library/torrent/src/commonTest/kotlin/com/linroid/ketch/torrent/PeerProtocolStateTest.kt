package com.linroid.ketch.torrent

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PeerProtocolStateTest {
  @Test
  fun chokeRetryAcceptsOneResponseAndIgnoresItsLateDuplicate() {
    val state = PeerProtocolState(1, maxPending = 1)
    val request = PeerMessage.Request(0, 0, 16)
    val response = PeerMessage.Piece(0, 0, ByteArray(16))
    state.received(PeerMessage.Have(0))
    state.received(PeerMessage.Control(PeerMessage.Signal.UNCHOKE))
    state.requested(request)
    state.received(PeerMessage.Control(PeerMessage.Signal.CHOKE))
    state.received(PeerMessage.Control(PeerMessage.Signal.UNCHOKE))
    state.requested(request)
    assertTrue(state.received(response))
    assertFalse(state.received(response))
    assertFailsWith<IllegalArgumentException> {
      state.received(PeerMessage.Piece(0, 16, ByteArray(16)))
    }
  }

  @Test
  fun request_enforcesChokingAvailabilityAndPipeline() {
    val state = PeerProtocolState(2, maxPending = 1)
    val first = PeerMessage.Request(0, 0, 16)
    assertFailsWith<IllegalStateException> { state.requested(first) }
    state.received(PeerMessage.Control(PeerMessage.Signal.UNCHOKE))
    assertFailsWith<IllegalArgumentException> { state.requested(first) }
    state.received(PeerMessage.Bitfield(byteArrayOf(-64)))
    state.requested(first)
    assertFailsWith<IllegalArgumentException> { state.requested(PeerMessage.Request(1, 0, 16)) }
    assertFailsWith<IllegalArgumentException> {
      state.received(PeerMessage.Piece(0, 0, ByteArray(15)))
    }
    assertTrue(state.received(PeerMessage.Piece(0, 0, ByteArray(16))))
    assertTrue(state.requests.isEmpty())
  }

  @Test
  fun choke_clearsPipelineAndIgnoresLateCanceledBlocks() {
    val state = PeerProtocolState(1)
    state.received(PeerMessage.Have(0))
    state.received(PeerMessage.Control(PeerMessage.Signal.UNCHOKE))
    state.requested(PeerMessage.Request(0, 0, 16))
    state.received(PeerMessage.Control(PeerMessage.Signal.CHOKE))
    assertEquals(emptySet(), state.requests)
    assertFalse(state.received(PeerMessage.Piece(0, 0, ByteArray(16))))
    assertFailsWith<IllegalArgumentException> {
      state.received(PeerMessage.Piece(0, 16, ByteArray(16)))
    }
    assertFailsWith<IllegalArgumentException> {
      state.received(PeerMessage.Bitfield(byteArrayOf(-128)))
    }
  }
}
