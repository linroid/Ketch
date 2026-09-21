package com.linroid.ketch.engine

import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import javax.net.SocketFactory

/** Binds every socket before connecting, including sockets later wrapped for TLS. */
internal class LocalAddressSocketFactory(private val address: InetAddress) : SocketFactory() {
  override fun createSocket(): Socket = boundSocket(address, 0)

  override fun createSocket(host: String, port: Int): Socket =
    connect(InetSocketAddress(host, port), address, 0)

  override fun createSocket(host: InetAddress, port: Int): Socket =
    connect(InetSocketAddress(host, port), address, 0)

  override fun createSocket(
    host: String,
    port: Int,
    localHost: InetAddress,
    localPort: Int,
  ): Socket = connect(InetSocketAddress(host, port), localHost, localPort)

  override fun createSocket(
    host: InetAddress,
    port: Int,
    localHost: InetAddress,
    localPort: Int,
  ): Socket = connect(InetSocketAddress(host, port), localHost, localPort)

  private fun boundSocket(localHost: InetAddress, localPort: Int): Socket {
    require(localHost == address) { "Cannot override the selected local address" }
    val socket = Socket()
    try {
      socket.bind(InetSocketAddress(address, localPort))
      return socket
    } catch (e: Exception) {
      socket.close()
      throw e
    }
  }

  private fun connect(remote: InetSocketAddress, localHost: InetAddress, localPort: Int): Socket {
    val socket = boundSocket(localHost, localPort)
    try {
      socket.connect(remote)
      return socket
    } catch (e: Exception) {
      socket.close()
      throw e
    }
  }
}
