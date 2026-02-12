package com.linroid.kdown.engine

import com.linroid.kdown.api.KDownError
import com.linroid.kdown.api.ResolvedSource
import com.linroid.kdown.core.engine.DownloadContext
import com.linroid.kdown.core.engine.DownloadSource
import com.linroid.kdown.core.engine.HttpDownloadSource
import com.linroid.kdown.core.engine.ServerInfo
import com.linroid.kdown.core.engine.SourceResolver
import com.linroid.kdown.core.engine.SourceResumeState
import com.linroid.kdown.core.file.DefaultFileNameResolver
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ResolveUrlTest {

  // -- HttpDownloadSource.resolve() tests --

  @Test
  fun resolve_httpUrl_returnsCorrectResolvedSource() = runTest {
    val engine = FakeHttpEngine(
      serverInfo = ServerInfo(
        contentLength = 5000,
        acceptRanges = true,
        etag = "\"test-etag\"",
        lastModified = "Wed, 01 Jan 2025 00:00:00 GMT",
      ),
    )
    val source = HttpDownloadSource(
      httpEngine = engine,
      fileNameResolver = DefaultFileNameResolver(),
    )
    val resolved = source.resolve(
      "https://example.com/file.zip",
    )
    assertEquals("https://example.com/file.zip", resolved.url)
    assertEquals("http", resolved.sourceType)
    assertEquals(5000L, resolved.totalBytes)
    assertTrue(resolved.supportsResume)
    assertEquals(4, resolved.maxSegments)
    assertEquals("\"test-etag\"", resolved.metadata["etag"])
    assertEquals(
      "Wed, 01 Jan 2025 00:00:00 GMT",
      resolved.metadata["lastModified"],
    )
    assertEquals("true", resolved.metadata["acceptRanges"])
  }

  @Test
  fun resolve_serverWithoutRanges_maxSegmentsIsOne() = runTest {
    val engine = FakeHttpEngine(
      serverInfo = ServerInfo(
        contentLength = 3000,
        acceptRanges = false,
        etag = null,
        lastModified = null,
      ),
    )
    val source = HttpDownloadSource(
      httpEngine = engine,
      fileNameResolver = DefaultFileNameResolver(),
    )
    val resolved = source.resolve(
      "https://example.com/file.zip",
    )
    assertFalse(resolved.supportsResume)
    assertEquals(1, resolved.maxSegments)
    assertFalse(resolved.metadata.containsKey("etag"))
    assertFalse(resolved.metadata.containsKey("lastModified"))
  }

  @Test
  fun resolve_unknownContentLength_returnsMinus1() = runTest {
    val engine = FakeHttpEngine(
      serverInfo = ServerInfo(
        contentLength = null,
        acceptRanges = false,
        etag = null,
        lastModified = null,
      ),
    )
    val source = HttpDownloadSource(
      httpEngine = engine,
      fileNameResolver = DefaultFileNameResolver(),
    )
    val resolved = source.resolve("https://example.com/stream")
    assertEquals(-1L, resolved.totalBytes)
    assertFalse(resolved.supportsResume)
    assertEquals(1, resolved.maxSegments)
  }

  @Test
  fun resolve_withCustomHeaders_passesThrough() = runTest {
    val engine = FakeHttpEngine()
    val source = HttpDownloadSource(
      httpEngine = engine,
      fileNameResolver = DefaultFileNameResolver(),
    )
    val headers = mapOf(
      "Authorization" to "Bearer token",
      "X-Custom" to "value",
    )
    source.resolve("https://example.com/file", headers)
    assertEquals(headers, engine.lastHeadHeaders)
    assertEquals(1, engine.headCallCount)
  }

  @Test
  fun resolve_networkFailure_throwsNetworkError() = runTest {
    val engine = FakeHttpEngine(failOnHead = true)
    val source = HttpDownloadSource(
      httpEngine = engine,
      fileNameResolver = DefaultFileNameResolver(),
    )
    assertFailsWith<KDownError.Network> {
      source.resolve("https://example.com/file")
    }
  }

  @Test
  fun resolve_httpError_throwsHttpError() = runTest {
    val engine = FakeHttpEngine(httpErrorCode = 404)
    val source = HttpDownloadSource(
      httpEngine = engine,
      fileNameResolver = DefaultFileNameResolver(),
    )
    assertFailsWith<KDownError.Http> {
      source.resolve("https://example.com/file")
    }
  }

  @Test
  fun resolve_contentDisposition_extractsFileName() = runTest {
    val engine = FakeHttpEngine(
      serverInfo = ServerInfo(
        contentLength = 2048,
        acceptRanges = true,
        etag = null,
        lastModified = null,
        contentDisposition = "attachment; filename=\"report.pdf\"",
      ),
    )
    val source = HttpDownloadSource(
      httpEngine = engine,
      fileNameResolver = DefaultFileNameResolver(),
    )
    val resolved = source.resolve("https://example.com/dl?id=1")
    assertEquals("report.pdf", resolved.suggestedFileName)
  }

  @Test
  fun resolve_noContentDisposition_fallsBackToUrlPath() = runTest {
    val engine = FakeHttpEngine(
      serverInfo = ServerInfo(
        contentLength = 1000,
        acceptRanges = true,
        etag = null,
        lastModified = null,
        contentDisposition = null,
      ),
    )
    val source = HttpDownloadSource(
      httpEngine = engine,
      fileNameResolver = DefaultFileNameResolver(),
    )
    val resolved = source.resolve("https://example.com/file.zip")
    assertEquals("file.zip", resolved.suggestedFileName)
  }

  @Test
  fun resolve_metadataOnlyIncludesNonNullValues() = runTest {
    val engine = FakeHttpEngine(
      serverInfo = ServerInfo(
        contentLength = 1000,
        acceptRanges = false,
        etag = null,
        lastModified = null,
      ),
    )
    val source = HttpDownloadSource(
      httpEngine = engine,
      fileNameResolver = DefaultFileNameResolver(),
    )
    val resolved = source.resolve("https://example.com/file")
    assertTrue(resolved.metadata.isEmpty())
  }

  @Test
  fun resolve_etagOnly_includesEtagInMetadata() = runTest {
    val engine = FakeHttpEngine(
      serverInfo = ServerInfo(
        contentLength = 1000,
        acceptRanges = true,
        etag = "\"v1\"",
        lastModified = null,
      ),
    )
    val source = HttpDownloadSource(
      httpEngine = engine,
      fileNameResolver = DefaultFileNameResolver(),
    )
    val resolved = source.resolve("https://example.com/file")
    assertEquals("\"v1\"", resolved.metadata["etag"])
    assertFalse(resolved.metadata.containsKey("lastModified"))
  }

  // -- SourceResolver.resolve() for unsupported URLs --

  @Test
  fun sourceResolver_unsupportedUrl_throwsUnsupported() {
    val fakeSource = object : DownloadSource {
      override val type = "magnet"
      override fun canHandle(url: String) =
        url.startsWith("magnet:")
      override suspend fun resolve(
        url: String,
        headers: Map<String, String>,
      ) = ResolvedSource(
        url = url,
        sourceType = "magnet",
        totalBytes = 1000,
        supportsResume = false,
        suggestedFileName = null,
        maxSegments = 1,
      )
      override suspend fun download(context: DownloadContext) {}
      override suspend fun resume(
        context: DownloadContext,
        resumeState: SourceResumeState,
      ) {}
    }
    val resolver = SourceResolver(listOf(fakeSource))
    assertFailsWith<KDownError.Unsupported> {
      resolver.resolve("https://example.com/file")
    }
  }

  // -- Custom source resolve tests --

  @Test
  fun resolve_customSource_returnsCorrectResolvedSource() = runTest {
    val fakeSource = object : DownloadSource {
      override val type = "magnet"
      override fun canHandle(url: String) =
        url.startsWith("magnet:")
      override suspend fun resolve(
        url: String,
        headers: Map<String, String>,
      ) = ResolvedSource(
        url = url,
        sourceType = "magnet",
        totalBytes = 50000,
        supportsResume = false,
        suggestedFileName = "torrent-file.bin",
        maxSegments = 1,
      )
      override suspend fun download(context: DownloadContext) {}
      override suspend fun resume(
        context: DownloadContext,
        resumeState: SourceResumeState,
      ) {}
    }

    val engine = FakeHttpEngine()
    val httpSource = HttpDownloadSource(
      httpEngine = engine,
      fileNameResolver = DefaultFileNameResolver(),
    )
    val resolver = SourceResolver(listOf(fakeSource, httpSource))
    val source = resolver.resolve("magnet:?xt=urn:btih:abc123")
    val resolved = source.resolve("magnet:?xt=urn:btih:abc123")

    assertEquals("magnet", resolved.sourceType)
    assertEquals(50000L, resolved.totalBytes)
    assertFalse(resolved.supportsResume)
    assertEquals("torrent-file.bin", resolved.suggestedFileName)
    assertEquals(1, resolved.maxSegments)
    // HTTP engine should not be called for magnet URLs
    assertEquals(0, engine.headCallCount)
  }
}
