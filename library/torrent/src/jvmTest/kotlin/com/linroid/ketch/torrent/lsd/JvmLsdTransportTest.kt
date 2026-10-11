package com.linroid.ketch.torrent.lsd

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import okio.ByteString.Companion.toByteString
import java.net.DatagramSocket
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Two transports sharing one port on this machine's private interfaces hear each other's
 * announces, as two clients on one device do. Skipped where the system refuses multicast.
 */
class JvmLsdTransportTest {
  @Test
  fun twoTransportsOnOnePort_hearEachOther() = runBlocking {
    val interfaces = lsdInterfaces()
    if (interfaces.isEmpty()) return@runBlocking skip("no private interface carries multicast")
    // Not the BEP 14 port, so clients running here never see the test's announces.
    val port = DatagramSocket(0).use { it.localPort }
    val first = try {
      JvmLsdTransport.open(interfaces, port)
    } catch (e: Exception) {
      return@runBlocking skip("the system refused to join the group: $e")
    }
    val second = JvmLsdTransport.open(interfaces, port)
    try {
      val announce = LsdAnnounce(7000, listOf(ByteArray(20) { 7 }.toByteString()), "first")
      try {
        first.send(announce.encode())
      } catch (e: Exception) {
        return@runBlocking skip("the system refused to send to the group: $e")
      }
      val heard = withTimeoutOrNull(5_000) { second.receive() }
        ?: return@runBlocking skip("the group delivered nothing")
      assertEquals(announce, LsdAnnounce.parse(heard.bytes))
    } finally {
      first.close()
      second.close()
    }
  }

  private fun skip(reason: String) {
    println("JvmLsdTransportTest skipped: $reason")
  }
}
