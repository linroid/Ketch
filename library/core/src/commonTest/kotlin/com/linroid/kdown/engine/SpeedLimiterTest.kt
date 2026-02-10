package com.linroid.kdown.engine

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.time.measureTime

class SpeedLimiterTest {

  @Test
  fun unlimited_acquire_isNoOp() = runTest {
    val limiter = SpeedLimiter.Unlimited
    val elapsed = measureTime {
      limiter.acquire(1_000_000)
    }
    assertTrue(
      elapsed.inWholeMilliseconds < 50,
      "Unlimited acquire should be instant, took $elapsed"
    )
  }

  @Test
  fun unlimited_multipleAcquires_noDelay() = runTest {
    val limiter = SpeedLimiter.Unlimited
    val elapsed = measureTime {
      repeat(10_000) {
        limiter.acquire(8192)
      }
    }
    assertTrue(
      elapsed.inWholeMilliseconds < 100,
      "Unlimited acquire loop should be fast, took $elapsed"
    )
  }

  @Test
  fun unlimited_zeroBytes_noDelay() = runTest {
    val limiter = SpeedLimiter.Unlimited
    val elapsed = measureTime {
      limiter.acquire(0)
    }
    assertTrue(
      elapsed.inWholeMilliseconds < 50,
      "Unlimited acquire(0) should be instant, took $elapsed"
    )
  }
}
