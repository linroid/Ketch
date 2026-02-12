package com.linroid.kdown.engine

import com.linroid.kdown.api.KDownError
import com.linroid.kdown.api.ResolvedSource
import com.linroid.kdown.core.engine.DownloadContext
import com.linroid.kdown.core.engine.DownloadSource
import com.linroid.kdown.core.engine.HttpDownloadSource
import com.linroid.kdown.core.engine.SourceResolver
import com.linroid.kdown.core.engine.SourceResumeState
import com.linroid.kdown.core.file.DefaultFileNameResolver
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SourceResolverTest {

  private val httpSource = HttpDownloadSource(
    httpEngine = FakeHttpEngine(),
    fileNameResolver = DefaultFileNameResolver(),
  )

  private val fakeSource = object : DownloadSource {
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

  @Test
  fun resolve_httpUrl_returnsHttpSource() {
    val resolver = SourceResolver(listOf(httpSource))
    val source = resolver.resolve("https://example.com/file.zip")
    assertEquals("http", source.type)
  }

  @Test
  fun resolve_customSource_matchesFirst() {
    val resolver = SourceResolver(listOf(fakeSource, httpSource))
    val source = resolver.resolve("magnet:?xt=urn:btih:abc123")
    assertEquals("magnet", source.type)
  }

  @Test
  fun resolve_httpFallback_whenCustomDoesNotMatch() {
    val resolver = SourceResolver(listOf(fakeSource, httpSource))
    val source = resolver.resolve("https://example.com/file.zip")
    assertEquals("http", source.type)
  }

  @Test
  fun resolve_noMatch_throwsUnsupported() {
    val resolver = SourceResolver(listOf(fakeSource))
    assertFailsWith<KDownError.Unsupported> {
      resolver.resolve("https://example.com/file.zip")
    }
  }

  @Test
  fun resolveByType_findsSourceByType() {
    val resolver = SourceResolver(listOf(fakeSource, httpSource))
    val source = resolver.resolveByType("magnet")
    assertEquals("magnet", source.type)
  }

  @Test
  fun resolveByType_findsHttpSource() {
    val resolver = SourceResolver(listOf(fakeSource, httpSource))
    val source = resolver.resolveByType("http")
    assertEquals("http", source.type)
  }

  @Test
  fun resolveByType_unknownType_throwsUnsupported() {
    val resolver = SourceResolver(listOf(httpSource))
    assertFailsWith<KDownError.Unsupported> {
      resolver.resolveByType("torrent")
    }
  }

  @Test
  fun httpSource_canHandle_httpUrl() {
    assertTrue(httpSource.canHandle("http://example.com/file"))
    assertTrue(httpSource.canHandle("https://example.com/file"))
    assertTrue(httpSource.canHandle("HTTP://EXAMPLE.COM/FILE"))
    assertTrue(httpSource.canHandle("HTTPS://EXAMPLE.COM/FILE"))
  }

  @Test
  fun httpSource_canNotHandle_nonHttpUrl() {
    assertFalse(httpSource.canHandle("ftp://example.com/file"))
    assertFalse(httpSource.canHandle("magnet:?xt=urn:btih:abc"))
    assertFalse(httpSource.canHandle("/local/path/file"))
  }
}
