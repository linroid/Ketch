package com.linroid.ketch.engine

import com.linroid.ketch.api.Destination
import com.linroid.ketch.api.DownloadConfig
import com.linroid.ketch.api.DownloadRequest
import com.linroid.ketch.api.DownloadState
import com.linroid.ketch.api.Proxy
import com.linroid.ketch.api.SpeedLimit
import com.linroid.ketch.core.Ketch
import com.sun.net.httpserver.HttpServer
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.http.Url
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.job
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.nio.file.Files
import java.util.concurrent.Executors
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

class KtorHttpEngineReleaseTest {
  @Test
  fun pausedDownload_leavesNothingHoldingTheBodiesItStoppedReading() = runBlocking {
    // Real time and sockets: a download held back by a speed limit stops reading bodies the
    // server keeps sending, which fill every buffer on their way, when it is paused.
    val body = ByteArray(32 * 1024 * 1024)
    val server = HttpServer.create(InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0), 0)
    server.executor = Executors.newCachedThreadPool()
    server.createContext("/file.bin") { exchange ->
      try {
        val range = exchange.requestHeaders.getFirst("Range")?.removePrefix("bytes=")?.split('-')
        val first = range?.get(0)?.toInt() ?: 0
        val last = range?.get(1)?.toIntOrNull() ?: (body.size - 1)
        exchange.responseHeaders.add("Accept-Ranges", "bytes")
        if (exchange.requestMethod == "HEAD") {
          exchange.responseHeaders.add("Content-Length", body.size.toString())
          exchange.sendResponseHeaders(200, -1)
          return@createContext
        }
        if (range != null) {
          exchange.responseHeaders.add("Content-Range", "bytes $first-$last/${body.size}")
        }
        exchange.sendResponseHeaders(if (range != null) 206 else 200, (last - first + 1).toLong())
        exchange.responseBody.use { it.write(body, first, last - first + 1) }
      } catch (_: IOException) {
        // The download went away.
      } finally {
        exchange.close()
      }
    }
    server.start()
    // As JvmTransports builds its client for plain HTTP.
    val client = HttpClient(CIO) {
      followRedirects = false
      engine { dispatcher = cioDispatcher() }
      install(HttpTimeout) {
        connectTimeoutMillis = 30_000
        socketTimeoutMillis = Long.MAX_VALUE
        requestTimeoutMillis = Long.MAX_VALUE
      }
    }
    // This very client, not a copy, so its job shows the coroutines its calls leave behind.
    val transports = object : HttpTransports {
      override fun supports(proxy: Proxy) = proxy == Proxy.System
      override fun clientFor(url: Url, proxy: Proxy) = RoutedClient(client, ProxyRoute.Direct)
      override fun close() = client.close()
    }
    val engine = KtorHttpEngine(transports, logRequests = true, userAgent = null)
    val directory = Files.createTempDirectory("ketch-release").toFile()
    val ketch = Ketch(httpEngine = engine, config = DownloadConfig(retryCount = 0))
    try {
      val task = ketch.download(DownloadRequest(
        url = "http://127.0.0.1:${server.address.port}/file.bin",
        destination = Destination(directory.absolutePath + "/"),
        connections = 4,
        speedLimit = SpeedLimit.mbps(4),
      ))
      repeat(3) {
        task.state.first { it is DownloadState.Downloading }
        delay(700)
        task.pause()
        task.state.first { it is DownloadState.Paused }
        task.resume()
      }
      task.pause()
      task.state.first { it is DownloadState.Paused }

      // Every call ends; one whose body is still copied would stay a child of the client's job.
      val calls = client.coroutineContext.job
      val left = withTimeoutOrNull(5.seconds) {
        while (calls.children.any()) delay(50.milliseconds)
      }?.let { 0 } ?: calls.children.count()
      assertEquals(0, left)
    } finally {
      ketch.close()
      engine.close()
      client.close()
      server.stop(0)
      directory.deleteRecursively()
    }
  }
}
