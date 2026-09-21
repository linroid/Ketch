package com.linroid.ketch.engine

import com.linroid.ketch.core.engine.MultiNetworkHttpEngine
import com.sun.net.httpserver.HttpServer
import java.net.InetAddress
import java.net.InetSocketAddress
import java.util.Collections
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class NetworkHttpEngineTest {
  private val loopback = InetAddress.getByName("127.0.0.1")

  @Test
  fun socket_isBoundBeforeConnecting() {
    LocalAddressSocketFactory(loopback).createSocket().use { socket ->
      assertTrue(socket.isBound)
      assertTrue(!socket.isConnected)
      assertEquals(loopback, socket.localAddress)
    }
  }

  @Test
  fun wildcardAndNonLocalAddresses_areRejected() {
    for (address in listOf("0.0.0.0", "224.0.0.1", "192.0.2.1")) {
      assertFailsWith<IllegalArgumentException> {
        KtorHttpEngine.forLocalAddress(InetAddress.getByName(address))
      }
    }
  }

  @Test
  fun boundEngines_downloadRangesThroughRealHttpConnections() = runTest {
    val peers = Collections.synchronizedList(mutableListOf<InetAddress>())
    val ranges = Collections.synchronizedList(mutableListOf<String>())
    val content = ByteArray(32) { it.toByte() }
    val server = HttpServer.create(InetSocketAddress(loopback, 0), 0)
    server.createContext("/file") { exchange ->
      exchange.use {
        peers.add(it.remoteAddress.address)
        it.responseHeaders.add("Accept-Ranges", "bytes")
        if (it.requestMethod == "HEAD") {
          it.responseHeaders.add("Content-Length", content.size.toString())
          it.sendResponseHeaders(200, -1)
        } else {
          val range = it.requestHeaders.getFirst("Range")
          ranges.add(range)
          val (start, end) = range.removePrefix("bytes=").split('-').map(String::toInt)
          val bytes = content.copyOfRange(start, end + 1)
          it.responseHeaders.add("Content-Range", "bytes $start-$end/${content.size}")
          it.sendResponseHeaders(206, bytes.size.toLong())
          it.responseBody.write(bytes)
        }
      }
    }
    server.start()
    val engine = MultiNetworkHttpEngine(List(2) { KtorHttpEngine.forLocalAddress(loopback) })
    try {
      val url = "http://127.0.0.1:${server.address.port}/file"
      assertEquals(content.size.toLong(), engine.head(url).contentLength)
      val downloaded = ByteArray(content.size)
      List(4) { index ->
        async {
          var offset = index * 8
          engine.download(url, offset.toLong()..offset + 7L) { bytes ->
            bytes.copyInto(downloaded, offset)
            offset += bytes.size
          }
        }
      }.awaitAll()
      assertContentEquals(content, downloaded)
      assertEquals(setOf("bytes=0-7", "bytes=8-15", "bytes=16-23", "bytes=24-31"), ranges.toSet())
      assertEquals(5, peers.size)
      assertTrue(peers.all { it == loopback })
    } finally {
      engine.close()
      server.stop(0)
    }
  }
}
