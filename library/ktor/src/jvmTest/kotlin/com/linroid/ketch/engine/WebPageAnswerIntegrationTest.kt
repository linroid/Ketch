package com.linroid.ketch.engine

import com.linroid.ketch.api.DownloadConfig
import com.linroid.ketch.api.DownloadRequest
import com.linroid.ketch.api.DownloadState
import com.linroid.ketch.api.KetchError
import com.linroid.ketch.api.ResolveInput
import com.linroid.ketch.core.Ketch
import com.sun.net.httpserver.HttpServer
import java.io.File
import java.net.InetAddress
import java.net.InetSocketAddress
import java.nio.file.Files
import java.util.Collections
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * A link named like a file that redirects to a web page, as a file host answers one without its
 * browser's cookies, through [KtorHttpEngine] and a real server.
 */
class WebPageAnswerIntegrationTest {
  @Test
  fun resolve_fileLinkRedirectedToPage_fails() = runTest(timeout = 20.seconds) {
    withServer { ketch, base, _, requests ->
      val error = assertFailsWith<KetchError.SourceError> {
        ketch.resolve(ResolveInput.Url("$base/download/Movie.mkv"))
      }

      assertTrue(error.detail.orEmpty().startsWith("The server sent a web page"), error.detail)
      // The page is fetched once to read its start, after the HEAD.
      assertEquals(listOf("HEAD /d/0b1c2d3e", "GET /d/0b1c2d3e"), requests.distinct())
    }
  }

  @Test
  fun download_fileLinkRedirectedToPage_failsAndSavesNothing() = runTest(timeout = 20.seconds) {
    withServer { ketch, base, folder, _ ->
      val task = ketch.download(DownloadRequest(url = "$base/download/Movie.mkv"))

      val failed = assertIs<DownloadState.Failed>(task.state.first { it.isTerminal })
      assertIs<KetchError.SourceError>(failed.error)
      assertTrue(folder.list().orEmpty().isEmpty())
    }
  }

  @Test
  fun download_fileSentAsHtml_completes() = runTest(timeout = 20.seconds) {
    withServer { ketch, base, _, _ ->
      val task = ketch.download(DownloadRequest(url = "$base/mislabeled/Movie.mkv"))

      val completed = assertIs<DownloadState.Completed>(task.state.first { it.isTerminal })
      assertContentEquals(FILE, File(completed.outputPath).readBytes())
    }
  }

  private suspend fun withServer(
    block: suspend (Ketch, String, File, List<String>) -> Unit,
  ) = withContext(Dispatchers.IO) {
    val requests: MutableList<String> = Collections.synchronizedList(mutableListOf())
    val server = HttpServer.create(InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0), 0)
    fun serve(path: String, body: ByteArray) = server.createContext(path) { exchange ->
      exchange.use {
        requests += "${it.requestMethod} ${it.requestURI.path}"
        it.responseHeaders.add("Content-Type", "text/html; charset=utf-8")
        if (it.requestMethod == "HEAD") {
          it.responseHeaders.add("Content-Length", body.size.toString())
          it.sendResponseHeaders(200, -1)
        } else {
          it.sendResponseHeaders(200, body.size.toLong())
          // The client may stop reading once it has seen the start.
          runCatching { it.responseBody.write(body) }
        }
      }
    }
    server.createContext("/download/") { exchange ->
      exchange.use {
        it.responseHeaders.add("Location", "/d/0b1c2d3e")
        it.sendResponseHeaders(302, -1)
      }
    }
    serve("/d/", PAGE)
    serve("/mislabeled/", FILE)
    server.start()
    val folder = Files.createTempDirectory("ketch-web-page-").toFile()
    val ketch = Ketch(
      httpEngine = KtorHttpEngine(),
      config = DownloadConfig(defaultDirectory = folder.absolutePath, retryCount = 0),
    )
    try {
      block(ketch, "http://127.0.0.1:${server.address.port}", folder, requests)
    } finally {
      ketch.close()
      server.stop(0)
      folder.deleteRecursively()
    }
  }

  private companion object {
    /** Longer than what is read of it, so the transfer is ended early. */
    val PAGE = ("<!DOCTYPE html><html><head><title>Sign in</title></head><body>" +
      "<p>Sign in to download this file.</p>".repeat(20_000) + "</body></html>")
      .encodeToByteArray()
    val FILE = ByteArray(300_000) { ((it * 31 + it / 251) % 256).toByte() }
  }
}
