package com.linroid.ketch.engine

import com.linroid.ketch.api.DownloadRequest
import com.linroid.ketch.api.ResolvedSource
import com.linroid.ketch.core.engine.HttpDownloadSource
import com.linroid.ketch.core.engine.ServerInfo
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Tests for the pre-resolved URL flow:
 * - [DownloadRequest.resolvedSource] field behavior
 * - [HttpDownloadSource.resolve] producing correct [ResolvedSource]
 * - Serialization round-trip of requests with resolvedUrl
 */
class PreResolvedDownloadTest {

  private val json = Json { ignoreUnknownKeys = true }

  // -- DownloadRequest.resolvedUrl field --

  @Test
  fun downloadRequest_defaultResolvedSource_isNull() {
    val request = DownloadRequest(
      url = "https://example.com/file",
    )
    assertNull(request.resolvedSource)
  }

  @Test
  fun downloadRequest_withResolvedSource_preserved() {
    val resolved = ResolvedSource(
      url = "https://example.com/file.zip",
      sourceType = "http",
      totalBytes = 5000,
      supportsResume = true,
      suggestedFileName = "file.zip",
      maxSegments = 4,
    )
    val request = DownloadRequest(
      url = "https://example.com/file.zip",
      resolvedSource = resolved,
    )
    assertEquals(resolved, request.resolvedSource)
  }

  @Test
  fun downloadRequest_resolvedSource_survivesSerialization() {
    val resolved = ResolvedSource(
      url = "https://example.com/file.zip",
      sourceType = "http",
      totalBytes = 5000,
      supportsResume = true,
      suggestedFileName = null,
      maxSegments = 4,
    )
    val request = DownloadRequest(
      url = "https://example.com/file.zip",
      resolvedSource = resolved,
    )
    val serialized = json.encodeToString(
      DownloadRequest.serializer(), request,
    )
    val deserialized = json.decodeFromString(
      DownloadRequest.serializer(), serialized,
    )
    assertEquals(resolved, deserialized.resolvedSource)
  }

  // -- Resolve produces correct metadata for pre-resolved flow --

  @Test
  fun resolve_resultCanBeUsedAsPreResolved() = runTest {
    val engine = FakeHttpEngine(
      serverInfo = ServerInfo(
        contentLength = 10000,
        acceptRanges = true,
        etag = "\"abc\"",
        lastModified = "Mon, 01 Jan 2024 00:00:00 GMT",
        contentDisposition = "attachment; filename=\"data.csv\"",
      ),
    )
    val source = HttpDownloadSource(
      httpEngine = engine,
    )
    val resolved = source.resolve(
      "https://example.com/data",
      mapOf("Authorization" to "Bearer tok"),
    )

    // Verify the resolved info contains everything needed for a
    // pre-resolved download
    assertEquals("https://example.com/data", resolved.url)
    assertEquals("http", resolved.sourceType)
    assertEquals(10000L, resolved.totalBytes)
    assertTrue(resolved.supportsResume)
    assertEquals("data.csv", resolved.suggestedFileName)
    assertEquals(4, resolved.maxSegments)
    assertEquals("\"abc\"", resolved.metadata["etag"])
    assertEquals(
      "Mon, 01 Jan 2024 00:00:00 GMT",
      resolved.metadata["lastModified"],
    )
    assertEquals("true", resolved.metadata["acceptRanges"])

    // This resolved URL can be passed to DownloadRequest
    val request = DownloadRequest(
      url = resolved.url,
      resolvedSource = resolved,
    )
    assertEquals(resolved, request.resolvedSource)
    assertEquals(1, engine.headCallCount)
  }

  @Test
  fun resolve_noRangeSupport_producesCorrectPreResolved() =
    runTest {
      val engine = FakeHttpEngine(
        serverInfo = ServerInfo(
          contentLength = 5000,
          acceptRanges = false,
          etag = null,
          lastModified = null,
        ),
      )
      val source = HttpDownloadSource(
        httpEngine = engine,
      )
      val resolved = source.resolve("https://example.com/file")

      assertFalse(resolved.supportsResume)
      assertEquals(1, resolved.maxSegments)
      assertEquals(5000L, resolved.totalBytes)
      assertTrue(resolved.metadata.isEmpty())
    }

  @Test
  fun resolve_unknownSize_producesMinusOne() = runTest {
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
  }

  @Test
  fun resolve_callsHeadOnce() = runTest {
    val engine = FakeHttpEngine()
    val source = HttpDownloadSource(
      httpEngine = engine,
    )
    source.resolve("https://example.com/file")
    assertEquals(1, engine.headCallCount)
    assertEquals(0, engine.downloadCallCount)
  }

  @Test
  fun resolve_thenResolveAgain_callsHeadTwice() = runTest {
    val engine = FakeHttpEngine()
    val source = HttpDownloadSource(
      httpEngine = engine,
    )
    source.resolve("https://example.com/a")
    source.resolve("https://example.com/b")
    assertEquals(2, engine.headCallCount)
    assertEquals(0, engine.downloadCallCount)
  }
}
