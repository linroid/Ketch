package com.linroid.ketch.engine

import com.sun.net.httpserver.HttpServer
import java.io.Closeable
import java.io.DataInputStream
import java.io.InputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URI
import java.util.Base64
import java.util.Collections
import kotlin.concurrent.thread

private val loopback: InetAddress = InetAddress.getByName("127.0.0.1")

/**
 * An HTTP server on loopback serving [content] at `/file`, with byte ranges, that records the
 * headers of every request.
 */
internal class OriginServer(val content: ByteArray) : Closeable {
  val requests: MutableList<Map<String, String>> = Collections.synchronizedList(mutableListOf())
  private val server = HttpServer.create(InetSocketAddress(loopback, 0), 0).apply {
    createContext("/file") { exchange ->
      exchange.use {
        requests.add(it.requestHeaders.entries.associate { (name, values) ->
          name.lowercase() to values.joinToString(",")
        })
        it.responseHeaders.add("Accept-Ranges", "bytes")
        val range = it.requestHeaders.getFirst("Range")
          ?.removePrefix("bytes=")?.split('-')?.let { (start, end) -> start.toInt()..end.toInt() }
        val body = if (range == null) content else content.copyOfRange(range.first, range.last + 1)
        if (range != null) {
          val contentRange = "bytes ${range.first}-${range.last}/${content.size}"
          it.responseHeaders.add("Content-Range", contentRange)
        }
        val status = if (range == null) 200 else 206
        if (it.requestMethod == "HEAD") {
          it.responseHeaders.add("Content-Length", body.size.toString())
          it.sendResponseHeaders(status, -1)
        } else {
          it.sendResponseHeaders(status, body.size.toLong())
          it.responseBody.write(body)
        }
      }
    }
    start()
  }

  val port: Int get() = server.address.port

  override fun close() {
    server.stop(0)
  }
}

/** A request a proxy received: its method, its target and its `Proxy-Authorization`. */
internal data class ProxiedRequest(val method: String, val target: String, val auth: String?)

/** A loopback proxy that accepts connections and serves each on a thread of its own. */
internal abstract class TestProxy : Closeable {
  private val server = ServerSocket(0, 50, loopback)
  val port: Int get() = server.localPort

  init {
    thread(isDaemon = true) {
      while (!server.isClosed) {
        val client = try {
          server.accept()
        } catch (_: Exception) {
          break
        }
        thread(isDaemon = true) { client.use { serve(it) } }
      }
    }
  }

  protected abstract fun serve(client: Socket)

  /** Connects to [port] on loopback, where every destination name leads. */
  protected fun connect(port: Int): Socket = Socket(loopback, port)

  /** Copies between [client] and [upstream] both ways until either side closes. */
  protected fun relay(client: Socket, upstream: Socket, clientInput: InputStream) {
    val back = thread(isDaemon = true) {
      try {
        upstream.getInputStream().copyTo(client.getOutputStream())
      } catch (_: Exception) {
      } finally {
        client.close()
      }
    }
    try {
      clientInput.copyTo(upstream.getOutputStream())
    } catch (_: Exception) {
    } finally {
      upstream.close()
    }
    back.join()
  }

  override fun close() {
    server.close()
  }
}

/**
 * An HTTP proxy that forwards plain requests and tunnels `CONNECT`, answering 407 unless
 * requests carry [username] and [password] with Basic authentication.
 */
internal class TestHttpProxy(
  private val username: String? = null,
  private val password: String? = null,
) : TestProxy() {
  val requests: MutableList<ProxiedRequest> = Collections.synchronizedList(mutableListOf())

  override fun serve(client: Socket) {
    val input = client.getInputStream().buffered()
    val head = readHead(input) ?: return
    val (method, target) = head.first().split(' ')
    val headers = head.drop(1).associate {
      it.substringBefore(':').trim().lowercase() to it.substringAfter(':').trim()
    }
    val auth = headers["proxy-authorization"]
    requests.add(ProxiedRequest(method, target, auth))
    val expected = username?.let {
      "Basic " + Base64.getEncoder().encodeToString("$it:$password".toByteArray())
    }
    val output = client.getOutputStream()
    if (expected != null && auth != expected) {
      output.write(
        ("HTTP/1.1 407 Proxy Authentication Required\r\n" +
          "Proxy-Authenticate: Basic realm=\"test\"\r\nContent-Length: 0\r\n" +
          "Connection: close\r\n\r\n").toByteArray(),
      )
      return
    }
    if (method == "CONNECT") {
      connect(target.substringAfterLast(':').toInt()).use { upstream ->
        output.write("HTTP/1.1 200 Connection established\r\n\r\n".toByteArray())
        output.flush()
        relay(client, upstream, input)
      }
      return
    }
    val url = URI(target)
    connect(url.port).use { upstream ->
      val forwarded = buildString {
        append("$method ${url.rawPath} HTTP/1.1\r\n")
        for (line in head.drop(1)) {
          val name = line.substringBefore(':').trim().lowercase()
          if (!name.startsWith("proxy-") && name != "connection") append("$line\r\n")
        }
        append("Connection: close\r\n\r\n")
      }
      upstream.getOutputStream().write(forwarded.toByteArray())
      upstream.getInputStream().copyTo(output)
    }
  }

  private fun readHead(input: InputStream): List<String>? {
    val lines = mutableListOf<String>()
    val line = StringBuilder()
    while (true) {
      val byte = input.read()
      if (byte < 0) return null
      if (byte == '\n'.code) {
        val text = line.toString().trimEnd('\r')
        if (text.isEmpty()) return lines
        lines.add(text)
        line.clear()
      } else {
        line.append(byte.toChar())
      }
    }
  }
}

/**
 * A SOCKS5 proxy that records the destination each connection asks for, requiring [username]
 * and [password] when they are set.
 */
internal class TestSocks5Proxy(
  private val username: String? = null,
  private val password: String? = null,
) : TestProxy() {
  /** The destinations asked for, as `host:port`, with `domain:` before names. */
  val destinations: MutableList<String> = Collections.synchronizedList(mutableListOf())

  override fun serve(client: Socket) {
    val input = DataInputStream(client.getInputStream())
    val output = client.getOutputStream()
    check(input.readByte() == 5.toByte())
    val methods = ByteArray(input.readUnsignedByte()).also { input.readFully(it) }
    val method: Byte = if (username != null) 2 else 0
    if (method !in methods) {
      output.write(byteArrayOf(5, 0xff.toByte()))
      return
    }
    output.write(byteArrayOf(5, method))
    if (method == 2.toByte()) {
      input.readByte()
      val user = String(ByteArray(input.readUnsignedByte()).also { input.readFully(it) })
      val pass = String(ByteArray(input.readUnsignedByte()).also { input.readFully(it) })
      val accepted = user == username && pass == password
      output.write(byteArrayOf(1, if (accepted) 0 else 1))
      if (!accepted) return
    }
    check(input.readByte() == 5.toByte() && input.readByte() == 1.toByte())
    input.readByte()
    val destination = when (input.readByte().toInt()) {
      1 -> InetAddress.getByAddress(ByteArray(4).also { input.readFully(it) }).hostAddress
      3 -> "domain:" + String(ByteArray(input.readUnsignedByte()).also { input.readFully(it) })
      else -> InetAddress.getByAddress(ByteArray(16).also { input.readFully(it) }).hostAddress
    }
    val port = input.readUnsignedShort()
    destinations.add("$destination:$port")
    connect(port).use { upstream ->
      output.write(byteArrayOf(5, 0, 0, 1, 127, 0, 0, 1, 0, 0))
      output.flush()
      relay(client, upstream, input)
    }
  }

  private operator fun ByteArray.contains(byte: Byte): Boolean = any { it == byte }
}
