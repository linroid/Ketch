package com.linroid.kdown.core.engine

import com.linroid.kdown.core.log.KDownLogger
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.time.Clock
import kotlin.time.Instant

internal class TokenBucket(
  bytesPerSecond: Long,
  private val burstSize: Long = 65536
) : SpeedLimiter {
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
        KDownLogger.v("TokenBucket") {
          "Throttling: waiting ${waitMs}ms for $remaining bytes"
        }
        delay(waitMs)
      }
    }
  }

  fun updateRate(newBytesPerSecond: Long) {
    rate = newBytesPerSecond.toDouble()
    KDownLogger.d("TokenBucket") {
      "Rate updated to $newBytesPerSecond bytes/sec"
    }
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
