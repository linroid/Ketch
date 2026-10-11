package com.linroid.ketch.torrent.lsd

import com.linroid.ketch.torrent.net.PeerEndpoint
import com.linroid.ketch.torrent.net.TorrentDatagram
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.net.DatagramPacket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.MulticastSocket
import java.net.NetworkInterface
import java.net.SocketTimeoutException

internal actual suspend fun openLsdMulticast(): LsdTransport? = withContext(Dispatchers.IO) {
  val interfaces = lsdInterfaces()
  if (interfaces.isEmpty()) null else JvmLsdTransport.open(interfaces, LSD_PORT)
}

/** What identifies [interfaces] for noticing a network change: names and IPv4 addresses. */
private fun signature(interfaces: List<NetworkInterface>): Set<String> =
  interfaces.flatMapTo(mutableSetOf()) { nif ->
    nif.inetAddresses.toList().filterIsInstance<Inet4Address>()
      .map { "${nif.name} ${it.hostAddress}" }
  }

/** The interfaces up with multicast and a private or link-local IPv4 address. */
internal fun lsdInterfaces(): List<NetworkInterface> =
  NetworkInterface.getNetworkInterfaces()?.toList().orEmpty().filter { nif ->
    runCatching { nif.isUp && !nif.isLoopback && nif.supportsMulticast() }.getOrDefault(false) &&
      nif.inetAddresses.toList().any { it is Inet4Address && lsdSenderAllowed(it.address) }
  }

/**
 * A multicast socket on [port], shared with other clients (SO_REUSEADDR), that joined the group
 * on [interfaces] and sends there on each in turn. Receives wake every [pollMs] to notice a
 * cancelled caller, as closing the socket from another thread is the only other way to stop them
 * on Android.
 */
internal class JvmLsdTransport private constructor(
  private val socket: MulticastSocket,
  private val interfaces: List<NetworkInterface>,
  private val group: InetSocketAddress,
  /** The signature of the interfaces offered when it opened, joined or not. */
  private val offered: Set<String>,
) : LsdTransport {
  private val buffer = ByteArray(LsdAnnounce.MAX_BYTES + 1)

  override suspend fun send(bytes: ByteArray) = withContext(Dispatchers.IO) {
    var sent = 0
    var failure: Exception? = null
    // The interface option and the send go together.
    synchronized(socket) {
      for (nif in interfaces) {
        try {
          socket.networkInterface = nif
          socket.send(DatagramPacket(bytes, bytes.size, group))
          sent++
        } catch (e: Exception) {
          failure = e
        }
      }
    }
    if (sent == 0) failure?.let { throw it }
  }

  override suspend fun receive(): TorrentDatagram = withContext(Dispatchers.IO) {
    var datagram: TorrentDatagram? = null
    while (datagram == null) {
      ensureActive()
      val packet = DatagramPacket(buffer, buffer.size)
      try {
        socket.receive(packet)
      } catch (_: SocketTimeoutException) {
        continue
      }
      val host = packet.address?.hostAddress?.substringBefore('%') ?: continue
      datagram = TorrentDatagram(PeerEndpoint(host, packet.port),
        packet.data.copyOfRange(packet.offset, packet.offset + packet.length))
    }
    datagram
  }

  override suspend fun isCurrent(): Boolean = withContext(Dispatchers.IO) {
    signature(lsdInterfaces()) == offered
  }

  override fun close() {
    socket.close()
  }

  companion object {
    private const val POLL_MS = 500

    /** Joins the group on every interface that lets it, or throws when none does. */
    fun open(
      interfaces: List<NetworkInterface>,
      port: Int,
      groupAddress: String = LSD_GROUP_IPV4,
    ): JvmLsdTransport {
      val group = InetSocketAddress(InetAddress.getByName(groupAddress), port)
      val socket = MulticastSocket(null)
      try {
        socket.reuseAddress = true
        socket.bind(InetSocketAddress(port))
        socket.soTimeout = POLL_MS
        val joined = interfaces.filter { nif ->
          runCatching { socket.joinGroup(group, nif) }.isSuccess
        }
        check(joined.isNotEmpty()) { "No interface joined the local service discovery group" }
        return JvmLsdTransport(socket, joined, group, signature(interfaces))
      } catch (e: Throwable) {
        socket.close()
        throw e
      }
    }
  }
}
