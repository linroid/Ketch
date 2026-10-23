package com.linroid.ketch.engine

import com.linroid.ketch.api.DownloadConfig
import com.linroid.ketch.api.DownloadRequest
import com.linroid.ketch.api.KetchError
import com.linroid.ketch.core.engine.DownloadContext
import com.linroid.ketch.core.engine.HttpDownloadSource
import com.linroid.ketch.core.engine.HttpEngine
import com.linroid.ketch.core.engine.HttpSourceState
import com.linroid.ketch.core.engine.RangeIgnoredException
import com.linroid.ketch.core.engine.ServerInfo
import com.linroid.ketch.core.engine.resolve
import com.linroid.ketch.core.file.FileAccessor
import com.linroid.ketch.core.file.NoOpFileAccessor
import kotlinx.coroutines.delay
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

    HttpDownloadSource(engine).download(context, null)

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

    HttpDownloadSource(engine).download(context, null)

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

    HttpDownloadSource(engine).download(context, null)

    assertEquals(1, engine.downloadCallCount)
    assertEquals(1000L, context.segments.value.single().downloadedBytes)
  }

  @Test
  fun download_noRangeSupportRetry_restartsFromZero() = runTest {
    val engine = FakeHttpEngine(serverInfo = NO_RANGES, failAfterBytes = 300)
    val context = context()
    val source = HttpDownloadSource(engine)
    assertFailsWith<KetchError.Network> { source.download(context, null) }
    assertEquals(300L, context.segments.value.single().downloadedBytes)

    engine.failAfterBytes = -1
    source.download(context, null)

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

    HttpDownloadSource(engine).download(context, null)

    assertContentEquals(engine.content, file.bytes)
    assertEquals(1, engine.downloadCallCount)
    assertTrue(context.segments.value.isEmpty())
    assertEquals(1000L to 1000L, progress.last())
  }

  @Test
  fun download_serverIgnoringAdvertisedRanges_restartsOverOneConnection() = runTest {
    val engine = FakeHttpEngine(ignoresRanges = true)
    val file = MemoryFile()
    val context = context(
      config = DownloadConfig(maxConnectionsPerDownload = 4),
      fileAccessor = file,
    )

    HttpDownloadSource(engine).download(context, null)

    assertContentEquals(engine.content, file.bytes)
    assertEquals(0L..999L, engine.lastDownloadRange)
    assertEquals(1000L, context.segments.value.single().downloadedBytes)
  }

  @Test
  fun resolve_redirectedLink_isNamedAfterTheAnsweringUrl() = runTest {
    val engine = FakeHttpEngine(
      serverInfo = FakeHttpEngine().serverInfo.copy(
        url = "https://cdn.example.com/pub/App%201.2.3.dmg",
      ),
    )

    val resolved = HttpDownloadSource(engine)
      .resolve("https://example.com/download?id=5", emptyMap(), DownloadConfig.Default)

    assertEquals("App 1.2.3.dmg", resolved.suggestedFileName)
  }

  @Test
  fun download_ranges_askIfRangeWithTheFilesValidator() = runTest {
    val engine = FakeHttpEngine()
    HttpDownloadSource(engine).download(context(), null)
    assertEquals("\"test-etag\"", engine.lastDownloadHeaders["If-Range"])

    // A weak ETag cannot validate a range; the modification time does.
    val weak = FakeHttpEngine(serverInfo = FakeHttpEngine().serverInfo.copy(etag = "W/\"x\""))
    HttpDownloadSource(weak).download(context(), null)
    assertEquals("Wed, 01 Jan 2025 00:00:00 GMT", weak.lastDownloadHeaders["If-Range"])
  }

  @Test
  fun download_fileReplacedWhileDownloading_savesTheNewFileWhole() = runTest {
    val before = ByteArray(1000) { 1 }
    val after = ByteArray(1000) { 2 }
    val engine = ReplacedFileEngine(before, after, replaceAfter = 2)
    val file = MemoryFile()

    HttpDownloadSource(engine).download(
      context(config = DownloadConfig(maxConnectionsPerDownload = 4), fileAccessor = file),
      null,
    )

    // Never parts of both: the new file, restarted over one connection.
    assertContentEquals(after, file.bytes)
  }

  @Test
  fun download_fileReplacedBySizeWhileDownloading_failsAsChanged() = runTest {
    val engine = ReplacedFileEngine(ByteArray(1000) { 1 }, ByteArray(700) { 2 }, replaceAfter = 2)

    assertFailsWith<KetchError.FileChanged> {
      HttpDownloadSource(engine).download(
        context(config = DownloadConfig(maxConnectionsPerDownload = 4)),
        null,
      )
    }
  }

  @Test
  fun download_unknownSizeRetry_restartsInEmptiedFile() = runTest {
    val engine = FakeHttpEngine(serverInfo = UNKNOWN_SIZE, failAfterBytes = 300)
    val file = MemoryFile()
    val context = context(fileAccessor = file)
    val source = HttpDownloadSource(engine)
    assertFailsWith<KetchError.Network> { source.download(context, null) }

    // A generated archive can come back different, here shorter, on the next request.
    engine.failAfterBytes = -1
    engine.content = ByteArray(200) { 7 }
    source.download(context, null)

    assertContentEquals(engine.content, file.bytes)
  }

  @Test
  fun resume_unknownSize_restartsWithoutCheckingServerIdentity() = runTest {
    val engine = FakeHttpEngine(serverInfo = UNKNOWN_SIZE.copy(etag = "\"new\""))
    val file = MemoryFile(ByteArray(1500) { 9 })
    val state = HttpSourceState(
      etag = "\"old\"", lastModified = null, totalBytes = -1,
    )

    HttpDownloadSource(engine).download(context(fileAccessor = file), state)

    assertContentEquals(engine.content, file.bytes)
    assertEquals(0, engine.headCallCount)
  }

  @Test
  fun resume_emptySegmentsWithFullSizeFile_downloadsFromZero() = runTest {
    val engine = FakeHttpEngine()
    // A zero-filled file of the full size passes the local file check.
    val file = MemoryFile(ByteArray(1000))
    val context = context(fileAccessor = file)
    val sourceState = HttpSourceState(null, null, totalBytes = 1000)

    HttpDownloadSource(engine).download(context, sourceState)

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

    HttpDownloadSource(engine).download(context, null)

    assertEquals(1, engine.probeCallCount)
    assertEquals(4, engine.downloadCallCount)
    assertContentEquals(engine.content, file.bytes)
  }

  @Test
  fun resume_headRefused_checksServerIdentityWithProbe() = runTest {
    val engine = FakeHttpEngine(headErrorCode = 405)
    val file = MemoryFile(ByteArray(1000))
    val state = HttpSourceState(
      etag = "\"old\"", lastModified = null, totalBytes = 1000,
    )

    assertFailsWith<KetchError.FileChanged> {
      HttpDownloadSource(engine).download(context(fileAccessor = file), state)
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

  @Test
  fun download_finishedConnectionTakesOverTheSlowOnesTail() = runTest {
    val size = 4 * 1024 * 1024
    val half = size / 2L
    val (requests, context) = downloadWithSlowSecondConnection(
      ServerInfo(size.toLong(), true, null, null),
    )
    assertTrue(
      requests.any { it.first > half },
      "The first connection must take over the end of the second one: $requests",
    )
    assertTrue(context.segments.value.all { it.isComplete })
    assertEquals(size.toLong(), context.segments.value.sumOf { it.totalBytes })
  }

  @Test
  fun download_requestQuota_neverTakesOverRanges() = runTest {
    val size = 4 * 1024 * 1024L
    val (requests, _) = downloadWithSlowSecondConnection(
      ServerInfo(size, true, null, null, rateLimitRemaining = 2),
    )
    assertEquals(2, requests.size, "Taking over a range would spend a request: $requests")
  }

  /**
   * Downloads [serverInfo]'s content over two connections, the second of which starts slow, and
   * checks the file; returns the requests sent and the context.
   */
  private suspend fun downloadWithSlowSecondConnection(
    serverInfo: ServerInfo,
  ): Pair<List<LongRange>, DownloadContext> {
    val size = checkNotNull(serverInfo.contentLength).toInt()
    val half = size / 2L
    val content = ByteArray(size) { (it * 31 % 251).toByte() }
    val requests = mutableListOf<LongRange>()
    val fake = FakeHttpEngine(serverInfo)
    val engine = object : HttpEngine by fake {
      override suspend fun download(
        url: String,
        range: LongRange?,
        headers: Map<String, String>,
        onData: suspend (ByteArray) -> Unit,
      ) {
        val requested = checkNotNull(range)
        requests.add(requested)
        // The request the second connection starts with is slow, every other one fast.
        val pause = if (requested.first == half) 100L else 1L
        var offset = requested.first
        while (offset <= requested.last) {
          delay(pause)
          val end = minOf(offset + 64 * 1024, requested.last + 1)
          onData(content.copyOfRange(offset.toInt(), end.toInt()))
          offset = end
        }
      }
    }
    val file = MemoryFile()
    val context = context(connections = MutableStateFlow(2), fileAccessor = file)

    HttpDownloadSource(engine).download(context, null)

    assertContentEquals(content, file.bytes)
    return requests to context
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

  /**
   * Serves [before] (ETag `"a"`) until [replaceAfter] transfers started, then [after] (`"b"`),
   * honoring `If-Range` as servers do: a range whose validator no longer matches gets the whole
   * file, which the engine reports for a part as [RangeIgnoredException].
   */
  private class ReplacedFileEngine(
    private val before: ByteArray,
    private val after: ByteArray,
    private val replaceAfter: Int,
  ) : HttpEngine {
    private var transfers = 0

    override suspend fun head(url: String, headers: Map<String, String>) =
      ServerInfo(before.size.toLong(), acceptRanges = true, etag = "\"a\"", lastModified = null)

    override suspend fun download(
      url: String,
      range: LongRange?,
      headers: Map<String, String>,
      onData: suspend (ByteArray) -> Unit,
    ) {
      val replaced = ++transfers > replaceAfter
      val content = if (replaced) after else before
      val current = if (replaced) "\"b\"" else "\"a\""
      val asked = checkNotNull(range)
      if (headers["If-Range"] == current) return onData(content.sliceArray(asked.toIntRange()))
      // Changed: the whole file, which only a range of all of it, and of its size, takes.
      if (asked.first != 0L || asked.last != content.size - 1L) {
        throw KetchError.Unsupported(RangeIgnoredException())
      }
      onData(content)
    }

    override fun close() {}

    private fun LongRange.toIntRange() = first.toInt()..last.toInt()
  }
}

