package com.linroid.ketch.engine

import com.linroid.ketch.api.KetchApi
import com.sun.net.httpserver.Headers
import com.sun.net.httpserver.HttpServer
import java.net.InetAddress
import java.net.InetSocketAddress
import java.util.Collections
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** Redirects over real connections with the platform's default Ktor transport. */
class KtorHttpEngineRedirectTest {
  private val loopback = InetAddress.getByName("127.0.0.1")

  @Test
  fun download_redirectToOtherPort_dropsCredentialsAndReusesTarget() = runTest {
    val content = ByteArray(32) { it.toByte() }
    val received = Collections.synchronizedList(mutableListOf<Pair<String, Headers>>())
    val target = HttpServer.create(InetSocketAddress(loopback, 0), 0)
    target.createContext("/file") { exchange ->
      exchange.use {
        received += "${it.requestMethod} target" to it.requestHeaders
        val range = it.requestHeaders.getFirst("Range")
        if (it.requestMethod == "HEAD") {
          it.responseHeaders.add("Accept-Ranges", "bytes")
          it.responseHeaders.add("Content-Length", content.size.toString())
          it.sendResponseHeaders(200, -1)
        } else {
          val (start, end) = range.removePrefix("bytes=").split('-').map(String::toInt)
          it.responseHeaders.add("Content-Range", "bytes $start-$end/${content.size}")
          it.sendResponseHeaders(206, (end - start + 1).toLong())
          it.responseBody.write(content, start, end - start + 1)
        }
      }
    }
    val origin = HttpServer.create(InetSocketAddress(loopback, 0), 0)
    origin.createContext("/file") { exchange ->
      exchange.use {
        received += "${it.requestMethod} origin" to it.requestHeaders
        it.responseHeaders.add("Location", "http://127.0.0.1:${target.address.port}/file")
        it.sendResponseHeaders(302, -1)
      }
    }
    target.start()
    origin.start()
    val engine = KtorHttpEngine()
    try {
      val url = "http://127.0.0.1:${origin.address.port}/file"
      val headers = mapOf("Cookie" to "sid=1", "Authorization" to "Bearer t", "Accept" to "*/*")

      assertEquals(content.size.toLong(), engine.head(url, headers).contentLength)
      val downloaded = ByteArray(content.size)
      engine.download(url, 0L..31L, headers) { it.copyInto(downloaded) }

      assertContentEquals(content, downloaded)
      assertEquals(listOf("HEAD origin", "HEAD target", "GET target"), received.map { it.first })
      val (_, originHeaders) = received.first()
      assertEquals("sid=1", originHeaders.getFirst("Cookie"))
      for ((_, sent) in received.drop(1)) {
        assertNull(sent.getFirst("Cookie"))
        assertNull(sent.getFirst("Authorization"))
        assertEquals("*/*", sent.getFirst("Accept"))
        assertEquals("Ketch/${KetchApi.VERSION}", sent.getFirst("User-Agent"))
      }
    } finally {
      engine.close()
      origin.stop(0)
      target.stop(0)
    }
  }
}
