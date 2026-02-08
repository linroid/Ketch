package com.linroid.kdown.internal

import com.linroid.kdown.DownloadRequest
import com.linroid.kdown.model.ServerInfo
import kotlinx.io.files.Path
import kotlin.test.Test
import kotlin.test.assertEquals

class DefaultFileNameResolverTest {

  private val resolver = DefaultFileNameResolver()
  private val dir = Path("/tmp")

  private fun serverInfo(contentDisposition: String? = null) = ServerInfo(
    contentLength = 1000,
    acceptRanges = true,
    etag = null,
    lastModified = null,
    contentDisposition = contentDisposition
  )

  private fun request(url: String) = DownloadRequest(
    url = url,
    directory = dir
  )

  // --- Content-Disposition: filename*=UTF-8'' ---

  @Test
  fun resolve_filenameStarUtf8() {
    val info = serverInfo("attachment; filename*=UTF-8''my%20file.zip")
    assertEquals(
      "my file.zip",
      resolver.resolve(request("https://example.com"), info)
    )
  }

  @Test
  fun resolve_filenameStarUtf8_caseInsensitive() {
    val info = serverInfo("attachment; Filename*=utf-8''report.pdf")
    assertEquals(
      "report.pdf",
      resolver.resolve(request("https://example.com"), info)
    )
  }

  // --- Content-Disposition: filename="..." ---

  @Test
  fun resolve_filenameQuoted() {
    val info = serverInfo("attachment; filename=\"document.pdf\"")
    assertEquals(
      "document.pdf",
      resolver.resolve(request("https://example.com"), info)
    )
  }

  @Test
  fun resolve_filenameQuoted_withSpaces() {
    val info = serverInfo("attachment; filename=\"my document.pdf\"")
    assertEquals(
      "my document.pdf",
      resolver.resolve(request("https://example.com"), info)
    )
  }

  // --- Content-Disposition: filename=<unquoted> ---

  @Test
  fun resolve_filenameUnquoted() {
    val info = serverInfo("attachment; filename=report.csv")
    assertEquals(
      "report.csv",
      resolver.resolve(request("https://example.com"), info)
    )
  }

  // --- URL path extraction ---

  @Test
  fun resolve_fromUrlPath() {
    val info = serverInfo()
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
    val info = serverInfo()
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
    val info = serverInfo()
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
    val info = serverInfo()
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
    val info = serverInfo()
    assertEquals(
      "dir",
      resolver.resolve(request("https://example.com/dir/"), info)
    )
  }

  // --- Fallback ---

  @Test
  fun resolve_fallback_noPathNoDisposition() {
    val info = serverInfo()
    assertEquals(
      "download",
      resolver.resolve(request("https://example.com/"), info)
    )
  }

  @Test
  fun resolve_fallback_rootUrl() {
    val info = serverInfo()
    assertEquals(
      "download",
      resolver.resolve(request("https://example.com"), info)
    )
  }

  // --- Priority ---

  @Test
  fun resolve_requestFileNameTakesPriority() {
    val info = serverInfo("attachment; filename=\"server-name.zip\"")
    val req = DownloadRequest(
      url = "https://example.com/url-name.zip",
      directory = dir,
      fileName = "explicit.zip"
    )
    assertEquals("explicit.zip", resolver.resolve(req, info))
  }

  @Test
  fun resolve_dispositionTakesPriorityOverUrl() {
    val info = serverInfo("attachment; filename=\"server-name.zip\"")
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
