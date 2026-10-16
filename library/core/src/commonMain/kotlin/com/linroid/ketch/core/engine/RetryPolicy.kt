package com.linroid.ketch.core.engine

import com.linroid.ketch.api.DownloadConfig
import kotlin.random.Random

/**
 * When a download retries, from [DownloadConfig]: whether another retry is allowed and how long
 * to wait before it.
 *
 * The delay of retry `n` in a row is [DownloadConfig.retryDelayMs] doubled `n - 1` times, capped
 * at [DownloadConfig.maxRetryDelayMs], then randomized between half of it and all of it ("equal
 * jitter"), so downloads that failed together spread their retries out.
 */
internal class RetryPolicy(
  private val config: DownloadConfig,
  private val random: Random = Random.Default,
) {
  /** Whether a download that already retried [retries] times in a row may retry again. */
  fun allows(retries: Int): Boolean = config.unlimitedRetries || retries < config.retryCount

  /** The capped delay before retry [attempt] (1-based), without its random part. */
  fun cappedDelayMs(attempt: Int): Long {
    require(attempt >= 1) { "attempt must be at least 1" }
    val cap = config.maxRetryDelayMs
    var delay = minOf(config.retryDelayMs, cap)
    repeat(attempt - 1) {
      if (delay >= cap) return cap
      delay = if (delay > cap / 2) cap else delay * 2
    }
    return delay
  }

  /** How long to wait before retry [attempt] (1-based): within the upper half of its delay. */
  fun delayMs(attempt: Int): Long {
    val capped = cappedDelayMs(attempt)
    val half = capped / 2
    return capped - half + random.nextLong(half + 1)
  }
}
