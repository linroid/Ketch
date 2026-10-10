package com.linroid.ketch.torrent

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
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
    val message = sender.message(7, mapOf(first to 0, second to 0))!!.also(sender::commit)
      .message
    assertEquals(7, message.id)
    assertEquals(setOf(first, second), receiver.receive(message.payload).added.toSet())
    assertNull(sender.message(7, emptyMap()))
    now = 60_000
    val dropped = receiver.receive(sender.message(7, emptyMap())!!.also(sender::commit)
      .message.payload)
    assertEquals(setOf(first, second), dropped.dropped.toSet())
    assertTrue(dropped.added.isEmpty())
  }

  @Test
  fun uncommittedMessageIsBuiltAgain() {
    var now = 0L
    val sender = PeerExchange { now }
    val first = PeerEndpoint("1.2.3.4", 6881)
    val second = PeerEndpoint("5.6.7.8", 6881)
    // Built but never handed on, as when the peer's queue is full: nothing counts as told.
    val lost = assertNotNull(sender.message(7, mapOf(first to 0)))
    assertTrue(sender.due())
    val retried = assertNotNull(sender.message(7, mapOf(first to 0, second to 0x10)))
    assertEquals(listOf(first, second), PeerExchange().receive(retried.message.payload).added)
    assertEquals(listOf(first), PeerExchange().receive(lost.message.payload).added)
    sender.commit(retried)
    assertFalse(sender.due())
    assertNull(sender.message(7, mapOf(first to 0, second to 0x10)))
  }

  @Test
  fun unsentMessageIsBuiltAgain() {
    var now = 0L
    val sender = PeerExchange { now }
    val first = PeerEndpoint("1.2.3.4", 6881)
    val second = PeerEndpoint("5.6.7.8", 6881)
    sender.commit(assertNotNull(sender.message(7, mapOf(first to 0))))
    now = 60_000
    // Second joins and first leaves, but that message is dropped after it was handed on.
    val dropped = assertNotNull(sender.message(7, mapOf(second to 0)))
    sender.commit(dropped)
    assertFalse(sender.unsent(PeerMessage.Extended(7, dropped.message.payload)))
    assertTrue(sender.unsent(dropped.message))
    // The next message may go out at once and says the same again.
    val again = PeerExchange().receive(assertNotNull(sender.message(7, mapOf(second to 0)))
      .message.payload)
    assertEquals(listOf(second), again.added)
    assertEquals(listOf(first), again.dropped)
    assertFalse(sender.unsent(dropped.message))
  }

  @Test
  fun flagsStayPairedWhenEntriesAreFiltered() {
    val noPort = PeerEndpoint("1.1.1.1", 0)
    val kept = PeerEndpoint("2.2.2.2", 6881)
    val sameHost = PeerEndpoint("2.2.2.2", 6882)
    val last = PeerEndpoint("3.3.3.3", 6881)
    val ipv6 = PeerEndpoint("2001:4860:0:0:0:0:0:1", 51413)
    fun compact(vararg peers: PeerEndpoint) = peers.fold(ByteArray(0)) { bytes, peer ->
      bytes + DhtCodec.compactEndpoint(peer)
    }
    val update = PeerExchange().receive(Bencode.encode(mapOf(
      "added" to compact(noPort, kept, sameHost, last),
      "added.f" to byteArrayOf(0x01, 0x10, 0x02, 0x04),
      "added6" to compact(ipv6),
      "added6.f" to byteArrayOf(0x12),
    )))
    // The port-0 entry and the repeated host are dropped after their flags were paired.
    assertEquals(listOf(kept, last, ipv6), update.added)
    assertEquals(mapOf(kept to 0x10, last to 0x04, ipv6 to 0x12), update.flags)
    // Without flags the update carries none rather than zeros.
    val bare = PeerExchange().receive(Bencode.encode(mapOf("added" to compact(kept))))
    assertEquals(listOf(kept), bare.added)
    assertTrue(bare.flags.isEmpty())
  }

  @Test
  fun allowsThreeMessagesPerMinuteAndRejectsTheFourth() {
    var now = 0L
    val receiver = PeerExchange { now }
    fun message(index: Int) = Bencode.encode(mapOf(
      "added" to DhtCodec.compactEndpoint(PeerEndpoint("1.2.3.$index", 6881))))
    receiver.receive(message(1))
    now = 10_000
    receiver.receive(message(2))
    now = 20_000
    receiver.receive(message(3))
    now = 59_999
    assertFailsWith<IllegalArgumentException> { receiver.receive(message(4)) }
    // The first message leaves the window after a minute, making room for one more.
    now = 60_000
    assertEquals(listOf(PeerEndpoint("1.2.3.5", 6881)), receiver.receive(message(5)).added)
    assertFailsWith<IllegalArgumentException> { receiver.receive(message(6)) }
  }

  @Test
  fun truncatesOversizedLaterUpdates() {
    var now = 0L
    val receiver = PeerExchange { now }
    fun peers(count: Int, offset: Int = 0) = (offset until offset + count).fold(ByteArray(0)) {
        bytes, index ->
      bytes + DhtCodec.compactEndpoint(PeerEndpoint("10.${index / 256}.${index % 256}.1", 6881))
    }
    // The first message may introduce up to 200 peers.
    assertEquals(200, receiver.receive(Bencode.encode(mapOf("added" to peers(200)))).added.size)
    now = 60_000
    // Later ones are cut to 50 added and 50 dropped rather than rejected.
    val later = receiver.receive(Bencode.encode(mapOf("added" to peers(120, 200),
      "added.f" to ByteArray(120) { 0x10 }, "dropped" to peers(80))))
    assertEquals(50, later.added.size)
    assertEquals(50, later.dropped.size)
    assertEquals(later.added.toSet(), later.flags.keys)
    // More entries than any message may carry is a violation.
    now = 120_000
    assertFailsWith<IllegalArgumentException> {
      receiver.receive(Bencode.encode(mapOf("added" to peers(201, 300))))
    }
  }

  @Test
  fun onlyOutgoingContactsCarryReachableFlag() {
    val link = object : TorrentConnection {
      override val remote = PeerEndpoint("4.4.4.4", 6881)
      override suspend fun readExactly(size: Int): ByteArray = error("Unused")
      override suspend fun write(bytes: ByteArray) = error("Unused")
      override fun close() = Unit
    }
    val dialed = PeerEndpoint("4.4.4.4", 6881)
    val listening = PeerEndpoint("5.5.5.5", 51413)
    assertEquals(PEX_FLAG_REACHABLE, pexFlags(reachedOutgoing = true, link, PeerExtensions()))
    assertEquals(0, pexFlags(reachedOutgoing = false, link, PeerExtensions()))
    val message = PeerExchange().message(7, mapOf(
      dialed to pexFlags(reachedOutgoing = true, link, PeerExtensions()),
      listening to pexFlags(reachedOutgoing = false, link, PeerExtensions()),
    ))!!.message
    val update = PeerExchange().receive(message.payload)
    assertEquals(mapOf(dialed to PEX_FLAG_REACHABLE, listening to 0), update.flags)
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
  fun introductionsCountEveryHostAPeerEverNamed() {
    val introductions = PexIntroductions(limit = 3)
    assertTrue(introductions.admit(PeerEndpoint("1.2.3.4", 6881)))
    // One endpoint per host: another port of it is not a new peer.
    assertFalse(introductions.admit(PeerEndpoint("1.2.3.4", 6882)))
    assertTrue(introductions.admit(PeerEndpoint("1.2.3.5", 6881)))
    assertTrue(introductions.admit(PeerEndpoint("1.2.3.6", 6881)))
    // The limit is over the connection: whatever the peer dropped since, nothing more gets in.
    assertFalse(introductions.admit(PeerEndpoint("1.2.3.7", 6881)))
    assertFalse(introductions.admit(PeerEndpoint("1.2.3.4", 6881)))
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
