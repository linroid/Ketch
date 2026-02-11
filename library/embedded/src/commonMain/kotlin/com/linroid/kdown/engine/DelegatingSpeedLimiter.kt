package com.linroid.kdown.engine

internal class DelegatingSpeedLimiter(
  var delegate: SpeedLimiter = SpeedLimiter.Unlimited
) : SpeedLimiter {
  override suspend fun acquire(bytes: Int) = delegate.acquire(bytes)
}
