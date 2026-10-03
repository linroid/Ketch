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
  fun download_unknownSize_streamsWholeResponse() = runTest {
    val engine = FakeHttpEngine(serverInfo = UNKNOWN_SIZE)
    val file = MemoryFile()
    val progress = mutableListOf<Pair<Long, Long>>()
    val context = context(fileAccessor = file, onProgress = { downloaded, total ->
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
    val context = context(fileAccessor = file)
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

    HttpDownloadSource(engine).resume(context(fileAccessor = file), state)

    assertContentEquals(engine.content, file.bytes)
    assertEquals(0, engine.headCallCount)
  }

  @Test
  fun resume_emptySegmentsWithFullSizeFile_downloadsFromZero() = runTest {
    val engine = FakeHttpEngine()
    // A zero-filled file of the full size passes the local file check.
    val file = MemoryFile(ByteArray(1000))
    val context = context(fileAccessor = file)
    val resumeState = HttpDownloadSource.buildResumeState(null, null, totalBytes = 1000)

    HttpDownloadSource(engine).resume(context, resumeState)

    assertContentEquals(engine.content, file.bytes)
    assertTrue(context.segments.value.all { it.isComplete })
  }

  @Test
  fun download_headRefused_probesWithGetAndDownloadsSegments() = runTest {
    val engine = FakeHttpEngine(headErrorCode = 403)
    val file = MemoryFile()
    val context = context(
      config = DownloadConfig(maxConnectionsPerDownload = 4),
      fileAccessor = file,
    )

    HttpDownloadSource(engine).download(context)

    assertEquals(1, engine.probeCallCount)
    assertEquals(4, engine.downloadCallCount)
    assertContentEquals(engine.content, file.bytes)
  }

  @Test
  fun resume_headRefused_checksServerIdentityWithProbe() = runTest {
    val engine = FakeHttpEngine(headErrorCode = 405)
    val file = MemoryFile(ByteArray(1000))
    val state = HttpDownloadSource.buildResumeState(
      etag = "\"old\"", lastModified = null, totalBytes = 1000,
    )

    assertFailsWith<KetchError.FileChanged> {
      HttpDownloadSource(engine).resume(context(fileAccessor = file), state)
    }
    assertEquals(1, engine.probeCallCount)
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
    onProgress: suspend (Long, Long) -> Unit = { _, _ -> },
  ): DownloadContext = DownloadContext(
    taskId = "http-source",
    url = "https://example.com/file",
    request = DownloadRequest("https://example.com/file"),
    fileAccessor = fileAccessor,
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
