package com.linroid.kdown.api

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DownloadProgressTest {

  @Test
  fun percent_halfComplete() {
    val progress = DownloadProgress(downloadedBytes = 500, totalBytes = 1000)
    assertEquals(0.5f, progress.percent)
  }

  @Test
  fun percent_complete() {
    val progress = DownloadProgress(downloadedBytes = 1000, totalBytes = 1000)
    assertEquals(1.0f, progress.percent)
  }

  @Test
  fun percent_zeroProgress() {
    val progress = DownloadProgress(downloadedBytes = 0, totalBytes = 1000)
    assertEquals(0.0f, progress.percent)
  }

  @Test
  fun percent_zeroTotal_returnsZero() {
    val progress = DownloadProgress(downloadedBytes = 0, totalBytes = 0)
    assertEquals(0.0f, progress.percent)
  }

  @Test
  fun isComplete_whenFullyDownloaded() {
    val progress = DownloadProgress(downloadedBytes = 500, totalBytes = 500)
    assertTrue(progress.isComplete)
  }

  @Test
  fun isComplete_whenOverDownloaded() {
    val progress = DownloadProgress(downloadedBytes = 600, totalBytes = 500)
    assertTrue(progress.isComplete)
  }

  @Test
  fun isNotComplete_whenPartial() {
    val progress = DownloadProgress(downloadedBytes = 200, totalBytes = 500)
    assertFalse(progress.isComplete)
  }

  @Test
  fun isNotComplete_whenZeroTotal() {
    val progress = DownloadProgress(downloadedBytes = 0, totalBytes = 0)
    assertFalse(progress.isComplete)
  }

  @Test
  fun defaultBytesPerSecond_isZero() {
    val progress = DownloadProgress(downloadedBytes = 100, totalBytes = 200)
    assertEquals(0, progress.bytesPerSecond)
  }
}
