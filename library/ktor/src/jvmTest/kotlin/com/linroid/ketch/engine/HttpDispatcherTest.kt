package com.linroid.ketch.engine

import com.linroid.ketch.api.ProxyConfig
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.net.InetAddress
import java.net.InetSocketAddress
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
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

  @Test
  fun cio_runsFewTasksAtOnce(): Unit = runBlocking {
    // A burst of CIO's short tasks takes at most its slice, not a thread each up to OkHttp's 64.
    val slice = cioDispatcher()
    val running = AtomicInteger()
    val most = AtomicInteger()
    List(40) {
      async(slice) {
        most.accumulateAndGet(running.incrementAndGet(), ::maxOf)
        Thread.sleep(20)
        running.decrementAndGet()
      }
    }.awaitAll()
    assertEquals(8, most.get())
  }

  @Test
  fun cio_clientsDoNotShareTheirSlices(): Unit = runBlocking {
    // Each CIO client's selector keeps a thread of its slice while it has sockets: another
    // client's slice must run even when every thread of the first one is taken.
    val first = cioDispatcher()
    val second = cioDispatcher()
    val release = CountDownLatch(1)
    val held = CountDownLatch(8)
    val holders = List(8) {
      async(first) {
        held.countDown()
        release.await()
      }
    }
    try {
      check(held.await(5, TimeUnit.SECONDS)) { "The first slice never filled" }
      // Awaiting the probe needs no thread of either slice, so a shared one fails the test
      // rather than hanging it.
      val probe = async(second) { 1 }
      assertEquals(1, withTimeoutOrNull(5.seconds) { probe.await() }, "The slices are shared")
    } finally {
      release.countDown()
      holders.awaitAll()
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
      val firstBytes = CountDownLatch(1)
      var waiting = emptyList<String>()
      // Samples while the response stalls, which starts once its first bytes arrive: a fixed delay
      // missed the stall when a cold client took long to connect.
      val sampler = thread(isDaemon = true) {
        if (firstBytes.await(30, TimeUnit.SECONDS)) waiting = threadsWhere(waits)
      }
      withTimeout(30.seconds) {
        engine.withProxy(proxy).download(url, null) {
          received.write(it)
          firstBytes.countDown()
        }
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
