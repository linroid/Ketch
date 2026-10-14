package com.linroid.ketch.torrent.peer

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PeerServeCreditTest {
  private var now = 0L
  private val credit = PeerServeCredit(capacity = 1_000, nowMs = { now }, refillMs = 10_000)

  @Test
  fun tryCharge_pastTheShare_takesNothing() {
    assertTrue(credit.tryCharge(600))

    assertFalse(credit.tryCharge(600))
    assertEquals(400, credit.available)
  }

  @Test
  fun earn_paysTwiceThePayload_upToTheCapacity() {
    credit.tryCharge(1_000)

    credit.earn(100)
    assertEquals(200, credit.available)
    credit.earn(10_000)
    assertEquals(1_000, credit.available)
  }

  @Test
  fun refill_overTheWindow_restoresTheShareBitByBit() {
    credit.tryCharge(1_000)

    // Many small steps add up as one long one would.
    repeat(50) {
      now += 50
      credit.available
    }
    assertEquals(250, credit.available)
    now += 100_000
    assertEquals(1_000, credit.available)
  }

  @Test
  fun charge_mayOwe_andRefundGivesBack() {
    credit.charge(1_500)
    assertEquals(0, credit.available)
    assertFalse(credit.tryCharge(1))

    credit.refund(700)
    assertEquals(200, credit.available)
  }

  @Test
  fun capacityFor_holdsTwoPiecesAndRoomForHashes() {
    assertEquals(2L * 1_048_576 + 4L * 1_048_576, PeerServeCredit.capacityFor(1_048_576))
  }
}
