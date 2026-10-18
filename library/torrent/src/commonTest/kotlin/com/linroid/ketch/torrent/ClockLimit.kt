package com.linroid.ketch.torrent

import kotlinx.coroutines.delay
import kotlin.concurrent.atomics.AtomicInt
import kotlin.concurrent.atomics.AtomicLong
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.concurrent.atomics.decrementAndFetch
import kotlin.concurrent.atomics.incrementAndFetch
import kotlin.concurrent.atomics.update

/**
 * A speed limit of [bytesPerSecond] on a [clock] the test moves on: each call waits until the
 * clock passed its bytes, after those of the calls before it.
 */
@OptIn(ExperimentalAtomicApi::class)
internal class ClockLimit(private val clock: AtomicLong, private val bytesPerSecond: Long) {
  private val free = AtomicLong(0)
  private val waiters = AtomicInt(0)

  /** Whether a call waits now. */
  val waiting: Boolean get() = waiters.load() > 0

  suspend fun acquire(bytes: Int) {
    var due = 0L
    free.update { previous ->
      (maxOf(previous, clock.load()) + bytes * 1_000L / bytesPerSecond).also { due = it }
    }
    waiters.incrementAndFetch()
    try {
      while (clock.load() < due) delay(5)
    } finally {
      waiters.decrementAndFetch()
    }
  }
}
