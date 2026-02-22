package com.linroid.ketch.engine

import com.linroid.ketch.core.engine.SpeedLimiter
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
}
