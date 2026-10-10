package com.linroid.ketch.torrent

import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.net.InetAddress

/**
 * Both downloaders discover the same seeder through this loopback-only fixture tracker. It reads
 * [peerPort] on each announce, as the seeder may move to another port while it starts.
 */
internal class TorrentBenchmarkTracker(peerPort: () -> Int, dataHost: String) {
  private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
  val url: String get() = "http://127.0.0.1:${server.address.port}/announce"

  init {
    val host = InetAddress.getByName(dataHost).address
    server.createContext("/announce") { exchange ->
      try {
        val port = peerPort()
        val peer = host + byteArrayOf((port shr 8).toByte(), port.toByte())
        val response = Bencode.encode(mapOf("interval" to 60L, "peers" to peer))
        exchange.sendResponseHeaders(200, response.size.toLong())
        exchange.responseBody.use { it.write(response) }
      } finally { exchange.close() }
    }
    server.start()
  }

  fun close() { server.stop(0) }
}
