package com.linroid.ketch.core.engine

import kotlin.concurrent.Volatile

internal class DelegatingSpeedLimiter(
  @Volatile var delegate: SpeedLimiter = SpeedLimiter.Unlimited,
) : SpeedLimiter {
  override suspend fun acquire(bytes: Int) = delegate.acquire(bytes)
}
