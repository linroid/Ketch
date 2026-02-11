package com.linroid.kdown.segment

import com.linroid.kdown.engine.FakeHttpEngine
import com.linroid.kdown.engine.ServerInfo
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SegmentDownloaderTest {

  @Test
  fun download_completesFullSegment() = runTest {
    val content = ByteArray(500) { (it % 256).toByte() }
    val engine = FakeHttpEngine(
      serverInfo = ServerInfo(500, true, null, null),
      content = content,
      chunkSize = 100
    )
    val fileData = ByteArray(500)
    var bytesWritten = 0

    val segment = Segment(index = 0, start = 0, end = 499, downloadedBytes = 0)

    assertEquals(500, segment.totalBytes)
    assertEquals(0, segment.currentOffset)

    engine.download("https://example.com/file", segment.currentOffset..segment.end) { data ->
      data.copyInto(fileData, bytesWritten)
      bytesWritten += data.size
    }

    assertEquals(500, bytesWritten)
    assertTrue(content.contentEquals(fileData))
  }

  @Test
  fun download_resumesFromPartialSegment() = runTest {
    val content = ByteArray(500) { (it % 256).toByte() }
    val engine = FakeHttpEngine(
      serverInfo = ServerInfo(500, true, null, null),
      content = content,
      chunkSize = 100
    )

    // Segment that has already downloaded 200 bytes
    val segment = Segment(index = 0, start = 0, end = 499, downloadedBytes = 200)

    assertEquals(200, segment.currentOffset)
    assertEquals(300, segment.remainingBytes)

    var bytesReceived = 0L
    engine.download("https://example.com/file", segment.currentOffset..segment.end) { data ->
      bytesReceived += data.size
    }

    assertEquals(300, bytesReceived)
  }

  @Test
  fun download_alreadyCompleteSegment_isSkipped() {
    val segment = Segment(index = 0, start = 0, end = 99, downloadedBytes = 100)
    assertTrue(segment.isComplete)
  }

  @Test
  fun download_multipleSegments_coverFullRange() = runTest {
    val totalBytes = 1000L
    val content = ByteArray(totalBytes.toInt()) { (it % 256).toByte() }
    val engine = FakeHttpEngine(
      serverInfo = ServerInfo(totalBytes, true, null, null),
      content = content,
      chunkSize = 50
    )

    val segments = SegmentCalculator.calculateSegments(totalBytes, connections = 4)
    assertEquals(4, segments.size)

    val result = ByteArray(totalBytes.toInt())
    for (segment in segments) {
      var segmentOffset = 0
      engine.download("https://example.com/file", segment.currentOffset..segment.end) { data ->
        data.copyInto(result, segment.start.toInt() + segmentOffset)
        segmentOffset += data.size
      }
    }

    // Verify the downloaded content matches the original
    assertTrue(content.contentEquals(result))
    // Verify all segments together cover the full byte range
    assertEquals(totalBytes, segments.sumOf { it.totalBytes })
    assertEquals(0L, segments.first().start)
    assertEquals(totalBytes - 1, segments.last().end)
  }

  @Test
  fun download_engineCalledWithCorrectRange() = runTest {
    val content = ByteArray(1000) { (it % 256).toByte() }
    val engine = FakeHttpEngine(
      serverInfo = ServerInfo(1000, true, null, null),
      content = content,
      chunkSize = 200
    )

    val segment = Segment(index = 1, start = 250, end = 499, downloadedBytes = 0)

    var bytesReceived = 0L
    engine.download("https://example.com/file", segment.currentOffset..segment.end) { data ->
      bytesReceived += data.size
    }

    assertEquals(250, bytesReceived)
    assertEquals(1, engine.downloadCallCount)
  }
}
