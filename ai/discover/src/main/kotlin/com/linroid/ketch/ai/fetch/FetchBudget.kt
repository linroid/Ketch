package com.linroid.ketch.ai.fetch

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Request and byte allowance for one discovery run, so an agent that
 * loops cannot fetch without bound.
 *
 * @param maxRequests page fetches and HEAD requests allowed in the run
 * @param maxBytes page body bytes allowed in the run
 */
internal class FetchBudget(
  val maxRequests: Int,
  val maxBytes: Long,
) {

  init {
    require(maxRequests >= 0) { "maxRequests must not be negative" }
    require(maxBytes >= 0) { "maxBytes must not be negative" }
  }

  private val mutex = Mutex()
  private var requestsUsed = 0
  private var bytesLeft = maxBytes

  /** Whether a request is left, without taking it. */
  suspend fun hasRequestLeft(): Boolean = mutex.withLock { requestsUsed < maxRequests }

  /** Whether any body bytes are left, without reserving them. */
  suspend fun hasBytesLeft(): Boolean = mutex.withLock { bytesLeft > 0 }

  /** Takes one request, or returns `false` when none are left. */
  suspend fun tryTakeRequest(): Boolean = mutex.withLock {
    if (requestsUsed >= maxRequests) return@withLock false
    requestsUsed++
    true
  }

  /**
   * Reserves up to [wanted] body bytes and returns how many were
   * reserved: `0` once the byte allowance is spent. Hand back what a
   * fetch did not read with [returnBytes].
   */
  suspend fun reserveBytes(wanted: Long): Long = mutex.withLock {
    val granted = wanted.coerceIn(0, bytesLeft)
    bytesLeft -= granted
    granted
  }

  /** Returns [bytes] reserved with [reserveBytes] that were not read. */
  suspend fun returnBytes(bytes: Long) {
    if (bytes <= 0) return
    mutex.withLock { bytesLeft = (bytesLeft + bytes).coerceAtMost(maxBytes) }
  }
}
