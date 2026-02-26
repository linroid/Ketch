package com.linroid.ketch.file

import com.linroid.ketch.api.DownloadRequest
import com.linroid.ketch.api.ResolvedSource
import com.linroid.ketch.core.file.DefaultFileNameResolver
import kotlin.test.Test
import kotlin.test.assertEquals

class DefaultFileNameResolverTest {

  private val resolver = DefaultFileNameResolver()
  private val dir = "/tmp"

  private fun resolved(
    contentDisposition: String? = null,
  ) = ResolvedSource(
    url = "https://example.com",
    sourceType = "http",
    totalBytes = 1000,
    supportsResume = true,
    suggestedFileName = null,
    maxSegments = 4,
    metadata = buildMap {
      contentDisposition?.let {
        put(DefaultFileNameResolver.META_CONTENT_DISPOSITION, it)
      }
    },
  )

  private fun request(url: String) = DownloadRequest(
    url = url,
    destination = com.linroid.ketch.api.Destination("$dir/"),
  )

  // --- Content-Disposition: filename*=UTF-8'' ---

  @Test
  fun resolve_filenameStarUtf8() {
    val info = resolved("attachment; filename*=UTF-8''my%20file.zip")
    assertEquals(
      "my file.zip",
      resolver.resolve(request("https://example.com"), info)
    )
  }

  @Test
  fun resolve_filenameStarUtf8_caseInsensitive() {
    val info = resolved("attachment; Filename*=utf-8''report.pdf")
    assertEquals(
      "report.pdf",
      resolver.resolve(request("https://example.com"), info)
    )
  }

  // --- Content-Disposition: filename="..." ---

  @Test
  fun resolve_filenameQuoted() {
    val info = resolved("attachment; filename=\"document.pdf\"")
    assertEquals(
      "document.pdf",
      resolver.resolve(request("https://example.com"), info)
    )
  }

  @Test
  fun resolve_filenameQuoted_withSpaces() {
    val info = resolved("attachment; filename=\"my document.pdf\"")
    assertEquals(
      "my document.pdf",
      resolver.resolve(request("https://example.com"), info)
    )
  }

  // --- Content-Disposition: filename=<unquoted> ---

  @Test
  fun resolve_filenameUnquoted() {
    val info = resolved("attachment; filename=report.csv")
    assertEquals(
      "report.csv",
      resolver.resolve(request("https://example.com"), info)
    )
  }

  // --- URL path extraction ---

  @Test
  fun resolve_fromUrlPath() {
    val info = resolved()
    assertEquals(
      "archive.tar.gz",
      resolver.resolve(
        request("https://example.com/files/archive.tar.gz"),
        info
      )
    )
  }

  @Test
  fun resolve_fromUrlPath_withQuery() {
    val info = resolved()
    assertEquals(
      "file.zip",
      resolver.resolve(
        request("https://example.com/file.zip?token=abc"),
        info
      )
    )
  }

  @Test
  fun resolve_fromUrlPath_withFragment() {
    val info = resolved()
    assertEquals(
      "file.zip",
      resolver.resolve(
        request("https://example.com/file.zip#section"),
        info
      )
    )
  }

  @Test
  fun resolve_fromUrlPath_percentEncoded() {
    val info = resolved()
    assertEquals(
      "my file.zip",
      resolver.resolve(
        request("https://example.com/my%20file.zip"),
        info
      )
    )
  }

  @Test
  fun resolve_fromUrlPath_trailingSlash() {
    val info = resolved()
    assertEquals(
      "dir",
      resolver.resolve(request("https://example.com/dir/"), info)
    )
  }

  // --- Fallback ---

  @Test
  fun resolve_fallback_noPathNoDisposition() {
    val info = resolved()
    assertEquals(
      "download",
      resolver.resolve(request("https://example.com/"), info)
    )
  }

  @Test
  fun resolve_fallback_rootUrl() {
    val info = resolved()
    assertEquals(
      "download",
      resolver.resolve(request("https://example.com"), info)
    )
  }

  // --- Priority ---

  @Test
  fun resolve_dispositionTakesPriorityOverUrl_explicit() {
    // Explicit file names via Destination are now handled by the
    // coordinator, not the resolver. The resolver always returns
    // the server-derived name.
    val info = resolved("attachment; filename=\"server-name.zip\"")
    val req = DownloadRequest(
      url = "https://example.com/url-name.zip",
      destination = com.linroid.ketch.api.Destination("explicit.zip"),
    )
    assertEquals("server-name.zip", resolver.resolve(req, info))
  }

  @Test
  fun resolve_dispositionTakesPriorityOverUrl() {
    val info = resolved("attachment; filename=\"server-name.zip\"")
    assertEquals(
      "server-name.zip",
      resolver.resolve(
        request("https://example.com/url-name.zip"),
        info
      )
    )
  }

  // --- Percent-decoding helper ---

  @Test
  fun percentDecode_basic() {
    assertEquals(
      "hello world",
      DefaultFileNameResolver.percentDecode("hello%20world")
    )
  }

  @Test
  fun percentDecode_noEncoding() {
    assertEquals(
      "simple.txt",
      DefaultFileNameResolver.percentDecode("simple.txt")
    )
  }

  @Test
  fun percentDecode_invalidHex_passedThrough() {
    assertEquals(
      "%GGtest",
      DefaultFileNameResolver.percentDecode("%GGtest")
    )
  }

  @Test
  fun percentDecode_trailingPercent_passedThrough() {
    assertEquals(
      "test%",
      DefaultFileNameResolver.percentDecode("test%")
    )
  }
}
