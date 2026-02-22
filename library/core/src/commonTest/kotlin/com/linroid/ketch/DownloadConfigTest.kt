package com.linroid.ketch

import com.linroid.ketch.api.config.DownloadConfig
import kotlin.test.Test
import kotlin.test.assertFailsWith

class DownloadConfigTest {

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
