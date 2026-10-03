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
import kotlin.test.assertContentEquals
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
  fun download_unknownSize_streamsWholeResponse() = runTest {
    val engine = FakeHttpEngine(serverInfo = UNKNOWN_SIZE)
    val file = MemoryFile()
    val progress = mutableListOf<Pair<Long, Long>>()
    val context = context(file = file, onProgress = { downloaded, total ->
      progress += downloaded to total
    })

    HttpDownloadSource(engine).download(context)

    assertContentEquals(engine.content, file.bytes)
    assertEquals(1, engine.downloadCallCount)
    assertTrue(context.segments.value.isEmpty())
    assertEquals(1000L to 1000L, progress.last())
  }

  @Test
  fun download_unknownSizeRetry_restartsInEmptiedFile() = runTest {
    val engine = FakeHttpEngine(serverInfo = UNKNOWN_SIZE, failAfterBytes = 300)
    val file = MemoryFile()
    val context = context(file = file)
    val source = HttpDownloadSource(engine)
    assertFailsWith<KetchError.Network> { source.download(context) }

    // A generated archive can come back different, here shorter, on the next request.
    engine.failAfterBytes = -1
    engine.content = ByteArray(200) { 7 }
    source.download(context)

    assertContentEquals(engine.content, file.bytes)
  }

  @Test
  fun resume_unknownSize_restartsWithoutCheckingServerIdentity() = runTest {
    val engine = FakeHttpEngine(serverInfo = UNKNOWN_SIZE.copy(etag = "\"new\""))
    val file = MemoryFile(ByteArray(1500) { 9 })
    val state = HttpDownloadSource.buildResumeState(
      etag = "\"old\"", lastModified = null, totalBytes = -1,
    )

    HttpDownloadSource(engine).resume(context(file = file), state)

    assertContentEquals(engine.content, file.bytes)
    assertEquals(0, engine.headCallCount)
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
    file: FileAccessor = NoOpFileAccessor,
    onProgress: suspend (Long, Long) -> Unit = { _, _ -> },
  ): DownloadContext = DownloadContext(
    taskId = "http-source",
    url = "https://example.com/file",
    request = DownloadRequest("https://example.com/file"),
    fileAccessor = file,
    segments = MutableStateFlow(emptyList()),
    onProgress = onProgress,
    throttle = throttle,
    headers = emptyMap(),
    maxConnections = connections,
    config = config,
  )

  /** A file kept in memory; [preallocate] resizes it. */
  private class MemoryFile(
    var bytes: ByteArray = ByteArray(0),
  ) : FileAccessor by NoOpFileAccessor {
    override suspend fun writeAt(offset: Long, data: ByteArray) {
      val end = offset.toInt() + data.size
      if (end > bytes.size) bytes = bytes.copyOf(end)
      data.copyInto(bytes, offset.toInt())
    }

    override suspend fun size(): Long = bytes.size.toLong()

    override suspend fun preallocate(size: Long) {
      bytes = bytes.copyOf(size.toInt())
    }
  }

  private companion object {
    val UNKNOWN_SIZE = ServerInfo(
      contentLength = null,
      acceptRanges = false,
      etag = null,
      lastModified = null,
    )
    val NO_RANGES = ServerInfo(
      contentLength = 1000,
      acceptRanges = false,
      etag = null,
      lastModified = null,
    )
  }
}
