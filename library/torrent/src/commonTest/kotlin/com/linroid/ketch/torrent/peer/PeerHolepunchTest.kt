package com.linroid.ketch.torrent.peer

import com.linroid.ketch.torrent.net.PeerEndpoint
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNull

class PeerHolepunchTest {
  @Test
  fun ipv4Connect_encodesTheBep55Layout() {
    val message = HolepunchMessage(HolepunchType.CONNECT, PeerEndpoint("203.0.113.5", 6881))
    assertContentEquals(
      byteArrayOf(1, 0, 203.toByte(), 0, 113, 5, 0x1a, 0xe1.toByte(), 0, 0, 0, 0),
      message.encode(),
    )
    assertEquals(message, HolepunchMessage.decode(message.encode()))
  }

  @Test
  fun ipv6Error_roundTripsWithItsCode() {
    val message = HolepunchMessage(HolepunchType.ERROR, PeerEndpoint("2001:db8:0:0:0:0:0:1", 51413),
      HolepunchError.NOT_CONNECTED)
    val bytes = message.encode()
    assertEquals(1 + 1 + 16 + 2 + 4, bytes.size)
    assertEquals(message, HolepunchMessage.decode(bytes))
  }

  @Test
  fun decode_errCodeLeftOut_readsAsZero() {
    val bytes = byteArrayOf(0, 0, 198.toByte(), 51, 100, 7, 0x1a, 0xe1.toByte())
    assertEquals(HolepunchMessage(HolepunchType.RENDEZVOUS, PeerEndpoint("198.51.100.7", 6881)),
      HolepunchMessage.decode(bytes))
  }

  @Test
  fun decode_unknownOrShortMessages_areIgnored() {
    assertNull(HolepunchMessage.decode(byteArrayOf(9, 0, 1, 2, 3, 4, 0, 1)))
    assertNull(HolepunchMessage.decode(byteArrayOf(1, 7, 1, 2, 3, 4, 0, 1)))
    assertNull(HolepunchMessage.decode(byteArrayOf(1, 0, 1, 2, 3)))
    assertNull(HolepunchMessage.decode(byteArrayOf(1, 0, 1, 2, 3, 4, 0, 0)))
  }

  @Test
  fun handshake_offersHolepunchOnlyWhenAsked() {
    fun mapping(holepunch: Boolean): Map<String, Long> {
      val payload = PeerExtensions.handshake(utMetadata = false, metadataSize = null, pex = true,
        listenPort = 0, requestQueue = 16, holepunch = holepunch).payload
      val extensions = PeerExtensions().apply { receive(payload, 1024) }
      return listOf("ut_pex", HOLEPUNCH_EXTENSION).associateWith { extensions.id(it).toLong() }
    }
    assertEquals(mapOf("ut_pex" to 2L, HOLEPUNCH_EXTENSION to 3L), mapping(true))
    assertEquals(mapOf("ut_pex" to 2L, HOLEPUNCH_EXTENSION to 0L), mapping(false))
  }
}
