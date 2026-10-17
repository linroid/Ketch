package com.linroid.ketch.torrent.engine

import com.linroid.ketch.torrent.net.PeerEndpoint
import kotlin.test.Test
import kotlin.test.assertEquals

class TorrentAdvertisedPortTest {
  @Test
  fun localPeers_reachTheListenPort_othersTheMappedOne() {
    val port = TorrentAdvertisedPort()
    port.setListen(40_000)
    port.setMapped(60_000)
    // On this machine or its networks, a peer dials our own address, where we listen.
    for (host in listOf("127.0.0.1", "192.168.31.11", "10.1.2.3", "::1", "fe80::1", "fd00::5")) {
      assertEquals(40_000, port.portFor(PeerEndpoint(host, 6881)), host)
    }
    // Others reach the router, which forwards the mapped port.
    assertEquals(60_000, port.portFor(PeerEndpoint("203.0.113.5", 6881)))
    assertEquals(60_000, port.portFor(PeerEndpoint("2001:db8::1", 6881)))
    port.setMapped(null)
    assertEquals(40_000, port.portFor(PeerEndpoint("203.0.113.5", 6881)))
  }
}
