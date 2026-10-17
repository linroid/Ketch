package com.linroid.ketch.torrent.lsd

import com.linroid.ketch.torrent.TorrentDispatchers
import com.linroid.ketch.torrent.net.PeerEndpoint
import com.linroid.ketch.torrent.net.TorrentDatagram
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.net.DatagramPacket
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.MulticastSocket
import java.net.NetworkInterface
import java.net.SocketTimeoutException
import java.net.StandardProtocolFamily
import java.net.StandardSocketOptions
import java.nio.ByteBuffer
import java.nio.channels.DatagramChannel

internal actual suspend fun openLsdMulticast(): LsdTransport? =
  withContext(TorrentDispatchers.lookup) {
    val ipv4 = lsdInterfaces()
    val ipv6 = lsdIpv6Interfaces()
    if (ipv4.isEmpty() && ipv6.isEmpty()) null else JvmLsdTransport.open(ipv4, ipv6, LSD_PORT)
  }

/**
 * What identifies the interfaces offered for noticing a network change: names with their IPv4
 * addresses, and their link-local and unique local IPv6 ones (never global IPv6 addresses, which
 * privacy extensions rotate).
 */
private fun signature(ipv4: List<NetworkInterface>, ipv6: List<NetworkInterface>): Set<String> =
  ipv4.flatMapTo(mutableSetOf()) { nif ->
    nif.inetAddresses.toList().filterIsInstance<Inet4Address>()
      .map { "${nif.name} ${it.hostAddress}" }
  } + ipv6.flatMap { nif -> lanIpv6Addresses(nif).map { "${nif.name} ${it.hostAddress}" } }

/**
 * Whether [nif] is up, with multicast, and neither loopback nor a point-to-point link: a VPN
 * tunnel has no LAN behind it, though it may have a private IPv4 or a link-local IPv6 address,
 * and peers heard through it carry the tunnel's address, which its proxy resets.
 */
private fun lanMulticast(nif: NetworkInterface): Boolean = runCatching {
  nif.isUp && !nif.isLoopback && !nif.isPointToPoint && nif.supportsMulticast()
}.getOrDefault(false)

/** The LAN interfaces ([lanMulticast]) with a private or link-local IPv4 address. */
internal fun lsdInterfaces(): List<NetworkInterface> =
  NetworkInterface.getNetworkInterfaces()?.toList().orEmpty().filter { nif ->
    lanMulticast(nif) &&
      nif.inetAddresses.toList().any { it is Inet4Address && lsdSenderAllowed(it.address) }
  }

/** The LAN interfaces ([lanMulticast]) with a link-local or unique local IPv6 address. */
internal fun lsdIpv6Interfaces(): List<NetworkInterface> =
  NetworkInterface.getNetworkInterfaces()?.toList().orEmpty().filter { nif ->
    lanMulticast(nif) && lanIpv6Addresses(nif).isNotEmpty()
  }

private fun lanIpv6Addresses(nif: NetworkInterface): List<Inet6Address> =
  nif.inetAddresses.toList().filterIsInstance<Inet6Address>()
    .filter { lsdSenderAllowed(it.address) }

/**
 * A multicast socket on [port], shared with other clients (SO_REUSEADDR), that joined the IPv4
 * group, the IPv6 group or both on the interfaces of each [memberships] entry, and sends there on
 * each in turn. Receives wake every [POLL_MS] to notice a cancelled caller, as closing the socket
 * from another thread is the only other way to stop them on Android.
 */
internal class JvmLsdTransport private constructor(
  private val socket: MulticastSocket,
  /**
   * Sends the IPv4 announces, while [socket] sends the IPv6 ones. [socket] is an IPv6 socket, and
   * on macOS its interface option does not steer IPv4 multicast: every IPv4 announce left on the
   * interface the routes choose, a VPN's tunnel when one takes them. An IPv4 socket's does.
   */
  private val ipv4Sender: DatagramChannel?,
  private val memberships: List<Membership>,
  /** The signature of the interfaces offered when it opened, joined or not. */
  private val offered: Set<String>,
) : LsdTransport {
  /** One family's group, its announces' `Host` header, and the interfaces that joined it. */
  private class Membership(
    val group: InetSocketAddress,
    val host: String,
    val interfaces: List<NetworkInterface>,
  ) {
    /**
     * Where to send on [nif]: the IPv6 group scoped to it, as macOS finds no route to a
     * site-local group without a zone even with the multicast interface set.
     */
    fun destination(nif: NetworkInterface): InetSocketAddress {
      val address = group.address as? Inet6Address ?: return group
      return InetSocketAddress(Inet6Address.getByAddress(null, address.address, nif), group.port)
    }
  }

  private val buffer = ByteArray(LsdAnnounce.MAX_BYTES + 1)

  // Not on the receiving slice, which a quiet LAN's receive keeps between its timeouts.
  override suspend fun send(announce: LsdAnnounce) = withContext(TorrentDispatchers.disk) {
    var sent = 0
    var failure: Exception? = null
    // The interface option and the send go together.
    synchronized(socket) {
      for (membership in memberships) {
        val bytes = announce.encode(membership.host)
        for (nif in membership.interfaces) {
          try {
            sendOn(nif, bytes, membership.destination(nif))
            sent++
          } catch (e: Exception) {
            failure = e
          }
        }
      }
    }
    if (sent == 0) failure?.let { throw it }
  }

  /** Sends [bytes] to [destination] out of [nif]. */
  private fun sendOn(nif: NetworkInterface, bytes: ByteArray, destination: InetSocketAddress) {
    val channel = ipv4Sender?.takeIf { destination.address is Inet4Address }
    if (channel != null) {
      channel.setOption(StandardSocketOptions.IP_MULTICAST_IF, nif)
      channel.send(ByteBuffer.wrap(bytes), destination)
    } else {
      socket.networkInterface = nif
      socket.send(DatagramPacket(bytes, bytes.size, destination))
    }
  }

  override suspend fun receive(): TorrentDatagram = withContext(TorrentDispatchers.multicast) {
    var datagram: TorrentDatagram? = null
    while (datagram == null) {
      ensureActive()
      val packet = DatagramPacket(buffer, buffer.size)
      try {
        socket.receive(packet)
      } catch (_: SocketTimeoutException) {
        continue
      }
      // The zone of a link-local IPv6 sender goes: such senders are never dialed.
      val host = packet.address?.hostAddress?.substringBefore('%') ?: continue
      datagram = TorrentDatagram(PeerEndpoint(host, packet.port),
        packet.data.copyOfRange(packet.offset, packet.offset + packet.length))
    }
    datagram
  }

  override suspend fun isCurrent(): Boolean = withContext(TorrentDispatchers.lookup) {
    signature(lsdInterfaces(), lsdIpv6Interfaces()) == offered
  }

  override fun close() {
    ipv4Sender?.close()
    socket.close()
  }

  companion object {
    private const val POLL_MS = 500

    /**
     * Joins the IPv4 group on [ipv4] and the IPv6 group on [ipv6], on every interface that lets
     * it, or throws when none does.
     */
    fun open(
      ipv4: List<NetworkInterface>,
      ipv6: List<NetworkInterface>,
      port: Int,
    ): JvmLsdTransport {
      val socket = MulticastSocket(null)
      var ipv4Sender: DatagramChannel? = null
      try {
        socket.reuseAddress = true
        socket.bind(InetSocketAddress(port))
        socket.soTimeout = POLL_MS
        val memberships = listOf(
          Triple(LSD_GROUP_IPV4, LSD_HOST_IPV4, ipv4),
          Triple(LSD_GROUP_IPV6, LSD_HOST_IPV6, ipv6),
        ).mapNotNull { (address, host, interfaces) ->
          val group = InetSocketAddress(InetAddress.getByName(address), port)
          // An IPv4-only stack (java.net.preferIPv4Stack) refuses the IPv6 group.
          val joined = interfaces.filter { nif ->
            runCatching { socket.joinGroup(group, nif) }.isSuccess
          }
          if (joined.isEmpty()) null else Membership(group, host, joined)
        }
        check(memberships.isNotEmpty()) {
          "No interface joined a local service discovery group"
        }
        if (memberships.any { it.group.address is Inet4Address }) {
          ipv4Sender = DatagramChannel.open(StandardProtocolFamily.INET)
          // Looped back, so that clients on this device hear it too.
          ipv4Sender.setOption(StandardSocketOptions.IP_MULTICAST_LOOP, true)
        }
        return JvmLsdTransport(socket, ipv4Sender, memberships, signature(ipv4, ipv6))
      } catch (e: Throwable) {
        ipv4Sender?.close()
        socket.close()
        throw e
      }
    }
  }
}
