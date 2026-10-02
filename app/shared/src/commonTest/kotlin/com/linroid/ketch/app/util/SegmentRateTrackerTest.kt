package com.linroid.ketch.app.util

import com.linroid.ketch.api.Segment
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TestTimeSource

class SegmentRateTrackerTest {
  private val time = TestTimeSource()
  private val tracker = SegmentRateTracker(time)

  @Test
  fun update_firstSnapshot_reportsZeroWithoutStall() {
    val rates = tracker.update(listOf(segment(0, 999, 100)), downloading = true)

    assertEquals(listOf(SegmentRate(0, 0)), rates)
  }

  @Test
  fun update_steadyProgress_smoothsWithMovingAverage() {
    tracker.update(listOf(segment(0, 9999, 0)), downloading = true)
    time += 1.seconds
    assertEquals(100, tracker.update(listOf(segment(0, 9999, 100)), true).single().bytesPerSecond)
    time += 1.seconds

    val rate = tracker.update(listOf(segment(0, 9999, 300)), true).single()

    // 0.35 × 200 + 0.65 × 100
    assertEquals(135, rate.bytesPerSecond)
    assertNull(rate.stalledFor)
  }

  @Test
  fun update_halfSecondApart_measuresPerSecond() {
    tracker.update(listOf(segment(0, 9999, 0)), downloading = true)
    time += 0.5.seconds

    assertEquals(200, tracker.update(listOf(segment(0, 9999, 100)), true).single().bytesPerSecond)
  }

  @Test
  fun update_sameInstant_waitsForTimeToPass() {
    tracker.update(listOf(segment(0, 9999, 0)), downloading = true)
    time += 1.seconds
    tracker.update(listOf(segment(0, 9999, 100)), true)
    tracker.update(listOf(segment(0, 9999, 150)), true)
    time += 1.seconds

    // 0.35 × 100 + 0.65 × 100: the 50 bytes of the same instant count in the next second.
    assertEquals(100, tracker.update(listOf(segment(0, 9999, 200)), true).single().bytesPerSecond)
  }

  @Test
  fun update_bytesGoDown_ignoresNegativeDelta() {
    tracker.update(listOf(segment(0, 9999, 0)), downloading = true)
    time += 1.seconds
    tracker.update(listOf(segment(0, 9999, 500)), true)
    time += 1.seconds

    val restarted = tracker.update(listOf(segment(0, 9999, 100)), true).single()
    time += 1.seconds
    val next = tracker.update(listOf(segment(0, 9999, 600)), true).single()

    assertEquals(500, restarted.bytesPerSecond)
    assertEquals(500, next.bytesPerSecond)
  }

  @Test
  fun update_resegment_finishedPartStopsAndNewLanesStartFresh() {
    tracker.update(listOf(segment(0, 999, 0)), downloading = true)
    time += 1.seconds
    tracker.update(listOf(segment(0, 999, 500)), true)
    time += 1.seconds
    // The engine keeps the received part and splits the rest into two new segments.
    val split = listOf(segment(0, 499, 500), segment(500, 749, 0), segment(750, 999, 0))

    val afterSplit = tracker.update(split, true)
    time += 1.seconds
    val later = tracker.update(
      listOf(segment(0, 499, 500), segment(500, 749, 80), segment(750, 999, 60)),
      true
    )

    assertEquals(listOf(0L, 0L, 0L), afterSplit.map { it.bytesPerSecond })
    assertEquals(listOf(0L, 80L, 60L), later.map { it.bytesPerSecond })
    assertEquals(listOf(0L, 500L, 750L), later.map { it.start })
  }

  @Test
  fun update_noDataWhileDownloading_stallsAfterThreeSeconds() {
    tracker.update(listOf(segment(0, 9999, 0)), downloading = true)
    time += 1.seconds
    tracker.update(listOf(segment(0, 9999, 100)), true)
    time += 2.seconds
    val moving = tracker.update(listOf(segment(0, 9999, 100)), true).single()
    time += 1.seconds
    val stalled = tracker.update(listOf(segment(0, 9999, 100)), true).single()
    time += 3.seconds
    val longer = tracker.update(listOf(segment(0, 9999, 100)), true).single()

    assertNull(moving.stalledFor)
    assertEquals(3.seconds, stalled.stalledFor)
    assertEquals(6.seconds, longer.stalledFor)
  }

  @Test
  fun update_dataArrivesAgain_clearsStall() {
    tracker.update(listOf(segment(0, 9999, 0)), downloading = true)
    time += 4.seconds
    tracker.update(listOf(segment(0, 9999, 0)), true)
    time += 1.seconds

    assertNull(tracker.update(listOf(segment(0, 9999, 50)), true).single().stalledFor)
  }

  @Test
  fun update_notDownloading_neitherMovesNorStalls() {
    tracker.update(listOf(segment(0, 9999, 0)), downloading = true)
    time += 1.seconds
    tracker.update(listOf(segment(0, 9999, 100)), true)
    time += 10.seconds
    val paused = tracker.update(listOf(segment(0, 9999, 100)), downloading = false).single()
    time += 2.seconds
    val resumed = tracker.update(listOf(segment(0, 9999, 100)), downloading = true).single()

    assertEquals(SegmentRate(0, 0), paused)
    assertNull(resumed.stalledFor)
  }

  @Test
  fun update_finishedSegment_reportsZero() {
    tracker.update(listOf(segment(0, 99, 0)), downloading = true)
    time += 10.seconds

    assertEquals(SegmentRate(0, 0), tracker.update(listOf(segment(0, 99, 100)), true).single())
  }

  @Test
  fun update_segmentGone_forgetsItsLane() {
    tracker.update(listOf(segment(0, 9999, 0), segment(10000, 19999, 0)), downloading = true)
    time += 1.seconds
    tracker.update(listOf(segment(0, 9999, 100)), true)
    time += 1.seconds

    val back = tracker.update(listOf(segment(0, 9999, 200), segment(10000, 19999, 500)), true)

    assertEquals(listOf(100L, 0L), back.map { it.bytesPerSecond })
  }

  @Test
  fun health_byStallLength_movesStallsThenSticks() {
    assertEquals(LaneHealth.Moving, SegmentRate(0, 100).health)
    assertEquals(LaneHealth.Stalled, SegmentRate(0, 0, stalledFor = 3.seconds).health)
    assertEquals(LaneHealth.Stalled, SegmentRate(0, 0, stalledFor = 10.seconds).health)
    assertEquals(LaneHealth.Stuck, SegmentRate(0, 0, stalledFor = 11.seconds).health)
  }

  private fun segment(start: Long, end: Long, downloaded: Long) =
    Segment(index = 0, start = start, end = end, downloadedBytes = downloaded)
}
