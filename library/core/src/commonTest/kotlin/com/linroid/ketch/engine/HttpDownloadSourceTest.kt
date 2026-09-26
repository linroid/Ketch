package com.linroid.ketch.engine

import com.linroid.ketch.api.DownloadConfig
import com.linroid.ketch.api.DownloadRequest
import com.linroid.ketch.core.engine.DownloadContext
import com.linroid.ketch.core.engine.HttpDownloadSource
import com.linroid.ketch.core.engine.ServerInfo
import com.linroid.ketch.core.file.NoOpFileAccessor
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class HttpDownloadSourceTest {
  @Test
  fun download_noConnectionsRequested_usesConfigDefault() = runTest {
    val engine = FakeHttpEngine()
    val context = context(config = DownloadConfig(maxConnectionsPerDownload = 3))

    HttpDownloadSource(engine).download(context)

    assertEquals(3, engine.downloadCallCount)
    assertEquals(3, context.segments.value.size)
    assertTrue(context.segments.value.all { it.isComplete })
  }

  @Test
  fun download_noRangeSupport_ignoresLiveConnectionChange() = runTest {
    val engine = FakeHttpEngine(serverInfo = NO_RANGES)
    val connections = MutableStateFlow(0)
    var chunks = 0
    val context = context(connections = connections, throttle = {
      if (++chunks == 2) {
        connections.value = 4
        yield()
      }
    })

    HttpDownloadSource(engine).download(context)

    assertEquals(1, engine.downloadCallCount)
    assertEquals(1000L, context.segments.value.single().downloadedBytes)
  }

  @Test
  fun resolve_withConfig_reportsDefaultConnectionsAsMaxSegments() = runTest {
    val source = HttpDownloadSource(FakeHttpEngine())

    val resolved = source.resolve(
      "https://example.com/file", emptyMap(), DownloadConfig(maxConnectionsPerDownload = 6),
    )

    assertEquals(6, resolved.maxSegments)
  }

  private fun context(
    config: DownloadConfig = DownloadConfig.Default,
    connections: MutableStateFlow<Int> = MutableStateFlow(0),
    throttle: suspend (Int) -> Unit = {},
  ): DownloadContext = DownloadContext(
    taskId = "http-source",
    url = "https://example.com/file",
    request = DownloadRequest("https://example.com/file"),
    fileAccessor = NoOpFileAccessor,
    segments = MutableStateFlow(emptyList()),
    onProgress = { _, _ -> },
    throttle = throttle,
    headers = emptyMap(),
    maxConnections = connections,
    config = config,
  )

  private companion object {
    val NO_RANGES = ServerInfo(
      contentLength = 1000,
      acceptRanges = false,
      etag = null,
      lastModified = null,
    )
  }
}
