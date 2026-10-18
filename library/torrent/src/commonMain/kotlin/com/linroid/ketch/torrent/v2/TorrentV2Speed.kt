package com.linroid.ketch.torrent.v2

import com.linroid.ketch.torrent.peer.PeerWire

/**
 * A v2 or hybrid download's speed, from the payload it has received, sampled as its task reports
 * progress. V2 owners pay the download limits before they ask for a block, so payload arrives a
 * block at a time: at 5 KiB/s, 16 KiB every 3.2 s. A rate over a fixed second reads each block as
 * three times the limit and the gaps between as none.
 *
 * The speed is measured between arrivals: from the latest arrival that leaves at least
 * [MIN_SPAN_MS] and [MIN_BYTES] after it, else the oldest one kept, through the newest, so blocks
 * arriving at a steady pace read at that pace whenever they are sampled. Arrivals older than
 * [MAX_SPAN_MS] before the newest are forgotten, keeping two. Once the time since the newest
 * outgrows the usual gap between arrivals, the speed falls as it grows, and past twice that gap,
 * and at least [IDLE_MS], it reads 0.
 */
internal class TorrentV2Speed {
  // When each arrival was sampled, and the payload received by then; the first is where the
  // measure starts.
  private val times = ArrayDeque<Long>()
  private val totals = ArrayDeque<Long>()

  /** The speed in bytes per second at [now], given the payload [received] so far. */
  fun sample(now: Long, received: Long): Long {
    if (totals.isEmpty() || received < totals.last()) {
      times.clear()
      totals.clear()
      times.addLast(now)
      totals.addLast(received)
      return 0
    }
    if (received > totals.last()) {
      times.addLast(now)
      totals.addLast(received)
    }
    while (times.size > 2 && times.last() - times[1] >= MAX_SPAN_MS) {
      times.removeFirst()
      totals.removeFirst()
    }
    // Earlier ones are never needed again: later arrivals only leave more after this one.
    repeat(start()) {
      times.removeFirst()
      totals.removeFirst()
    }
    val arrivals = times.size - 1
    if (arrivals == 0) return 0
    val span = times.last() - times.first()
    val gap = span / arrivals
    if (now - times.last() > maxOf(IDLE_MS, 2 * gap)) return 0
    val elapsed = maxOf(span, now - times.first() - gap).coerceAtLeast(1)
    return (totals.last() - totals.first()) * 1000 / elapsed
  }

  /** The latest arrival that leaves enough after it to measure from, else the oldest. */
  private fun start(): Int {
    for (index in times.lastIndex - 1 downTo 0) {
      if (times.last() - times[index] >= MIN_SPAN_MS &&
        totals.last() - totals[index] >= MIN_BYTES) {
        return index
      }
    }
    return 0
  }

  companion object {
    /** The least time measured over, as v1 sessions measure theirs. */
    const val MIN_SPAN_MS = 1_000L

    /** The least payload measured over: a few blocks, so one more or less changes little. */
    const val MIN_BYTES = 4L * PeerWire.BLOCK_SIZE

    /** How far back from the newest arrival a measure may start, when fewer blocks arrived. */
    const val MAX_SPAN_MS = 20_000L

    /** The least time without payload after which a download reads 0. */
    const val IDLE_MS = 2_000L
  }
}
