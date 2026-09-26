package com.linroid.ketch.ftp

import com.linroid.ketch.api.DownloadConfig
import com.linroid.ketch.api.DownloadRequest
import com.linroid.ketch.api.KetchError
import com.linroid.ketch.api.Segment
import com.linroid.ketch.core.engine.DownloadContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FtpDownloadSourceTest {

  private val source = FtpDownloadSource()

  @Test
  fun canHandle_ftpUrl() {
    assertTrue(source.canHandle("ftp://example.com/file.zip"))
  }

  @Test
  fun canHandle_ftpsUrl() {
    assertTrue(source.canHandle("ftps://example.com/file.zip"))
  }

  @Test
  fun canHandle_ftpUrl_caseInsensitive() {
    assertTrue(source.canHandle("FTP://example.com/file.zip"))
    assertTrue(source.canHandle("FTPS://example.com/file.zip"))
    assertTrue(source.canHandle("Ftp://example.com/file.zip"))
    assertTrue(source.canHandle("Ftps://example.com/file.zip"))
  }

  @Test
  fun canHandle_ftpUrl_withCredentials() {
    assertTrue(
      source.canHandle("ftp://user:pass@example.com/file.zip")
    )
  }

  @Test
  fun canHandle_ftpUrl_withPort() {
    assertTrue(source.canHandle("ftp://example.com:2121/file.zip"))
  }

  @Test
  fun canHandle_httpUrl_returnsFalse() {
    assertFalse(source.canHandle("http://example.com/file.zip"))
  }

  @Test
  fun canHandle_httpsUrl_returnsFalse() {
    assertFalse(source.canHandle("https://example.com/file.zip"))
  }

  @Test
  fun canHandle_sftpUrl_returnsFalse() {
    assertFalse(source.canHandle("sftp://example.com/file.zip"))
  }

  @Test
  fun canHandle_magnetUrl_returnsFalse() {
    assertFalse(source.canHandle("magnet:?xt=urn:btih:abc123"))
  }

  @Test
  fun canHandle_localPath_returnsFalse() {
    assertFalse(source.canHandle("/local/path/file.zip"))
  }

  @Test
  fun canHandle_emptyString_returnsFalse() {
    assertFalse(source.canHandle(""))
  }

  @Test
  fun download_noConnectionsRequested_usesGlobalDefault() = runTest {
    val server = FakeFtpServer(content(1000))
    val file = MemoryFileAccessor()
    val context = context(
      file,
      config = DownloadConfig(maxConnectionsPerDownload = 3, bufferSize = 4096),
    )

    source(server).download(context)

    assertEquals(listOf(0L, 334L, 667L), server.retrieveOffsets.sorted())
    assertContentEquals(server.content, file.bytes)
    assertTrue(server.bufferSizes.all { it == 4096 })
  }

  @Test
  fun resolve_withConfig_reportsDefaultConnectionsAsMaxSegments() = runTest {
    val resolved = source(FakeFtpServer(content(1000)))
      .resolve(URL, emptyMap(), DownloadConfig(maxConnectionsPerDownload = 6))

    assertEquals(6, resolved.maxSegments)
  }

  @Test
  fun download_noRestSupport_usesSingleConnection() = runTest {
    val server = FakeFtpServer(content(1000), supportsRest = false)
    val file = MemoryFileAccessor()

    source(server).download(context(file, connections = 4))

    assertEquals(listOf(0L), server.retrieveOffsets)
    assertContentEquals(server.content, file.bytes)
  }

  @Test
  fun download_retryAfterDroppedConnection_keepsProgress() = runTest {
    val server = FakeFtpServer(content(1000))
    server.dropNextTransferAfter = 300
    val file = MemoryFileAccessor()
    val context = context(file, connections = 1)
    val source = source(server)

    assertFailsWith<KetchError.Network> { source.download(context) }
    assertEquals(300L, context.segments.value.sumOf { it.downloadedBytes })
    // The engine retries a retryable failure by calling download again with the same context.
    source.download(context)

    assertEquals(listOf(0L, 300L), server.retrieveOffsets)
    assertContentEquals(server.content, file.bytes)
  }

  @Test
  fun resume_noRestSupport_restartsFromZeroWithSingleConnection() = runTest {
    val server = FakeFtpServer(content(1000), supportsRest = false)
    val file = MemoryFileAccessor()
    file.preallocate(1000)
    val context = context(file, connections = 2)
    context.segments.value = listOf(
      Segment(index = 0, start = 0, end = 499, downloadedBytes = 200),
      Segment(index = 1, start = 500, end = 999, downloadedBytes = 100),
    )

    source(server).resume(context, FtpDownloadSource.buildResumeState(1000, null))

    assertEquals(listOf(0L), server.retrieveOffsets)
    assertContentEquals(server.content, file.bytes)
  }

  @Test
  fun download_noRestSupport_ignoresLiveConnectionChange() = runTest {
    val server = FakeFtpServer(content(1000), supportsRest = false)
    val file = MemoryFileAccessor()
    val connections = MutableStateFlow(0)
    var chunks = 0
    val context = context(file, maxConnections = connections, throttle = {
      if (++chunks == 2) {
        connections.value = 4
        yield()
      }
    })

    source(server).download(context)

    assertEquals(listOf(0L), server.retrieveOffsets)
    assertContentEquals(server.content, file.bytes)
  }

  private fun source(server: FakeFtpServer) = FtpDownloadSource().apply {
    clientFactory = { _, bufferSize -> server.client(bufferSize) }
  }

  private fun content(size: Int) = ByteArray(size) { (it % 251).toByte() }

  private fun context(
    file: MemoryFileAccessor,
    connections: Int = 0,
    config: DownloadConfig = DownloadConfig.Default,
    maxConnections: MutableStateFlow<Int> = MutableStateFlow(0),
    throttle: suspend (Int) -> Unit = {},
  ) = DownloadContext(
    taskId = "ftp",
    url = URL,
    request = DownloadRequest(URL, connections = connections),
    fileAccessor = file,
    segments = MutableStateFlow(emptyList()),
    onProgress = { _, _ -> },
    throttle = throttle,
    headers = emptyMap(),
    maxConnections = maxConnections,
    config = config,
  )

  private companion object {
    const val URL = "ftp://user:secret@ftp.example.com/pub/file.bin"
  }
}
