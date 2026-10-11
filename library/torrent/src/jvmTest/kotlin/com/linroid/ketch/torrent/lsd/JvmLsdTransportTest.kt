package com.linroid.ketch.torrent.lsd

import com.linroid.ketch.torrent.net.TorrentDatagram
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import okio.ByteString.Companion.toByteString
import java.net.DatagramSocket
import java.net.NetworkInterface
import kotlin.test.Test
import kotlin.test.assertEquals
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

  private fun skip(reason: String) {
    println("JvmLsdTransportTest skipped: $reason")
  }
}
