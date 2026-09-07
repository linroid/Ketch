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
