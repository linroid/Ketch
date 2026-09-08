package com.linroid.ketch.torrent

import kotlinx.coroutines.async
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TorrentRateLimiterTest {
  @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
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
}
