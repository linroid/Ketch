package com.linroid.ketch.core.engine

internal interface SpeedLimiter {
  suspend fun acquire(bytes: Int)

  /**
   * Acquires [bytes] for [owner]: a limiter several owners share, as every task shares the
   * global one, divides its rate between the owners waiting rather than between their calls.
   * Defaults to [acquire].
   */
  suspend fun acquire(bytes: Int, owner: Any) = acquire(bytes)

  companion object {
    val Unlimited: SpeedLimiter = object : SpeedLimiter {
      override suspend fun acquire(bytes: Int) { /* no-op */ }
    }
  }
}
