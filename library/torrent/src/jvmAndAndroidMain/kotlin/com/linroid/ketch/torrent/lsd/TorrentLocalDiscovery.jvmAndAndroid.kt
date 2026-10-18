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
  } + ipv6.flatMap { nif ->
    lanIpv6Addresses(nif.inetAddresses.toList()).map { "${nif.name} ${it.hostAddress}" }
  }

/**
 * What local service discovery reads of a network interface to choose it: the choice
 * ([joinsIpv4Group], [joinsIpv6Group]) depends on these facts alone.
 */
internal class LsdInterfaceFacts(
  val up: Boolean,
  val loopback: Boolean,
  val pointToPoint: Boolean,
  val multicast: Boolean,
  val addresses: List<InetAddress>,
) {
  /**
   * Whether the interface is up, with multicast, and neither loopback nor a point-to-point link:
   * a VPN tunnel has no LAN behind it, though it may have a private IPv4 or a link-local IPv6
   * address, and peers heard through it carry the tunnel's address, which its proxy resets.
   */
  private val lanMulticast: Boolean get() = up && !loopback && !pointToPoint && multicast

  /** Whether the IPv4 group is joined here: a LAN one with a private or link-local address. */
  val joinsIpv4Group: Boolean
    get() = lanMulticast && addresses.any { it is Inet4Address && lsdSenderAllowed(it.address) }

  /** Whether the IPv6 group is joined here: a LAN one with a link-local or unique local address. */
  val joinsIpv6Group: Boolean get() = lanMulticast && lanIpv6Addresses(addresses).isNotEmpty()
}

/** The interfaces that join the IPv4 group ([LsdInterfaceFacts.joinsIpv4Group]). */
internal fun lsdInterfaces(): List<NetworkInterface> = systemInterfaces { it.joinsIpv4Group }

/** The interfaces that join the IPv6 group ([LsdInterfaceFacts.joinsIpv6Group]). */
internal fun lsdIpv6Interfaces(): List<NetworkInterface> = systemInterfaces { it.joinsIpv6Group }

/** The system's interfaces whose facts [chosen] accepts; none whose facts cannot be read. */
private fun systemInterfaces(chosen: (LsdInterfaceFacts) -> Boolean): List<NetworkInterface> =
  NetworkInterface.getNetworkInterfaces()?.toList().orEmpty().filter { nif ->
    runCatching {
      LsdInterfaceFacts(up = nif.isUp, loopback = nif.isLoopback,
        pointToPoint = nif.isPointToPoint, multicast = nif.supportsMulticast(),
        addresses = nif.inetAddresses.toList())
    }.getOrNull()?.let(chosen) == true
  }

private fun lanIpv6Addresses(addresses: List<InetAddress>): List<Inet6Address> =
  addresses.filterIsInstance<Inet6Address>().filter { lsdSenderAllowed(it.address) }

/**
 * A multicast socket on [port], shared with other clients (SO_REUSEADDR), that joined the IPv4
 * group, the IPv6 group or both on the interfaces of each [memberships] entry, none of them a
 * point-to-point link ([LsdInterfaceFacts]). It receives every announce and sends the IPv6 ones;
 * the IPv4 ones leave through [ipv4Sender], a separate IPv4 socket on an ephemeral port. Each
 * announce goes out of every joined interface in turn. Receives wake every [POLL_MS] to notice a
 * cancelled caller, as closing the socket from another thread is the only other way to stop them
 * on Android.
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
