package com.linroid.ketch.torrent.lsd

import com.linroid.ketch.torrent.net.PeerEndpoint
import com.linroid.ketch.torrent.net.TorrentDatagram
import com.linroid.ketch.torrent.net.numericAddress
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import okio.ByteString
import okio.ByteString.Companion.toByteString
import okio.IOException
import kotlin.concurrent.atomics.AtomicInt
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** A multicast group in memory: what we send is recorded, what others send is queued. */
@OptIn(ExperimentalAtomicApi::class)
internal class FakeLsdTransport : LsdTransport {
  val sent = MutableStateFlow<List<LsdAnnounce>>(emptyList())
  val incoming = Channel<TorrentDatagram>(Channel.UNLIMITED)
  val opens = AtomicInt(0)
  val closes = AtomicInt(0)
  /** False once the device's interfaces changed; opening again joins the current ones. */
  var current = true
  /** Sends fail while set, as when no interface reaches the group any more. */
  var failSends = false

  /** Opens this transport, as the engine's factory would open the group. */
  suspend fun open(): LsdTransport {
    opens.addAndFetch(1)
    current = true
    return this
  }

  override suspend fun send(announce: LsdAnnounce) {
    if (failSends) throw IOException("Network is unreachable")
    assertEquals(announce, LsdAnnounce.parse(announce.encode()))
    sent.update { it + announce }
  }

  override suspend fun receive(): TorrentDatagram = incoming.receive()

  override suspend fun isCurrent(): Boolean = current

  override fun close() {
    closes.addAndFetch(1)
  }

  /** Every hash we announced, in order, repeats included. */
  fun announcedHashes(): List<ByteString> = sent.value.flatMap { it.hashes }

  /** Queues an announce of [hashes] from [host], as another client on the LAN sends it. */
  fun announceFrom(host: String, port: Int, vararg hashes: ByteString, cookie: String = "other") {
    incoming.trySend(TorrentDatagram(PeerEndpoint(host, LSD_PORT),
      LsdAnnounce(port, hashes.toList(), cookie).encode()))
  }
}

@OptIn(ExperimentalAtomicApi::class)
class TorrentLocalDiscoveryTest {
  private val hashA = ByteArray(20) { 0xa1.toByte() }.toByteString()
  private val hashB = ByteArray(20) { 0xb2.toByte() }.toByteString()

  private fun TestScope.service(
    transport: FakeLsdTransport,
    port: () -> Int = { 6881 },
    enabled: Boolean = true,
  ) = TorrentLocalDiscovery(backgroundScope, transport::open, port, enabled,
    nowMs = { testScheduler.currentTime }, cookie = "ours")

  /** Joins [hashes] in the background, collecting the peers delivered for each. */
  private fun TestScope.join(
    service: TorrentLocalDiscovery,
    found: MutableList<Pair<ByteString, PeerEndpoint>>,
    vararg hashes: ByteString,
  ): Job = backgroundScope.launch {
    service.participate(hashes.map { hash -> LsdTopic(hash) { found += hash to it } })
  }

  @Test
  fun parse_readsWhatEncodeWrites() {
    val announce = LsdAnnounce(51413, listOf(hashA, hashB), "c00k1e")
    assertEquals(announce, LsdAnnounce.parse(announce.encode()))
    val text = announce.encode().decodeToString()
    assertTrue(text.startsWith("BT-SEARCH * HTTP/1.1\r\nHost: 239.192.152.143:6771\r\n"))
    assertTrue(text.endsWith("\r\n\r\n\r\n"))
  }

  @Test
  fun encode_forTheIpv6Group_namesItsHost() {
    val announce = LsdAnnounce(51413, listOf(hashA), "c00k1e")
    val bytes = announce.encode(LSD_HOST_IPV6)
    assertTrue(bytes.decodeToString()
      .startsWith("BT-SEARCH * HTTP/1.1\r\nHost: [ff15::efc0:988f]:6771\r\n"))
    assertEquals(announce, LsdAnnounce.parse(bytes))
  }

  @Test
  fun lsdSenderAllowed_ipv6LinkLocalAndUniqueLocalOnly() {
    for (host in listOf("fe80::1", "febf::1", "fd12:3456::7", "fc00::1")) {
      assertTrue(lsdSenderAllowed(assertNotNull(numericAddress(host))), host)
    }
    for (host in listOf("2001:db8::1", "fec0::1", "ff15::efc0:988f", "::1", "::ffff:192.168.1.2")) {
      assertFalse(lsdSenderAllowed(assertNotNull(numericAddress(host))), host)
    }
    assertFalse(lsdPeerDialable(assertNotNull(numericAddress("fe80::1"))))
    assertTrue(lsdPeerDialable(assertNotNull(numericAddress("fd12:3456::7"))))
    assertTrue(lsdPeerDialable(assertNotNull(numericAddress("192.168.1.2"))))
  }

  @Test
  fun parse_headersOfAnyCaseAndBareLineFeeds() {
    val text = "BT-SEARCH * HTTP/1.1\nHOST: 239.192.152.143:6771\nport:  7000 \n" +
      "INFOHASH: ${hashA.hex().uppercase()}\ninfoHash: ${hashB.hex()}\nCookie: x\n\n"
    assertEquals(LsdAnnounce(7000, listOf(hashA, hashB), "x"),
      LsdAnnounce.parse(text.encodeToByteArray()))
  }

  @Test
  fun parse_refusesWhatIsNotAnAnnounce() {
    val hash = hashA.hex()
    val refused = listOf(
      "",
      "GET / HTTP/1.1\r\nPort: 7000\r\nInfohash: $hash\r\n\r\n",
      "BT-SEARCH * HTTP/1.1\r\nInfohash: $hash\r\n\r\n",
      "BT-SEARCH * HTTP/1.1\r\nPort: 0\r\nInfohash: $hash\r\n\r\n",
      "BT-SEARCH * HTTP/1.1\r\nPort: 70000\r\nInfohash: $hash\r\n\r\n",
      "BT-SEARCH * HTTP/1.1\r\nPort: 7000\r\nPort: 7001\r\nInfohash: $hash\r\n\r\n",
      "BT-SEARCH * HTTP/1.1\r\nPort: 7000\r\n\r\n",
      "BT-SEARCH * HTTP/1.1\r\nPort: 7000\r\nInfohash: ${hash.drop(1)}\r\n\r\n",
      "BT-SEARCH * HTTP/1.1\r\nPort: 7000\r\nInfohash: ${hash.dropLast(1)}g\r\n\r\n",
      "BT-SEARCH * HTTP/1.1\r\nPort: 7000\r\ngarbage\r\nInfohash: $hash\r\n\r\n",
      "BT-SEARCH * HTTP/1.1\r\nPort: 7000\r\n" + (0..LsdAnnounce.MAX_HASHES).joinToString("") {
        "Infohash: ${it.toString().padStart(40, '0')}\r\n"
      },
    )
    for (text in refused) assertNull(LsdAnnounce.parse(text.encodeToByteArray()), text)
    assertNull(LsdAnnounce.parse(ByteArray(LsdAnnounce.MAX_BYTES + 1) { 'a'.code.toByte() }))
    assertNull(LsdAnnounce.parse(ByteArray(64) { (it * 37).toByte() }))
  }

  @Test
  fun participate_announcesAtOnceThenEveryFiveMinutes() = runTest {
    val transport = FakeLsdTransport()
    val service = service(transport)
    join(service, mutableListOf(), hashA)
    runCurrent()
    assertEquals(listOf(LsdAnnounce(6881, listOf(hashA), "ours")), transport.sent.value)
    advanceTimeBy(TorrentLocalDiscovery.ANNOUNCE_INTERVAL_MS - 1)
    assertEquals(1, transport.sent.value.size)
    advanceTimeBy(2)
    assertEquals(listOf(hashA, hashA), transport.announcedHashes())
  }

  @Test
  fun networkChange_joinsTheGroupAgain() = runTest {
    val transport = FakeLsdTransport()
    val service = service(transport)
    join(service, mutableListOf(), hashA)
    runCurrent()
    assertEquals(1, transport.opens.load())
    transport.current = false
    advanceTimeBy(TorrentLocalDiscovery.NETWORK_CHECK_MS + 1_000)
    assertEquals(2, transport.opens.load())
    assertEquals(1, transport.closes.load())
  }

  @Test
  fun failedAnnounce_isSentAgainOnceTheGroupIsJoinedAgain() = runTest {
    val transport = FakeLsdTransport()
    transport.failSends = true
    val service = service(transport)
    join(service, mutableListOf(), hashA)
    runCurrent()
    assertEquals(1, transport.closes.load())
    transport.failSends = false
    // The default retry is a minute, far short of the five-minute announce interval.
    advanceTimeBy(60_000 + 1)
    assertEquals(2, transport.opens.load())
    assertEquals(listOf(hashA), transport.announcedHashes())
  }

  @Test
  fun participate_batchesTorrentsDueTogether() = runTest {
    val transport = FakeLsdTransport()
    val service = service(transport)
    join(service, mutableListOf(), hashA)
    join(service, mutableListOf(), hashB)
    runCurrent()
    assertEquals(listOf(LsdAnnounce(6881, listOf(hashA, hashB), "ours")), transport.sent.value)
  }

  @Test
  fun participate_againWithinAMinute_waitsForTheMinute() = runTest {
    val transport = FakeLsdTransport()
    val service = service(transport)
    val first = join(service, mutableListOf(), hashA)
    runCurrent()
    advanceTimeBy(10_000)
    first.cancel()
    runCurrent()
    // The group is left with the last torrent.
    assertEquals(1, transport.closes.load())
    advanceTimeBy(10_000)
    join(service, mutableListOf(), hashA)
    runCurrent()
    assertEquals(1, transport.sent.value.size)
    advanceTimeBy(TorrentLocalDiscovery.MIN_INTERVAL_MS - 20_000 - 1)
    assertEquals(1, transport.sent.value.size)
    advanceTimeBy(2)
    assertEquals(listOf(hashA, hashA), transport.announcedHashes())
  }

  @Test
  fun participate_withoutListenPort_waitsForIt() = runTest {
    val transport = FakeLsdTransport()
    var port = 0
    val service = service(transport, port = { port })
    join(service, mutableListOf(), hashA)
    advanceTimeBy(5_000)
    assertEquals(emptyList(), transport.sent.value)
    port = 7000
    advanceTimeBy(1_001)
    assertEquals(listOf(LsdAnnounce(7000, listOf(hashA), "ours")), transport.sent.value)
  }

  @Test
  fun accept_deliversAPeerToEveryParticipantOfItsHash() = runTest {
    val transport = FakeLsdTransport()
    val service = service(transport)
    // A task and a magnet lookup of the same torrent.
    val task = mutableListOf<Pair<ByteString, PeerEndpoint>>()
    val lookup = mutableListOf<Pair<ByteString, PeerEndpoint>>()
    join(service, task, hashA)
    join(service, lookup, hashA)
    runCurrent()
    transport.announceFrom("192.168.1.20", 7000, hashA)
    transport.announceFrom("192.168.1.20", 7000, hashA)
    runCurrent()
    val peer = hashA to PeerEndpoint("192.168.1.20", 7000)
    assertEquals(listOf(peer), task)
    assertEquals(listOf(peer), lookup)
  }

  @Test
  fun accept_deliversPeersOfOurTorrentsOncePerMinute() = runTest {
    val transport = FakeLsdTransport()
    val service = service(transport)
    val found = mutableListOf<Pair<ByteString, PeerEndpoint>>()
    join(service, found, hashA, hashB)
    runCurrent()
    val other = ByteArray(20) { 3 }.toByteString()
    transport.announceFrom("192.168.1.20", 7000, hashA, other)
    transport.announceFrom("192.168.1.20", 7000, hashA)
    transport.announceFrom("10.0.0.5", 7001, hashB)
    transport.announceFrom("169.254.3.4", 7002, hashA)
    runCurrent()
    assertEquals(listOf(
      hashA to PeerEndpoint("192.168.1.20", 7000),
      hashB to PeerEndpoint("10.0.0.5", 7001),
      hashA to PeerEndpoint("169.254.3.4", 7002),
    ), found)
    advanceTimeBy(TorrentLocalDiscovery.MIN_INTERVAL_MS)
    transport.announceFrom("192.168.1.20", 7000, hashA)
    runCurrent()
    assertEquals(4, found.size)
  }

  @Test
  fun accept_newPeer_isAnsweredAsSoonAsAMinuteAllows() = runTest {
    val transport = FakeLsdTransport()
    val service = service(transport)
    join(service, mutableListOf(), hashA)
    runCurrent()
    assertEquals(1, transport.sent.value.size)

    // Two minutes on, a device joins: it hears us at once, not at our next round.
    advanceTimeBy(2 * TorrentLocalDiscovery.MIN_INTERVAL_MS)
    transport.announceFrom("192.168.1.20", 7000, hashA)
    runCurrent()
    assertEquals(listOf(hashA, hashA), transport.announcedHashes())

    // Within the minute, more peers wait for it; one answer goes out then.
    advanceTimeBy(10_000)
    transport.announceFrom("192.168.1.21", 7000, hashA)
    transport.announceFrom("192.168.1.22", 7000, hashA)
    runCurrent()
    assertEquals(2, transport.sent.value.size)
    advanceTimeBy(TorrentLocalDiscovery.MIN_INTERVAL_MS - 10_000)
    runCurrent()
    assertEquals(3, transport.sent.value.size)
    advanceTimeBy(TorrentLocalDiscovery.MIN_INTERVAL_MS)
    assertEquals(3, transport.sent.value.size)
  }

  @Test
  fun accept_peersHeardEveryRound_neverAnswerEachOther() = runTest {
    val transport = FakeLsdTransport()
    val service = service(transport)
    join(service, mutableListOf(), hashA)
    runCurrent()
    advanceTimeBy(2 * TorrentLocalDiscovery.MIN_INTERVAL_MS)
    transport.announceFrom("192.168.1.20", 7000, hashA)
    runCurrent()
    assertEquals(2, transport.sent.value.size)

    // Its answer to ours, a minute on, and its rounds after are no newcomer's announces.
    advanceTimeBy(TorrentLocalDiscovery.MIN_INTERVAL_MS)
    transport.announceFrom("192.168.1.20", 7000, hashA)
    runCurrent()
    assertEquals(2, transport.sent.value.size)
    val round = TorrentLocalDiscovery.ANNOUNCE_INTERVAL_MS
    advanceTimeBy(round - TorrentLocalDiscovery.MIN_INTERVAL_MS - 1)
    transport.announceFrom("192.168.1.20", 7000, hashA)
    runCurrent()
    assertEquals(2, transport.sent.value.size)
    advanceTimeBy(2)
    assertEquals(3, transport.sent.value.size)
  }

  @Test
  fun accept_peerOfAnotherTorrent_isNotAnswered() = runTest {
    val transport = FakeLsdTransport()
    val service = service(transport)
    join(service, mutableListOf(), hashA)
    runCurrent()
    advanceTimeBy(2 * TorrentLocalDiscovery.MIN_INTERVAL_MS)
    transport.announceFrom("192.168.1.20", 7000, hashB)
    runCurrent()
    assertEquals(1, transport.sent.value.size)
  }

  @Test
  fun accept_ignoresOwnCookieAndPublicSenders() = runTest {
    val transport = FakeLsdTransport()
    val service = service(transport)
    val found = mutableListOf<Pair<ByteString, PeerEndpoint>>()
    join(service, found, hashA)
    runCurrent()
    transport.announceFrom("192.168.1.20", 7000, hashA, cookie = "ours")
    transport.announceFrom("8.8.8.8", 7000, hashA)
    transport.announceFrom("127.0.0.1", 7000, hashA)
    transport.incoming.trySend(TorrentDatagram(PeerEndpoint("192.168.1.21", LSD_PORT),
      "BT-SEARCH * HTTP/1.1\r\n\r\n".encodeToByteArray()))
    runCurrent()
    assertEquals(emptyList(), found)
  }

  @Test
  fun accept_ipv6_deliversUniqueLocalSendersOnly() = runTest {
    val transport = FakeLsdTransport()
    val service = service(transport)
    val found = mutableListOf<Pair<ByteString, PeerEndpoint>>()
    join(service, found, hashA)
    runCurrent()
    transport.announceFrom("fd12:3456:0:0:0:0:0:7", 7000, hashA)
    // Link-local senders need the zone they were heard on, which endpoints do not carry.
    transport.announceFrom("fe80::1", 7001, hashA)
    transport.announceFrom("2001:db8::1", 7002, hashA)
    transport.announceFrom("::1", 7003, hashA)
    runCurrent()
    assertEquals(listOf(hashA to PeerEndpoint("fd12:3456:0:0:0:0:0:7", 7000)), found)
  }

  @Test
  fun accept_limitsHowManyAnnouncesItTakes() = runTest {
    val transport = FakeLsdTransport()
    val service = service(transport)
    val found = mutableListOf<Pair<ByteString, PeerEndpoint>>()
    join(service, found, hashA)
    runCurrent()
    repeat(100) { transport.announceFrom("192.168.2.${it + 1}", 7000, hashA) }
    runCurrent()
    assertEquals(TorrentLocalDiscovery.ACCEPT_BURST, found.size)
    advanceTimeBy(1_000)
    repeat(100) { transport.announceFrom("192.168.3.${it + 1}", 7000, hashA) }
    runCurrent()
    assertEquals(TorrentLocalDiscovery.ACCEPT_BURST + TorrentLocalDiscovery.ACCEPT_PER_SECOND,
      found.size)
  }

  @Test
  fun setEnabled_offLeavesTheGroupAndStopsAnnouncing() = runTest {
    val transport = FakeLsdTransport()
    val service = service(transport)
    val found = mutableListOf<Pair<ByteString, PeerEndpoint>>()
    join(service, found, hashA)
    runCurrent()
    service.setEnabled(false)
    runCurrent()
    assertEquals(1, transport.closes.load())
    advanceTimeBy(3 * TorrentLocalDiscovery.ANNOUNCE_INTERVAL_MS)
    transport.announceFrom("192.168.1.20", 7000, hashA)
    runCurrent()
    assertEquals(1, transport.sent.value.size)
    assertEquals(emptyList(), found)
    service.setEnabled(true)
    runCurrent()
    assertEquals(2, transport.opens.load())
    assertEquals(listOf(hashA, hashA), transport.announcedHashes())
    assertEquals(listOf(hashA to PeerEndpoint("192.168.1.20", 7000)), found)
  }

  @Test
  fun disabled_neverJoinsTheGroup() = runTest {
    val transport = FakeLsdTransport()
    val service = service(transport, enabled = false)
    join(service, mutableListOf(), hashA)
    advanceTimeBy(TorrentLocalDiscovery.ANNOUNCE_INTERVAL_MS)
    assertEquals(0, transport.opens.load())
    assertEquals(emptyList(), transport.sent.value)
  }
}
