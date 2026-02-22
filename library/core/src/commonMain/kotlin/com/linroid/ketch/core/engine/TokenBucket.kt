package com.linroid.ketch.core.engine

import com.linroid.ketch.api.log.KetchLogger
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.time.Clock
import kotlin.time.Instant

internal class TokenBucket(
  bytesPerSecond: Long,
  private val burstSize: Long = 65536,
) : SpeedLimiter {
  private val log = KetchLogger("TokenBucket")
  private val mutex = Mutex()
  private var tokens: Long = burstSize
  private var lastRefillTime: Instant = Clock.System.now()
  private var rate: Double = bytesPerSecond.toDouble()

  override suspend fun acquire(bytes: Int) {
    if (bytes <= 0) return
    var remaining = bytes.toLong()
    while (remaining > 0) {
      val waitMs = mutex.withLock {
        refill()
        if (tokens > 0) {
          val consumed = minOf(tokens, remaining)
          tokens -= consumed
          remaining -= consumed
          0L
        } else {
          val needed = minOf(remaining, burstSize)
          (needed / rate * 1000).toLong().coerceAtLeast(1)
        }
      }
      if (waitMs > 0) {
        log.v { "Throttling: waiting ${waitMs}ms for $remaining bytes" }
        delay(waitMs)
      }
    }
  }

  suspend fun updateRate(newBytesPerSecond: Long) = mutex.withLock {
    rate = newBytesPerSecond.toDouble()
    log.d { "Rate updated to $newBytesPerSecond bytes/sec" }
  }

  private fun refill() {
    val now = Clock.System.now()
    val elapsed = now - lastRefillTime
    val elapsedMs = elapsed.inWholeMilliseconds
    if (elapsedMs <= 0) return
    val newTokens = (elapsedMs * rate / 1000).toLong()
    if (newTokens > 0) {
      tokens = (tokens + newTokens).coerceAtMost(burstSize)
      lastRefillTime = now
    }
  }
}
