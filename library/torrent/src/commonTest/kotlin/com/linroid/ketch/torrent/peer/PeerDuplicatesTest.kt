package com.linroid.ketch.torrent.peer

import com.linroid.ketch.torrent.engine.TorrentAdvertisedPort
import com.linroid.ketch.torrent.net.PeerEndpoint
import okio.ByteString.Companion.toByteString
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

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

  @Test
  fun keepsOutgoing_bothEndsAgreeWhileARouterMapsAnotherPort() {
    // D listens on 40000 and its router forwards 60000 to it; S listens on 50000, unmapped.
    val d = TorrentAdvertisedPort().apply {
      setListen(40_000)
      setMapped(60_000)
    }
    val s = TorrentAdvertisedPort().apply { setListen(50_000) }
    val dId = ByteArray(20) { 1 }.toByteString()
    val sId = ByteArray(20) { 2 }.toByteString()
    // Each dials the other at a local address, as a magnet's x.pe and local discovery name them.
    val dDials = PeerEndpoint("127.0.0.1", 50_000)
    val sDials = PeerEndpoint("192.168.31.11", 40_000)
    // Both keep D's dial, as D listens on the lower port: one end keeps its dial, the other not.
    assertTrue(keepsOutgoing(d.portFor(dDials), dDials, dId, sId))
    assertFalse(keepsOutgoing(s.portFor(sDials), sDials, sId, dId))
    // Over the internet, S dials the port D's router forwards, and both keep S's dial.
    val dDialsPublic = PeerEndpoint("198.51.100.7", 50_000)
    val sDialsPublic = PeerEndpoint("203.0.113.5", 60_000)
    assertFalse(keepsOutgoing(d.portFor(dDialsPublic), dDialsPublic, dId, sId))
    assertTrue(keepsOutgoing(s.portFor(sDialsPublic), sDialsPublic, sId, dId))
  }
}
