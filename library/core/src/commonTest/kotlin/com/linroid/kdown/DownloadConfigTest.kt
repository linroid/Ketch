package com.linroid.kdown

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class DownloadConfigTest {

  @Test
  fun defaultConfig_hasExpectedValues() {
    val config = DownloadConfig.Default
    assertEquals(4, config.maxConnections)
    assertEquals(3, config.retryCount)
    assertEquals(1000, config.retryDelayMs)
    assertEquals(200, config.progressUpdateIntervalMs)
    assertEquals(8192, config.bufferSize)
  }

  @Test
  fun customConfig_preservesValues() {
    val config = DownloadConfig(
      maxConnections = 8,
      retryCount = 5,
      retryDelayMs = 2000,
      progressUpdateIntervalMs = 500,
      bufferSize = 16384
    )
    assertEquals(8, config.maxConnections)
    assertEquals(5, config.retryCount)
    assertEquals(2000, config.retryDelayMs)
    assertEquals(500, config.progressUpdateIntervalMs)
    assertEquals(16384, config.bufferSize)
  }

  @Test
  fun zeroRetryCount_allowed() {
    val config = DownloadConfig(retryCount = 0)
    assertEquals(0, config.retryCount)
  }

  @Test
  fun invalidMaxConnections_throws() {
    assertFailsWith<IllegalArgumentException> {
      DownloadConfig(maxConnections = 0)
    }
  }

  @Test
  fun negativeRetryCount_throws() {
    assertFailsWith<IllegalArgumentException> {
      DownloadConfig(retryCount = -1)
    }
  }

  @Test
  fun negativeRetryDelay_throws() {
    assertFailsWith<IllegalArgumentException> {
      DownloadConfig(retryDelayMs = -1)
    }
  }

  @Test
  fun zeroProgressInterval_throws() {
    assertFailsWith<IllegalArgumentException> {
      DownloadConfig(progressUpdateIntervalMs = 0)
    }
  }

  @Test
  fun zeroBufferSize_throws() {
    assertFailsWith<IllegalArgumentException> {
      DownloadConfig(bufferSize = 0)
    }
  }
}
