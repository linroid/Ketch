package com.linroid.ketch.ai.fetch

import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlin.time.TimeSource

/**
 * Spaces requests to the same host and caps how many requests run at
 * once across all hosts.
 *
 * One instance is shared by every discovery run, so concurrent runs
 * are polite to a host together.
 *
 * @param delayMs minimum time between the starts of two requests to
 *   the same host, in milliseconds
 * @param maxConcurrent maximum requests in flight across all hosts
 * @param nowMs monotonic clock in milliseconds
 */
internal class RateLimiter(
  private val delayMs: Long = DEFAULT_DELAY_MS,
  maxConcurrent: Int = DEFAULT_MAX_CONCURRENT,
  private val nowMs: () -> Long = monotonicClock(),
) {

  init {
    require(delayMs >= 0) { "delayMs must not be negative" }
    require(maxConcurrent >= 1) { "maxConcurrent must be at least 1" }
  }

  private val slots = Semaphore(maxConcurrent)
  private val mutex = Mutex()
  private val nextStart = mutableMapOf<String, Long>()

  /**
   * Runs [block] once [host] may be contacted again and a request slot
   * is free, suspending until both hold.
   */
  suspend fun <T> withPermit(host: String, block: suspend () -> T): T {
    val key = host.lowercase()
    while (true) {
      // The host's start time is reserved only while holding a slot, so
      // callers that queued for a slot cannot start back to back. No
      // slot is held while waiting out the spacing.
      val waitMs = slots.withPermit {
        val wait = reserve(key)
        if (wait == 0L) return block()
        wait
      }
      delay(waitMs)
    }
  }

  /**
   * Reserves a start for [key] now and returns `0`, or returns how long
   * to wait before [key] may start.
   */
  private suspend fun reserve(key: String): Long = mutex.withLock {
    val now = nowMs()
    // Hosts whose spacing has passed need no entry; dropping them
    // keeps the map from growing for the life of the app.
    nextStart.values.removeAll { it <= now }
    val next = nextStart[key]
    if (next != null) {
      next - now
    } else {
      nextStart[key] = now + delayMs
      0L
    }
  }

  companion object {
    private const val DEFAULT_DELAY_MS = 1000L
    private const val DEFAULT_MAX_CONCURRENT = 3
  }
}

private fun monotonicClock(): () -> Long {
  val origin = TimeSource.Monotonic.markNow()
  return { origin.elapsedNow().inWholeMilliseconds }
}
