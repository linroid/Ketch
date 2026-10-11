package com.linroid.ketch.engine

import com.linroid.ketch.api.DownloadConfig
import com.linroid.ketch.core.engine.RetryPolicy
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RetryPolicyTest {
  private val config = DownloadConfig(retryDelayMs = 1_000, maxRetryDelayMs = 10_000)

  @Test
  fun cappedDelayMs_doublesUpToTheCap() {
    val policy = RetryPolicy(config)
    assertEquals(
      listOf(1_000L, 2_000L, 4_000L, 8_000L, 10_000L, 10_000L),
      (1..6).map { policy.cappedDelayMs(it) },
    )
  }

  @Test
  fun cappedDelayMs_manyAttempts_doesNotOverflow() {
    val policy = RetryPolicy(DownloadConfig(retryDelayMs = 1_000, maxRetryDelayMs = Long.MAX_VALUE))
    assertEquals(1_000L shl 40, policy.cappedDelayMs(41))
    assertEquals(Long.MAX_VALUE, policy.cappedDelayMs(10_000))
  }

  @Test
  fun cappedDelayMs_baseAboveCap_usesCap() {
    val policy = RetryPolicy(DownloadConfig(retryDelayMs = 5_000, maxRetryDelayMs = 2_000))
    assertEquals(2_000L, policy.cappedDelayMs(1))
  }

  @Test
  fun delayMs_staysWithinUpperHalfOfCappedDelay() {
    val policy = RetryPolicy(config, Random(42))
    repeat(200) {
      val attempt = it % 8 + 1
      val capped = policy.cappedDelayMs(attempt)
      val delay = policy.delayMs(attempt)
      assertTrue(delay in (capped - capped / 2)..capped, "delay $delay for capped $capped")
    }
  }

  @Test
  fun delayMs_isRandomized() {
    val policy = RetryPolicy(config, Random(7))
    val delays = List(20) { policy.delayMs(5) }.toSet()
    assertTrue(delays.size > 1)
  }

  @Test
  fun delayMs_extremesOfRandom_coverHalfToFull() {
    val low = RetryPolicy(config, FixedRandom(0))
    val high = RetryPolicy(config, FixedRandom(Long.MAX_VALUE))
    assertEquals(5_000L, low.delayMs(5))
    assertEquals(10_000L, high.delayMs(5))
  }

  @Test
  fun delayMs_zeroBase_isZero() {
    assertEquals(0L, RetryPolicy(DownloadConfig(retryDelayMs = 0)).delayMs(3))
  }

  @Test
  fun allows_limitedAndUnlimited() {
    val limited = RetryPolicy(DownloadConfig(retryCount = 2))
    assertTrue(limited.allows(1))
    assertFalse(limited.allows(2))
    val unlimited = RetryPolicy(DownloadConfig(retryCount = 0, unlimitedRetries = true))
    assertTrue(unlimited.allows(1_000_000))
  }

  /** Returns [value] clamped into the requested range. */
  private class FixedRandom(private val value: Long) : Random() {
    override fun nextBits(bitCount: Int): Int = 0
    override fun nextLong(until: Long): Long = value.coerceIn(0, until - 1)
  }
}
