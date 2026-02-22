package com.linroid.ketch

import com.linroid.ketch.api.DownloadConfig
import kotlin.test.Test
import kotlin.test.assertFailsWith

class DownloadConfigTest {

  @Test
  fun invalidMaxSegments_throws() {
    assertFailsWith<IllegalArgumentException> {
      DownloadConfig(maxConnectionsPerDownload = 0)
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
      DownloadConfig(progressIntervalMs = 0)
    }
  }

  @Test
  fun zeroBufferSize_throws() {
    assertFailsWith<IllegalArgumentException> {
      DownloadConfig(bufferSize = 0)
    }
  }

  @Test
  fun negativeMaxConcurrentDownloads_throws() {
    assertFailsWith<IllegalArgumentException> {
      DownloadConfig(maxConcurrentDownloads = -1)
    }
  }

  @Test
  fun negativeMaxConnectionsPerHost_throws() {
    assertFailsWith<IllegalArgumentException> {
      DownloadConfig(maxConnectionsPerHost = -1)
    }
  }
}
