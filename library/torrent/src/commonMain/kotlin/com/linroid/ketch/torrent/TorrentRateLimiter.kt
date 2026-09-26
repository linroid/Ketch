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
  private var tokens = MAX_BLOCK.toDouble()
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
    require(bytes in 1..MAX_BLOCK && task !== this)
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
    tokens = minOf(capacity(currentRate),
      tokens + (now - updated).coerceAtLeast(0) * currentRate.toDouble() / 1000.0)
    updated = now
  }

  /** Holds 100 ms of traffic so a waiter sleeping up to [MAX_WAIT_MS] never loses refill. */
  private fun capacity(currentRate: Long): Double =
    maxOf(MAX_BLOCK.toDouble(), currentRate / 10.0)

  private fun delayFor(bytes: Int, currentRate: Long): Long {
    if (currentRate == 0L || tokens >= bytes) return 0
    return shortfallMs(bytes - tokens, currentRate)
  }

  private fun shortfallMs(missing: Double, currentRate: Long): Long =
    ceil(missing * 1000 / currentRate).toLong().coerceIn(1, MAX_WAIT_MS)

  suspend fun acquire(bytes: Int) {
    require(bytes >= 0)
    var remaining = bytes
    while (remaining > 0) {
      val wait = mutex.withLock {
        val currentRate = rate.load()
        if (currentRate == 0L) return
        refill(currentRate)
        val consumed = minOf(remaining, tokens.toInt().coerceAtLeast(0))
        tokens -= consumed
        remaining -= consumed
        if (remaining == 0) return
        val next = minOf(remaining.toDouble(), capacity(currentRate))
        shortfallMs(next - tokens, currentRate)
      }
      delay(wait)
    }
  }

  private companion object {
    const val MAX_BLOCK = 16_384
    const val MAX_WAIT_MS = 50L
  }
}
