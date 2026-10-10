package com.linroid.ketch.engine

import java.io.DataInputStream
import java.io.IOException
import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketAddress
import java.net.SocketException
import javax.net.SocketFactory

/** Creates [Socks5Socket]s for [route] over [binding]. */
internal class Socks5SocketFactory(
  private val route: ProxyRoute.Socks5,
  private val binding: SocketBinding,
) : SocketFactory() {
  override fun createSocket(): Socket = Socks5Socket(route, binding)

  override fun createSocket(host: String, port: Int): Socket =
    createSocket().apply { connect(InetSocketAddress.createUnresolved(host, port)) }

  override fun createSocket(host: InetAddress, port: Int): Socket =
    createSocket().apply { connect(InetSocketAddress(host, port)) }

  override fun createSocket(
    host: String,
    port: Int,
    localHost: InetAddress,
    localPort: Int,
  ): Socket = throw SocketException("A SOCKS5 socket cannot choose its local address")

  override fun createSocket(
    host: InetAddress,
    port: Int,
    localHost: InetAddress,
    localPort: Int,
  ): Socket = throw SocketException("A SOCKS5 socket cannot choose its local address")
}

/**
 * A socket connected to its destination through the SOCKS5 proxy of [route] (RFC 1928), with
 * username and password authentication (RFC 1929) when the route has credentials. The proxy
 * resolves destinations given by name: unresolved addresses, and those holding [UNRESOLVED]
 * with their host name. Everything after [connect] is the destination's.
 */
internal class Socks5Socket(
  private val route: ProxyRoute.Socks5,
  private val binding: SocketBinding,
) : Socket() {
  override fun connect(endpoint: SocketAddress?) {
    connect(endpoint, 0)
  }

  override fun connect(endpoint: SocketAddress?, timeout: Int) {
    val target = endpoint as? InetSocketAddress
      ?: throw IllegalArgumentException("Unsupported address: $endpoint")
    try {
      binding.prepare(this)
      val proxy = binding.lookup(route.host).firstOrNull()
        ?: throw SocketException("SOCKS5 proxy ${route.host} has no address")
      super.connect(InetSocketAddress(proxy, route.port), timeout)
      val previousTimeout = soTimeout
      // The handshake waits no longer than connecting may.
      soTimeout = timeout
      handshake(target)
      soTimeout = previousTimeout
    } catch (e: IOException) {
      close()
      throw e
    }
  }

  private fun handshake(target: InetSocketAddress) {
    val input = DataInputStream(super.getInputStream())
    val output = super.getOutputStream()
    val credentials = route.credentials
    val methods = if (credentials == null) byteArrayOf(NO_AUTH) else byteArrayOf(NO_AUTH, PASSWORD)
    output.write(byteArrayOf(VERSION, methods.size.toByte()) + methods)
    output.flush()
    expect(input.readByte() == VERSION, "SOCKS5 proxy answered with another protocol")
    when (input.readByte()) {
      NO_AUTH -> Unit
      PASSWORD -> {
        if (credentials == null) {
          throw SocketException("SOCKS5 proxy asked for credentials it was not offered")
        }
        authenticate(input, output, credentials)
      }
      else -> throw SocketException(
        if (credentials == null) {
          "SOCKS5 proxy requires a username and password"
        } else {
          "SOCKS5 proxy accepts none of the offered authentication methods"
        },
      )
    }
    output.write(byteArrayOf(VERSION, CONNECT, 0) + address(target) + port(target.port))
    output.flush()
    expect(input.readByte() == VERSION, "SOCKS5 proxy answered with another protocol")
    val reply = input.readByte().toInt()
    if (reply != 0) throw SocketException("SOCKS5 proxy refused the connection: ${reason(reply)}")
    input.readByte()
    // The address the proxy connected from, which nothing here needs.
    val boundLength = when (input.readByte()) {
      IPV4 -> 4
      IPV6 -> 16
      DOMAIN -> input.readUnsignedByte()
      else -> throw SocketException("SOCKS5 proxy sent an unknown address type")
    }
    input.readFully(ByteArray(boundLength + 2))
  }

  private fun authenticate(
    input: DataInputStream,
    output: OutputStream,
    credentials: ProxyCredentials,
  ) {
    val username = credentials.username.encodeToByteArray()
    val password = credentials.password.encodeToByteArray()
    if (username.size !in 1..255 || password.size > 255) {
      throw SocketException("SOCKS5 credentials must be 1 to 255 bytes long")
    }
    output.write(
      byteArrayOf(1, username.size.toByte()) + username + byteArrayOf(password.size.toByte()) +
        password,
    )
    output.flush()
    input.readByte()
    if (input.readByte() != 0.toByte()) {
      throw SocketException("SOCKS5 proxy refused the username or password")
    }
  }

  private fun address(target: InetSocketAddress): ByteArray {
    val resolved = target.address
    if (resolved != null && !resolved.address.contentEquals(UNRESOLVED)) {
      val type = if (resolved.address.size == 4) IPV4 else IPV6
      return byteArrayOf(type) + resolved.address
    }
    val host = target.hostString.encodeToByteArray()
    if (host.size !in 1..255) throw SocketException("Host name is too long for SOCKS5")
    return byteArrayOf(DOMAIN, host.size.toByte()) + host
  }

  private fun port(port: Int): ByteArray = byteArrayOf((port shr 8).toByte(), port.toByte())

  private fun expect(condition: Boolean, message: String) {
    if (!condition) throw SocketException(message)
  }

  private fun reason(reply: Int): String = when (reply) {
    1 -> "general failure"
    2 -> "not allowed by its rules"
    3 -> "network unreachable"
    4 -> "host unreachable"
    5 -> "connection refused"
    6 -> "TTL expired"
    7 -> "command not supported"
    8 -> "address type not supported"
    else -> "reply $reply"
  }

  companion object {
    /** The address of a destination the proxy resolves, `0.0.0.0`, which none connects to. */
    val UNRESOLVED: ByteArray = ByteArray(4)

    private const val VERSION: Byte = 5
    private const val NO_AUTH: Byte = 0
    private const val PASSWORD: Byte = 2
    private const val CONNECT: Byte = 1
    private const val IPV4: Byte = 1
    private const val DOMAIN: Byte = 3
    private const val IPV6: Byte = 4
  }
}
