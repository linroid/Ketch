package com.linroid.ketch.core.mirror

import com.linroid.ketch.api.KetchError
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeMark
import kotlin.time.TimeSource

/**
 * The mirrors of one download run and how each fares. Transfers [acquire] the least busy mirror
 * that is not backing off, preferring lower priorities, so connections spread over the best
 * mirrors. A mirror that fails backs off, doubling from [BASE_BACKOFF], and is dropped after
 * [MAX_FAILURES] failures in a row or at once for a failure retrying cannot fix, such as a 404.
 * The last mirror left is never dropped or backed off here: its failure goes to Ketch's retry
 * policy, as a plain HTTP download's does.
 *
 * Mirrors are named by their index in priority order, never by host, in logs.
 */
internal class MirrorPool(
  mirrors: List<Mirror>,
  private val timeSource: TimeSource = TimeSource.Monotonic,
) {
  private class Entry(val mirror: Mirror, val index: Int) {
    var active = 0
    var failures = 0
    var retryAt: TimeMark? = null
    var dropped = false
  }

  private val entries = mirrors.sortedBy { it.priority }.mapIndexed { i, m -> Entry(m, i) }
  private val mutex = Mutex()
  private var lastError: Throwable? = null

  init {
    require(entries.isNotEmpty()) { "No mirror to download from" }
  }

  /** The mirror at [index], in priority order. */
  fun mirror(index: Int): Mirror = entries[index].mirror

  /** How many mirrors are not dropped. */
  suspend fun usable(): Int = mutex.withLock { entries.count { !it.dropped } }

  /**
   * Takes the least busy usable mirror and returns its index, waiting while every usable mirror
   * backs off. Pair each call with [release].
   *
   * @throws Throwable the last failure when every mirror was dropped
   */
  suspend fun acquire(): Int {
    while (true) {
      val wait = mutex.withLock {
        val ready = entries.filter { !it.dropped && it.retryAt?.hasPassedNow() != false }
        val best = ready.minWithOrNull(compareBy<Entry>({ it.active }, { it.index }))
        if (best != null) {
          best.active++
          best.retryAt = null
          return best.index
        }
        val waiting = entries.filter { !it.dropped }
        if (waiting.isEmpty()) {
          throw lastError ?: KetchError.Network(IllegalStateException("No mirror left"))
        }
        waiting.minOf { -(it.retryAt?.elapsedNow() ?: Duration.ZERO) }
      }
      delay(wait.coerceAtLeast(MIN_WAIT))
    }
  }

  /** Ends a transfer from the mirror [index] that [acquire] returned. */
  suspend fun release(index: Int) {
    mutex.withLock { entries[index].active-- }
  }

  /** Records that the mirror [index] delivered a whole range. */
  suspend fun succeeded(index: Int) {
    mutex.withLock { entries[index].failures = 0 }
  }

  /**
   * Records that the mirror [index] failed with [error] and returns whether the transfer should
   * move to another mirror. `false` means it was the last one: the caller rethrows [error].
   */
  suspend fun failed(index: Int, error: Throwable): Boolean = mutex.withLock {
    lastError = error
    val entry = entries[index]
    if (entries.none { it !== entry && !it.dropped }) return false
    entry.failures++
    if (isPermanent(error) || entry.failures >= MAX_FAILURES) {
      entry.dropped = true
    } else {
      val doubled = BASE_BACKOFF * (1 shl (entry.failures - 1).coerceAtMost(6))
      val asked = ((error as? KetchError.Http)?.retryAfterSeconds ?: 0).seconds
      entry.retryAt = timeSource.markNow() + maxOf(doubled, asked).coerceAtMost(MAX_BACKOFF)
    }
    true
  }

  /**
   * Drops the mirrors at [indices], which sent pieces that failed verification, as long as
   * another usable mirror remains, and returns those it dropped.
   */
  suspend fun blame(indices: Set<Int>): Set<Int> = mutex.withLock {
    val blamed = entries.filter { it.index in indices && !it.dropped }
    if (entries.none { !it.dropped && it.index !in indices }) return emptySet()
    blamed.forEach { it.dropped = true }
    blamed.mapTo(LinkedHashSet()) { it.index }
  }

  private fun isPermanent(error: Throwable): Boolean = when (error) {
    is KetchError.Http -> !error.isRetryable && error.code != 408
    is KetchError.Unsupported, is KetchError.FileChanged, is KetchError.AuthenticationFailed -> true
    else -> false
  }

  companion object {
    /** Failures in a row after which a mirror is dropped. */
    const val MAX_FAILURES: Int = 5

    private val BASE_BACKOFF = 1.seconds
    private val MAX_BACKOFF = 60.seconds
    private val MIN_WAIT = 10.milliseconds
  }
}
