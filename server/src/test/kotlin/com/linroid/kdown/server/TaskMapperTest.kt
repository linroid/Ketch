package com.linroid.kdown.server

import com.linroid.kdown.api.DownloadProgress
import com.linroid.kdown.api.DownloadSchedule
import com.linroid.kdown.api.DownloadState
import com.linroid.kdown.api.KDownError
import com.linroid.kdown.api.Segment
import kotlinx.io.files.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TaskMapperTest {

  @Test
  fun `stateToString maps all states`() {
    assertEquals("idle", TaskMapper.stateToString(
      DownloadState.Idle
    ))
    assertEquals("scheduled", TaskMapper.stateToString(
      DownloadState.Scheduled(DownloadSchedule.Immediate)
    ))
    assertEquals("queued", TaskMapper.stateToString(
      DownloadState.Queued
    ))
    assertEquals("pending", TaskMapper.stateToString(
      DownloadState.Pending
    ))
    assertEquals("downloading", TaskMapper.stateToString(
      DownloadState.Downloading(
        DownloadProgress(100, 1000)
      )
    ))
    assertEquals("paused", TaskMapper.stateToString(
      DownloadState.Paused(DownloadProgress(500, 1000))
    ))
    assertEquals("completed", TaskMapper.stateToString(
      DownloadState.Completed(Path("/tmp/file.zip"))
    ))
    assertEquals("failed", TaskMapper.stateToString(
      DownloadState.Failed(
        KDownError.Network(RuntimeException("test"))
      )
    ))
    assertEquals("canceled", TaskMapper.stateToString(
      DownloadState.Canceled
    ))
  }

  @Test
  fun `toProgressResponse maps all fields`() {
    val progress = DownloadProgress(
      downloadedBytes = 500,
      totalBytes = 1000,
      bytesPerSecond = 100
    )
    val response = TaskMapper.toProgressResponse(progress)
    assertEquals(500L, response.downloadedBytes)
    assertEquals(1000L, response.totalBytes)
    assertEquals(0.5f, response.percent)
    assertEquals(100L, response.bytesPerSecond)
  }

  @Test
  fun `toSegmentResponse maps all fields`() {
    val segment = Segment(
      index = 0,
      start = 0,
      end = 499,
      downloadedBytes = 250
    )
    val response = TaskMapper.toSegmentResponse(segment)
    assertEquals(0, response.index)
    assertEquals(0L, response.start)
    assertEquals(499L, response.end)
    assertEquals(250L, response.downloadedBytes)
    assertEquals(false, response.isComplete)
  }

  @Test
  fun `toSegmentResponse for complete segment`() {
    val segment = Segment(
      index = 1,
      start = 500,
      end = 999,
      downloadedBytes = 500
    )
    val response = TaskMapper.toSegmentResponse(segment)
    assertTrue(response.isComplete)
  }
}
