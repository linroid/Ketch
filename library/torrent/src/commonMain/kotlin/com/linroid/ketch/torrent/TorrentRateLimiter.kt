package com.linroid.ketch.torrent

import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.concurrent.atomics.AtomicLong
import kotlin.concurrent.atomics.ExperimentalAtomicApi

/** Live rate changes take effect within 50 ms, including removing a limit while waiting. */
@OptIn(ExperimentalAtomicApi::class)
internal class TorrentRateLimiter(
  bytesPerSecond: Long = 0,
  private val nowMs: () -> Long = monotonicClock(),
) {
  private val rate = AtomicLong(bytesPerSecond)
  private val mutex = Mutex()
  private var tokens = 16_384.0
  private var updated = nowMs()

  init { require(bytesPerSecond >= 0) }

  fun set(bytesPerSecond: Long) {
    require(bytesPerSecond >= 0)
    rate.store(bytesPerSecond)
  }

  suspend fun acquire(bytes: Int) {
    require(bytes >= 0)
    var remaining = bytes
    while (remaining > 0) {
      val consumed = mutex.withLock {
        val currentRate = rate.load()
        if (currentRate == 0L) return
        val now = nowMs()
        tokens = minOf(16_384.0, tokens + (now - updated).coerceAtLeast(0) * currentRate.toDouble() / 1000.0)
        updated = now
        minOf(remaining, tokens.toInt()).also { tokens -= it }
      }
      remaining -= consumed
      if (remaining > 0) delay(50)
    }
  }
}
