package com.linroid.ketch.engine

import com.linroid.ketch.api.Proxy
import io.ktor.client.request.get
import io.ktor.http.Url
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withTimeout
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.seconds

class AndroidTransportsTest {

  @Test
  fun platformClient_manyRequestsToOneHost_reachTheServerTogether() = runBlocking {
    // The client that unbound engines send requests under the system proxy with, as the app's
    // downloads, trackers and web seeds are. The server holds every response's headers back:
    // OkHttp's default dispatcher held a request past the fifth to a host, and past the 64th in
    // all, until an earlier one had its headers.
    val requests = 80
    val arrived = CountDownLatch(requests)
    val release = CountDownLatch(1)
    val handlers = Executors.newCachedThreadPool()
    val server = ServerSocket(0, requests, InetAddress.getLoopbackAddress())
    handlers.execute {
      while (!server.isClosed) {
        val socket = try {
          server.accept()
        } catch (_: Exception) {
          break
        }
        handlers.execute { answerWhenReleased(socket, arrived, release) }
      }
    }
    val transports = AndroidTransports(network = null)
    try {
      val routed = transports.clientFor(Url("https://example.com/file"), Proxy.System)
      assertEquals(ProxyRoute.Platform, routed.route)
      val url = "http://${server.inetAddress.hostAddress}:${server.localPort}/file"
      val responses = List(requests) {
        async(Dispatchers.IO) { routed.client.get(url) }
      }
      runInterruptible(Dispatchers.IO) { arrived.await(10, TimeUnit.SECONDS) }
      val together = requests - arrived.count.toInt()
      release.countDown()
      withTimeout(60.seconds) { responses.awaitAll() }

      assertEquals(requests, together, "Requests that reached the server together")
    } finally {
      release.countDown()
      transports.close()
      server.close()
      handlers.shutdownNow()
    }
  }

  /** Reads a request's head, then answers it once [release] opens. */
  private fun answerWhenReleased(
    socket: Socket,
    arrived: CountDownLatch,
    release: CountDownLatch,
  ) {
    socket.use {
      val reader = it.getInputStream().bufferedReader(Charsets.ISO_8859_1)
      while (!reader.readLine().isNullOrEmpty()) Unit
      arrived.countDown()
      release.await(30, TimeUnit.SECONDS)
      val response = "HTTP/1.1 200 OK\r\nContent-Length: 1\r\nConnection: close\r\n\r\nx"
      it.getOutputStream().apply {
        write(response.toByteArray(Charsets.ISO_8859_1))
        flush()
      }
    }
  }
}
