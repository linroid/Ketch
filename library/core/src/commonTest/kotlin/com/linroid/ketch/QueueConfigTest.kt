package com.linroid.ketch

import com.linroid.ketch.api.config.QueueConfig
import kotlin.test.Test
import kotlin.test.assertFailsWith

class QueueConfigTest {

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
}
