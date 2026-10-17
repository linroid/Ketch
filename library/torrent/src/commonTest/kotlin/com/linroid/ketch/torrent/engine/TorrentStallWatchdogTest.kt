package com.linroid.ketch.torrent.engine

import com.linroid.ketch.torrent.api.TorrentStall
import com.linroid.ketch.torrent.api.TorrentStallReason
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TorrentStallWatchdogTest {
  private var now = 0L
  private val watchdog = TorrentStallWatchdog { now }

  private fun signals(
    payloadBytes: Long = 0,
    unsettledBytes: Long = 0,
    wastedBytes: Long = 0,
    recentWastedBytes: Long = 0,
    throttledMs: Long = 0,
    peers: Int = 0,
    unchokingPeers: Int = 0,
    missingPieces: Int = 0,
    missingFileIds: List<String> = emptyList(),
    lateBlocks: Int = 0,
    webSeeds: Int = 0,
    droppedWebSeeds: Int = 0,
    downloading: Boolean = true,
  ) = StallSignals(downloading, peers, unchokingPeers, missingPieces, missingFileIds, lateBlocks,
    webSeeds, droppedWebSeeds, payloadBytes, unsettledBytes, wastedBytes, recentWastedBytes,
    throttledMs, pieceLength = 262_144)

  /** Checks once a second up to [until], as a swarm does. */
  private fun runTo(until: Long, signals: (Long) -> StallSignals): Set<StallRecovery> {
    val due = mutableSetOf<StallRecovery>()
    while (now < until) {
      now += 1_000
      due += watchdog.check(signals(now))
    }
    return due
  }

  /** Checks as [runTo] does, and returns the stall after each check. */
  private fun stallsTo(until: Long, signals: (Long) -> StallSignals): List<TorrentStall?> =
    buildList {
      while (now < until) {
        now += 1_000
        watchdog.check(signals(now))
        add(watchdog.stall)
      }
    }

  @Test
  fun payloadAndPieces_areNoStall() {
    runTo(300_000) { time ->
      // A piece of 256 KiB every 10 s.
      if (time % 10_000 == 0L) watchdog.progressed()
      signals(payloadBytes = time * 30, peers = 3, unchokingPeers = 1)
    }
    assertNull(watchdog.stall)
  }

  @Test
  fun noPayload_isAStallWithItsReason() {
    runTo(29_000) { signals() }
    assertNull(watchdog.stall)
    runTo(31_000) { signals() }
    assertEquals(TorrentStallReason.NO_PEERS, assertNotNull(watchdog.stall).reason)
    // Peers that all choke us.
    runTo(32_000) { signals(peers = 4) }
    assertEquals(TorrentStallReason.CHOKED, watchdog.stall?.reason)
    runTo(33_000) { signals(peers = 4, unchokingPeers = 2, lateBlocks = 7) }
    assertEquals(TorrentStallReason.SLOW_PEERS, watchdog.stall?.reason)
    assertEquals(33, watchdog.stall?.seconds)
    // A verified piece ends it.
    watchdog.progressed()
    assertNull(watchdog.stall)
  }

  @Test
  fun recovered_thenStalledAgain_countsFromTheRecovery() {
    runTo(90_000) { signals() }
    assertEquals(90, watchdog.stall?.seconds)
    // A verified piece ends it.
    runTo(95_000) { time -> signals(payloadBytes = time, peers = 1, unchokingPeers = 1) }
    watchdog.progressed()
    assertNull(watchdog.stall)
    // Stalled again: its time counts from the piece that ended the last one.
    runTo(130_000) { signals(payloadBytes = 95_000) }
    assertEquals(35, assertNotNull(watchdog.stall).seconds)
  }

  @Test
  fun payloadBackAfterAGap_endsTheStallAndGivesAPieceItsTimeAgain() {
    runTo(90_000) { signals() }
    assertEquals(TorrentStallReason.NO_PEERS, watchdog.stall?.reason)
    // Peers are back and send payload: no stall, though no piece verified for 90 s.
    runTo(95_000) { time -> signals(payloadBytes = time, peers = 1, unchokingPeers = 1) }
    assertNull(watchdog.stall)
    // Still no piece once four 256 KiB pieces at 16 KiB/s are due: a stall again, which counts
    // from the last verified piece.
    runTo(154_000) { time -> signals(payloadBytes = time, peers = 1, unchokingPeers = 1) }
    assertNull(watchdog.stall)
    runTo(156_000) { time -> signals(payloadBytes = time, peers = 1, unchokingPeers = 1) }
    val stall = assertNotNull(watchdog.stall)
    assertEquals(TorrentStallReason.UNKNOWN, stall.reason)
    assertEquals(156, stall.seconds)
  }

  @Test
  fun payloadComingAndGoing_givesAPieceItsTimeAgainOnlyOnce() {
    // Peers mostly choke us: every 100 s, 10 s of payload at 10 KB a second, and no piece
    // verifies.
    fun received(time: Long) = (1..time / 100_000).sumOf { burst ->
      (time - burst * 100_000).coerceIn(0, 10_000) * 10
    }
    val stalls = stallsTo(600_000) { time ->
      val sending = time % 100_000 in 1..10_000 && time > 100_000
      signals(payloadBytes = received(time), peers = 1, unchokingPeers = if (sending) 1 else 0)
    }
    // The first burst after the stall began gives a piece its time again, and ends it.
    assertNull(stalls[100])
    // No more than that: once that time is up, the stall stays, bursts or not.
    val after = stalls.drop(164)
    assertTrue(after.all { it != null }, after.toString())
  }

  @Test
  fun wastedPayload_isNoProgress() {
    runTo(40_000) { signals(webSeeds = 1) }
    assertNotNull(watchdog.stall)
    // A web seed sends a piece that fails its hash check at once.
    runTo(41_000) {
      signals(payloadBytes = 16_384, wastedBytes = 16_384, recentWastedBytes = 16_384, webSeeds = 1)
    }
    assertEquals(41, assertNotNull(watchdog.stall).seconds)
    // The next one arrives over four checks, which take it as payload while it may still be
    // kept, then fails too, with the 384 bytes that arrived since the last check.
    runTo(45_000) { time ->
      val arrived = (time - 41_000) * 4
      signals(payloadBytes = 16_384 + arrived, unsettledBytes = arrived, wastedBytes = 16_384,
        recentWastedBytes = 16_384, webSeeds = 1)
    }
    assertNull(watchdog.stall)
    runTo(46_000) {
      signals(payloadBytes = 32_768, wastedBytes = 32_768, recentWastedBytes = 16_768, webSeeds = 1)
    }
    assertEquals(46, assertNotNull(watchdog.stall).seconds)
    // Payload kept after it counts again.
    runTo(47_000) {
      signals(payloadBytes = 40_000, wastedBytes = 32_768, recentWastedBytes = 16_768, webSeeds = 1)
    }
    assertNull(watchdog.stall)
  }

  @Test
  fun payloadThatMayBeThrownAway_neverEndsALongStall() {
    // Ten minutes without payload, then a web seed sends a piece over five checks.
    runTo(600_000) { signals(webSeeds = 1) }
    val arriving = stallsTo(605_000) { time ->
      val arrived = (time - 600_000) * 4
      signals(payloadBytes = arrived, unsettledBytes = arrived, webSeeds = 1)
    }
    // Until it is kept, the time for a piece still counts from before the gap.
    assertTrue(arriving.all { it != null }, arriving.toString())
    // It fails its hash check: still a stall, which counts from the start.
    runTo(606_000) { signals(payloadBytes = 20_000, wastedBytes = 20_000, webSeeds = 1) }
    assertEquals(606, assertNotNull(watchdog.stall).seconds)
  }

  @Test
  fun wastedPayload_neverLooksLikePayloadBackAfterAGap() {
    // A peer trickles 1 KB a second and no piece verifies: a stall once four pieces are due.
    runTo(70_000) { time -> signals(payloadBytes = time, peers = 1, unchokingPeers = 1) }
    assertEquals(TorrentStallReason.UNKNOWN, watchdog.stall?.reason)
    // A web seed sends 60 KB a second besides for 40 s, then its connection resets: all of it is
    // thrown away.
    val fetching = stallsTo(109_000) { time ->
      val fetched = (time - 70_000) * 60
      signals(payloadBytes = time + fetched, unsettledBytes = fetched, peers = 1,
        unchokingPeers = 1, webSeeds = 1)
    }
    // The peer goes on trickling: its next bytes come after no gap, so the stall stays.
    val wasted = 39_000L * 60
    val after = stallsTo(140_000) { time ->
      signals(payloadBytes = time + wasted, wastedBytes = wasted, peers = 1, unchokingPeers = 1)
    }
    assertTrue((fetching + after).all { it != null }, (fetching + after).toString())
  }

  @Test
  fun wastedPieceAndTheNextOneInOneCheck_stallCountsFromThePayloadBefore() {
    // A peer sends payload for 5 s and leaves.
    runTo(5_000) { time -> signals(payloadBytes = time, peers = 1, unchokingPeers = 1) }
    // From 11 s a web seed sends a piece over four checks. The fourth also brings the first
    // 32 KiB of the next piece and finds the first corrupt; the next one is corrupt too. Each
    // fails with the last 64 KiB or 32 KiB it brought since the check before.
    val bySecond = listOf(65_536L, 131_072, 196_608, 294_912, 360_448, 425_984, 491_520, 524_288)
    fun sent(time: Long) = if (time < 11_000) 0L else bySecond[minOf(time / 1_000 - 11, 7).toInt()]
    fun wasted(time: Long) = when {
      time < 14_000 -> 0L
      time < 18_000 -> 262_144L
      else -> 524_288L
    }
    fun recent(time: Long) = when {
      time < 14_000 -> 0L
      time < 18_000 -> 65_536L
      else -> 98_304L
    }
    val stalls = stallsTo(40_000) { time ->
      signals(payloadBytes = 5_000 + sent(time), unsettledBytes = sent(time) - wasted(time),
        wastedBytes = wasted(time), recentWastedBytes = recent(time), webSeeds = 1)
    }
    // Nothing kept since 5 s: a stall half a minute after, not after the first corrupt copy came.
    assertTrue(stalls.take(29).all { it == null }, stalls.toString())
    assertEquals(35, assertNotNull(stalls[29]).seconds)
  }

  @Test
  fun payloadArrivingWithWastedPayload_staysPayload() {
    // A web seed sends 10 KB a second for 20 s.
    runTo(20_000) { time ->
      signals(payloadBytes = time * 10, unsettledBytes = time * 10, webSeeds = 1)
    }
    // Its piece fails as a peer sends 50 KB, kept at once, and leaves.
    val stalls = stallsTo(60_000) {
      signals(payloadBytes = 250_000, wastedBytes = 200_000, webSeeds = 1)
    }
    // A stall half a minute after the peer's payload.
    assertTrue(stalls.take(30).all { it == null }, stalls.toString())
    assertEquals(51, assertNotNull(stalls[30]).seconds)
  }

  @Test
  fun pieceWastedOnArrival_takesNoOtherPayloadBack() {
    // A slow web seed sends 10 KB a second for 5 s and hangs mid-piece; a peer sends 5 KB at 5 s,
    // kept at once, and leaves.
    fun slow(time: Long) = minOf(time / 1_000, 5) * 10_000
    fun peer(time: Long) = if (time < 5_000) 0L else 5_000L
    // At 10 s and 25 s a fast web seed sends a whole piece that fails its hash check at once.
    fun fast(time: Long) = 65_536L * listOf(10_000L, 25_000L).count { time >= it }
    // At 35 s the slow seed's request times out, and its bytes are thrown away.
    fun timedOut(time: Long) = time >= 35_000
    val stalls = stallsTo(60_000) { time ->
      signals(payloadBytes = slow(time) + peer(time) + fast(time),
        unsettledBytes = if (timedOut(time)) 0 else slow(time),
        wastedBytes = fast(time) + if (timedOut(time)) slow(time) else 0,
        recentWastedBytes = fast(time), webSeeds = 2)
    }
    // A stall half a minute after the peer's payload, the last kept.
    assertTrue(stalls.take(34).all { it == null }, stalls.toString())
    assertEquals(35, assertNotNull(stalls[34]).seconds)
  }

  @Test
  fun wastedPayloadFromBeforeAVerifiedPiece_takesNoneOfTheNextPayload() {
    // A web seed sends 10 KB a second for 20 s, and a peer's piece verifies at 10 s.
    runTo(20_000) { time ->
      if (time == 10_000L) watchdog.progressed()
      signals(payloadBytes = time * 10, unsettledBytes = time * 10, webSeeds = 1)
    }
    // The seed's piece fails as a peer sends 50 KB, kept at once, and leaves.
    val stalls = stallsTo(60_000) {
      signals(payloadBytes = 250_000, wastedBytes = 200_000, webSeeds = 1)
    }
    // What the seed sent before the verified piece takes none of the peer's payload back: a stall
    // half a minute after the peer's payload.
    assertTrue(stalls.take(30).all { it == null }, stalls.toString())
    assertEquals(41, assertNotNull(stalls[30]).seconds)
  }

  @Test
  fun pieceWastedOnArrival_givesPayloadThatMayBeThrownAwayNoTimeAgain() {
    // Ten minutes without payload, then a web seed sends 10 KB a second.
    runTo(600_000) { signals(webSeeds = 2) }
    fun slow(time: Long) = (time - 600_000) / 1_000 * 10_000
    // At 606 s another sends a whole piece that fails its hash check at once.
    fun fast(time: Long) = if (time < 606_000) 0L else 65_536L
    val stalls = stallsTo(610_000) { time ->
      signals(payloadBytes = slow(time) + fast(time), unsettledBytes = slow(time),
        wastedBytes = fast(time), recentWastedBytes = fast(time), webSeeds = 2)
    }
    // The first seed's bytes may still be thrown away: the time for a piece still counts from
    // before the gap.
    assertTrue(stalls.all { it != null }, stalls.toString())
  }

  @Test
  fun missingPieces_comeFirstAndNameTheirFiles() {
    runTo(31_000) {
      signals(peers = 5, unchokingPeers = 5, missingPieces = 2, missingFileIds = listOf("7"),
        webSeeds = 1)
    }
    val stall = assertNotNull(watchdog.stall)
    assertEquals(TorrentStallReason.MISSING_PIECES, stall.reason)
    assertEquals(2, stall.missingPieces)
    assertEquals(listOf("7"), stall.missingFileIds)
  }

  @Test
  fun lastSourceLeaving_keepsWhatWasMissingAWhile() {
    runTo(31_000) { signals(peers = 1, missingPieces = 2, missingFileIds = listOf("7")) }
    assertEquals(TorrentStallReason.MISSING_PIECES, watchdog.stall?.reason)
    // The one peer drops and reconnects: what it lacked is still missing meanwhile.
    runTo(34_000) { signals() }
    val stall = assertNotNull(watchdog.stall)
    assertEquals(TorrentStallReason.MISSING_PIECES, stall.reason)
    assertEquals(2, stall.missingPieces)
    assertEquals(listOf("7"), stall.missingFileIds)
    assertEquals(0, stall.peers)
    // Gone for longer, the download waits for peers.
    runTo(45_000) { signals() }
    assertEquals(TorrentStallReason.NO_PEERS, watchdog.stall?.reason)
  }

  @Test
  fun webSeedsStoringNothing_leaveTheReasonToPeers() {
    // A web seed backs off from a failing mirror, and no peer is connected.
    runTo(31_000) { signals(webSeeds = 1) }
    assertEquals(TorrentStallReason.NO_PEERS, watchdog.stall?.reason)
    // Peers that all choke us.
    runTo(32_000) { signals(peers = 2, webSeeds = 1) }
    assertEquals(TorrentStallReason.CHOKED, watchdog.stall?.reason)
  }

  @Test
  fun relaunchedWebSeeds_leaveWhatIsMissingToTheSwarm() {
    // Every web seed found two pieces it could not serve, and was dropped.
    val due = runTo(121_000) {
      signals(missingPieces = 2, missingFileIds = listOf("7"), droppedWebSeeds = 1)
    }
    assertTrue(StallRecovery.RELAUNCH_WEB_SEEDS in due)
    // Started again, a seed counts as stopped in the swarm until it answers: what the swarm says
    // stands at once, here a seed that answered with every piece it was asked for.
    runTo(122_000) { signals(webSeeds = 1) }
    val stall = assertNotNull(watchdog.stall)
    assertEquals(TorrentStallReason.NO_PEERS, stall.reason)
    assertEquals(0, stall.missingPieces)
    assertEquals(emptyList(), stall.missingFileIds)
  }

  @Test
  fun waitingOnSpeedLimits_isNoStall() {
    // Payload trickles in, every second of it spent waiting on a speed limit.
    runTo(600_000) { time -> signals(payloadBytes = time / 100, throttledMs = time, peers = 1) }
    assertNull(watchdog.stall)
  }

  @Test
  fun manyPeersWaitingOnLimits_countNoMoreThanTheTimeThatPassed() {
    // Twenty peers wait on a limit side by side, adding twenty seconds of waits each second.
    runTo(600_000) { time ->
      signals(payloadBytes = time / 100, throttledMs = time * 20, peers = 20)
    }
    assertNull(watchdog.stall)
    // The limit is lifted and the peers leave: a stall half a minute later, not hours.
    val lifted = now
    runTo(lifted + 31_000) { signals(payloadBytes = lifted / 100, throttledMs = lifted * 20) }
    assertEquals(TorrentStallReason.NO_PEERS, watchdog.stall?.reason)
  }

  @Test
  fun trickleWithoutAPiece_stallsOnceFourPiecesAtSixteenKibAreDue() {
    // 1 KiB a second and no speed limit: four 256 KiB pieces at 16 KiB/s take 64 s.
    runTo(63_000) { time -> signals(payloadBytes = time, peers = 1, unchokingPeers = 1) }
    assertNull(watchdog.stall)
    runTo(65_000) { time -> signals(payloadBytes = time, peers = 1, unchokingPeers = 1) }
    assertEquals(TorrentStallReason.UNKNOWN, watchdog.stall?.reason)
  }

  @Test
  fun recoveries_waitAMinuteThenKeepTheirCooldowns() {
    val early = runTo(59_000) { signals(droppedWebSeeds = 1) }
    assertTrue(early.isEmpty())
    val first = runTo(61_000) { signals(droppedWebSeeds = 1) }
    assertEquals(setOf(StallRecovery.REANNOUNCE, StallRecovery.REVIVE_PEERS), first)
    // Dropped web seeds start again once the stall has lasted two minutes; with a peer connected,
    // peers are revived only every five.
    assertEquals(setOf(StallRecovery.RELAUNCH_WEB_SEEDS),
      runTo(121_000) { signals(peers = 1, droppedWebSeeds = 1) })
    // The next reannounce waits five minutes, the one after it ten.
    val reannounces = mutableListOf<Long>()
    while (now < 1_000_000) {
      now += 1_000
      if (StallRecovery.REANNOUNCE in watchdog.check(signals())) reannounces += now
    }
    assertEquals(listOf(360_000L, 960_000L), reannounces)
  }

  @Test
  fun withoutPeers_peersAreRevivedEveryMinute() {
    val revives = mutableListOf<Long>()
    while (now < 600_000) {
      now += 1_000
      // No peer for five minutes, then two that choke us.
      val peers = if (now < 300_000) 0 else 2
      if (StallRecovery.REVIVE_PEERS in watchdog.check(signals(peers = peers))) revives += now
    }
    assertEquals(listOf(60_000L, 120_000L, 180_000L, 240_000L, 540_000L), revives)
  }

  @Test
  fun slowPeers_recoverWithoutReannouncing() {
    assertTrue(runTo(300_000) { signals(peers = 2, unchokingPeers = 2, lateBlocks = 3) }
      .isEmpty())
    assertEquals(TorrentStallReason.SLOW_PEERS, watchdog.stall?.reason)
  }

  @Test
  fun notDownloading_isNeverAStall() {
    runTo(120_000) { signals(downloading = false) }
    assertNull(watchdog.stall)
  }

  @Test
  fun stallReason_unknownOnTheWireDecodesAsUnknown() {
    val stall = Json.decodeFromString<TorrentStall>(
      """{"reason":"aliens","seconds":12,"peers":1}""")
    assertEquals(TorrentStallReason.UNKNOWN, stall.reason)
    assertEquals(""""missing-pieces"""",
      Json.encodeToString(TorrentStallReason.serializer(), TorrentStallReason.MISSING_PIECES))
  }
}
