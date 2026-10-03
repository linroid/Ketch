package com.linroid.ketch.engine

import com.linroid.ketch.api.DownloadConfig
import com.linroid.ketch.api.DownloadRequest
import com.linroid.ketch.api.KetchError
import com.linroid.ketch.core.engine.DownloadContext
import com.linroid.ketch.core.engine.HttpDownloadSource
import com.linroid.ketch.core.engine.ServerInfo
import com.linroid.ketch.core.file.FileAccessor
import com.linroid.ketch.core.file.NoOpFileAccessor
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
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
  fun download_connectionChangeWhilePreparing_isApplied() = runTest {
    val engine = FakeHttpEngine()
    val connections = MutableStateFlow(4)
    // The change lands after the segments are sized and before the first batch starts.
    val accessor = object : FileAccessor by NoOpFileAccessor {
      override suspend fun preallocate(size: Long) {
        connections.value = 6
      }
    }
    val context = context(connections = connections, fileAccessor = accessor)

    HttpDownloadSource(engine).download(context)

    assertEquals(6, engine.downloadCallCount)
    assertEquals(6, context.segments.value.size)
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
  fun download_noRangeSupportRetry_restartsFromZero() = runTest {
    val engine = FakeHttpEngine(serverInfo = NO_RANGES, failAfterBytes = 300)
    val context = context()
    val source = HttpDownloadSource(engine)
    assertFailsWith<KetchError.Network> { source.download(context) }
    assertEquals(300L, context.segments.value.single().downloadedBytes)

    engine.failAfterBytes = -1
    source.download(context)

    val segment = context.segments.value.single()
    assertEquals(0L, segment.start)
    assertEquals(1000L, segment.downloadedBytes)
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
    fileAccessor: FileAccessor = NoOpFileAccessor,
  ): DownloadContext = DownloadContext(
    taskId = "http-source",
    url = "https://example.com/file",
    request = DownloadRequest("https://example.com/file"),
    fileAccessor = fileAccessor,
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
