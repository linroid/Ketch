package com.linroid.ketch.torrent.v2

import com.linroid.ketch.torrent.net.PeerEndpoint
import com.linroid.ketch.torrent.peer.PeerHandshakeUnansweredException
import com.linroid.ketch.torrent.peer.PeerIdentityHandshake
import com.linroid.ketch.torrent.peer.PeerOrigin
import com.linroid.ketch.torrent.peer.PeerRedial
import com.linroid.ketch.torrent.peer.PeerSwarmChangedException
import okio.ByteString.Companion.toByteString
import okio.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TorrentV2CandidatesTest {
  private var now = 0L
  private val a = PeerEndpoint("10.0.0.1", 6881)
  private val b = PeerEndpoint("10.0.0.2", 6881)
  private val c = PeerEndpoint("10.0.0.3", 6881)

  private fun book(hybrid: Boolean = false, restricted: Boolean = false) =
    TorrentV2Candidates(hybrid, restricted) { now }

  private fun peerId(value: Int) = ByteArray(20).also {
    it[0] = (value shr 8).toByte()
    it[1] = value.toByte()
    it[19] = 1
  }.toByteString()

  @Test
  fun ownAddress_isNeverDialed() {
    // Our public address became known after the tracker named a's port there.
    var own: String? = null
    val book = TorrentV2Candidates(hybrid = false, restricted = false,
      isOwnAddress = { it.host == own }) { now }
    book.offer(a, PeerTopic.V2, PeerOrigin.TRACKER)
    book.offer(b, PeerTopic.V2, PeerOrigin.TRACKER)
    own = a.host
    assertEquals(b, assertNotNull(book.next()).endpoint)
    assertNull(book.next())
    // Found again: still ours.
    book.offer(a, PeerTopic.V2, PeerOrigin.DHT)
    assertNull(book.next())
  }

  @Test
  fun deduplicatesAndBacksOffFiveFifteenThirtySeconds() {
    val book = book()
    book.offer(a, PeerTopic.V2, PeerOrigin.TRACKER)
    book.offer(a, PeerTopic.V2, PeerOrigin.DHT)
    var target = assertNotNull(book.next())
    assertEquals(a, target.endpoint)
    // Found again while it is being dialed: still one candidate.
    book.offer(a, PeerTopic.V2, PeerOrigin.TRACKER)
    assertNull(book.next())
    for (wait in listOf(5_000L, 15_000L, 30_000L)) {
      book.failed(target, IOException("Connection refused"))
      assertTrue(book.hasWork())
      assertEquals(wait, book.nextDueMs())
      // Found again while it backs off: still not dialed early.
      book.offer(a, PeerTopic.V2, PeerOrigin.TRACKER)
      now += wait - 1
      assertNull(book.next())
      now += 1
      target = assertNotNull(book.next())
      assertEquals(a, target.endpoint)
    }
    book.failed(target, IOException("Connection refused"))
    assertFalse(book.hasWork())
    assertNull(book.nextDueMs())
    // It never brought payload: given up on until found again once its window has passed.
    now += TorrentV2Candidates.ATTEMPT_WINDOW_MS - 1
    book.offer(a, PeerTopic.V2, PeerOrigin.TRACKER)
    assertNull(book.next())
    now += 1
    book.offer(a, PeerTopic.V2, PeerOrigin.TRACKER)
    assertEquals(a, book.next()?.endpoint)
  }

  @Test
  fun peerThatBroughtPayload_isDialedAgainForAboutHalfAnHour() {
    val book = book()
    book.offer(a, PeerTopic.V2, PeerOrigin.TRACKER)
    book.connected(assertNotNull(book.next()).endpoint)
    // It dropped after sending us payload: it may come back.
    book.closed(a, violation = false, delivered = true)
    for (wait in listOf(5_000L, 15_000L, 30_000L)) {
      assertEquals(wait, book.nextDueMs())
      now += wait
      book.failed(assertNotNull(book.next()), IOException("Connection refused"))
    }
    assertEquals(60_000L, book.nextDueMs())
    // Back, it sends payload again: its dials start over once it drops.
    now += 60_000
    book.connected(assertNotNull(book.next()).endpoint)
    book.closed(a, violation = false, delivered = true)
    val waits = listOf(5_000L, 15_000L, 30_000L, 60_000L, 120_000L) + List(5) { 300_000L }
    for (wait in waits) {
      assertEquals(wait, book.nextDueMs())
      now += wait
      book.failed(assertNotNull(book.next()), IOException("Connection refused"))
    }
    // Ten dials in about half an hour all failed: it is given up as any other endpoint.
    assertFalse(book.hasWork())
    // Found again once its window has passed, it starts over as an endpoint that never brought
    // payload: given up after its fourth dial.
    now += TorrentV2Candidates.ATTEMPT_WINDOW_MS
    book.offer(a, PeerTopic.V2, PeerOrigin.DHT)
    var target = assertNotNull(book.next())
    for (wait in listOf(5_000L, 15_000L, 30_000L)) {
      book.failed(target, IOException("Connection refused"))
      now += wait
      target = assertNotNull(book.next())
    }
    book.failed(target, IOException("Connection refused"))
    assertFalse(book.hasWork())
  }

  @Test
  fun peerThatBroughtPayload_seedRetriesItNoMore() {
    val book = book()
    book.offer(a, PeerTopic.V2, PeerOrigin.TRACKER)
    book.connected(assertNotNull(book.next()).endpoint)
    book.closed(a, violation = false, complete = true, delivered = true)
    assertFalse(book.hasWork())
  }

  @Test
  fun peerThatBroughtPayload_foundAgain_isDialedThirtySecondsAfterItsLastDial() {
    val book = book()
    book.offer(a, PeerTopic.V2, PeerOrigin.TRACKER)
    book.connected(assertNotNull(book.next()).endpoint)
    book.closed(a, violation = false, delivered = true)
    repeat(3) {
      now += assertNotNull(book.nextDueMs())
      book.failed(assertNotNull(book.next()), IOException("Connection refused"))
    }
    assertEquals(60_000L, book.nextDueMs())
    // Ten seconds on, a source names it again: it may be back, so it waits twenty more seconds.
    now += 10_000
    book.offer(a, PeerTopic.V2, PeerOrigin.DHT)
    assertEquals(20_000L, book.nextDueMs())
    now += 20_000
    book.failed(assertNotNull(book.next()), IOException("Connection refused"))
    // The DHT names it every minute: that brings it forward again only five minutes after the
    // last time, not at each lookup.
    assertEquals(120_000L, book.nextDueMs())
    book.offer(a, PeerTopic.V2, PeerOrigin.DHT)
    assertEquals(120_000L, book.nextDueMs())
    now += 60_000
    book.offer(a, PeerTopic.V2, PeerOrigin.DHT)
    assertEquals(60_000L, book.nextDueMs())
    now += 60_000
    book.failed(assertNotNull(book.next()), IOException("Connection refused"))
    assertEquals(300_000L, book.nextDueMs())
    now += 159_999
    book.offer(a, PeerTopic.V2, PeerOrigin.DHT)
    assertNull(book.next())
    now += 1
    book.offer(a, PeerTopic.V2, PeerOrigin.DHT)
    assertEquals(a, book.next()?.endpoint)
  }

  @Test
  fun peerThatBroughtPayload_namedDuringTheThirtySecondWait_isBroughtForwardInTheNextOne() {
    val book = book()
    book.offer(a, PeerTopic.V2, PeerOrigin.TRACKER)
    book.connected(assertNotNull(book.next()).endpoint)
    book.closed(a, violation = false, delivered = true)
    // Each dial is refused 20 ms after it starts.
    fun refuseNextDial() {
      val target = assertNotNull(book.next())
      now += 20
      book.failed(target, IOException("Connection refused"))
    }
    repeat(2) {
      now += assertNotNull(book.nextDueMs())
      refuseNextDial()
    }
    // Named 10 s into the 30 s wait: 30 s after its last dial is only the 20 ms that dial took
    // sooner, so it waits as it did.
    now += 10_000
    book.offer(a, PeerTopic.V2, PeerOrigin.DHT)
    assertEquals(20_000L, book.nextDueMs())
    now += 20_000
    refuseNextDial()
    assertEquals(60_000L, book.nextDueMs())
    // Named 10 s into the minute's wait, it is dialed 30 s after its last dial: the naming
    // before spent nothing.
    now += 10_000
    book.offer(a, PeerTopic.V2, PeerOrigin.DHT)
    assertEquals(19_980L, book.nextDueMs())
  }

  @Test
  fun peerBroughtForward_backWithPayload_isBroughtForwardAgainOnceItDropsAgain() {
    val book = book()
    book.offer(a, PeerTopic.V2, PeerOrigin.TRACKER)
    book.connected(assertNotNull(book.next()).endpoint)
    book.closed(a, violation = false, delivered = true)
    repeat(3) {
      now += assertNotNull(book.nextDueMs())
      book.failed(assertNotNull(book.next()), IOException("Connection refused"))
    }
    // Local discovery names it as it restarts: dialed 30 s after its last dial, it is back.
    now += 10_000
    book.offer(a, PeerTopic.V2, PeerOrigin.LSD)
    now += assertNotNull(book.nextDueMs())
    book.connected(assertNotNull(book.next()).endpoint)
    // It sends payload for a minute and restarts again; its dials start over and fail.
    now += 60_000
    book.closed(a, violation = false, delivered = true)
    repeat(3) {
      now += assertNotNull(book.nextDueMs())
      book.failed(assertNotNull(book.next()), IOException("Connection refused"))
    }
    assertEquals(60_000L, book.nextDueMs())
    // Named again under five minutes after it was brought forward, it is dialed 30 s after its
    // last dial: the connection that bring-forward made showed it back.
    now += 10_000
    book.offer(a, PeerTopic.V2, PeerOrigin.LSD)
    assertEquals(20_000L, book.nextDueMs())
  }

  @Test
  fun peerThatBroughtPayload_foundWhileBeingDialed_isDialedThirtySecondsAfterThatDial() {
    val book = book()
    book.offer(a, PeerTopic.V2, PeerOrigin.TRACKER)
    book.connected(assertNotNull(book.next()).endpoint)
    book.closed(a, violation = false, delivered = true)
    repeat(3) {
      now += assertNotNull(book.nextDueMs())
      book.failed(assertNotNull(book.next()), IOException("Connection refused"))
    }
    now += assertNotNull(book.nextDueMs())
    val target = assertNotNull(book.next())
    // Local discovery names it 5 s into the dial, which times out 2 s later. Its next dial would
    // come two minutes on, but it may be back: it is dialed 30 s after the dial that failed.
    now += 5_000
    book.offer(a, PeerTopic.V2, PeerOrigin.LSD)
    now += 2_000
    book.failed(target, IOException("Connection timed out"))
    assertEquals(23_000L, book.nextDueMs())
  }

  @Test
  fun peerThatBroughtPayload_foundWhileADialReachesIt_waitsAsUsualOnceRefused() {
    val book = book()
    book.offer(a, PeerTopic.V2, PeerOrigin.TRACKER)
    book.connected(assertNotNull(book.next()).endpoint)
    book.closed(a, violation = false, delivered = true)
    repeat(3) {
      now += assertNotNull(book.nextDueMs())
      book.failed(assertNotNull(book.next()), IOException("Connection refused"))
    }
    now += assertNotNull(book.nextDueMs())
    assertNotNull(book.next())
    // Local discovery names it 5 s into the dial, which reaches it 2 s later, but the session,
    // full, refuses it after its handshake. It was there, so the naming was no news: its next
    // dial comes two minutes on, as usual.
    now += 5_000
    book.offer(a, PeerTopic.V2, PeerOrigin.LSD)
    now += 2_000
    book.closed(a, violation = false)
    assertEquals(120_000L, book.nextDueMs())
    // Named a minute later, it is brought forward: that naming was not spent.
    now += 60_000
    book.offer(a, PeerTopic.V2, PeerOrigin.DHT)
    assertEquals(0L, book.nextDueMs())
  }

  @Test
  fun peerThatBroughtPayload_foundWhileConnected_waitsAsUsualOnceItDrops() {
    val book = book()
    book.offer(a, PeerTopic.V2, PeerOrigin.TRACKER)
    book.connected(assertNotNull(book.next()).endpoint)
    book.closed(a, violation = false, delivered = true)
    now += 5_000
    book.connected(assertNotNull(book.next()).endpoint)
    // The DHT names a peer we are connected to again: that says nothing about whether it is back
    // once it drops.
    now += 10_000
    book.offer(a, PeerTopic.V2, PeerOrigin.DHT)
    now += 50_000
    book.closed(a, violation = false, delivered = true)
    assertEquals(5_000L, book.nextDueMs())
  }

  @Test
  fun connectionThatLasted_countsAsTheFirstDial() {
    val book = book()
    for (endpoint in listOf(a, b)) {
      book.offer(endpoint, PeerTopic.V2, PeerOrigin.TRACKER)
      var target = assertNotNull(book.next())
      for (wait in listOf(5_000L, 15_000L, 30_000L)) {
        book.failed(target, IOException("Connection refused"))
        now += wait
        target = assertNotNull(book.next())
      }
      // The fourth dial connects.
      book.connected(target.endpoint)
    }
    // b drops at once: its dials are spent.
    book.closed(b, violation = false)
    assertFalse(book.hasWork())
    // a stays up for five minutes without payload, as a peer with nothing we need yet does: its
    // dials start over.
    now += PeerRedial.LASTING_MS
    book.closed(a, violation = false)
    assertEquals(5_000L, book.nextDueMs())
  }

  @Test
  fun incomingPeerThatBroughtPayload_isDialedWhereItListensAsADialedOneIs() {
    val source = PeerEndpoint("10.0.0.9", 51_234)
    val listen = PeerEndpoint("10.0.0.9", 6881)
    val book = book()
    // Known and given up on already: a tracker named it, and every dial failed.
    book.offer(listen, PeerTopic.V2, PeerOrigin.TRACKER)
    var target = assertNotNull(book.next())
    for (wait in listOf(5_000L, 15_000L, 30_000L)) {
      book.failed(target, IOException("Connection refused"))
      now += wait
      target = assertNotNull(book.next())
    }
    book.failed(target, IOException("Connection refused"))
    assertFalse(book.hasWork())
    // It dialed us, sent payload and dropped: not dialed at once, but for about half an hour.
    book.incomingClosed(source, listen, PeerTopic.V2, delivered = true)
    val waits = listOf(5_000L, 15_000L, 30_000L, 60_000L, 120_000L) + List(5) { 300_000L }
    for (wait in waits) {
      assertNull(book.next())
      assertEquals(wait, book.nextDueMs())
      now += wait
      target = assertNotNull(book.next())
      assertEquals(listen, target.endpoint)
      book.failed(target, IOException("Connection refused"))
    }
    assertFalse(book.hasWork())
    // Restricted sessions dial only what trackers name.
    val restricted = book(restricted = true)
    restricted.incomingClosed(source, listen, PeerTopic.V2, delivered = true)
    assertFalse(restricted.hasWork())
  }

  @Test
  fun incomingOverUtp_fromThePortItListensOn_isDialedBackWhenItSentPayload() {
    // A peer dials uTP from the socket it listens on: its source is where it listens.
    val peer = PeerEndpoint("10.0.0.9", 51413)
    val book = book()
    book.incomingClosed(peer, peer, PeerTopic.V2, delivered = true)
    assertNull(book.next())
    assertEquals(5_000L, book.nextDueMs())
    now += 5_000
    assertEquals(peer, assertNotNull(book.next()).endpoint)
  }

  @Test
  fun duplicate_isGivenUpUntilFoundAfterItsWindow() {
    val book = book()
    book.offer(a, PeerTopic.V2, PeerOrigin.LSD)
    // It reached a peer we are connected to at another address.
    book.duplicate(assertNotNull(book.next()).endpoint)
    assertFalse(book.hasWork())
    book.offer(a, PeerTopic.V2, PeerOrigin.LSD)
    assertNull(book.next())
    now += TorrentV2Candidates.ATTEMPT_WINDOW_MS
    book.offer(a, PeerTopic.V2, PeerOrigin.LSD)
    assertEquals(a, book.next()?.endpoint)
  }

  @Test
  fun neverOffersIncomingSourcePort() {
    val source = PeerEndpoint("10.0.0.9", 51_234)
    val listen = PeerEndpoint("10.0.0.9", 6881)
    val book = book()
    // A peer that never said where it listens leaves nothing to dial.
    book.incomingClosed(source, null, PeerTopic.V2)
    assertNull(book.next())
    assertFalse(book.hasWork())
    book.incomingClosed(source, listen, PeerTopic.V2)
    assertEquals(listen, book.next()?.endpoint)
    assertNull(book.next())
    // Restricted sessions never dial an incoming peer back.
    val restricted = book(restricted = true)
    restricted.incomingClosed(source, listen, PeerTopic.V2)
    assertNull(restricted.next())
  }

  @Test
  fun bansEndpointsAndPeerIdsOnTheirOwnHost() {
    val book = book()
    book.offer(a, PeerTopic.V2, PeerOrigin.TRACKER)
    // A protocol violation, such as reaching ourselves, bans the endpoint.
    book.failed(assertNotNull(book.next()), IllegalArgumentException("Self peer connection"))
    assertFalse(book.hasWork())
    book.offer(a, PeerTopic.V2, PeerOrigin.TRACKER)
    assertNull(book.next())
    val banned = peerId(0)
    book.ban(b)
    book.ban(b.host, banned)
    assertTrue(book.isBanned(b.host, banned))
    assertFalse(book.isBanned(b.host, peerId(1)))
    // A peer ID is only a claim: another host claiming it is not the banned peer.
    assertFalse(book.isBanned(c.host, banned))
    book.offer(b, PeerTopic.V2, PeerOrigin.TRACKER)
    assertNull(book.next())
    // A connected endpoint that misbehaves is banned when it closes.
    book.offer(c, PeerTopic.V2, PeerOrigin.TRACKER)
    book.connected(assertNotNull(book.next()).endpoint)
    book.closed(c, violation = true)
    book.offer(c, PeerTopic.V2, PeerOrigin.TRACKER)
    assertNull(book.next())
    // Both lists are bounded; the oldest bans leave first.
    repeat(TorrentV2Candidates.MAX_BANS) { index ->
      val endpoint = PeerEndpoint("10.1.${index / 250}.${index % 250}", 1)
      book.ban(endpoint)
      book.ban(endpoint.host, peerId(index + 1))
    }
    assertFalse(book.isBanned(b.host, banned))
    book.offer(a, PeerTopic.V2, PeerOrigin.TRACKER)
    assertEquals(a, book.next()?.endpoint)
  }

  @Test
  fun resetStartsNewGenerationAndKeepsBans() {
    val book = book()
    val banned = peerId(7)
    book.offer(a, PeerTopic.V2, PeerOrigin.TRACKER)
    book.offer(b, PeerTopic.V2, PeerOrigin.TRACKER)
    val stale = assertNotNull(book.next())
    assertEquals(0L, stale.generation)
    book.ban(c)
    book.ban(c.host, banned)
    book.reset(1)
    assertEquals(1L, book.generation)
    assertNull(book.next())
    assertFalse(book.hasWork())
    // A dial chosen before the reset cannot schedule a retry in the new generation.
    book.failed(stale, IOException("Connection refused"))
    assertFalse(book.hasWork())
    book.offer(c, PeerTopic.V2, PeerOrigin.TRACKER)
    assertNull(book.next())
    assertTrue(book.isBanned(c.host, banned))
    book.offer(a, PeerTopic.V2, PeerOrigin.TRACKER)
    assertEquals(1L, book.next()?.generation)
  }

  @Test
  fun completeSessionDialsNewPeersButRetriesNobody() {
    val book = book()
    val source = PeerEndpoint("10.0.0.9", 51_234)
    val listen = PeerEndpoint("10.0.0.9", 6881)
    book.offer(a, PeerTopic.V2, PeerOrigin.TRACKER)
    book.offer(b, PeerTopic.V2, PeerOrigin.TRACKER)
    // A seed dials what it has not tried, but neither a failed dial nor a closed peer again.
    book.failed(assertNotNull(book.next()), IOException("Connection refused"), complete = true)
    val connected = assertNotNull(book.next())
    book.connected(connected.endpoint)
    book.closed(connected.endpoint, violation = false, complete = true)
    // An incoming peer that told us where it listens is not dialed back either.
    book.incomingClosed(source, listen, PeerTopic.V2, complete = true)
    assertFalse(book.hasWork())
    assertNull(book.nextDueMs())
    for (endpoint in listOf(a, b, listen)) book.offer(endpoint, PeerTopic.V2, PeerOrigin.TRACKER)
    assertNull(book.next())
    // Found again once its window has passed, each is tried once more.
    now += TorrentV2Candidates.ATTEMPT_WINDOW_MS
    for (endpoint in listOf(a, b, listen)) book.offer(endpoint, PeerTopic.V2, PeerOrigin.TRACKER)
    assertEquals(setOf(a, b, listen), List(3) { assertNotNull(book.next()).endpoint }.toSet())
  }

  @Test
  fun completionDropsRetriesStillBackingOff() {
    val book = book()
    book.offer(a, PeerTopic.V2, PeerOrigin.TRACKER)
    // It failed while we downloaded, so it waits to be dialed again.
    book.failed(assertNotNull(book.next()), IOException("Connection refused"))
    assertNotNull(book.nextDueMs())
    book.completed()
    assertFalse(book.hasWork())
    assertNull(book.nextDueMs())
    now += 30_001
    assertNull(book.next())
    // A new peer is still dialed, and the old one again once found after its window.
    book.offer(b, PeerTopic.V2, PeerOrigin.TRACKER)
    assertEquals(b, book.next()?.endpoint)
    book.offer(a, PeerTopic.V2, PeerOrigin.TRACKER)
    assertNull(book.next())
    now += TorrentV2Candidates.ATTEMPT_WINDOW_MS
    book.offer(a, PeerTopic.V2, PeerOrigin.TRACKER)
    assertEquals(a, book.next()?.endpoint)
  }

  @Test
  fun restrictedBookAcceptsOnlyTrackerOrigins() {
    val book = book(restricted = true)
    for (origin in PeerOrigin.entries.filter { it != PeerOrigin.TRACKER }) {
      book.offer(PeerEndpoint("10.0.1.${origin.ordinal + 1}", 6881), PeerTopic.V2, origin)
    }
    assertNull(book.next())
    assertFalse(book.hasWork())
    book.offer(a, PeerTopic.V2, PeerOrigin.TRACKER)
    assertEquals(a, book.next()?.endpoint)
  }

  @Test
  fun hybridV2RefusalFlipsEndpointToV1WithUpgrade() {
    val book = book(hybrid = true)
    book.offer(a, PeerTopic.V2, PeerOrigin.TRACKER)
    val first = assertNotNull(book.next())
    assertEquals(PeerIdentityHandshake.Mode.V2, first.mode)
    // A peer that only knows the v1 swarm hangs up on the v2 tag: it is dialed again at once,
    // in v1 mode with the upgrade offered.
    book.failed(first, PeerHandshakeUnansweredException(IOException("Connection reset")))
    val second = assertNotNull(book.next())
    assertEquals(PeerIdentityHandshake.Mode.V1, second.mode)
    assertTrue(second.upgrade)
    // The wrong mode cost no attempt, and finding it in the v2 swarm again keeps v1 mode.
    book.offer(a, PeerTopic.V2, PeerOrigin.DHT)
    book.failed(second, IOException("Connection refused"))
    assertEquals(5_000L, book.nextDueMs())
    now += 5_000
    assertEquals(PeerIdentityHandshake.Mode.V1, book.next()?.mode)
    // A peer that answers for another swarm flips the same way.
    book.offer(b, PeerTopic.V2, PeerOrigin.TRACKER)
    book.failed(assertNotNull(book.next()), PeerSwarmChangedException())
    assertEquals(PeerIdentityHandshake.Mode.V1, book.next()?.mode)
    // Without a v1 swarm to fall back to, that answer is a violation.
    val pure = book()
    pure.offer(c, PeerTopic.V2, PeerOrigin.TRACKER)
    pure.failed(assertNotNull(pure.next()), PeerSwarmChangedException())
    pure.offer(c, PeerTopic.V2, PeerOrigin.TRACKER)
    assertNull(pure.next())
  }

  @Test
  fun dialTargetCarriesReceivedPexFlags() {
    val book = book(hybrid = true)
    book.offer(a, PeerTopic.V1, PeerOrigin.PEX, flags = 0x10)
    book.offer(b, PeerTopic.V2, PeerOrigin.TRACKER)
    // Flags are kept even when the endpoint itself is already known.
    book.offer(b, PeerTopic.V2, PeerOrigin.PEX, flags = 0x01)
    val first = assertNotNull(book.next())
    assertEquals(a, first.endpoint)
    assertEquals(0x10, first.pexFlags)
    // A hybrid dials peers it only knows from the v1 swarm in v1 mode, offering the upgrade.
    assertEquals(PeerIdentityHandshake.Mode.V1, first.mode)
    assertTrue(first.upgrade)
    val second = assertNotNull(book.next())
    assertEquals(0x01, second.pexFlags)
    assertEquals(PeerIdentityHandshake.Mode.V2, second.mode)
    assertFalse(second.upgrade)
    // A refused dial goes back to the front of the queue with its flags.
    book.pushBack(second)
    assertEquals(0x01, book.next()?.pexFlags)
  }

  @Test
  fun revive_dialsEndpointsGivenUpOnAgainButNeverBannedOnes() {
    val book = book()
    for (endpoint in listOf(a, b, c)) book.offer(endpoint, PeerTopic.V2, PeerOrigin.TRACKER)
    val targets = generateSequence { book.next() }.associateBy { it.endpoint }
    // a is given up on after its last retry, b backs off, c broke the protocol.
    var target = targets.getValue(a)
    for (wait in listOf(5_000L, 15_000L, 30_000L)) {
      book.failed(target, IOException("Connection refused"))
      now += wait
      target = assertNotNull(book.next())
    }
    book.failed(target, IOException("Connection refused"))
    book.failed(targets.getValue(b), IOException("Connection refused"))
    book.failed(targets.getValue(c), IllegalArgumentException("Bad handshake"))
    assertNull(book.next())
    assertEquals(2, book.revive())
    // Both are dialed at once.
    val revived = generateSequence { book.next() }.associateBy { it.endpoint }
    assertEquals(setOf(a, b), revived.keys)
    // Each keeps its count of dials: a is given up again at once, b backs off for its second.
    book.failed(revived.getValue(a), IOException("Connection refused"))
    book.failed(revived.getValue(b), IOException("Connection refused"))
    assertEquals(15_000, book.nextDueMs())
    now += 15_000
    assertEquals(b, book.next()?.endpoint)
    assertNull(book.next())
  }

  @Test
  fun revive_leavesWhenAnEndpointWasGivenUp() {
    val book = book()
    book.offer(a, PeerTopic.V2, PeerOrigin.TRACKER)
    var target = assertNotNull(book.next())
    for (wait in listOf(5_000L, 15_000L, 30_000L)) {
      book.failed(target, IOException("Connection refused"))
      now += wait
      target = assertNotNull(book.next())
    }
    book.failed(target, IOException("Connection refused"))
    val givenUpAt = now
    // A stall without a peer revives it every minute, and each dial fails.
    repeat(4) {
      now += 60_000
      assertEquals(1, book.revive())
      book.failed(assertNotNull(book.next()), IOException("Connection refused"))
      assertFalse(book.hasWork())
    }
    // Found again five minutes after it was first given up, it starts over as a new endpoint.
    now = givenUpAt + TorrentV2Candidates.ATTEMPT_WINDOW_MS
    book.offer(a, PeerTopic.V2, PeerOrigin.DHT)
    target = assertNotNull(book.next())
    book.failed(target, IOException("Connection refused"))
    assertEquals(5_000L, book.nextDueMs())
  }
}
