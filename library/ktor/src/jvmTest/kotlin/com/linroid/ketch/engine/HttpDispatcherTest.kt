package com.linroid.ketch.engine

import com.linroid.ketch.api.ProxyConfig
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.net.InetAddress
import java.net.InetSocketAddress
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * The clients KtorHttpEngine builds block on Ketch's own `ketch-http-N` threads, never on the
 * application's `Dispatchers.IO`. Each response stalls after its first bytes, and a thread dump
 * taken meanwhile shows where the client waits.
 */
class HttpDispatcherTest {
  @Test
  fun okHttp_readsResponseBodiesOnKetchThreads() = runBlocking {
    StallingServer().use { server ->
      // Through SOCKS5, whose relay passes bytes on as they arrive: OkHttp takes every proxy. A
      // name, as loopback addresses never go through one; the proxy resolves it to loopback.
      TestSocks5Proxy().use { proxy ->
        val proxied = ProxyConfig.manual("socks5://127.0.0.1:${proxy.port}")
        val waiting = download(proxied, "http://origin.test:${server.port}/file") {
          frames -> frames.any { "io.ktor.client.engine.okhttp" in it && "toChannel" in it }
        }
        assertTrue(waiting.isNotEmpty(), "No thread was reading the response body")
        assertTrue(waiting.all { it.startsWith("ketch-http-") }, "Read on $waiting")
      }
    }
  }

  @Test
  fun cio_selectsOnKetchThreads() = runBlocking {
    StallingServer().use { server ->
      val waiting = download(ProxyConfig.System, "http://127.0.0.1:${server.port}/file") { frames ->
        frames.any { "ActorSelectorManager" in it && "select" in it }
      }
      assertTrue(waiting.any { it.startsWith("ketch-http-") }, "Selected on $waiting")
    }
  }

  /**
   * Downloads a [StallingServer]'s [url] through [proxy], and returns the names of the threads
   * whose stack frames match [waits] while the response stalls.
   */
  private suspend fun download(
    proxy: ProxyConfig,
    url: String,
    waits: (List<String>) -> Boolean,
  ): List<String> {
    val engine = KtorHttpEngine()
    try {
      val received = ByteArrayOutputStream()
      var waiting = emptyList<String>()
      // Samples while the response stalls, a little after its first bytes went out.
      val sampler = thread(isDaemon = true) {
        Thread.sleep(500)
        waiting = threadsWhere(waits)
      }
      withTimeout(30.seconds) {
        engine.withProxy(proxy).download(url, null) { received.write(it) }
      }
      sampler.join()
      assertEquals(StallingServer.SIZE, received.size())
      return waiting
    } finally {
      engine.close()
    }
  }

  /** Samples thread dumps for up to a second, until a thread's frames match [waits]. */
  private fun threadsWhere(waits: (List<String>) -> Boolean): List<String> {
    repeat(20) {
      val matching = Thread.getAllStackTraces().filter { (_, stack) ->
        waits(stack.map { "${it.className}.${it.methodName}" })
      }.keys.map { it.name }
      if (matching.isNotEmpty()) return matching
      Thread.sleep(50)
    }
    return emptyList()
  }

  /** Serves [SIZE] bytes, stopping for two seconds after the first [HEAD] of them. */
  private class StallingServer : Closeable {
    private val loopback = InetAddress.getLoopbackAddress()
    private val server = HttpServer.create(InetSocketAddress(loopback, 0), 0).apply {
      createContext("/file") { exchange ->
        exchange.use {
          it.sendResponseHeaders(200, SIZE.toLong())
          if (it.requestMethod != "HEAD") {
            it.responseBody.write(ByteArray(HEAD))
            it.responseBody.flush()
            Thread.sleep(2_000)
            it.responseBody.write(ByteArray(SIZE - HEAD))
          }
        }
      }
      start()
    }

    val port: Int get() = server.address.port

    override fun close() {
      server.stop(0)
    }

    companion object {
      const val SIZE = 64 * 1024
      const val HEAD = 1024
    }
  }
}
