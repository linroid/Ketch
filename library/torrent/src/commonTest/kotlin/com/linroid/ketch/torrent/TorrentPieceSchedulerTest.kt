package com.linroid.ketch.torrent

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TorrentPieceSchedulerTest {
  @Test
  fun scheduler_rarestFirst_releasesDisconnectedWorkAndBoundsEndgame() = runTest {
    val budget = TorrentBufferBudget(12)
    val scheduler = TorrentPieceScheduler(BooleanArray(3) { true }, BooleanArray(3), { 4 }, budget)
    scheduler.availability(1, booleanArrayOf(true, true, true))
    scheduler.availability(2, booleanArrayOf(false, true, true))
    assertEquals(0, scheduler.claim(1)?.index)
    assertEquals(1, scheduler.claim(2)?.index)
    scheduler.remove(1)
    assertEquals(4, budget.allocated)
    scheduler.availability(3, booleanArrayOf(true, true, true))
    assertEquals(0, scheduler.claim(3)?.index)
    scheduler.verified(0)
    scheduler.release(3)
    assertEquals(2, scheduler.claim(3)?.index)
    scheduler.availability(4, booleanArrayOf(false, true, false))
    assertEquals(1, scheduler.claim(4)?.index)
    scheduler.availability(5, booleanArrayOf(false, true, false))
    assertNull(scheduler.claim(5))
    scheduler.verified(1)
    assertTrue(scheduler.isVerified(1))
    for (peer in 2..5) scheduler.remove(peer)
    assertEquals(0, budget.allocated)
  }

  @Test
  fun announce_buildsAvailabilityIncrementallyAndIgnoresRepeatedIndexes() = runTest {
    val budget = TorrentBufferBudget(12)
    val scheduler = TorrentPieceScheduler(BooleanArray(2) { true }, BooleanArray(2), { 4 }, budget)
    // A peer may announce before sending any bitfield at all.
    assertNull(scheduler.claim(1))
    repeat(3) { scheduler.announce(1, 0) }
    assertEquals(0, scheduler.claim(1)?.index)
    scheduler.release(1)
    scheduler.announce(2, 1)
    scheduler.announce(3, 1)
    scheduler.availability(4, booleanArrayOf(true, true))
    // Piece 0 is held by peers 1 and 4; piece 1 by peers 2, 3 and 4. Rarest-first therefore
    // picks 0 — unless the three repeated announcements were each counted, which would make
    // piece 0 look like the most common one instead.
    assertEquals(0, scheduler.claim(4)?.index)
    for (peer in 1..4) scheduler.remove(peer)
    assertEquals(0, budget.allocated)
  }

  @Test
  fun budget_reservationCannotExceedCapacityAndCloseIsIdempotent() {
    val budget = TorrentBufferBudget(16)
    val first = assertNotNull(budget.reserve(12))
    assertNull(budget.reserve(5))
    val second = assertNotNull(budget.reserve(4))
    assertEquals(16, budget.allocated)
    first.close()
    first.close()
    assertEquals(4, budget.allocated)
    second.close()
    assertEquals(0, budget.allocated)
  }
}
