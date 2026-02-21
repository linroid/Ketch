package com.linroid.kdown

import com.linroid.kdown.api.SpeedLimit
import com.linroid.kdown.api.config.DownloadConfig
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class DownloadConfigTest {

  @Test
  fun defaultConfig_hasExpectedValues() {
    val config = DownloadConfig.Default
    assertEquals(4, config.maxConnections)
    assertEquals(3, config.retryCount)
    assertEquals(1000, config.retryDelayMs)
    assertEquals(200, config.progressUpdateIntervalMs)
    assertEquals(8192, config.bufferSize)
    assertTrue(config.speedLimit.isUnlimited)
  }

  @Test
  fun customConfig_preservesValues() {
    val config = DownloadConfig(
      maxConnections = 8,
      retryCount = 5,
      retryDelayMs = 2000,
      progressUpdateIntervalMs = 500,
      bufferSize = 16384,
    )
    assertEquals(8, config.maxConnections)
    assertEquals(5, config.retryCount)
    assertEquals(2000, config.retryDelayMs)
    assertEquals(500, config.progressUpdateIntervalMs)
    assertEquals(16384, config.bufferSize)
  }

  @Test
  fun customSpeedLimit_preserved() {
    val limit = SpeedLimit.kbps(512)
    val config = DownloadConfig(speedLimit = limit)
    assertEquals(limit, config.speedLimit)
    assertEquals(512 * 1024L, config.speedLimit.bytesPerSecond)
  }

  @Test
  fun defaultSpeedLimit_isUnlimited() {
    val config = DownloadConfig()
    assertEquals(SpeedLimit.Unlimited, config.speedLimit)
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
