package com.linroid.ketch.segment

import com.linroid.ketch.api.DownloadConfig
import com.linroid.ketch.api.DownloadRequest
import com.linroid.ketch.api.Segment
import com.linroid.ketch.core.engine.DownloadContext
import com.linroid.ketch.core.file.NoOpFileAccessor
import com.linroid.ketch.core.segment.SegmentedDownloadHelper
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
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
    val downloadedIndices = mutableListOf<Int>()
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
    SegmentedDownloadHelper().downloadAll(context, initial, 12) { segment, onProgress ->
      downloadedIndices.add(segment.index)
      onProgress(segment.totalBytes)
      segment.copy(downloadedBytes = segment.totalBytes)
    }
    assertEquals(setOf(1, 2), downloadedIndices.toSet())
    assertEquals(initial.map { it.copy(downloadedBytes = it.totalBytes) }, segments.value)
    assertEquals(12L, finalProgress)
  }

  @Test
  fun downloadAll_usesFinalProgressReturnedBySource() = runTest {
    val context = context()
    SegmentedDownloadHelper().downloadAll(context, context.segments.value, 12) { segment, _ ->
      segment.copy(downloadedBytes = segment.totalBytes)
    }
    assertEquals(12L, context.segments.value.single().downloadedBytes)
  }

  @Test
  fun downloadAll_cancellationPreservesUnreportedProgress() = runTest {
    val context = context()
    val job = launch {
      SegmentedDownloadHelper().downloadAll(context, context.segments.value, 12) { _, report ->
        report(3)
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
      SegmentedDownloadHelper().downloadAll(context, context.segments.value, 12) { _, report ->
        report(3)
        error("connection lost")
      }
    }
    assertEquals(3L, context.segments.value.single().downloadedBytes)
  }

  @Test
  fun downloadAll_connectionChangeCancelsAndRestartsBatch() = runTest {
    val context = context()
    val requests = mutableListOf<Segment>()
    val job = launch {
      val helper = SegmentedDownloadHelper()
      helper.downloadAll(context, context.segments.value, 12) { segment, report ->
        requests.add(segment)
        if (requests.size == 1) {
          report(3)
          awaitCancellation()
        }
        report(segment.totalBytes)
        segment.copy(downloadedBytes = segment.totalBytes)
      }
    }
    try {
      runCurrent()
      context.maxConnections.value = 2
      runCurrent()
      assertTrue(job.isCompleted, "Changing connections must restart the active transfers")
      assertEquals(listOf(0L..11L, 3L..7L, 8L..11L), requests.map { it.start..it.end })
      assertTrue(context.segments.value.all { it.isComplete })
    } finally {
      job.cancelAndJoin()
    }
  }

  @Test
  fun downloadAll_connectionChangeDuringInitialProgress_isApplied() = runTest {
    val connections = MutableStateFlow(1)
    val context = context(connections) { _, _ -> connections.value = 2 }
    val requests = mutableListOf<Segment>()
    SegmentedDownloadHelper().downloadAll(context, context.segments.value, 12) { segment, report ->
      requests.add(segment)
      report(segment.totalBytes)
      segment.copy(downloadedBytes = segment.totalBytes)
    }
    assertEquals(listOf(0L..5L, 6L..11L), requests.map { it.start..it.end })
  }

  @Test
  fun downloadAll_liveAuto_resegmentsToConfigDefault() = runTest {
    val context = context(
      connections = MutableStateFlow(1),
      config = DownloadConfig(maxConnectionsPerDownload = 3),
    )
    val requests = mutableListOf<Segment>()
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
    val requests = mutableListOf<Segment>()
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
    val requests = mutableListOf<Segment>()
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

  /** Downloads [context]'s segments; the first [firstBatch] requests wait until cancelled. */
  private suspend fun downloadBlockingFirstBatch(
    context: DownloadContext,
    firstBatch: Int,
    requests: MutableList<Segment>,
  ) {
    SegmentedDownloadHelper().downloadAll(context, context.segments.value, 12) { segment, report ->
      requests.add(segment)
      if (requests.size <= firstBatch) awaitCancellation()
      report(segment.totalBytes)
      segment.copy(downloadedBytes = segment.totalBytes)
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
