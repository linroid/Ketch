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
    webSeeds, droppedWebSeeds, payloadBytes, throttledMs, pieceLength = 262_144)

  /** Checks once a second up to [until], as a swarm does. */
  private fun runTo(until: Long, signals: (Long) -> StallSignals): Set<StallRecovery> {
    val due = mutableSetOf<StallRecovery>()
    while (now < until) {
      now += 1_000
      due += watchdog.check(signals(now))
    }
    return due
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
    // Payload alone does not end it: a piece has to verify.
    runTo(95_000) { time -> signals(payloadBytes = time, peers = 1, unchokingPeers = 1) }
    assertNotNull(watchdog.stall)
    watchdog.progressed()
    assertNull(watchdog.stall)
    // Stalled again: its time counts from the piece that ended the last one.
    runTo(130_000) { signals(payloadBytes = 95_000) }
    assertEquals(35, assertNotNull(watchdog.stall).seconds)
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
  fun waitingOnSpeedLimits_isNoStall() {
    // Payload trickles in, every second of it spent waiting on a speed limit.
    runTo(600_000) { time -> signals(payloadBytes = time / 100, throttledMs = time, peers = 1) }
    assertNull(watchdog.stall)
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
    // Dropped web seeds start again once the stall has lasted two minutes.
    assertEquals(setOf(StallRecovery.RELAUNCH_WEB_SEEDS),
      runTo(121_000) { signals(droppedWebSeeds = 1) })
    // The next reannounce waits five minutes, the one after it ten.
    val reannounces = mutableListOf<Long>()
    while (now < 1_000_000) {
      now += 1_000
      if (StallRecovery.REANNOUNCE in watchdog.check(signals())) reannounces += now
    }
    assertEquals(listOf(360_000L, 960_000L), reannounces)
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
