package com.linroid.ketch.torrent.engine

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TorrentHandshakeGateTest {
  @Test
  fun enter_onePeerOpeningMany_isRefusedPastItsShare() {
    val gate = TorrentHandshakeGate({ 64 }, perHost = 4)

    repeat(4) { assertTrue(gate.enter("203.0.113.7")) }

    assertFalse(gate.enter("203.0.113.7"))
    assertTrue(gate.enter("203.0.113.8"))
    gate.leave("203.0.113.7")
    assertTrue(gate.enter("203.0.113.7"))
  }

  @Test
  fun enter_manyPeers_isRefusedPastTheLimit() {
    var limit = 3
    val gate = TorrentHandshakeGate({ limit })

    repeat(3) { assertTrue(gate.enter("198.51.100.$it")) }

    assertFalse(gate.enter("198.51.100.9"))
    limit = 4
    assertTrue(gate.enter("198.51.100.9"))
  }

  @Test
  fun enter_ipv6_countsAWhole64AsOneHost() {
    val gate = TorrentHandshakeGate({ 64 }, perHost = 2)

    assertTrue(gate.enter("2001:db8:1:2::1"))
    assertTrue(gate.enter("2001:db8:1:2:ffff::9"))

    assertFalse(gate.enter("2001:0db8:0001:0002:0:0:0:abcd"))
    assertTrue(gate.enter("2001:db8:1:3::1"))
  }

  @Test
  fun enter_loopback_onlyCountsAgainstTheLimit() {
    val gate = TorrentHandshakeGate({ 6 }, perHost = 1)

    repeat(3) { assertTrue(gate.enter("127.0.0.1")) }
    repeat(3) { assertTrue(gate.enter("::1")) }

    assertFalse(gate.enter("127.0.0.1"))
  }

  @Test
  fun handshakeHostKey_namesTheHostItCounts() {
    assertEquals("203.0.113.7", handshakeHostKey("203.0.113.7"))
    assertEquals("203.0.113.7", handshakeHostKey("::ffff:203.0.113.7"))
    assertEquals(handshakeHostKey("2001:db8:1:2::1"), handshakeHostKey("2001:db8:1:2::2%eth0"))
    assertNull(handshakeHostKey("127.0.0.2"))
    assertNull(handshakeHostKey("::1"))
    assertEquals("peer.example", handshakeHostKey("peer.example"))
  }
}
