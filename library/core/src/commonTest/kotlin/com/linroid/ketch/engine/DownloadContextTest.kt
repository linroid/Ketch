package com.linroid.ketch.engine

import com.linroid.ketch.api.DownloadConfig
import com.linroid.ketch.api.DownloadRequest
import com.linroid.ketch.core.engine.DownloadContext
import com.linroid.ketch.core.file.NoOpFileAccessor
import kotlinx.coroutines.flow.MutableStateFlow
import kotlin.test.Test
import kotlin.test.assertEquals

class DownloadContextTest {
  @Test
  fun effectiveConnections_nothingRequested_usesConfigDefault() {
    assertEquals(6, context(requested = 0, live = 0).effectiveConnections())
  }

  @Test
  fun effectiveConnections_requestSet_overridesConfigDefault() {
    assertEquals(2, context(requested = 2, live = 0).effectiveConnections())
  }

  @Test
  fun effectiveConnections_liveOverride_winsOverRequest() {
    assertEquals(9, context(requested = 2, live = 9).effectiveConnections())
  }

  private fun context(requested: Int, live: Int): DownloadContext = DownloadContext(
    taskId = "context",
    url = "https://example.com/file",
    request = DownloadRequest("https://example.com/file", connections = requested),
    fileAccessor = NoOpFileAccessor,
    segments = MutableStateFlow(emptyList()),
    onProgress = { _, _ -> },
    throttle = {},
    headers = emptyMap(),
    maxConnections = MutableStateFlow(live),
    config = DownloadConfig(maxConnectionsPerDownload = 6),
  )
}
