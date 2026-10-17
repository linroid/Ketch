package com.linroid.ketch.engine

import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withTimeout
import java.net.InetAddress
import java.net.InetSocketAddress
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.seconds

class OkHttpTransportsTest {

  @Test
  fun okHttp_manyRequestsToOneHost_reachTheServerTogether() = runBlocking {
    // The server holds every response's headers back, as a server slow to answer does. OkHttp's
    // default dispatcher held a request past the fifth to a host, and past the 64th in all, until
    // an earlier one had its headers.
    val requests = 80
    val arrived = CountDownLatch(requests)
    val release = CountDownLatch(1)
    val handlers = Executors.newCachedThreadPool()
    val loopback = InetAddress.getLoopbackAddress()
    val server = HttpServer.create(InetSocketAddress(loopback, 0), requests).apply {
      executor = handlers
      createContext("/file") { exchange ->
        exchange.use {
          arrived.countDown()
          release.await(30, TimeUnit.SECONDS)
          it.sendResponseHeaders(200, 1)
          it.responseBody.write(1)
        }
      }
      start()
    }
    // Bound to an address, the engine takes OkHttp for plain HTTP too, as it does for HTTPS.
    val engine = KtorHttpEngine.forLocalAddress(loopback)
    try {
      val url = "http://${loopback.hostAddress}:${server.address.port}/file"
      val downloads = List(requests) {
        async { engine.download(url, null) {} }
      }
      runInterruptible(Dispatchers.IO) { arrived.await(10, TimeUnit.SECONDS) }
      val together = requests - arrived.count.toInt()
      release.countDown()
      withTimeout(60.seconds) { downloads.awaitAll() }

      assertEquals(requests, together, "Requests that reached the server together")
    } finally {
      release.countDown()
      engine.close()
      server.stop(0)
      handlers.shutdownNow()
    }
  }
}
