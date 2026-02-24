package com.linroid.ketch.ai.fetch

import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.time.TimeSource

/**
 * Rate limiter with per-domain and global request caps.
 *
 * Enforces a minimum interval between requests to the same domain
 * and a global maximum number of concurrent discovery requests.
 *
 * @param defaultDelayMs minimum delay between requests to the same
 *   domain in milliseconds (default 1000)
 * @param maxGlobalConcurrent maximum concurrent discovery requests
 *   across all domains (default 3)
 */
internal class RateLimiter(
  private val defaultDelayMs: Long = DEFAULT_DELAY_MS,
  private val maxGlobalConcurrent: Int = DEFAULT_MAX_CONCURRENT,
) {

  private val mutex = Mutex()
  private val domainTimestamps =
    mutableMapOf<String, TimeSource.Monotonic.ValueTimeMark>()
  private val domainDelays = mutableMapOf<String, Long>()
  private var activeRequests = 0

  /**
   * Sets a custom delay for [domain]
   * (e.g., from robots.txt Crawl-delay).
   */
  suspend fun setDomainDelay(domain: String, delayMs: Long) {
    mutex.withLock {
      domainDelays[domain] = delayMs
    }
  }

  /**
   * Acquires permission to make a request to [domain].
   * Suspends if the per-domain rate limit requires a wait.
   *
   * @return `true` if acquired, `false` if the global concurrent
   *   limit is reached
   */
  suspend fun acquire(domain: String): Boolean {
    val waitMs = mutex.withLock {
      if (activeRequests >= maxGlobalConcurrent) {
        return false
      }

      val delayMs = domainDelays[domain] ?: defaultDelayMs
      val lastRequest = domainTimestamps[domain]
      val wait = if (lastRequest != null) {
        val elapsed =
          lastRequest.elapsedNow().inWholeMilliseconds
        if (elapsed < delayMs) delayMs - elapsed else 0L
      } else {
        0L
      }
      wait
    }

    if (waitMs > 0) {
      delay(waitMs)
    }

    mutex.withLock {
      activeRequests++
      domainTimestamps[domain] = TimeSource.Monotonic.markNow()
    }
    return true
  }

  /**
   * Releases a request slot after a request completes.
   */
  suspend fun release() {
    mutex.withLock {
      activeRequests = (activeRequests - 1).coerceAtLeast(0)
    }
  }

  companion object {
    private const val DEFAULT_DELAY_MS = 1000L
    private const val DEFAULT_MAX_CONCURRENT = 3
  }
}
