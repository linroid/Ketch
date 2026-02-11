package com.linroid.kdown.core.engine

internal class DelegatingSpeedLimiter(
  var delegate: SpeedLimiter = SpeedLimiter.Unlimited
) : SpeedLimiter {
  override suspend fun acquire(bytes: Int) = delegate.acquire(bytes)
}
