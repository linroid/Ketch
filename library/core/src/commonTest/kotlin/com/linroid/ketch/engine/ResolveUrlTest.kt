package com.linroid.ketch.engine

import com.linroid.ketch.api.KetchError
import com.linroid.ketch.api.ResolvedSource
import com.linroid.ketch.core.engine.DownloadContext
import com.linroid.ketch.core.engine.DownloadSource
import com.linroid.ketch.core.engine.HttpDownloadSource
import com.linroid.ketch.core.engine.ServerInfo
import com.linroid.ketch.core.engine.SourceResolver
import com.linroid.ketch.core.engine.SourceResumeState
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
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
    )
    assertFailsWith<KetchError.Network> {
      source.resolve("https://example.com/file")
    }
  }

  @Test
  fun resolve_httpError_throwsHttpError() = runTest {
    val engine = FakeHttpEngine(httpErrorCode = 404)
    val source = HttpDownloadSource(
      httpEngine = engine,
    )
    assertFailsWith<KetchError.Http> {
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
    )
    val resolved = source.resolve("https://example.com/file")
    assertEquals("\"v1\"", resolved.metadata["etag"])
    assertFalse(resolved.metadata.containsKey("lastModified"))
  }

  @Test
  fun resolve_rateLimitHeaders_propagatedToMetadata() = runTest {
    val engine = FakeHttpEngine(
      serverInfo = ServerInfo(
        contentLength = 5000,
        acceptRanges = true,
        etag = null,
        lastModified = null,
        rateLimitRemaining = 10,
        rateLimitReset = 60,
      ),
    )
    val source = HttpDownloadSource(
      httpEngine = engine,
    )
    val resolved = source.resolve("https://example.com/file.zip")
    assertEquals("10", resolved.metadata["rateLimitRemaining"])
    assertEquals("60", resolved.metadata["rateLimitReset"])
  }

  @Test
  fun resolve_rateLimitZeroRemaining_propagatedToMetadata() = runTest {
    val engine = FakeHttpEngine(
      serverInfo = ServerInfo(
        contentLength = 5000,
        acceptRanges = true,
        etag = null,
        lastModified = null,
        rateLimitRemaining = 0,
        rateLimitReset = 5,
      ),
    )
    val source = HttpDownloadSource(
      httpEngine = engine,
    )
    val resolved = source.resolve("https://example.com/file.zip")
    assertEquals("0", resolved.metadata["rateLimitRemaining"])
    assertEquals("5", resolved.metadata["rateLimitReset"])
  }

  @Test
  fun resolve_noRateLimitHeaders_absentFromMetadata() = runTest {
    val engine = FakeHttpEngine(
      serverInfo = ServerInfo(
        contentLength = 5000,
        acceptRanges = true,
        etag = null,
        lastModified = null,
      ),
    )
    val source = HttpDownloadSource(
      httpEngine = engine,
    )
    val resolved = source.resolve("https://example.com/file.zip")
    assertFalse(resolved.metadata.containsKey("rateLimitRemaining"))
    assertFalse(resolved.metadata.containsKey("rateLimitReset"))
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
    assertFailsWith<KetchError.Unsupported> {
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
