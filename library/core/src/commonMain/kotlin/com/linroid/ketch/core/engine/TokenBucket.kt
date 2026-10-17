package com.linroid.ketch.core.engine

import com.linroid.ketch.api.log.KetchLogger
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.cancellation.CancellationException
import kotlin.math.ceil
import kotlin.time.TimeSource

/**
 * Lets bytes through at `bytesPerSecond`, after a burst of up to [burstSize]; a rate of `0` lets
 * everything through.
 *
 * Callers that have to wait queue by owner ([acquire] with an owner; the plain [acquire] is one
 * owner) and are served in turns of at most [SLICE] bytes, by start-time fair queuing: each turn
 * goes to the waiting owner granted the fewest bytes, so owners share the rate equally by bytes,
 * however many coroutines each waits with and however small its requests. An owner that stops
 * waiting gives up its turns rather than saving them: it waits again no further back than the
 * turn being served. One owner's callers are served in the order they came.
 */
internal class TokenBucket(
  bytesPerSecond: Long,
  private val burstSize: Long = 65536,
  private val timeSource: TimeSource.WithComparableMarks = TimeSource.Monotonic,
) : SpeedLimiter {
  private val log = KetchLogger("TokenBucket")
  private val mutex = Mutex()
  private var tokens: Double = burstSize.toDouble()
  private var lastRefill = timeSource.markNow()
  private var rate: Double = bytesPerSecond.toDouble()

  /**
   * The owners waiting, in the order they started to, and those that stopped ahead of
   * [virtualTime], which keep their place; empty while nobody waits.
   */
  private val lanes = ArrayList<Lane>()

  /** The bytes the lane served last had been granted before that turn. */
  private var virtualTime = 0L

  init {
    require(bytesPerSecond >= 0)
    require(burstSize > 0)
  }

  override suspend fun acquire(bytes: Int) = acquire(bytes, owner = this)

  override suspend fun acquire(bytes: Int, owner: Any) {
    if (bytes <= 0) return
    val waiter = mutex.withLock {
      if (rate == 0.0) return
      refill()
      if (lanes.isEmpty() && tokens >= bytes) {
        tokens -= bytes
        return
      }
      Waiter(lane(owner), bytes.toLong()).also {
        it.lane.waiters.addLast(it)
      }
    }
    try {
      while (true) {
        val waitMs = mutex.withLock {
          refill()
          grant()
          if (waiter.done) return
          waitMs(waiter)
        }
        if (waitMs == Long.MAX_VALUE) {
          waiter.signal.receive()
        } else {
          log.v { "Throttling: waiting ${waitMs}ms for ${waiter.remaining} bytes" }
          withTimeoutOrNull(waitMs) { waiter.signal.receive() }
        }
      }
    } catch (e: CancellationException) {
      withContext(NonCancellable) { mutex.withLock { leave(waiter) } }
      throw e
    }
  }

  suspend fun updateRate(newBytesPerSecond: Long) = mutex.withLock {
    require(newBytesPerSecond >= 0)
    refill()
    rate = newBytesPerSecond.toDouble()
    if (rate == 0.0) releaseAll()
    log.d { "Rate updated to $newBytesPerSecond bytes/sec" }
  }

  private fun refill() {
    val now = timeSource.markNow()
    val elapsed = now - lastRefill
    lastRefill = now
    tokens = minOf(burstSize.toDouble(), tokens + elapsed.inWholeNanoseconds * rate / 1e9)
  }

  /** The lane of [owner], no further back than [virtualTime] when it was not waiting. */
  private fun lane(owner: Any): Lane {
    val lane = lanes.firstOrNull { it.owner == owner }
      ?: Lane(owner, virtualTime).also { lanes += it }
    if (lane.waiters.isEmpty()) lane.served = maxOf(lane.served, virtualTime)
    return lane
  }

  /** The waiting lane granted the fewest bytes, the first of those to wait on a tie. */
  private fun next(): Lane? {
    var next: Lane? = null
    for (lane in lanes) {
      if (lane.waiters.isNotEmpty() && (next == null || lane.served < next.served)) next = lane
    }
    return next
  }

  /** Grants turns, each to the next lane's first waiter, while the tokens cover one. */
  private fun grant() {
    if (rate == 0.0) return releaseAll()
    while (true) {
      val lane = next() ?: return
      val waiter = lane.waiters.first()
      val slice = slice(waiter)
      if (tokens < slice) return
      tokens -= slice
      virtualTime = lane.served
      lane.served += slice
      waiter.remaining -= slice
      if (waiter.done) {
        lane.waiters.removeFirst()
        waiter.wake()
        lane.waiters.firstOrNull()?.wake()
      }
      forgetIdle()
    }
  }

  /**
   * Drops the lanes that stopped waiting no further ahead than [virtualTime], which would start
   * there anyway, and every lane once nobody waits.
   */
  private fun forgetIdle() {
    lanes.removeAll { it.waiters.isEmpty() && it.served <= virtualTime }
    if (lanes.none { it.waiters.isNotEmpty() }) {
      lanes.clear()
      virtualTime = 0
    }
  }

  /**
   * How long [waiter] waits before it looks again: a lane's first waiter until the tokens cover
   * the next turn, at most [MAX_WAIT_MS] so a rate change applies soon, and any other waiter
   * until it is first or done ([Long.MAX_VALUE]).
   */
  private fun waitMs(waiter: Waiter): Long {
    if (waiter.lane.waiters.first() !== waiter) return Long.MAX_VALUE
    val needed = slice(next()!!.waiters.first()) - tokens
    return ceil(needed / rate * 1000).toLong().coerceIn(1, MAX_WAIT_MS)
  }

  /** Removes a waiter that was cancelled, waking the waiter it leaves first in its lane. */
  private fun leave(waiter: Waiter) {
    if (waiter.done) return
    val lane = waiter.lane
    val first = lane.waiters.first() === waiter
    lane.waiters.remove(waiter)
    if (first) lane.waiters.firstOrNull()?.wake()
    forgetIdle()
  }

  /** Lets every waiter through, once the rate is unlimited. */
  private fun releaseAll() {
    for (lane in lanes) {
      for (waiter in lane.waiters) {
        waiter.remaining = 0
        waiter.wake()
      }
    }
    lanes.clear()
    virtualTime = 0
  }

  private fun slice(waiter: Waiter): Long = minOf(waiter.remaining, SLICE, burstSize)

  /** The waiters of one owner, first come first served, and the bytes it has been granted. */
  private class Lane(val owner: Any, var served: Long) {
    val waiters = ArrayDeque<Waiter>()
  }

  /** One call waiting for [remaining] more bytes. Read and written under the mutex. */
  private class Waiter(val lane: Lane, var remaining: Long) {
    val signal = Channel<Unit>(Channel.CONFLATED)
    val done: Boolean get() = remaining == 0L

    fun wake() {
      signal.trySend(Unit)
    }
  }

  private companion object {
    /** The most bytes one turn grants. */
    const val SLICE = 16_384L

    /** The longest a lane's first waiter sleeps before it looks again. */
    const val MAX_WAIT_MS = 50L
  }
}
