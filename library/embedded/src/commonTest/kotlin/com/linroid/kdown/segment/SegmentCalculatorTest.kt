package com.linroid.kdown.segment

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
    assertEquals(1, segments.size)
    assertEquals(0L, segments[0].start)
    assertEquals(0L, segments[0].end)
  }
}
