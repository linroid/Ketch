package com.linroid.kdown

import com.linroid.kdown.api.config.DownloadConfig
import com.linroid.kdown.api.config.QueueConfig
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class QueueConfigTest {

  @Test
  fun defaultConfig_hasExpectedValues() {
    val config = QueueConfig.Default
    assertEquals(3, config.maxConcurrentDownloads)
    assertEquals(4, config.maxConnectionsPerHost)
    assertTrue(config.autoStart)
  }

  @Test
  fun customConfig_preservesValues() {
    val config = QueueConfig(
      maxConcurrentDownloads = 5,
      maxConnectionsPerHost = 2,
      autoStart = false,
    )
    assertEquals(5, config.maxConcurrentDownloads)
    assertEquals(2, config.maxConnectionsPerHost)
    assertEquals(false, config.autoStart)
  }

  @Test
  fun zeroMaxConcurrentDownloads_throws() {
    assertFailsWith<IllegalArgumentException> {
      QueueConfig(maxConcurrentDownloads = 0)
    }
  }

  @Test
  fun negativeMaxConcurrentDownloads_throws() {
    assertFailsWith<IllegalArgumentException> {
      QueueConfig(maxConcurrentDownloads = -1)
    }
  }

  @Test
  fun zeroMaxConnectionsPerHost_throws() {
    assertFailsWith<IllegalArgumentException> {
      QueueConfig(maxConnectionsPerHost = 0)
    }
  }

  @Test
  fun negativeMaxConnectionsPerHost_throws() {
    assertFailsWith<IllegalArgumentException> {
      QueueConfig(maxConnectionsPerHost = -1)
    }
  }

  @Test
  fun singleConcurrentDownload_allowed() {
    val config = QueueConfig(maxConcurrentDownloads = 1)
    assertEquals(1, config.maxConcurrentDownloads)
  }

  @Test
  fun singleConnectionPerHost_allowed() {
    val config = QueueConfig(maxConnectionsPerHost = 1)
    assertEquals(1, config.maxConnectionsPerHost)
  }

  @Test
  fun equality_sameValues() {
    val a = QueueConfig(
      maxConcurrentDownloads = 5,
      maxConnectionsPerHost = 3,
      autoStart = true,
    )
    val b = QueueConfig(
      maxConcurrentDownloads = 5,
      maxConnectionsPerHost = 3,
      autoStart = true,
    )
    assertEquals(a, b)
  }

  @Test
  fun copy_preservesFields() {
    val original = QueueConfig(
      maxConcurrentDownloads = 5,
      maxConnectionsPerHost = 3,
      autoStart = false,
    )
    val copy = original.copy(maxConcurrentDownloads = 10)
    assertEquals(10, copy.maxConcurrentDownloads)
    assertEquals(3, copy.maxConnectionsPerHost)
    assertEquals(false, copy.autoStart)
  }

  @Test
  fun downloadConfig_includesQueueConfig() {
    val queueConfig = QueueConfig(
      maxConcurrentDownloads = 2,
      maxConnectionsPerHost = 1,
    )
    val config = DownloadConfig(queueConfig = queueConfig)
    assertEquals(queueConfig, config.queueConfig)
    assertEquals(2, config.queueConfig.maxConcurrentDownloads)
  }

  @Test
  fun downloadConfig_defaultQueueConfig() {
    val config = DownloadConfig()
    assertEquals(QueueConfig.Default, config.queueConfig)
  }
}
