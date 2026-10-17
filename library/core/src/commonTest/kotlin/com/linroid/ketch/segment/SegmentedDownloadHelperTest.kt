package com.linroid.ketch.segment

import com.linroid.ketch.api.DownloadConfig
import com.linroid.ketch.api.DownloadRequest
import com.linroid.ketch.api.KetchError
import com.linroid.ketch.api.Segment
import com.linroid.ketch.core.engine.DownloadContext
import com.linroid.ketch.core.file.NoOpFileAccessor
import com.linroid.ketch.core.segment.SegmentLane
import com.linroid.ketch.core.segment.SegmentedDownloadHelper
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SegmentedDownloadHelperTest {
  @Test
  fun downloadAll_keepsCompletedSegmentProgressAfterReturning() = runTest {
    val initial = listOf(
      Segment(index = 0, start = 0, end = 3, downloadedBytes = 4),
      Segment(index = 1, start = 4, end = 7, downloadedBytes = 2),
      Segment(index = 2, start = 8, end = 11, downloadedBytes = 0)
    )
    val segments = MutableStateFlow(initial)
    val downloadedStarts = mutableListOf<Long>()
    var finalProgress = 0L
    val context = DownloadContext(
      taskId = "progress-regression",
      url = "https://example.com/file",
      request = DownloadRequest("https://example.com/file"),
      fileAccessor = NoOpFileAccessor,
      segments = segments,
      onProgress = { downloaded, _ -> finalProgress = downloaded },
      throttle = {},
      headers = emptyMap(),
    )
    SegmentedDownloadHelper().downloadAll(context, initial, 12) { lane ->
      downloadedStarts.add(lane.start)
      lane.fill()
    }
    assertEquals(setOf(4L, 8L), downloadedStarts.toSet())
    assertEquals(initial.map { it.copy(downloadedBytes = it.totalBytes) }, segments.value)
    assertEquals(12L, finalProgress)
  }

  @Test
  fun downloadAll_transferEndingEarly_failsRetryably() = runTest {
    val context = context()
    assertFailsWith<KetchError.Network> {
      SegmentedDownloadHelper().downloadAll(context, context.segments.value, 12) { lane ->
        lane.fill(5)
      }
    }
    assertEquals(5L, context.segments.value.single().downloadedBytes)
  }

  @Test
  fun downloadAll_cancellationPreservesUnreportedProgress() = runTest {
    val context = context()
    val job = launch {
      SegmentedDownloadHelper().downloadAll(context, context.segments.value, 12) { lane ->
        lane.fill(3)
        awaitCancellation()
      }
    }
    runCurrent()
    job.cancelAndJoin()
    assertEquals(3L, context.segments.value.single().downloadedBytes)
  }

  @Test
  fun downloadAll_failurePreservesUnreportedProgressForRetry() = runTest {
    val context = context()
    assertFailsWith<IllegalStateException> {
      SegmentedDownloadHelper().downloadAll(context, context.segments.value, 12) { lane ->
        lane.fill(3)
        error("connection lost")
      }
    }
    assertEquals(3L, context.segments.value.single().downloadedBytes)
  }

  @Test
  fun downloadAll_connectionChangeCancelsAndRestartsBatch() = runTest {
    val context = context()
    val requests = mutableListOf<LongRange>()
    val job = launch {
      val helper = SegmentedDownloadHelper()
      helper.downloadAll(context, context.segments.value, 12) { lane ->
        requests.add(lane.offset..lane.end)
        if (requests.size == 1) {
          lane.fill(3)
          awaitCancellation()
        }
        lane.fill()
      }
    }
    try {
      runCurrent()
      context.maxConnections.value = 2
      runCurrent()
      assertTrue(job.isCompleted, "Changing connections must restart the active transfers")
      assertEquals(listOf(0L..11L, 3L..7L, 8L..11L), requests)
      assertTrue(context.segments.value.all { it.isComplete })
    } finally {
      job.cancelAndJoin()
    }
  }

  @Test
  fun downloadAll_connectionChangeDuringInitialProgress_isApplied() = runTest {
    val connections = MutableStateFlow(1)
    val context = context(connections) { _, _ -> connections.value = 2 }
    val requests = mutableListOf<LongRange>()
    SegmentedDownloadHelper().downloadAll(context, context.segments.value, 12) { lane ->
      requests.add(lane.offset..lane.end)
      lane.fill()
    }
    assertEquals(listOf(0L..5L, 6L..11L), requests)
  }

  @Test
  fun downloadAll_liveAuto_resegmentsToConfigDefault() = runTest {
    val context = context(
      connections = MutableStateFlow(1),
      config = DownloadConfig(maxConnectionsPerDownload = 3),
    )
    val requests = mutableListOf<LongRange>()
    val job = launch { downloadBlockingFirstBatch(context, firstBatch = 1, requests) }
    try {
      runCurrent()
      context.maxConnections.value = 0
      runCurrent()
      assertTrue(job.isCompleted, "Auto must restart the batch with the configured default")
      assertEquals(3, requests.drop(1).size)
      assertTrue(context.segments.value.all { it.isComplete })
    } finally {
      job.cancelAndJoin()
    }
  }

  @Test
  fun downloadAll_overrideEqualToAuto_keepsBatch() = runTest {
    val context = context(
      connections = MutableStateFlow(0),
      config = DownloadConfig(maxConnectionsPerDownload = 4),
      segments = (0..3).map { Segment(index = it, start = it * 3L, end = it * 3L + 2) },
    )
    val requests = mutableListOf<LongRange>()
    val job = launch { downloadBlockingFirstBatch(context, firstBatch = 4, requests) }
    try {
      runCurrent()
      assertEquals(4, requests.size)
      context.maxConnections.value = 4
      runCurrent()
      assertFalse(job.isCompleted)
      assertEquals(4, requests.size, "The same segment count must not restart the batch")
    } finally {
      job.cancelAndJoin()
    }
  }

  @Test
  fun downloadAll_fewerSegmentsThanAuto_resegmentsWhenSetToAuto() = runTest {
    // Two segments for four connections, as after HttpDownloadSource capped them.
    val context = context(
      connections = MutableStateFlow(4),
      config = DownloadConfig(maxConnectionsPerDownload = 4),
      segments = listOf(
        Segment(index = 0, start = 0, end = 5),
        Segment(index = 1, start = 6, end = 11),
      ),
    )
    val requests = mutableListOf<LongRange>()
    val job = launch { downloadBlockingFirstBatch(context, firstBatch = 2, requests) }
    try {
      runCurrent()
      context.maxConnections.value = 0
      runCurrent()
      assertTrue(job.isCompleted, "Auto must restart a batch running fewer segments")
      assertEquals(4, requests.drop(2).size)
    } finally {
      job.cancelAndJoin()
    }
  }

  @Test
  fun downloadAll_connectionChangeWhileBatchStops_isApplied() = runTest {
    val context = context(
      connections = MutableStateFlow(4),
      segments = (0..3).map { Segment(index = it, start = it * 3L, end = it * 3L + 2) },
    )
    val requests = mutableListOf<LongRange>()
    val job = launch {
      val helper = SegmentedDownloadHelper()
      helper.downloadAll(context, context.segments.value, 12) { lane ->
        requests.add(lane.offset..lane.end)
        if (requests.size <= 4) {
          try {
            awaitCancellation()
          } finally {
            // Closing a connection takes a while, and no watcher listens meanwhile.
            withContext(NonCancellable) { delay(100) }
          }
        }
        lane.fill()
      }
    }
    try {
      runCurrent()
      context.maxConnections.value = 5
      runCurrent()
      context.maxConnections.value = 6
      advanceUntilIdle()
      assertTrue(job.isCompleted)
      assertEquals(6, requests.drop(4).size, "The change made while stopping must be applied")
    } finally {
      job.cancelAndJoin()
    }
  }

  @Test
  fun downloadAll_connectionChangeAfterSizing_resegmentsFirstBatch() = runTest {
    // The source sized four segments, then the connections changed before the download began.
    val context = context(
      connections = MutableStateFlow(6),
      segments = (0..3).map { Segment(index = it, start = it * 3L, end = it * 3L + 2) },
    )
    val requests = mutableListOf<LongRange>()
    SegmentedDownloadHelper().downloadAll(
      context, context.segments.value, 12, requestedConnections = 4,
    ) { lane ->
      requests.add(lane.offset..lane.end)
      lane.fill()
    }
    assertEquals(6, requests.size)
    assertEquals(6, context.segments.value.size)
  }

  @Test
  fun downloadAll_finishedConnectionTakesOverSlowerOnesRemainingBytes() = runTest {
    val segments = listOf(
      Segment(index = 0, start = 0, end = 49),
      Segment(index = 1, start = 50, end = 99),
    )
    val context = context(connections = MutableStateFlow(2), segments = segments)
    val written = IntArray(100)
    val requests = mutableListOf<LongRange>()
    SegmentedDownloadHelper(minSplitBytes = 10, timeSource = testScheduler.timeSource)
      .downloadAll(context, segments, 100) { lane ->
        requests.add(lane.offset..lane.end)
        // The connection that started at byte 50 gets 10 bytes every 100 ms, the others every 10.
        val pause = if (lane.start == 50L) 100L else 10L
        var offset = lane.offset
        while (!lane.isComplete) {
          delay(pause)
          val allowed = lane.admit(10)
          for (i in 0 until allowed) written[(offset + i).toInt()]++
          offset += allowed
          lane.commit(allowed)
        }
      }
    assertTrue(written.all { it == 1 }, "Every byte must be written exactly once")
    assertTrue(requests.size > 2, "The fast connection must take over part of the slow one")
    assertTrue(
      currentTime < 500,
      "The slow connection alone would take 500 ms, took $currentTime ms",
    )
    val result = context.segments.value
    assertTrue(result.all { it.isComplete })
    assertEquals(0L, result.first().start)
    assertEquals(99L, result.last().end)
    assertTrue(result.zipWithNext().all { (a, b) -> a.end + 1 == b.start })
  }

  @Test
  fun downloadAll_withoutRangeSupport_neverSplits() = runTest {
    val segments = listOf(Segment(index = 0, start = 0, end = 99))
    val context = context(connections = MutableStateFlow(1), segments = segments)
    val requests = mutableListOf<LongRange>()
    SegmentedDownloadHelper(minSplitBytes = 1).downloadAll(
      context, segments, 100, supportsRanges = false,
    ) { lane ->
      requests.add(lane.offset..lane.end)
      lane.fill()
    }
    assertEquals(listOf(0L..99L), requests)
  }

  /** Downloads [context]'s segments; the first [firstBatch] requests wait until cancelled. */
  private suspend fun downloadBlockingFirstBatch(
    context: DownloadContext,
    firstBatch: Int,
    requests: MutableList<LongRange>,
  ) {
    SegmentedDownloadHelper().downloadAll(context, context.segments.value, 12) { lane ->
      requests.add(lane.offset..lane.end)
      if (requests.size <= firstBatch) awaitCancellation()
      lane.fill()
    }
  }

  private fun context(
    connections: MutableStateFlow<Int> = MutableStateFlow(0),
    config: DownloadConfig = DownloadConfig.Default,
    segments: List<Segment> = listOf(Segment(index = 0, start = 0, end = 11)),
    onProgress: suspend (Long, Long) -> Unit = { _, _ -> },
  ): DownloadContext = DownloadContext(
    taskId = "batch-regression",
    url = "https://example.com/file",
    request = DownloadRequest("https://example.com/file"),
    fileAccessor = NoOpFileAccessor,
    segments = MutableStateFlow(segments),
    onProgress = onProgress,
    throttle = {},
    headers = emptyMap(),
    maxConnections = connections,
    config = config,
  )
}

/** Writes the next [bytes] of the lane, as a source receiving them would. */
internal suspend fun SegmentLane.fill(bytes: Long = remaining) {
  var left = bytes
  while (left > 0) {
    val allowed = admit(minOf(left, Int.MAX_VALUE.toLong()).toInt())
    if (allowed == 0) return
    commit(allowed)
    left -= allowed
  }
}
