package com.linroid.kdown.engine

internal interface SpeedLimiter {
  suspend fun acquire(bytes: Int)

  companion object {
    val Unlimited: SpeedLimiter = object : SpeedLimiter {
      override suspend fun acquire(bytes: Int) { /* no-op */ }
    }
  }
}
