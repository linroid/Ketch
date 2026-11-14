package com.linroid.ketch.engine

import com.linroid.ketch.api.Destination
import com.linroid.ketch.api.DownloadConfig
import com.linroid.ketch.api.DownloadRequest
import com.linroid.ketch.api.DownloadState
import com.linroid.ketch.api.KetchError
import com.linroid.ketch.api.ResolveInput
import com.linroid.ketch.core.Ketch
import com.linroid.ketch.core.KetchDispatchers
import com.linroid.ketch.core.engine.HttpDownloadSource
import com.linroid.ketch.core.engine.HttpEngine
import com.linroid.ketch.core.engine.ServerInfo
import com.linroid.ketch.core.engine.WebPageAnswer
import com.linroid.ketch.core.engine.resolve
import com.linroid.ketch.core.file.platformFileSystem
import com.linroid.ketch.core.task.InMemoryTaskStore
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import okio.FileSystem
import okio.Path.Companion.toPath
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class WebPageAnswerTest {
  private val page =
    "<!DOCTYPE html>\n<html><head><title>Sign in</title></head><body></body></html>"
      .encodeToByteArray()

  // -- HttpDownloadSource.resolve --

  @Test
  fun resolve_fileLinkAnsweredWithPage_failsSayingSo() = runTest {
    // As a file host redirects a link without its browser's cookies to its landing page.
    val engine = pageEngine(answeredFrom = "https://example.com/d/0b1c2d3e")

    val error = assertFailsWith<KetchError.SourceError> {
      HttpDownloadSource(engine).resolve(VIDEO_URL)
    }

    assertEquals(WebPageAnswer.DETAIL, error.detail)
    assertEquals(HttpDownloadSource.TYPE, error.sourceType)
    assertFalse(error.isRetryable)
  }

  @Test
  fun resolve_pageAtTheLinkItself_failsWithoutARedirect() = runTest {
    val error = assertFailsWith<KetchError.SourceError> {
      HttpDownloadSource(pageEngine()).resolve("https://example.com/files/Setup.EXE?token=1")
    }

    assertEquals(WebPageAnswer.DETAIL, error.detail)
  }

  @Test
  fun resolve_xhtmlWithLeadingWhitespace_fails() = runTest {
    val content = "﻿\n <?xml version=\"1.0\"?>\n<html xmlns=\"http://www.w3.org/1999/xhtml\"/>"
    val engine = pageEngine(
      content = content.encodeToByteArray(),
      contentType = "application/xhtml+xml",
    )

    assertFailsWith<KetchError.SourceError> { HttpDownloadSource(engine).resolve(VIDEO_URL) }
  }

  @Test
  fun resolve_longPage_readsOnlyItsStart() = runTest {
    val long = page + ByteArray(50_000) { ' '.code.toByte() }
    val engine = pageEngine(content = long)
    var delivered = 0
    val counting = object : HttpEngine by engine {
      override suspend fun download(
        url: String,
        range: LongRange?,
        headers: Map<String, String>,
        onData: suspend (ByteArray) -> Unit,
      ) = engine.download(url, range, headers) {
        delivered += it.size
        onData(it)
      }
    }

    assertFailsWith<KetchError.SourceError> { HttpDownloadSource(counting).resolve(VIDEO_URL) }

    assertEquals(1, engine.downloadCallCount)
    assertTrue(delivered < 2 * WebPageAnswer.SNIFF_BYTES, "read $delivered bytes")
  }

  @Test
  fun resolve_fileLabeledAsHtml_resolves() = runTest {
    // A script that sends a file without setting its type, so its platform's default applies.
    val engine = pageEngine(content = ByteArray(5000) { (it % 251).toByte() })

    val resolved = HttpDownloadSource(engine).resolve(VIDEO_URL)

    assertEquals("Movie.mkv", resolved.suggestedFileName)
    assertEquals(5000L, resolved.totalBytes)
  }

  @Test
  fun resolve_htmlLink_resolvesWithoutReadingContent() = runTest {
    val engine = pageEngine()

    val resolved = HttpDownloadSource(engine).resolve("https://example.com/docs/index.html")

    assertEquals("index.html", resolved.suggestedFileName)
    assertEquals(0, engine.downloadCallCount)
  }

  @Test
  fun resolve_linksNamedLikePages_resolveWithoutReadingContent() = runTest {
    val engine = pageEngine()
    val source = HttpDownloadSource(engine)

    for (url in listOf(
      "https://example.com/download?id=5",
      "https://example.com/wiki/Node.js",
      "https://example.com/blog/release-1.2",
      "https://example.com/view.php?file=Movie.mkv",
      "https://example.com/",
    )) {
      source.resolve(url)
    }

    assertEquals(0, engine.downloadCallCount)
  }

  @Test
  fun resolve_pageSentAsDownload_resolves() = runTest {
    val engine = pageEngine(contentDisposition = "attachment; filename=\"Movie.mkv\"")

    val resolved = HttpDownloadSource(engine).resolve(VIDEO_URL)

    assertEquals("Movie.mkv", resolved.suggestedFileName)
    assertEquals(0, engine.downloadCallCount)
  }

  @Test
  fun resolve_otherContentTypes_resolveWithoutReadingContent() = runTest {
    val types = listOf("video/x-matroska", "application/octet-stream", "text/plain", null)
    for (contentType in types) {
      val engine = pageEngine(contentType = contentType)

      HttpDownloadSource(engine).resolve(VIDEO_URL)

      assertEquals(0, engine.downloadCallCount, "for $contentType")
    }
  }

  @Test
  fun resolve_emptyHtmlAnswer_resolves() = runTest {
    val engine = pageEngine(content = ByteArray(0))

    assertEquals(0L, HttpDownloadSource(engine).resolve(VIDEO_URL).totalBytes)
  }

  @Test
  fun resolve_contentRequestFails_reportsThatFailure() = runTest {
    val engine = pageEngine().apply { failAfterBytes = 0 }

    assertFailsWith<KetchError.Network> { HttpDownloadSource(engine).resolve(VIDEO_URL) }
  }

  // -- Ketch --

  @Test
  fun ketchResolve_fileLinkAnsweredWithPage_fails() = runTest {
    withKetch(pageEngine()) { ketch, _ ->
      val error = assertFailsWith<KetchError.SourceError> {
        ketch.resolve(ResolveInput.Url(VIDEO_URL))
      }

      assertEquals(WebPageAnswer.DETAIL, error.detail)
    }
  }

  @Test
  fun download_fileLinkAnsweredWithPage_failsAndSavesNothing() = runTest {
    val engine = pageEngine()
    withKetch(engine) { ketch, folder ->
      val task = ketch.download(DownloadRequest(url = VIDEO_URL))

      val failed = assertIs<DownloadState.Failed>(task.state.first { it.isTerminal })
      assertEquals(WebPageAnswer.DETAIL, assertIs<KetchError.SourceError>(failed.error).detail)
      assertTrue(platformFileSystem.list(folder.toPath()).isEmpty())
      // Not retried: the same page would come back.
      assertEquals(1, engine.headCallCount)
    }
  }

  @Test
  fun download_intoFolder_stillFails() = runTest {
    withKetch(pageEngine()) { ketch, folder ->
      val request = DownloadRequest(url = VIDEO_URL, destination = Destination("$folder/"))

      val task = ketch.download(request)

      assertIs<DownloadState.Failed>(task.state.first { it.isTerminal })
    }
  }

  @Test
  fun download_toFileTheRequestNames_savesThePage() = runTest {
    withKetch(pageEngine()) { ketch, folder ->
      val request = DownloadRequest(
        url = VIDEO_URL,
        destination = Destination("$folder/page.html"),
      )

      val task = ketch.download(request)

      assertIs<DownloadState.Completed>(task.state.first { it.isTerminal })
      assertContentEquals(
        page,
        platformFileSystem.read("$folder/page.html".toPath()) { readByteArray() },
      )
    }
  }

  @Test
  fun download_underNameTheRequestGives_savesThePage() = runTest {
    withKetch(pageEngine()) { ketch, folder ->
      val request = DownloadRequest(url = VIDEO_URL, destination = Destination("saved.mkv"))

      val task = ketch.download(request)

      assertIs<DownloadState.Completed>(task.state.first { it.isTerminal })
      assertTrue(platformFileSystem.exists("$folder/saved.mkv".toPath()))
    }
  }

  @Test
  fun download_htmlLink_savesThePage() = runTest {
    withKetch(pageEngine()) { ketch, folder ->
      val task = ketch.download(DownloadRequest(url = "https://example.com/docs/index.html"))

      assertIs<DownloadState.Completed>(task.state.first { it.isTerminal })
      assertContentEquals(
        page,
        platformFileSystem.read("$folder/index.html".toPath()) { readByteArray() },
      )
    }
  }

  // -- WebPageAnswer.isMarkup --

  @Test
  fun isMarkup_fileFormatsAndPlainText_areNotPages() {
    val matroska = byteArrayOf(0x1A, 0x45, 0xDF.toByte(), 0xA3.toByte(), 0x3C, 0x68, 0x74)
    assertFalse(WebPageAnswer.isMarkup(matroska))
    assertFalse(WebPageAnswer.isMarkup("PK\u0003\u0004<html>".encodeToByteArray()))
    assertFalse(WebPageAnswer.isMarkup("1\n00:00:01 --> 00:00:02\n<i>Hi</i>".encodeToByteArray()))
    assertFalse(WebPageAnswer.isMarkup("<plist version=\"1.0\"><dict/>".encodeToByteArray()))
  }

  @Test
  fun isMarkup_pageWithoutDoctype_isAPage() {
    assertTrue(WebPageAnswer.isMarkup("  <HTML lang=\"en\"><BODY>".encodeToByteArray()))
    assertTrue(WebPageAnswer.isMarkup("<!-- note --><script>go()</script>".encodeToByteArray()))
  }

  private fun pageEngine(
    content: ByteArray = page,
    contentType: String? = "text/html; charset=utf-8",
    contentDisposition: String? = null,
    answeredFrom: String? = null,
  ) = FakeHttpEngine(
    serverInfo = ServerInfo(
      contentLength = content.size.toLong(),
      acceptRanges = false,
      etag = null,
      lastModified = null,
      contentDisposition = contentDisposition,
      contentType = contentType,
      url = answeredFrom,
    ),
    content = content,
  )

  private suspend fun TestScope.withKetch(
    engine: FakeHttpEngine,
    block: suspend (Ketch, String) -> Unit,
  ) {
    val folder = FileSystem.SYSTEM_TEMPORARY_DIRECTORY / "ketch-web-page-test"
    platformFileSystem.deleteRecursively(folder)
    platformFileSystem.createDirectories(folder)
    val dispatcher = StandardTestDispatcher(testScheduler)
    val ketch = Ketch(
      httpEngine = engine,
      taskStore = InMemoryTaskStore(),
      config = DownloadConfig(defaultDirectory = folder.toString(), retryCount = 0),
      dispatchers = KetchDispatchers(dispatcher, dispatcher, dispatcher),
    )
    try {
      block(ketch, folder.toString())
    } finally {
      ketch.close()
      platformFileSystem.deleteRecursively(folder)
    }
  }

  private companion object {
    const val VIDEO_URL = "https://files.example.com/download/web/0b1c2d3e/Movie.mkv"
  }
}
