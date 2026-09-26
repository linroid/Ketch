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
