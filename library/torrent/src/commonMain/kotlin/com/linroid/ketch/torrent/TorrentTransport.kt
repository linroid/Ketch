package com.linroid.ketch.torrent

import io.ktor.network.selector.SelectorManager
import io.ktor.network.sockets.Datagram
import io.ktor.network.sockets.InetSocketAddress
import io.ktor.network.sockets.Socket
import io.ktor.network.sockets.aSocket
import io.ktor.network.sockets.openReadChannel
import io.ktor.network.sockets.openWriteChannel
import io.ktor.utils.io.readFully
import io.ktor.utils.io.writeFully
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.withTimeout
import kotlinx.io.Buffer
import kotlinx.io.readByteArray
import kotlin.concurrent.atomics.AtomicBoolean
import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.ExperimentalAtomicApi

internal expect fun createTorrentNetwork(): TorrentNetwork

internal data class PeerEndpoint(val host: String, val port: Int) {
  init {
    require(host.isNotBlank() && host.length <= 253 && port in 0..65535)
  }
}

internal interface TorrentConnection {
  val remote: PeerEndpoint
  suspend fun readExactly(size: Int): ByteArray
  suspend fun write(bytes: ByteArray)
  fun close()
}

internal interface TorrentListener {
  val local: PeerEndpoint
  suspend fun accept(): TorrentConnection
  fun close()
}

internal data class TorrentDatagram(val remote: PeerEndpoint, val bytes: ByteArray)

internal interface TorrentDatagramSocket {
  val local: PeerEndpoint
  suspend fun send(remote: PeerEndpoint, bytes: ByteArray)
  suspend fun receive(): TorrentDatagram
  fun close()
}

internal interface TorrentNetwork {
  suspend fun connect(remote: PeerEndpoint): TorrentConnection
  suspend fun listen(local: PeerEndpoint): TorrentListener
  suspend fun bindUdp(local: PeerEndpoint): TorrentDatagramSocket
  fun close()
}

/** Owns live sockets only; closing a connection removes its runtime reference immediately. */
@OptIn(ExperimentalAtomicApi::class)
internal class TorrentResources {
  private val resources = AtomicReference<List<Lease>?>(emptyList())

  inner class Lease(private val action: () -> Unit) {
    private val closed = AtomicBoolean(false)
    fun close() {
      if (!closed.compareAndSet(false, true)) return
      try {
        action()
      } finally {
        while (true) {
          val current = resources.load() ?: break
          if (resources.compareAndSet(current, current.filterNot { it === this })) break
        }
      }
    }
  }

  fun register(action: () -> Unit): Lease {
    val lease = Lease(action)
    while (true) {
      val current = resources.load()
      if (current == null) {
        lease.close()
        error("Torrent runtime is closed")
      }
      if (resources.compareAndSet(current, current + lease)) return lease
    }
  }

  val activeCount: Int get() = resources.load()?.size ?: 0

  fun close() {
    resources.exchange(null)?.forEach { lease -> runCatching { lease.close() } }
  }
}

/** TCP/UDP adapter. Higher layers own protocol deadlines and datagram validation. */
internal class KtorTorrentNetwork(private val connectTimeoutMs: Long = 10_000) : TorrentNetwork {
  private val selector = SelectorManager(Dispatchers.IO)
  private val resources = TorrentResources()

  override suspend fun connect(remote: PeerEndpoint): TorrentConnection {
    require(remote.port != 0)
    var connection: TorrentConnection? = null
    try {
      withTimeout(connectTimeoutMs) {
        connection = wrap(aSocket(selector).tcp().connect(remote.host, remote.port))
      }
      return checkNotNull(connection)
    } catch (e: Throwable) {
      connection?.close()
      throw e
    }
  }

  override suspend fun listen(local: PeerEndpoint): TorrentListener {
    val socket = aSocket(selector).tcp().bind(local.host, local.port)
    val lease = resources.register { socket.close() }
    return object : TorrentListener {
      override val local = endpoint(socket.localAddress as InetSocketAddress)
      override suspend fun accept(): TorrentConnection = wrap(socket.accept())
      override fun close() = lease.close()
    }
  }

  override suspend fun bindUdp(local: PeerEndpoint): TorrentDatagramSocket {
    val socket = aSocket(selector).udp().bind(local.host, local.port)
    val lease = resources.register { socket.close() }
    val bound = endpoint(socket.localAddress as InetSocketAddress)
    // JVM dual-stack UDP may report :: even when the requested wildcard is IPv4.
    val logicalLocal = if (local.host == "0.0.0.0") bound.copy(host = "0.0.0.0") else bound
    return object : TorrentDatagramSocket {
      override val local = logicalLocal
      override suspend fun send(remote: PeerEndpoint, bytes: ByteArray) {
        require(bytes.size <= 65507 && remote.port != 0)
        val packet = Buffer().apply { write(bytes) }
        socket.send(Datagram(packet, InetSocketAddress(remote.host, remote.port)))
      }
      override suspend fun receive(): TorrentDatagram {
        val datagram = socket.receive()
        return datagram.packet.use {
          TorrentDatagram(endpoint(datagram.address as InetSocketAddress), it.readByteArray())
        }
      }
      override fun close() = lease.close()
    }
  }

  private fun wrap(socket: Socket): TorrentConnection {
    val lease = resources.register { socket.close() }
    try {
      val input = socket.openReadChannel()
      val output = socket.openWriteChannel(autoFlush = true)
      return object : TorrentConnection {
        override val remote = endpoint(socket.remoteAddress as InetSocketAddress)
        override suspend fun readExactly(size: Int): ByteArray {
          require(size in 0..16 * 1024 * 1024)
          return ByteArray(size).also { input.readFully(it) }
        }
        override suspend fun write(bytes: ByteArray) {
          output.writeFully(bytes)
        }
        override fun close() = lease.close()
      }
    } catch (e: Throwable) {
      lease.close()
      throw e
    }
  }

  override fun close() {
    resources.close()
    selector.close()
  }

  private fun endpoint(address: InetSocketAddress): PeerEndpoint {
    val bytes = checkNotNull(address.resolveAddress()) { "Unresolved socket address" }
    return PeerEndpoint(numericHost(bytes), address.port)
  }
}

/** Keeps address families intact; reverse DNS may turn ::1 into IPv4 localhost. */
internal fun numericHost(bytes: ByteArray): String {
  require(bytes.size == 4 || bytes.size == 16)
  return if (bytes.size == 4) {
    bytes.joinToString(".") { (it.toInt() and 255).toString() }
  } else {
    (0 until 8).joinToString(":") { index ->
      (((bytes[index * 2].toInt() and 255) shl 8) or
        (bytes[index * 2 + 1].toInt() and 255)).toString(16)
    }
  }
}
