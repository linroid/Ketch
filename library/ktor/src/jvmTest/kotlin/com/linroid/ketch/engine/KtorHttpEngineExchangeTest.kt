package com.linroid.ketch.engine

import com.linroid.ketch.api.ConnectionRoute
import com.linroid.ketch.api.ProxyConfig
import com.linroid.ketch.core.engine.HttpEngine
import com.linroid.ketch.core.engine.HttpExchange
import com.linroid.ketch.core.engine.HttpExchangeObserver
import com.sun.net.httpserver.HttpServer
import io.ktor.http.HttpProtocolVersion
import java.net.InetAddress
import java.net.InetSocketAddress
import java.util.Collections
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.seconds

/** What [KtorHttpEngine] reports to an [HttpExchangeObserver], over real connections. */
class KtorHttpEngineExchangeTest {
  private val content = ByteArray(64) { it.toByte() }

  @Test
  fun download_directRequest_reportsHttp11AndDirect() = realTime {
    OriginServer(content).use { origin ->
      withEngine { engine ->
        val reported = engine.observe("http://127.0.0.1:${origin.port}/file")

        val exchange = reported.single()
        assertEquals("127.0.0.1", exchange.host)
        assertEquals(origin.port, exchange.port)
        assertEquals(false, exchange.secure)
        assertEquals("HTTP/1.1", exchange.protocol)
        assertEquals(ConnectionRoute.DIRECT, exchange.route)
      }
    }
  }

  @Test
  fun download_redirectToOtherPort_reportsEveryHopFinalLast() = realTime {
    OriginServer(content).use { target ->
      val redirect = HttpServer.create(InetSocketAddress(loopback, 0), 0)
      redirect.createContext("/file") { exchange ->
        exchange.use {
          it.responseHeaders.add("Location", "http://127.0.0.1:${target.port}/file")
          it.sendResponseHeaders(302, -1)
        }
      }
      redirect.start()
      try {
        withEngine { engine ->
          val reported = engine.observe("http://127.0.0.1:${redirect.address.port}/file")

          assertEquals(listOf(redirect.address.port, target.port), reported.map { it.port })
        }
      } finally {
        redirect.stop(0)
      }
    }
  }

  @Test
  fun download_throughHttpProxy_reportsTargetHostAndProxyRoute() = realTime {
    OriginServer(content).use { origin ->
      TestHttpProxy().use { proxy ->
        withEngine(ProxyConfig.manual("http://127.0.0.1:${proxy.port}")) { engine ->
          val reported = engine.observe("http://origin.test:${origin.port}/file")

          val exchange = reported.single()
          assertEquals("origin.test", exchange.host)
          assertEquals(origin.port, exchange.port)
          assertEquals(ConnectionRoute.HTTP_PROXY, exchange.route)
        }
      }
    }
  }

  @Test
  fun head_outsideObserver_reportsNothing() = realTime {
    OriginServer(content).use { origin ->
      withEngine { engine ->
        val reported = Collections.synchronizedList(mutableListOf<HttpExchange>())
        val url = "http://127.0.0.1:${origin.port}/file"

        engine.head(url)
        withContext(HttpExchangeObserver { reported += it }) {
          engine.download(url, 0L..9L) {}
        }

        assertEquals(1, reported.size)
        assertEquals(1, origin.requests.count { "range" in it })
      }
    }
  }

  @Test
  fun protocolLabel_shortensMajorVersionsFromTwo() {
    assertEquals("HTTP/1.1", KtorHttpEngine.protocolLabel(HttpProtocolVersion.HTTP_1_1))
    assertEquals("HTTP/1.0", KtorHttpEngine.protocolLabel(HttpProtocolVersion.HTTP_1_0))
    assertEquals("HTTP/2", KtorHttpEngine.protocolLabel(HttpProtocolVersion.HTTP_2_0))
    assertEquals("HTTP/3", KtorHttpEngine.protocolLabel(HttpProtocolVersion("HTTP", 3, 0)))
  }

  private suspend fun HttpEngine.observe(url: String): List<HttpExchange> {
    val reported = Collections.synchronizedList(mutableListOf<HttpExchange>())
    withContext(HttpExchangeObserver { reported += it }) {
      download(url, null) {}
    }
    return reported.toList()
  }

  private suspend fun withEngine(
    proxy: ProxyConfig = ProxyConfig.System,
    block: suspend (HttpEngine) -> Unit,
  ) {
    val engine = KtorHttpEngine()
    try {
      block(if (proxy == ProxyConfig.System) engine else engine.withProxy(proxy))
    } finally {
      engine.close()
    }
  }

  private fun realTime(block: suspend () -> Unit) = runTest {
    withContext(Dispatchers.Default) {
      withTimeout(20.seconds) { block() }
    }
  }

  private companion object {
    val loopback: InetAddress = InetAddress.getByName("127.0.0.1")
  }
}
