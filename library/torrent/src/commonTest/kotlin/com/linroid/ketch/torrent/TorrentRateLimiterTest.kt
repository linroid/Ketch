package com.linroid.ketch.torrent

import kotlinx.coroutines.async
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class TorrentRateLimiterTest {
  @Test
  fun rejectedQueueAndBlockedBucketDoNotConsumeTheOtherBucket() = runTest {
    val global = TorrentRateLimiter(1) { testScheduler.currentTime }
    val task = TorrentRateLimiter(1) { testScheduler.currentTime }
    assertEquals(0L, global.requestDelay(16_384, task) { false })
    assertEquals(0L, global.requestDelay(16_384, task))
    val freshTask = TorrentRateLimiter(1) { testScheduler.currentTime }
    assertEquals(50L, global.requestDelay(16_384, freshTask))
    val freshGlobal = TorrentRateLimiter(1) { testScheduler.currentTime }
    assertEquals(50L, freshGlobal.requestDelay(16_384, task))
    assertEquals(0L, freshGlobal.requestDelay(16_384, freshTask))
  }

  @Test
  fun chargeLeavesDebtThatHoldsLaterRequestsUntilRepaid() = runTest {
    val global = TorrentRateLimiter(16_384) { testScheduler.currentTime }
    val task = TorrentRateLimiter() { testScheduler.currentTime }
    // Asking is free: a full bucket admits work as often as it is asked.
    assertTrue(global.canCharge(task))
    assertTrue(global.canCharge(task))
    // It admits a whole piece at once, but the bucket owes at most one refill window of it.
    global.charge(65_536, task)
    assertTrue(global.requestDelay(16_384, task) { false } > 0)
    assertFalse(global.canCharge(task))
    // A second refills the block that requests need; bulk work waits until the bucket would have
    // repaid the piece twice over.
    testScheduler.advanceTimeBy(1_000)
    assertEquals(0L, global.requestDelay(16_384, task) { false })
    assertFalse(global.canCharge(task))
    testScheduler.advanceTimeBy(6_999)
    assertFalse(global.canCharge(task))
    testScheduler.advanceTimeBy(1)
    assertTrue(global.canCharge(task))
    // The task's own bucket holds it back just the same, and unlimited buckets never do.
    val limited = TorrentRateLimiter(1) { testScheduler.currentTime }
    val unlimited = TorrentRateLimiter() { testScheduler.currentTime }
    assertTrue(unlimited.canCharge(limited))
    unlimited.charge(1L shl 24, limited)
    assertFalse(unlimited.canCharge(limited))
    val free = TorrentRateLimiter()
    val freeTask = TorrentRateLimiter()
    free.charge(1L shl 24, freeTask)
    assertTrue(free.canCharge(freeTask))
  }

  @Test
  fun bulkChargesLeaveMostOfTheLimitToBlockRequests() = runTest {
    // A peer asks for an uncached block proof whenever the bucket allows one, every millisecond,
    // and always first; one block request competes with it. The engine allows 512 KiB/s.
    for ((piece, share) in listOf(4L shl 20 to 0.95, 16_384L to 0.45)) {
      var now = 0L
      val global = TorrentRateLimiter(512 * 1024) { now }
      val task = TorrentRateLimiter() { now }
      var sent = 0L
      var nextRequest = 0L
      while (now < 20_000) {
        if (global.canCharge(task)) global.charge(piece, task)
        if (now >= nextRequest) {
          val delay = global.requestDelay(16_384, task)
          if (delay == 0L) sent += 16_384 else nextRequest = now + delay
        }
        now++
      }
      // Before proofs were bounded, a 4 MiB piece left blocks about 0.4% of the limit.
      val rate = sent / 20.0
      assertTrue(rate >= share * 512 * 1024, "piece=$piece: $rate bytes/s")
    }
  }

  @Test
  fun requestRetryDelayTracksRateWithoutAnArtificialPollingThroughputCeiling() = runTest {
    val global = TorrentRateLimiter(1024 * 1024) { testScheduler.currentTime }
    val task = TorrentRateLimiter() { testScheduler.currentTime }
    assertEquals(0L, global.requestDelay(16_384, task))
    assertEquals(16L, global.requestDelay(16_384, task))
    advanceTimeBy(16)
    assertEquals(0L, global.requestDelay(16_384, task))
    global.set(1)
    assertEquals(50L, global.requestDelay(16_384, task))
    global.set(0)
    assertEquals(0L, global.requestDelay(16_384, task))
  }

  @Test
  fun limit_enforcesSustainedRateAndRemovingLimitUnblocksWaiters() = runTest {
    val limiter = TorrentRateLimiter(1024) { testScheduler.currentTime }
    limiter.acquire(16_384)
    val transfer = async { limiter.acquire(1024) }
    advanceTimeBy(900)
    assertFalse(transfer.isCompleted)
    advanceTimeBy(150)
    runCurrent()
    assertTrue(transfer.isCompleted)
    limiter.set(1)
    val waiting = async { limiter.acquire(16_384) }
    advanceTimeBy(100)
    assertFalse(waiting.isCompleted)
    limiter.set(0)
    advanceTimeBy(50)
    runCurrent()
    assertTrue(waiting.isCompleted)
  }

  @Test
  fun acquire_sustainsRatesAboveOneBlockPerPoll() = runTest {
    val rate = 4L * 1024 * 1024
    val global = TorrentRateLimiter(rate) { testScheduler.currentTime }
    val task = TorrentRateLimiter(rate) { testScheduler.currentTime }
    val start = testScheduler.currentTime
    // 1 MiB through two stacked 4 MiB/s limits, as a torrent task's session and engine limits.
    repeat(64) {
      global.acquire(16_384)
      task.acquire(16_384)
    }
    val elapsed = testScheduler.currentTime - start
    // ~246 ms after the initial 16 KiB burst; a 16 KiB-per-50 ms bucket needed over 3 s.
    assertTrue(elapsed in 200L..300L, "1 MiB at 4 MiB/s took $elapsed ms")
  }
}
