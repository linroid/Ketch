package com.linroid.ketch.engine

import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.test.runTest
import java.net.InetAddress
import java.net.InetSocketAddress
import java.util.Collections
import kotlin.test.Test
import kotlin.test.assertEquals

class KtorHttpEngineEncodingTest {
  @Test
  fun requests_askForIdentity_unlessTheCallerNamesAnEncoding() = runTest {
    val seen = Collections.synchronizedList(mutableListOf<String>())
    val server = HttpServer.create(InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0), 0)
    server.createContext("/file") { exchange ->
      exchange.use {
        seen += "${it.requestMethod} ${it.requestHeaders.getFirst("Accept-Encoding")}"
        if (it.requestMethod == "HEAD") {
          it.responseHeaders.add("Content-Length", "4")
          it.sendResponseHeaders(200, -1)
        } else {
          it.sendResponseHeaders(200, 4)
          it.responseBody.write(byteArrayOf(1, 2, 3, 4))
        }
      }
    }
    server.start()
    val engine = KtorHttpEngine()
    try {
      val url = "http://127.0.0.1:${server.address.port}/file"

      // Compressed on the fly, a server's lengths would not match the ranges Ketch then asks.
      engine.head(url, emptyMap())
      engine.download(url, null, emptyMap()) {}
      engine.head(url, mapOf("accept-encoding" to "gzip"))

      assertEquals(listOf("HEAD identity", "GET identity", "HEAD gzip"), seen.toList())
    } finally {
      engine.close()
      server.stop(0)
    }
  }
}
