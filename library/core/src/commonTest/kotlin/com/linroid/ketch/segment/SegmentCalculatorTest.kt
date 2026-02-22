package com.linroid.ketch.segment

import com.linroid.ketch.api.Segment
import com.linroid.ketch.core.segment.SegmentCalculator
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class SegmentCalculatorTest {

  @Test
  fun singleConnection_createsOneSegment() {
    val segments = SegmentCalculator.calculateSegments(totalBytes = 1000, connections = 1)
    assertEquals(1, segments.size)
    assertEquals(Segment(index = 0, start = 0, end = 999, downloadedBytes = 0), segments[0])
  }

  @Test
  fun twoConnections_splitEvenly() {
    val segments = SegmentCalculator.calculateSegments(totalBytes = 1000, connections = 2)
    assertEquals(2, segments.size)
    assertEquals(0L, segments[0].start)
    assertEquals(499L, segments[0].end)
    assertEquals(500L, segments[1].start)
    assertEquals(999L, segments[1].end)
  }

  @Test
  fun threeConnections_withRemainder() {
    val segments = SegmentCalculator.calculateSegments(totalBytes = 10, connections = 3)
    assertEquals(3, segments.size)

    // Total bytes across all segments should equal totalBytes
    val totalCovered = segments.sumOf { it.totalBytes }
    assertEquals(10L, totalCovered)

    // Segments should be contiguous with no gaps or overlaps
    for (i in 1 until segments.size) {
      assertEquals(segments[i - 1].end + 1, segments[i].start)
    }
    assertEquals(0L, segments.first().start)
    assertEquals(9L, segments.last().end)
  }

  @Test
  fun connectionsExceedTotalBytes_cappedToTotalBytes() {
    val segments = SegmentCalculator.calculateSegments(totalBytes = 3, connections = 10)
    assertEquals(3, segments.size)
    assertEquals(3L, segments.sumOf { it.totalBytes })
    assertEquals(0L, segments.first().start)
    assertEquals(2L, segments.last().end)
  }

  @Test
  fun singleByte_oneSegment() {
    val segments = SegmentCalculator.calculateSegments(totalBytes = 1, connections = 5)
    assertEquals(1, segments.size)
    assertEquals(Segment(index = 0, start = 0, end = 0, downloadedBytes = 0), segments[0])
  }

  @Test
  fun largeFile_fourConnections() {
    val totalBytes = 1_000_000L
    val segments = SegmentCalculator.calculateSegments(totalBytes, connections = 4)
    assertEquals(4, segments.size)

    // All segments should be contiguous
    for (i in 1 until segments.size) {
      assertEquals(segments[i - 1].end + 1, segments[i].start)
    }
    assertEquals(0L, segments.first().start)
    assertEquals(totalBytes - 1, segments.last().end)
    assertEquals(totalBytes, segments.sumOf { it.totalBytes })
  }

  @Test
  fun largeFile_exceedingIntMax_fourConnections() {
    // 2.28 GB — exceeds Int.MAX_VALUE (2,147,483,647)
    val totalBytes = 2_283_360_256L
    val segments = SegmentCalculator.calculateSegments(
      totalBytes, connections = 4,
    )
    assertEquals(4, segments.size)

    for (i in 1 until segments.size) {
      assertEquals(segments[i - 1].end + 1, segments[i].start)
    }
    assertEquals(0L, segments.first().start)
    assertEquals(totalBytes - 1, segments.last().end)
    assertEquals(totalBytes, segments.sumOf { it.totalBytes })
  }

  @Test
  fun allSegments_startWithZeroDownloadedBytes() {
    val segments = SegmentCalculator.calculateSegments(totalBytes = 500, connections = 3)
    assertTrue(segments.all { it.downloadedBytes == 0L })
  }

  @Test
  fun segmentIndices_areSequential() {
    val segments = SegmentCalculator.calculateSegments(totalBytes = 100, connections = 5)
    segments.forEachIndexed { index, segment ->
      assertEquals(index, segment.index)
    }
  }

  @Test
  fun invalidTotalBytes_throws() {
    assertFailsWith<IllegalArgumentException> {
      SegmentCalculator.calculateSegments(totalBytes = 0, connections = 1)
    }
    assertFailsWith<IllegalArgumentException> {
      SegmentCalculator.calculateSegments(totalBytes = -1, connections = 1)
    }
  }

  @Test
  fun invalidConnections_throws() {
    assertFailsWith<IllegalArgumentException> {
      SegmentCalculator.calculateSegments(totalBytes = 100, connections = 0)
    }
    assertFailsWith<IllegalArgumentException> {
      SegmentCalculator.calculateSegments(totalBytes = 100, connections = -1)
    }
  }

  // --- singleSegment tests ---

  @Test
  fun singleSegment_coversFullRange() {
    val segments = SegmentCalculator.singleSegment(totalBytes = 500)
    assertEquals(1, segments.size)
    assertEquals(Segment(index = 0, start = 0, end = 499, downloadedBytes = 0), segments[0])
  }

  @Test
  fun singleSegment_zeroBytes() {
    val segments = SegmentCalculator.singleSegment(totalBytes = 0)
    assertEquals(0, segments.size)
  }

  // --- resegment tests ---

  @Test
  fun resegment_allComplete_returnsUnchanged() {
    val segments = listOf(
      Segment(0, 0, 499, downloadedBytes = 500),
      Segment(1, 500, 999, downloadedBytes = 500),
    )
    val result = SegmentCalculator.resegment(segments, 4)
    assertEquals(2, result.size)
    assertTrue(result.all { it.isComplete })
  }

  @Test
  fun resegment_increaseConnections() {
    // 2 segments, each half done -> resegment to 4
    val segments = listOf(
      Segment(0, 0, 499, downloadedBytes = 300),
      Segment(1, 500, 999, downloadedBytes = 100),
    )
    val result = SegmentCalculator.resegment(segments, 4)

    // Total bytes coverage must equal original
    val completedBytes = result.filter { it.isComplete }
      .sumOf { it.downloadedBytes }
    assertEquals(400L, completedBytes)

    // All 1000 bytes must be covered with no gaps or overlaps
    assertFullCoverage(result, 0, 999)

    // Should have 4 incomplete segments
    val incomplete = result.count { !it.isComplete }
    assertEquals(4, incomplete)
  }

  @Test
  fun resegment_decreaseConnections() {
    // 4 segments, some progress -> reduce to 2
    val segments = listOf(
      Segment(0, 0, 249, downloadedBytes = 200),
      Segment(1, 250, 499, downloadedBytes = 0),
      Segment(2, 500, 749, downloadedBytes = 0),
      Segment(3, 750, 999, downloadedBytes = 0),
    )
    val result = SegmentCalculator.resegment(segments, 2)

    // Downloaded bytes preserved
    val completedBytes = result.filter { it.isComplete }
      .sumOf { it.downloadedBytes }
    assertEquals(200L, completedBytes)

    // All 1000 bytes covered
    assertFullCoverage(result, 0, 999)

    // Should have 2 incomplete segments
    val incomplete = result.count { !it.isComplete }
    assertEquals(2, incomplete)
  }

  @Test
  fun resegment_noProgress_splitsCleanly() {
    // 2 segments, no progress -> resegment to 4
    val segments = listOf(
      Segment(0, 0, 499, downloadedBytes = 0),
      Segment(1, 500, 999, downloadedBytes = 0),
    )
    val result = SegmentCalculator.resegment(segments, 4)

    assertEquals(4, result.size)
    assertTrue(result.all { !it.isComplete })
    assertFullCoverage(result, 0, 999)
  }

  @Test
  fun resegment_partiallyComplete_preservesCompletedSegment() {
    // Segment 0 fully done, segment 1 half done
    val segments = listOf(
      Segment(0, 0, 499, downloadedBytes = 500),
      Segment(1, 500, 999, downloadedBytes = 200),
    )
    val result = SegmentCalculator.resegment(segments, 3)

    // Segment 0 stays complete
    val done = result.filter { it.isComplete }
    assertTrue(done.any { it.start == 0L && it.end == 499L })

    // Completed portion of segment 1 preserved
    assertTrue(done.any { it.start == 500L && it.end == 699L })

    // 3 new incomplete segments for remaining [700, 999]
    val incomplete = result.filter { !it.isComplete }
    assertEquals(3, incomplete.size)

    assertFullCoverage(result, 0, 999)
  }

  @Test
  fun resegment_sameConnections_returnsOriginal() {
    // 2 incomplete segments, resegment to 2 -> should still work
    val segments = listOf(
      Segment(0, 0, 499, downloadedBytes = 100),
      Segment(1, 500, 999, downloadedBytes = 0),
    )
    val result = SegmentCalculator.resegment(segments, 2)
    assertFullCoverage(result, 0, 999)
    assertEquals(2, result.count { !it.isComplete })
  }

  @Test
  fun resegment_indicesAreSequential() {
    val segments = listOf(
      Segment(0, 0, 499, downloadedBytes = 250),
      Segment(1, 500, 999, downloadedBytes = 0),
    )
    val result = SegmentCalculator.resegment(segments, 3)
    result.forEachIndexed { index, segment ->
      assertEquals(index, segment.index)
    }
  }

  @Test
  fun resegment_invalidConnections_throws() {
    val segments = listOf(
      Segment(0, 0, 999, downloadedBytes = 0),
    )
    assertFailsWith<IllegalArgumentException> {
      SegmentCalculator.resegment(segments, 0)
    }
  }

  private fun assertFullCoverage(
    segments: List<Segment>,
    expectedStart: Long,
    expectedEnd: Long,
  ) {
    val sorted = segments.sortedBy { it.start }
    assertEquals(expectedStart, sorted.first().start)
    assertEquals(expectedEnd, sorted.last().end)
    for (i in 1 until sorted.size) {
      assertEquals(
        sorted[i - 1].end + 1, sorted[i].start,
        "Gap or overlap between segment ${i - 1} and $i: " +
          "${sorted[i - 1]} vs ${sorted[i]}"
      )
    }
  }
}
