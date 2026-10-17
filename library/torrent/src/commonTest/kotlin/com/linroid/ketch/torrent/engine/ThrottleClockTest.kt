package com.linroid.ketch.torrent.engine

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class ThrottleClockTest {
  private var now = 0L
  private val clock = ThrottleClock { now }

  @Test
  fun throttledMs_waitUnderWay_countsAsItPasses() {
    now = 2_000
    clock.enter()
    now += 1_000
    assertEquals(1_000, clock.throttledMs())
    now += 4_000
    assertEquals(5_000, clock.throttledMs())
    clock.exit()
    // Time nothing waits is not counted.
    now += 3_000
    assertEquals(5_000, clock.throttledMs())
  }

  @Test
  fun throttledMs_overlappingWaits_countOnce() {
    // Two peers wait side by side for 3 s; one started a second earlier, the other ends 2 s later.
    clock.enter()
    now += 1_000
    clock.enter()
    now += 3_000
    clock.exit()
    now += 2_000
    assertEquals(6_000, clock.throttledMs())
    clock.exit()
    // A second without a wait, then another wait.
    now += 1_000
    clock.waiting { now += 2_000 }
    assertEquals(8_000, clock.throttledMs())
  }

  @Test
  fun waiting_blockFails_endsTheWait() {
    assertFailsWith<IllegalStateException> {
      clock.waiting {
        now += 1_000
        error("Stopped")
      }
    }
    now += 1_000
    assertEquals(1_000, clock.throttledMs())
  }
}
