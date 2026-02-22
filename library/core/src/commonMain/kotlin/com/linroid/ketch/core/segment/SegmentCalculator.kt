package com.linroid.ketch.core.segment

import com.linroid.ketch.api.Segment
import com.linroid.ketch.api.log.KetchLogger

internal object SegmentCalculator {

  private val log = KetchLogger("SegmentCalc")

  fun calculateSegments(totalBytes: Long, connections: Int): List<Segment> {
    require(totalBytes > 0) { "totalBytes must be positive" }
    require(connections > 0) { "connections must be positive" }

    val effectiveConnections =
      minOf(connections.toLong(), totalBytes.coerceAtLeast(1)).toInt()
    log.d {
      "Calculating segments: totalBytes=$totalBytes," +
        " connections=$connections, effective=$effectiveConnections"
    }
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
    if (totalBytes == 0L) return emptyList()
    return listOf(
      Segment(
        index = 0,
        start = 0,
        end = totalBytes - 1,
        downloadedBytes = 0,
      )
    )
  }

  /**
   * Recalculates segments for a resumed download when the connection
   * count has changed.
   *
   * - Completed segments are preserved (already written to disk).
   * - Partially-downloaded segments are split: the downloaded portion
   *   becomes a completed segment, and the remaining bytes join the
   *   pool of work to redistribute.
   * - All remaining byte ranges are merged where contiguous, then
   *   distributed across [newConnections] new download segments.
   *
   * @param oldSegments the segments from the paused download
   * @param newConnections desired number of active download connections
   * @return re-segmented list, sorted by byte offset, re-indexed
   */
  fun resegment(
    oldSegments: List<Segment>,
    newConnections: Int,
  ): List<Segment> {
    require(newConnections > 0) { "connections must be positive" }

    if (oldSegments.all { it.isComplete }) {
      log.d { "Resegment: all ${oldSegments.size} segments complete, skipping" }
      return oldSegments
    }
    log.d {
      "Resegmenting: ${oldSegments.size} old segments," +
        " newConnections=$newConnections"
    }

    val completed = mutableListOf<Segment>()
    val remainingRanges = mutableListOf<LongRange>()

    for (seg in oldSegments) {
      if (seg.isComplete) {
        completed.add(seg)
      } else {
        if (seg.downloadedBytes > 0) {
          completed.add(
            Segment(
              index = 0,
              start = seg.start,
              end = seg.currentOffset - 1,
              downloadedBytes = seg.downloadedBytes,
            )
          )
        }
        remainingRanges.add(seg.currentOffset..seg.end)
      }
    }

    if (remainingRanges.isEmpty()) {
      return completed.sortedBy { it.start }
        .mapIndexed { i, seg -> seg.copy(index = i) }
    }

    val merged = mergeContiguousRanges(remainingRanges)
    val targetActive = newConnections.coerceAtLeast(merged.size)
    val perRange = distributeCount(merged, targetActive)

    val result = completed.toMutableList()
    for ((range, count) in merged.zip(perRange)) {
      val rangeSize = range.last - range.first + 1
      val segSize = rangeSize / count
      val extra = rangeSize % count
      var offset = range.first
      for (j in 0 until count) {
        val size = segSize + if (j < extra) 1 else 0
        result.add(
          Segment(
            index = 0,
            start = offset,
            end = offset + size - 1,
            downloadedBytes = 0,
          )
        )
        offset += size
      }
    }

    result.sortBy { it.start }
    val reindexed = result.mapIndexed { i, seg -> seg.copy(index = i) }
    log.d { "Resegmented: ${reindexed.size} segments" }
    return reindexed
  }

  /**
   * Merges contiguous or overlapping byte ranges into the minimal
   * set of non-overlapping ranges, sorted by start offset.
   */
  private fun mergeContiguousRanges(
    ranges: List<LongRange>,
  ): List<LongRange> {
    if (ranges.isEmpty()) return emptyList()
    val sorted = ranges.sortedBy { it.first }
    val result = mutableListOf(sorted.first())
    for (i in 1 until sorted.size) {
      val last = result.last()
      val current = sorted[i]
      if (current.first <= last.last + 1) {
        result[result.lastIndex] =
          last.first..maxOf(last.last, current.last)
      } else {
        result.add(current)
      }
    }
    return result
  }

  /**
   * Distributes [total] segment slots across [ranges] proportionally
   * to each range's byte size, with at least 1 slot per range.
   */
  private fun distributeCount(
    ranges: List<LongRange>,
    total: Int,
  ): List<Int> {
    val sizes = ranges.map { it.last - it.first + 1 }
    val totalSize = sizes.sum()
    val counts = sizes.map {
      ((it.toDouble() / totalSize) * total)
        .toInt().coerceAtLeast(1)
    }.toMutableList()

    var assigned = counts.sum()
    while (assigned < total) {
      val best = counts.indices
        .maxByOrNull { sizes[it].toDouble() / counts[it] }
        ?: break
      counts[best]++
      assigned++
    }
    while (assigned > total && counts.any { it > 1 }) {
      val worst = counts.indices
        .filter { counts[it] > 1 }
        .minByOrNull { sizes[it].toDouble() / counts[it] }
        ?: break
      counts[worst]--
      assigned--
    }
    return counts
  }
}
