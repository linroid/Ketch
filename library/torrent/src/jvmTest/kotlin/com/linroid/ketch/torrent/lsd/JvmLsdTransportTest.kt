package com.linroid.ketch.torrent.lsd

import com.linroid.ketch.torrent.net.TorrentDatagram
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import okio.ByteString.Companion.toByteString
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.StandardProtocolFamily
import java.net.StandardSocketOptions
import java.nio.ByteBuffer
import java.nio.channels.DatagramChannel
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Two transports sharing one port on this machine's LAN interfaces hear each other's announces,
 * as two clients on one device do, in each family. Skipped where the system refuses multicast.
 */
class JvmLsdTransportTest {
  @Test
  fun ipv4_twoTransportsOnOnePort_hearEachOther() = runBlocking {
    val interfaces = lsdInterfaces()
    if (interfaces.isEmpty()) return@runBlocking skip("no private IPv4 interface")
    val heard = exchange(interfaces, emptyList()) ?: return@runBlocking
    assertTrue(heard.bytes.decodeToString().contains("\r\nHost: $LSD_HOST_IPV4\r\n"))
  }

  @Test
  fun ipv6_twoTransportsOnOnePort_hearEachOther() = runBlocking {
    val interfaces = lsdIpv6Interfaces()
    if (interfaces.isEmpty()) return@runBlocking skip("no link-local or unique local interface")
    val heard = exchange(emptyList(), interfaces) ?: return@runBlocking
    assertTrue(':' in heard.remote.host, heard.remote.host)
    assertTrue('%' !in heard.remote.host, heard.remote.host)
    assertTrue(heard.bytes.decodeToString().contains("\r\nHost: $LSD_HOST_IPV6\r\n"))
  }

  @Test
  fun interfaceFacts_tunnelBesideTheLan_onlyTheLanJoins() {
    // A TUN proxy's utun beside the Wi-Fi it runs over: the tunnel has a private IPv4 address,
    // but peers heard through it carry the tunnel's own address, which its proxy resets.
    fun facts(pointToPoint: Boolean, vararg addresses: String) = LsdInterfaceFacts(up = true,
      loopback = false, pointToPoint = pointToPoint, multicast = true,
      addresses = addresses.map { InetAddress.getByName(it) })
    val en0 = facts(pointToPoint = false, "192.168.31.11", "fe80::1c2b:3aff:fe4d:5e6f")
    val utun4 = facts(pointToPoint = true, "172.19.0.1", "fe80::a1b2:c3d4:e5f6:1")
    assertTrue(en0.joinsIpv4Group)
    assertTrue(en0.joinsIpv6Group)
    assertFalse(utun4.joinsIpv4Group)
    assertFalse(utun4.joinsIpv6Group)
    // Its addresses alone would have it join.
    val lan = facts(pointToPoint = false, "172.19.0.1", "fe80::a1b2:c3d4:e5f6:1")
    assertTrue(lan.joinsIpv4Group)
    assertTrue(lan.joinsIpv6Group)
  }

  @Test
  fun ipv4_sendsOutOfTheInterfaceItJoined(): Unit = runBlocking {
    // The multicast routes never choose loopback, so only a send steered there reaches it: an
    // IPv6 socket's interface option leaves IPv4 multicast on the routes' interface (macOS).
    val loopback = NetworkInterface.getNetworkInterfaces().toList()
      .firstOrNull { it.isUp && it.isLoopback }
    if (loopback == null || !loopbackMulticast(loopback)) {
      return@runBlocking skip("no IPv4 multicast on loopback")
    }
    val port = DatagramSocket(0).use { it.localPort }
    // The transport joins through its dual-stack socket, which may refuse what the probe's IPv4
    // channel took.
    val first = try {
      JvmLsdTransport.open(listOf(loopback), emptyList(), port)
    } catch (e: Exception) {
      return@runBlocking skip("the system refused to join the group on loopback: $e")
    }
    val second = try {
      JvmLsdTransport.open(listOf(loopback), emptyList(), port)
    } catch (e: Exception) {
      first.close()
      return@runBlocking skip("the system refused to join the group on loopback: $e")
    }
    try {
      val announce = LsdAnnounce(7000, listOf(ByteArray(20) { 7 }.toByteString()), "first")
      first.send(announce)
      val heard = assertNotNull(withTimeoutOrNull(5_000) { second.receive() },
        "The announce left on another interface")
      assertEquals(announce, LsdAnnounce.parse(heard.bytes))
      assertEquals("127.0.0.1", heard.remote.host)
    } finally {
      first.close()
      second.close()
    }
  }

  @Test
  fun send_doesNotWaitForAReceiveInProgress(): Unit = runBlocking {
    val ipv4 = lsdInterfaces()
    val ipv6 = lsdIpv6Interfaces()
    if (ipv4.isEmpty() && ipv6.isEmpty()) return@runBlocking skip("no LAN interface")
    val port = DatagramSocket(0).use { it.localPort }
    val transport = try {
      JvmLsdTransport.open(ipv4, ipv6, port)
    } catch (e: Exception) {
      return@runBlocking skip("the system refused to join the group: $e")
    }
    try {
      // On a group nobody else uses, the receive keeps waiting between its timeouts.
      val receiving = launch(Dispatchers.Default) { runCatching { transport.receive() } }
      delay(200)
      val announce = LsdAnnounce(7000, listOf(ByteArray(20) { 7 }.toByteString()), "first")
      val sending = async(Dispatchers.Default) { runCatching { transport.send(announce) } }
      // Awaiting the result needs no thread of the transport's: a send stuck behind the receive
      // fails the test rather than hanging it.
      val sent = withTimeoutOrNull(3_000) { sending.await() }
      receiving.cancel()
      sending.cancel()
      assertNotNull(sent, "The announce waited for the receive")
    } finally {
      transport.close()
    }
  }

  /** Sends an announce from one transport and returns what the other heard, or null if skipped. */
  private suspend fun exchange(
    ipv4: List<NetworkInterface>,
    ipv6: List<NetworkInterface>,
  ): TorrentDatagram? {
    // Not the BEP 14 port, so clients running here never see the test's announces.
    val port = DatagramSocket(0).use { it.localPort }
    val first = try {
      JvmLsdTransport.open(ipv4, ipv6, port)
    } catch (e: Exception) {
      skip("the system refused to join the group: $e")
      return null
    }
    val second = JvmLsdTransport.open(ipv4, ipv6, port)
    try {
      val announce = LsdAnnounce(7000, listOf(ByteArray(20) { 7 }.toByteString()), "first")
      try {
        first.send(announce)
      } catch (e: Exception) {
        skip("the system refused to send to the group: $e")
        return null
      }
      val heard = withTimeoutOrNull(5_000) { second.receive() }
      if (heard == null) {
        skip("the group delivered nothing")
        return null
      }
      assertEquals(announce, LsdAnnounce.parse(heard.bytes))
      return heard
    } finally {
      first.close()
      second.close()
    }
  }

  /** Whether IPv4 multicast an IPv4 socket sends out of [loopback] reaches a member there. */
  private fun loopbackMulticast(loopback: NetworkInterface): Boolean = try {
    val group = InetAddress.getByName(LSD_GROUP_IPV4)
    DatagramChannel.open(StandardProtocolFamily.INET).use { receiver ->
      receiver.bind(InetSocketAddress(0))
      receiver.join(group, loopback)
      DatagramChannel.open(StandardProtocolFamily.INET).use { sender ->
        sender.setOption(StandardSocketOptions.IP_MULTICAST_IF, loopback)
        sender.send(ByteBuffer.wrap(byteArrayOf(1)),
          InetSocketAddress(group, (receiver.localAddress as InetSocketAddress).port))
      }
      receiver.socket().soTimeout = 2_000
      receiver.socket().receive(DatagramPacket(ByteArray(16), 16))
      true
    }
  } catch (_: IOException) {
    false
  }

  private fun skip(reason: String) {
    println("JvmLsdTransportTest skipped: $reason")
  }
}
