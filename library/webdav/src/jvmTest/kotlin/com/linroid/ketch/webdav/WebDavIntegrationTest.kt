package com.linroid.ketch.webdav

import com.linroid.ketch.api.Destination
import com.linroid.ketch.api.DownloadConfig
import com.linroid.ketch.api.DownloadRequest
import com.linroid.ketch.api.DownloadState
import com.linroid.ketch.api.KetchError
import com.linroid.ketch.core.Ketch
import com.linroid.ketch.core.engine.HttpEngine
import com.linroid.ketch.core.engine.resolve
import com.linroid.ketch.engine.KtorHttpEngine
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.nio.file.Files
import java.security.MessageDigest
import java.util.Collections
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * Downloads from a WebDAV server on loopback, which requires Digest authentication, through
 * [KtorHttpEngine] and a [Ketch] instance.
 */
class WebDavIntegrationTest {
  @Test
  fun download_withDigest_inSegments() = runTest(timeout = 30.seconds) {
    withServer { server ->
      val state = server.download(connections = 4)
      assertContentEquals(server.content, File(assertIs<DownloadState.Completed>(state).outputPath)
        .readBytes())
      assertTrue("PROPFIND" in server.methods)
      // Every other request is a probe of the first byte.
      assertEquals(4, server.ranges.count { it != PROBE })
    }
  }

  @Test
  fun pause_resume_continuesFromTheBytesWritten() = runTest(timeout = 30.seconds) {
    withServer(pauseFirst = true) { server ->
      val task = server.start(connections = 1)
      server.firstChunk.await()
      task.pause()
      val written = task.segments.value.sumOf { it.downloadedBytes }
      assertTrue(written > 0)
      server.ranges.clear()
      // The server asks again with a new nonce, which the resume answers.
      server.nonce.set("nonce-2")
      task.resume()
      val state = assertIs<DownloadState.Completed>(task.state.first { it.isTerminal })
      assertContentEquals(server.content, File(state.outputPath).readBytes())
      assertEquals(
        listOf("bytes=$written-${server.content.size - 1}"),
        server.ranges.filter { it != PROBE },
      )
    }
  }

  @Test
  fun resume_afterTheFileChanged_fails() = runTest(timeout = 30.seconds) {
    withServer(pauseFirst = true) { server ->
      val task = server.start(connections = 1)
      server.firstChunk.await()
      task.pause()
      server.etag.set("\"v2\"")
      task.resume()
      val state = assertIs<DownloadState.Failed>(task.state.first { it.isTerminal })
      assertIs<KetchError.FileChanged>(state.error)
    }
  }

  @Test
  fun unencodedLink_answersDigestForTheTargetSent() = runTest(timeout = 30.seconds) {
    withServer { server ->
      val task = server.start(connections = 2, path = "/dav/报告 final.bin")
      val state = assertIs<DownloadState.Completed>(task.state.first { it.isTerminal })
      assertEquals("报告 final.bin", File(state.outputPath).name)
      assertContentEquals(server.content, File(state.outputPath).readBytes())
    }
  }

  @Test
  fun wrongPassword_failsAuthentication() = runTest(timeout = 30.seconds) {
    withServer { server ->
      val state = server.download(connections = 1, password = "nope")
      assertIs<KetchError.AuthenticationFailed>(assertIs<DownloadState.Failed>(state).error)
    }
  }

  @Test
  fun folder_isRefused() = runTest(timeout = 30.seconds) {
    withServer { server ->
      assertFailsWith<KetchError.SourceError> {
        WebDavDownloadSource(server.engine).resolve(server.url("/dav/folder/", "secret"))
      }
    }
  }

  @Test
  fun sameOriginRedirect_answersDigestForTheNewTarget() = runTest(timeout = 30.seconds) {
    withServer { server ->
      val task = server.start(connections = 2, path = ALIAS)
      val state = assertIs<DownloadState.Completed>(task.state.first { it.isTerminal })
      assertContentEquals(server.content, File(state.outputPath).readBytes())
    }
  }

  private companion object {
    const val PROBE = "bytes=0-0"
    const val ALIAS = "/dav/alias.bin"
  }

  private suspend fun withServer(
    pauseFirst: Boolean = false,
    block: suspend (DavServer) -> Unit,
  ) = withContext(Dispatchers.IO) {
    val server = DavServer(pauseFirst)
    try {
      block(server)
    } finally {
      server.close()
    }
  }

  private class DavServer(private val pauseFirst: Boolean) {
    val content = ByteArray(200_003) { ((it * 31 + it / 251) % 256).toByte() }
    val methods: MutableList<String> = Collections.synchronizedList(mutableListOf())
    val ranges: MutableList<String> = Collections.synchronizedList(mutableListOf())
    val etag = AtomicReference("\"v1\"")
    val nonce = AtomicReference("nonce-1")
    val firstChunk = CompletableDeferred<Unit>()
    private val directory = Files.createTempDirectory("ketch-webdav-integration-").toFile()
    private val server = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0)
    private val executor = Executors.newCachedThreadPool()
    private val transport = KtorHttpEngine()
    val engine: HttpEngine = object : HttpEngine by transport {
      override suspend fun download(
        url: String,
        range: LongRange?,
        headers: Map<String, String>,
        onData: suspend (ByteArray) -> Unit,
      ) {
        transport.download(url, range, headers) { bytes ->
          onData(bytes)
          if (pauseFirst && firstChunk.complete(Unit)) awaitCancellation()
        }
      }
    }
    private val ketch = Ketch(
      httpEngine = engine,
      config = DownloadConfig(retryCount = 0, progressIntervalMs = 100),
      additionalSources = listOf(WebDavDownloadSource(engine)),
    )

    init {
      server.executor = executor
      server.createContext("/dav/") { exchange ->
        try {
          exchange.use(::handle)
        } catch (_: IOException) {
          // The downloader cancelled the transfer.
        }
      }
      server.start()
    }

    fun url(path: String, password: String) =
      "dav://alice:$password@127.0.0.1:${server.address.port}$path"

    suspend fun start(
      connections: Int,
      password: String = "secret",
      path: String = "/dav/My%20File.bin",
    ) = ketch.download(
      DownloadRequest(
        url = url(path, password),
        destination = Destination("${directory.absolutePath}/"),
        connections = connections,
      ),
    )

    suspend fun download(connections: Int, password: String = "secret"): DownloadState =
      start(connections, password).state.first { it.isTerminal }

    fun close() {
      ketch.close()
      server.stop(0)
      executor.shutdownNow()
      directory.deleteRecursively()
    }

    private fun handle(exchange: HttpExchange) {
      methods += exchange.requestMethod
      if (exchange.requestURI.rawPath == ALIAS) {
        exchange.responseHeaders.add("Location", "/dav/My%20File.bin")
        exchange.sendResponseHeaders(301, -1)
        return
      }
      if (!authorized(exchange)) {
        exchange.responseHeaders.add(
          "WWW-Authenticate",
          "Digest realm=\"dav\", qop=\"auth\", algorithm=MD5, nonce=\"${nonce.get()}\"",
        )
        exchange.sendResponseHeaders(401, -1)
        return
      }
      val path = exchange.requestURI.rawPath
      when (exchange.requestMethod) {
        "PROPFIND" -> {
          require(exchange.requestHeaders.getFirst("Depth") == "0")
          exchange.requestBody.readBytes()
          val body = if (path.endsWith("/")) {
            "<d:multistatus xmlns:d=\"DAV:\"><d:response><d:href>$path</d:href><d:propstat>" +
              "<d:prop><d:resourcetype><d:collection/></d:resourcetype></d:prop>" +
              "<d:status>HTTP/1.1 200 OK</d:status></d:propstat></d:response></d:multistatus>"
          } else {
            "<?xml version=\"1.0\" encoding=\"utf-8\"?><D:multistatus xmlns:D=\"DAV:\">" +
              "<D:response><D:href>$path</D:href><D:propstat><D:prop><D:resourcetype/>" +
              "<D:getcontentlength>${content.size}</D:getcontentlength>" +
              "<D:getetag>${etag.get().replace("\"", "&quot;")}</D:getetag>" +
              "</D:prop><D:status>HTTP/1.1 200 OK</D:status></D:propstat></D:response>" +
              "</D:multistatus>"
          }
          val bytes = body.encodeToByteArray()
          exchange.responseHeaders.add("Content-Type", "application/xml; charset=utf-8")
          exchange.sendResponseHeaders(207, bytes.size.toLong())
          exchange.responseBody.write(bytes)
        }
        "HEAD" -> {
          exchange.responseHeaders.add("Accept-Ranges", "bytes")
          exchange.responseHeaders.add("ETag", etag.get())
          exchange.responseHeaders.add("Content-Length", content.size.toString())
          exchange.sendResponseHeaders(200, -1)
        }
        "GET" -> {
          val range = exchange.requestHeaders.getFirst("Range")
          ranges += range ?: "none"
          exchange.responseHeaders.add("Accept-Ranges", "bytes")
          exchange.responseHeaders.add("ETag", etag.get())
          val (start, end) = range?.removePrefix("bytes=")?.split('-')?.map(String::toInt)
            ?: listOf(0, content.lastIndex)
          if (range != null) {
            exchange.responseHeaders.add("Content-Range", "bytes $start-$end/${content.size}")
          }
          exchange.sendResponseHeaders(if (range != null) 206 else 200, (end - start + 1).toLong())
          exchange.responseBody.write(content, start, end - start + 1)
        }
        else -> exchange.sendResponseHeaders(405, -1)
      }
    }

    /** Checks the Digest answer (RFC 7616, MD5, `qop=auth`) to the current nonce. */
    private fun authorized(exchange: HttpExchange): Boolean {
      val header = exchange.requestHeaders.getFirst("Authorization") ?: return false
      if (!header.startsWith("Digest ")) return false
      val params = Challenge.parse(listOf(header)).single().params
      if (params["nonce"] != nonce.get() || params["username"] != "alice") return false
      if (params["uri"] != exchange.requestURI.rawPath) return false
      val ha1 = md5("alice:dav:secret")
      val ha2 = md5("${exchange.requestMethod}:${params["uri"]}")
      val expected = md5("$ha1:${nonce.get()}:${params["nc"]}:${params["cnonce"]}:auth:$ha2")
      return params["response"] == expected
    }

    private fun md5(text: String): String =
      MessageDigest.getInstance("MD5").digest(text.toByteArray())
        .joinToString("") { "%02x".format(it) }
  }
}
