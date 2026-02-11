package com.linroid.kdown.engine

import com.linroid.kdown.core.engine.SpeedLimiter
import com.linroid.kdown.core.engine.TokenBucket
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.measureTime

class TokenBucketTest {

  // --- Tests that stay within burst size (no refill needed) ---

  @Test
  fun acquire_withinBurst_doesNotDelay() = runTest {
    val limiter = TokenBucket(1000)
    val elapsed = measureTime {
      limiter.acquire(100)
    }
    assertTrue(elapsed < 500.milliseconds, "Expected no delay, took $elapsed")
  }

  @Test
  fun acquire_zeroBytes_doesNotDelay() = runTest {
    val limiter = TokenBucket(100)
    val elapsed = measureTime {
      limiter.acquire(0)
    }
    assertTrue(
      elapsed < 500.milliseconds,
      "Expected no delay for 0 bytes, took $elapsed"
    )
  }

  @Test
  fun acquire_exactlyBurstSize_doesNotDelay() = runTest {
    val limiter = TokenBucket(1000, burstSize = 500)
    val elapsed = measureTime {
      limiter.acquire(500)
    }
    assertTrue(elapsed < 500.milliseconds, "Expected no delay, took $elapsed")
  }

  @Test
  fun veryLargeRate_withinBurst_doesNotDelay() = runTest {
    val limiter = TokenBucket(Long.MAX_VALUE / 2, burstSize = 1_000_000)
    val elapsed = measureTime {
      limiter.acquire(1_000_000)
    }
    assertTrue(
      elapsed < 500.milliseconds,
      "Very large rate should not delay, took $elapsed"
    )
  }

  @Test
  fun defaultBurstSize_is65536() = runTest {
    val limiter = TokenBucket(100_000)
    // Should be able to acquire up to default burst size (65536)
    // without delay since initial tokens = burstSize
    val elapsed = measureTime {
      limiter.acquire(65536)
    }
    assertTrue(
      elapsed < 500.milliseconds,
      "Expected no delay within default burst, took $elapsed"
    )
  }

  @Test
  fun unlimitedSpeedLimiter_neverDelays() = runTest {
    val limiter = SpeedLimiter.Unlimited
    val elapsed = measureTime {
      repeat(1000) {
        limiter.acquire(1_000_000)
      }
    }
    assertTrue(
      elapsed < 500.milliseconds,
      "Unlimited should never delay, took $elapsed"
    )
  }

  // --- Tests that require real time (refill behavior) ---
  // TokenBucket uses Clock.System.now() for refill timing,
  // so these tests use Dispatchers.Default for real wall-clock time.

  @Test
  fun acquire_exceedingBurst_delays() = runTest {
    withContext(Dispatchers.Default) {
      val limiter = TokenBucket(1000, burstSize = 100)
      // Drain the bucket
      limiter.acquire(100)
      // Next acquire should delay since tokens are exhausted
      val elapsed = measureTime {
        limiter.acquire(50)
      }
      assertTrue(elapsed >= 10.milliseconds, "Expected delay, took $elapsed")
    }
  }

  @Test
  fun burstSize_limitsInitialTokens() = runTest {
    withContext(Dispatchers.Default) {
      val limiter = TokenBucket(10000, burstSize = 50)
      // Acquiring exactly burstSize should succeed immediately
      limiter.acquire(50)
      // Now tokens are drained, next acquire should delay
      val elapsed = measureTime {
        limiter.acquire(50)
      }
      assertTrue(
        elapsed >= 1.milliseconds,
        "Expected delay after draining burstSize, took $elapsed"
      )
    }
  }

  @Test
  fun updateRate_increasesSpeed() = runTest {
    withContext(Dispatchers.Default) {
      val limiter = TokenBucket(100, burstSize = 100)
      // Drain bucket
      limiter.acquire(100)

      // Increase rate to much higher - refill will be faster
      limiter.updateRate(1_000_000)

      withTimeout(2.seconds) {
        limiter.acquire(50)
      }
    }
  }

  @Test
  fun acquire_completesWithinTimeout() = runTest {
    withContext(Dispatchers.Default) {
      val limiter = TokenBucket(10000)

      withTimeout(10.seconds) {
        repeat(10) {
          limiter.acquire(200)
        }
      }
    }
  }

  @Test
  fun acquire_multipleChunks_withinBurst_noDelay() = runTest {
    val limiter = TokenBucket(10000, burstSize = 1000)
    // Acquire multiple small chunks within burst
    val elapsed = measureTime {
      repeat(10) {
        limiter.acquire(100)
      }
    }
    assertTrue(
      elapsed < 500.milliseconds,
      "Expected no delay within burst, took $elapsed"
    )
  }

  @Test
  fun updateRate_canBeCalledMultipleTimes() = runTest {
    val limiter = TokenBucket(1000)
    limiter.updateRate(2000)
    limiter.updateRate(500)
    limiter.updateRate(10000)
    // Should not throw, just update rate
    limiter.acquire(100)
  }

  @Test
  fun verySmallBurst_stillWorks() = runTest {
    withContext(Dispatchers.Default) {
      val limiter = TokenBucket(1000, burstSize = 1)
      withTimeout(5.seconds) {
        limiter.acquire(1)
        limiter.acquire(1)
      }
    }
  }

  @Test
  fun acquire_largerThanBurst_completesViaPartialConsumption() = runTest {
    withContext(Dispatchers.Default) {
      // Request 200 bytes with burstSize of only 50.
      // TokenBucket must drain and refill multiple times.
      val limiter = TokenBucket(100_000, burstSize = 50)
      withTimeout(5.seconds) {
        limiter.acquire(200)
      }
    }
  }

  @Test
  fun acquire_negativeBytes_doesNotDelay() = runTest {
    val limiter = TokenBucket(1000)
    // Negative bytes should be treated as no-op (bytes <= 0)
    val elapsed = measureTime {
      limiter.acquire(-1)
    }
    assertTrue(
      elapsed < 500.milliseconds,
      "Expected no delay for negative bytes, took $elapsed"
    )
  }
}
