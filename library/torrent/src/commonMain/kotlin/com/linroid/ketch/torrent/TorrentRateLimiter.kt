package com.linroid.ketch.torrent

import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.concurrent.atomics.AtomicLong
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.math.ceil

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

  /**
   * Nonblocking block-request admission. Zero consumes both buckets; a positive delay consumes
   * neither. Queue rejection also consumes neither. The admission callback must not suspend or
   * reenter a limiter. Always invoke on the engine/global bucket with a distinct task-local bucket so lock
   * ordering remains global then task. Waiting sessions poll live rate changes within 50 ms.
   */
  suspend fun requestDelay(
    bytes: Int,
    task: TorrentRateLimiter,
    admit: () -> Boolean = { true },
  ): Long {
    require(bytes in 1..16_384 && task !== this)
    return mutex.withLock {
      task.mutex.withLock {
        val globalRate = rate.load()
        val taskRate = task.rate.load()
        refill(globalRate)
        task.refill(taskRate)
        val delay = maxOf(delayFor(bytes, globalRate), task.delayFor(bytes, taskRate))
        if (delay == 0L && admit()) {
          if (globalRate != 0L) tokens -= bytes
          if (taskRate != 0L) task.tokens -= bytes
        }
        delay
      }
    }
  }

  private fun refill(currentRate: Long) {
    val now = nowMs()
    tokens = minOf(16_384.0,
      tokens + (now - updated).coerceAtLeast(0) * currentRate.toDouble() / 1000.0)
    updated = now
  }

  private fun delayFor(bytes: Int, currentRate: Long): Long {
    if (currentRate == 0L || tokens >= bytes) return 0
    return ceil((bytes - tokens) * 1000 / currentRate).toLong().coerceIn(1, 50)
  }

  suspend fun acquire(bytes: Int) {
    require(bytes >= 0)
    var remaining = bytes
    while (remaining > 0) {
      val consumed = mutex.withLock {
        val currentRate = rate.load()
        if (currentRate == 0L) return
        refill(currentRate)
        minOf(remaining, tokens.toInt()).also { tokens -= it }
      }
      remaining -= consumed
      if (remaining > 0) delay(50)
    }
  }
}
