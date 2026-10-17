package com.linroid.ketch.torrent.engine

import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.ExperimentalAtomicApi

/**
 * How long a transfer has spent waiting on speed limits, which its [TorrentStallWatchdog] counts
 * as progress: the time during which at least one wait ran, peers' and web seeds' alike. Waits
 * that overlap count once, and a wait counts as it passes rather than once it ends, so
 * [throttledMs] never runs ahead of the clock, and a download a low limit holds for longer than a
 * stall check is never taken for a stalled one.
 *
 * Thread-safe: peers and web seeds wait on their own threads.
 */
@OptIn(ExperimentalAtomicApi::class)
internal class ThrottleClock(private val nowMs: () -> Long) {
  /** [waiters] running since [since], and [total] of the time before it. */
  private class State(val waiters: Int, val since: Long, val total: Long)

  private val state = AtomicReference(State(waiters = 0, since = 0, total = 0))

  /** A wait starts; [exit] ends it. */
  fun enter() {
    update { now, current ->
      if (current.waiters == 0) State(1, now, current.total)
      else State(current.waiters + 1, current.since, current.total)
    }
  }

  /** A wait [enter] started ends. */
  fun exit() {
    update { now, current ->
      check(current.waiters > 0) { "No wait to end" }
      if (current.waiters > 1) State(current.waiters - 1, current.since, current.total)
      else State(0, 0, current.total + (now - current.since).coerceAtLeast(0))
    }
  }

  /** The time so far during which something waited, a wait still running included. */
  fun throttledMs(): Long {
    val current = state.load()
    if (current.waiters == 0) return current.total
    return current.total + (nowMs() - current.since).coerceAtLeast(0)
  }

  private inline fun update(next: (now: Long, current: State) -> State) {
    while (true) {
      val current = state.load()
      if (state.compareAndSet(current, next(nowMs(), current))) return
    }
  }
}

/** Runs [block], a wait on speed limits, counted by this clock while it runs. */
internal inline fun <T> ThrottleClock.waiting(block: () -> T): T {
  enter()
  try {
    return block()
  } finally {
    exit()
  }
}
