package com.linroid.ketch.app.util

import com.linroid.ketch.api.Segment
import kotlin.math.roundToLong
import kotlin.time.ComparableTimeMark
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.DurationUnit
import kotlin.time.TimeSource

/**
 * Live rate of one segment of a download.
 *
 * @property start first byte of the segment, which identifies its lane between snapshots.
 * @property bytesPerSecond smoothed rate; `0` for a finished segment or a task that is not
 *   downloading.
 * @property stalledFor how long the segment has received no data while its task downloads, once
 *   that reaches [SegmentRateTracker.STALL_AFTER]; `null` while data arrives.
 */
data class SegmentRate(
  val start: Long,
  val bytesPerSecond: Long,
  val stalledFor: Duration? = null,
)

/**
 * Per-segment rates of one task, measured from consecutive snapshots of its segments.
 *
 * Segments are matched by [Segment.start]. Each rate is an exponential moving average with
 * α = [SMOOTHING] of the bytes received between snapshots. When a segment's downloaded bytes go
 * down, as when the engine re-splits the remaining bytes, the measurement starts over instead of
 * reporting a negative rate. A segment of a downloading task that receives nothing for
 * [STALL_AFTER] is stalled.
 *
 * Not thread-safe: feed it from one coroutine.
 *
 * @param timeSource clock that measures the time between snapshots.
 */
class SegmentRateTracker(
  private val timeSource: TimeSource.WithComparableMarks = TimeSource.Monotonic,
) {
  private class Lane(
    var bytes: Long,
    var measuredAt: ComparableTimeMark,
    var movedAt: ComparableTimeMark,
    var rate: Double? = null,
  )

  private val lanes = HashMap<Long, Lane>()

  /**
   * Measures the rates of the latest [segments] of the task, given in any order; the result
   * keeps that order. [downloading] tells whether the task is downloading, since segments of a
   * paused or queued task neither move nor stall.
   */
  fun update(segments: List<Segment>, downloading: Boolean): List<SegmentRate> {
    val now = timeSource.markNow()
    lanes.keys.retainAll(segments.mapTo(HashSet()) { it.start })
    return segments.map { segment ->
      val lane = lanes[segment.start]
      if (lane == null) {
        lanes[segment.start] = Lane(segment.downloadedBytes, now, now)
        SegmentRate(segment.start, 0)
      } else {
        measure(lane, segment, now, downloading)
      }
    }
  }

  /** Forgets every segment, as when the tracked task changes. */
  fun reset() {
    lanes.clear()
  }

  private fun measure(
    lane: Lane,
    segment: Segment,
    now: ComparableTimeMark,
    downloading: Boolean,
  ): SegmentRate {
    val delta = segment.downloadedBytes - lane.bytes
    val elapsed = now - lane.measuredAt
    when {
      !downloading -> {
        lane.rate = null
        lane.movedAt = now
      }
      // Two snapshots at the same instant: wait for time to pass before measuring.
      elapsed <= Duration.ZERO -> return rateOf(lane, segment, now, downloading)
      delta >= 0 -> {
        val sample = delta / elapsed.toDouble(DurationUnit.SECONDS)
        lane.rate = lane.rate?.let { SMOOTHING * sample + (1 - SMOOTHING) * it } ?: sample
        if (delta > 0) lane.movedAt = now
      }
    }
    lane.bytes = segment.downloadedBytes
    lane.measuredAt = now
    return rateOf(lane, segment, now, downloading)
  }

  private fun rateOf(
    lane: Lane,
    segment: Segment,
    now: ComparableTimeMark,
    downloading: Boolean,
  ): SegmentRate {
    if (segment.isComplete || !downloading) return SegmentRate(segment.start, 0)
    val idle = now - lane.movedAt
    return SegmentRate(
      start = segment.start,
      bytesPerSecond = lane.rate?.roundToLong() ?: 0,
      stalledFor = idle.takeIf { it >= STALL_AFTER },
    )
  }

  companion object {
    /** Weight of the newest sample in the moving average. */
    const val SMOOTHING: Double = 0.35

    /** A segment of a downloading task that receives no data for this long is stalled. */
    val STALL_AFTER: Duration = 3.seconds
  }
}
