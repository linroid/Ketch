package com.linroid.kdown.core.segment

import com.linroid.kdown.api.Segment

internal object SegmentCalculator {

  fun calculateSegments(totalBytes: Long, connections: Int): List<Segment> {
    require(totalBytes > 0) { "totalBytes must be positive" }
    require(connections > 0) { "connections must be positive" }

    val effectiveConnections = minOf(connections, totalBytes.toInt().coerceAtLeast(1))
    val segmentSize = totalBytes / effectiveConnections
    val remainder = totalBytes % effectiveConnections

    return (0 until effectiveConnections).map { index ->
      val start = index * segmentSize + minOf(index.toLong(), remainder)
      val extraByte = if (index < remainder) 1L else 0L
      val end = start + segmentSize - 1 + extraByte

      Segment(
        index = index,
        start = start,
        end = end,
        downloadedBytes = 0,
      )
    }
  }

  fun singleSegment(totalBytes: Long): List<Segment> {
    return listOf(
      Segment(
        index = 0,
        start = 0,
        end = if (totalBytes > 0) totalBytes - 1 else 0,
        downloadedBytes = 0,
      )
    )
  }
}
