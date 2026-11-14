package com.linroid.ketch.torrent.net

import com.linroid.ketch.api.ProxyAddress
import kotlinx.coroutines.awaitCancellation
import okio.IOException
import kotlin.io.encoding.Base64

/**
 * A network that reaches peers only through [proxy], so no peer, tracker or router learns this
 * machine's address from it: TCP connections are tunneled (SOCKS5 `CONNECT`, RFC 1928 with
 * RFC 1929 passwords, or HTTP `CONNECT`), with host names left to the proxy to resolve. What a
 * proxy cannot carry goes nowhere rather than around it: UDP sockets ([bindUdp]) send nothing
 * and receive nothing, which silences DHT, uTP, UDP trackers and port mapping, and the peer
 * listener ([listen]) is bound to loopback, as peers could only reach it at the address the
 * proxy hides.
 */
internal class ProxiedTorrentNetwork(
  private val delegate: TorrentNetwork,
  private val proxy: ProxyAddress,
) : TorrentNetwork {
  override suspend fun connect(remote: PeerEndpoint): TorrentConnection {
    val tunnel = delegate.connect(PeerEndpoint(proxy.host, proxy.port))
    try {
      when (proxy.type) {
        ProxyAddress.Type.SOCKS5 -> socks5(tunnel, remote)
        ProxyAddress.Type.HTTP -> httpConnect(tunnel, remote)
      }
    } catch (e: Throwable) {
      tunnel.close()
      throw e
    }
    return Tunneled(tunnel, remote)
  }

  override suspend fun listen(local: PeerEndpoint): TorrentListener =
    delegate.listen(PeerEndpoint(if (':' in local.host) "::1" else "127.0.0.1", local.port))

  override suspend fun bindUdp(local: PeerEndpoint): TorrentDatagramSocket = Silent(local)

  override fun close() = delegate.close()

  private suspend fun socks5(tunnel: TorrentConnection, remote: PeerEndpoint) {
    val username = proxy.username
    val methods = if (username != null) byteArrayOf(NO_AUTH, PASSWORD) else byteArrayOf(NO_AUTH)
    tunnel.write(byteArrayOf(VERSION, methods.size.toByte()) + methods)
    val chosen = tunnel.readExactly(2)
    if (chosen[0] != VERSION) throw IOException("The proxy does not speak SOCKS5")
    when (chosen[1]) {
      NO_AUTH -> Unit
      PASSWORD -> {
        val name = username?.encodeToByteArray()
        val secret = proxy.password.orEmpty().encodeToByteArray()
        if (name == null || name.size !in 1..255 || secret.size > 255) {
          throw IOException("The proxy asks for a user name and password it can take")
        }
        tunnel.write(byteArrayOf(1, name.size.toByte()) + name +
          byteArrayOf(secret.size.toByte()) + secret)
        if (tunnel.readExactly(2)[1] != 0.toByte()) {
          throw IOException("The proxy refused the user name or password")
        }
      }
      else -> throw IOException("The proxy accepts no sign-in this client offers")
    }
    val address = numericAddress(remote.host)
    val target = when {
      address != null && address.size == 4 -> byteArrayOf(IPV4) + address
      address != null -> byteArrayOf(IPV6) + address
      else -> {
        val name = remote.host.encodeToByteArray()
        if (name.size > 255) throw IOException("The host name is too long for the proxy")
        byteArrayOf(DOMAIN, name.size.toByte()) + name
      }
    }
    tunnel.write(byteArrayOf(VERSION, CONNECT, 0) + target +
      byteArrayOf((remote.port shr 8).toByte(), remote.port.toByte()))
    val reply = tunnel.readExactly(4)
    if (reply[0] != VERSION || reply[1] != 0.toByte()) {
      throw IOException("The proxy could not connect (SOCKS5 reply ${reply[1].toInt() and 0xff})")
    }
    // The address the proxy bound, which nothing here uses.
    val bound = when (reply[3]) {
      IPV4 -> 4
      IPV6 -> 16
      DOMAIN -> tunnel.readExactly(1)[0].toInt() and 0xff
      else -> throw IOException("The proxy sent an address of an unknown kind")
    }
    tunnel.readExactly(bound + 2)
  }

  private suspend fun httpConnect(tunnel: TorrentConnection, remote: PeerEndpoint) {
    val host = if (':' in remote.host) "[${remote.host}]" else remote.host
    val request = StringBuilder("CONNECT $host:${remote.port} HTTP/1.1\r\n")
      .append("Host: $host:${remote.port}\r\n")
    proxy.username?.let { name ->
      val credentials = "$name:${proxy.password.orEmpty()}".encodeToByteArray()
      request.append("Proxy-Authorization: Basic ${Base64.Default.encode(credentials)}\r\n")
    }
    tunnel.write(request.append("\r\n").toString().encodeToByteArray())
    // One byte at a time: what follows the blank line already belongs to the peer.
    val head = StringBuilder()
    while (!head.endsWith("\r\n\r\n")) {
      if (head.length >= MAX_HTTP_HEAD) throw IOException("The proxy's answer is too long")
      head.append((tunnel.readExactly(1)[0].toInt() and 0xff).toChar())
    }
    val status = head.lineSequence().first().split(' ').getOrNull(1)?.toIntOrNull()
    if (status == null || status !in 200..299) {
      throw IOException("The proxy could not connect (HTTP ${status ?: "answer unreadable"})")
    }
  }

  /** The tunnel [connection], named after the peer it reaches. */
  private class Tunneled(
    private val connection: TorrentConnection,
    override val remote: PeerEndpoint,
  ) : TorrentConnection by connection

  /** A UDP socket that sends nothing and hears nothing. */
  private class Silent(override val local: PeerEndpoint) : TorrentDatagramSocket {
    override suspend fun send(remote: PeerEndpoint, bytes: ByteArray) = Unit

    override suspend fun receive(): TorrentDatagram = awaitCancellation()

    override fun close() = Unit
  }

  private companion object {
    const val VERSION: Byte = 5
    const val NO_AUTH: Byte = 0
    const val PASSWORD: Byte = 2
    const val CONNECT: Byte = 1
    const val IPV4: Byte = 1
    const val DOMAIN: Byte = 3
    const val IPV6: Byte = 4
    const val MAX_HTTP_HEAD = 8192
  }
}
