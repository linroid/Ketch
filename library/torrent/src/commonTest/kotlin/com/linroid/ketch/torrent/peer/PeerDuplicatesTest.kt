package com.linroid.ketch.torrent.peer

import okio.ByteString.Companion.toByteString
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

class PeerDuplicatesTest {
  private val id = ByteArray(20) { 7 }.toByteString()

  @Test
  fun identity_addressesOnThisMachineOrItsNetworksAreOnePeer() {
    val loopback = PeerIdentity.of("127.0.0.1", id)
    val hosts = listOf("192.168.31.11", "10.0.0.2", "172.16.5.1", "169.254.1.1", "::1",
      "fe80::1", "fd00::5", "::ffff:192.168.1.2")
    for (host in hosts) assertEquals(loopback, PeerIdentity.of(host, id), host)
    assertNotEquals(loopback, PeerIdentity.of("127.0.0.1", ByteArray(20).toByteString()))
  }

  @Test
  fun identity_publicHostsClaimingOneIdAreDifferentPeers() {
    // A peer ID is only a claim: no public host takes another's connection with it.
    val public = PeerIdentity.of("203.0.113.5", id)
    assertNotEquals(public, PeerIdentity.of("198.51.100.66", id))
    assertNotEquals(public, PeerIdentity.of("127.0.0.1", id))
    assertNotEquals(PeerIdentity.of("2001:db8::1", id), PeerIdentity.of("::1", id))
  }
}
