package com.linroid.ketch.torrent

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PeerExchangeTest {
  @Test
  fun updates_onlyAdvertiseConnectedPeersAndSendDropsAfterInterval() {
    var now = 0L
    val sender = PeerExchange { now }
    val receiver = PeerExchange { now }
    val first = PeerEndpoint("1.2.3.4", 6881)
    val second = PeerEndpoint("2001:4860:0:0:0:0:0:1", 6881)
    val message = sender.message(7, setOf(first, second))!!
    assertEquals(7, message.id)
    assertEquals(setOf(first, second), receiver.receive(message.payload).added.toSet())
    assertNull(sender.message(7, emptySet()))
    assertFailsWith<IllegalArgumentException> { receiver.receive(message.payload) }
    now = 60_000
    val dropped = receiver.receive(sender.message(7, emptySet())!!.payload)
    assertEquals(setOf(first, second), dropped.dropped.toSet())
    assertTrue(dropped.added.isEmpty())
  }

  @Test
  fun malformedFlagsAndOversizedUpdatesAreRejected() {
    val bytes = DhtCodec.compactEndpoint(PeerEndpoint("1.2.3.4", 6881))
    assertFailsWith<IllegalArgumentException> {
      PeerExchange().receive(Bencode.encode(mapOf("added" to bytes, "added.f" to ByteArray(0))))
    }
    assertFailsWith<IllegalArgumentException> {
      PeerExchange().receive(Bencode.encode(mapOf("added" to bytes, "dropped" to bytes)))
    }
  }

  @Test
  fun privateDirectory_ignoresPublicSourcesAndClearsPreviousTracker() {
    val peers = TorrentPeerDirectory(privateTorrent = true)
    val first = PeerEndpoint("1.2.3.4", 6881)
    val second = PeerEndpoint("5.6.7.8", 6881)
    assertFalse(peers.update(PeerOrigin.DHT, "dht", listOf(first)))
    assertTrue(peers.candidates().isEmpty())
    peers.update(PeerOrigin.TRACKER, "a", listOf(first))
    assertFalse(peers.update(PeerOrigin.PEX, "peer", listOf(second)))
    assertEquals(listOf(first), peers.candidates())
    assertTrue(peers.update(PeerOrigin.TRACKER, "b", listOf(second)))
    assertEquals(listOf(second), peers.candidates())
  }

  @Test
  fun droppingPexProvenance_preservesOtherSources() {
    val peers = TorrentPeerDirectory(privateTorrent = false)
    val endpoint = PeerEndpoint("1.2.3.4", 6881)
    peers.update(PeerOrigin.PEX, "peer", listOf(endpoint))
    peers.update(PeerOrigin.TRACKER, "tracker", listOf(endpoint))
    peers.update(PeerOrigin.PEX, "peer", emptyList(), listOf(endpoint))
    assertEquals(listOf(endpoint), peers.candidates())
  }

  @Test
  fun privateTracker_doesNotSwitchBackWhileCurrentTrackerWorks() = runTest {
    var firstOnline = true
    val calls = mutableListOf<String>()
    val tiers = TrackerTiers(listOf(listOf("a"), listOf("b"))) { url, _, _ ->
      calls += url
      if (url == "a" && !firstOnline) error("offline")
      TrackerResponse(emptyList(), 1)
    }
    tiers.preferCurrentTracker()
    val request = TrackerAnnounce(InfoHash.fromBytes(ByteArray(20)), ByteArray(20), 6881, 0, 1)
    tiers.announce(request)
    firstOnline = false
    tiers.announce(request)
    firstOnline = true
    tiers.announce(request)
    assertEquals(listOf("a", "a", "b", "b"), calls)
  }
}
