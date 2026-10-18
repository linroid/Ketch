package com.linroid.ketch.torrent.peer

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class PeerRedialTest {
  @Test
  fun neverDelivered_isGivenUpAfterThreeRetries() {
    assertEquals(listOf(5_000L, 15_000L, 30_000L),
      (1..3).map { PeerRedial.delayMs(it, delivered = false) })
    assertNull(PeerRedial.delayMs(4, delivered = false))
  }

  @Test
  fun delivered_isRetriedForAboutHalfAnHourThenGivenUp() {
    val delays = (1..PeerRedial.DELIVERED_RETRIES).map {
      PeerRedial.delayMs(it, delivered = true)
    }
    assertEquals(listOf(5_000L, 15_000L, 30_000L, 60_000L, 120_000L) + List(5) { 300_000L },
      delays)
    assertEquals(1_730_000L, delays.sumOf { it ?: 0 })
    assertNull(PeerRedial.delayMs(PeerRedial.DELIVERED_RETRIES + 1, delivered = true))
  }

  @Test
  fun foundAgain_bringsTheDialForwardAtMostEveryFiveMinutes() {
    // Last dialed at 100 s and due at 400 s: found at 110 s, it is dialed at 130 s.
    assertEquals(130_000L, PeerRedial.foundAgainAt(now = 110_000, dialedAt = 100_000,
      retryAt = 400_000, broughtForwardAt = null))
    // Not when that would be no sooner.
    assertNull(PeerRedial.foundAgainAt(now = 110_000, dialedAt = 100_000, retryAt = 130_000,
      broughtForwardAt = null))
    // Nor within five minutes of the last time a source brought it forward.
    assertNull(PeerRedial.foundAgainAt(now = 409_999, dialedAt = 300_000, retryAt = 600_000,
      broughtForwardAt = 110_000))
    assertEquals(330_000L, PeerRedial.foundAgainAt(now = 410_000, dialedAt = 300_000,
      retryAt = 600_000, broughtForwardAt = 110_000))
  }

  @Test
  fun foundAgain_gainingSecondsOrLess_leavesTheDialWhereItIs() {
    // During the 30 s wait after a dial refused in 5 ms: only those 5 ms sooner.
    assertNull(PeerRedial.foundAgainAt(now = 10_000, dialedAt = 0, retryAt = 30_005,
      broughtForwardAt = null))
    // Nor 3 s before the dial is due anyway.
    assertNull(PeerRedial.foundAgainAt(now = 57_000, dialedAt = 0, retryAt = 60_000,
      broughtForwardAt = null))
    // Five seconds or more count.
    assertEquals(30_000L, PeerRedial.foundAgainAt(now = 10_000, dialedAt = 0, retryAt = 35_000,
      broughtForwardAt = null))
    assertEquals(30_000L, PeerRedial.foundAgainAt(now = 55_000, dialedAt = 0, retryAt = 60_000,
      broughtForwardAt = null))
  }
}
