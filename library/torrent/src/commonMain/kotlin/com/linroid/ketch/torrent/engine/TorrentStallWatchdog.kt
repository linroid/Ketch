package com.linroid.ketch.torrent.engine

import com.linroid.ketch.torrent.api.TorrentStall
import com.linroid.ketch.torrent.api.TorrentStallReason

/**
 * What a swarm knows when its [TorrentStallWatchdog] checks it.
 *
 * @property downloading pieces are wanted and the swarm fetches them; only then can it stall.
 * @property peers peers connected now.
 * @property unchokingPeers connected peers that let us download.
 * @property missingPieces wanted pieces that no connected peer or running web seed has; with
 *   nothing connected, those a web seed found it could not serve, as the rest wait for peers.
 * @property missingFileIds the files those pieces belong to.
 * @property lateBlocks requested blocks their peer is late with.
 * @property webSeeds web seeds running now.
 * @property droppedWebSeeds web seeds that stopped and would have a wanted piece to fetch,
 *   which [StallRecovery.RELAUNCH_WEB_SEEDS] starts again.
 * @property payloadBytes payload received so far, from peers and web seeds.
 * @property wastedBytes of that payload, what was thrown away so far: web seed bytes of pieces
 *   that failed their hash check or that a failed request cut off.
 * @property throttledMs time spent so far waiting on speed limits, which is never a stall: the
 *   waits of every peer added up, of which a check counts at most the time since the last.
 * @property pieceLength the torrent's piece length.
 */
internal data class StallSignals(
  val downloading: Boolean,
  val peers: Int,
  val unchokingPeers: Int,
  val missingPieces: Int,
  val missingFileIds: List<String>,
  val lateBlocks: Int,
  val webSeeds: Int,
  val droppedWebSeeds: Int,
  val payloadBytes: Long,
  val wastedBytes: Long,
  val throttledMs: Long,
  val pieceLength: Long,
)

/** What a stalled swarm does to recover, each when [TorrentStallWatchdog.check] says. */
internal enum class StallRecovery {
  /** Announces to the torrent's trackers now rather than when they asked. */
  REANNOUNCE,

  /** Dials again the peers it gave up on or waits to retry. */
  REVIVE_PEERS,

  /** Starts the web seeds it dropped again. */
  RELAUNCH_WEB_SEEDS,
}

/**
 * Notices a download that stopped making progress, at any stage, says why ([stall]) and which
 * recoveries are due. A download stalls when no payload arrived for [NO_PAYLOAD_MS], or no piece
 * verified for four pieces' worth of time at its rate (at least [MIN_PIECE_MS], at most
 * [MAX_PIECE_MS]), counted from the last verified piece or from payload coming back after a gap,
 * as the gap was no slow piece. Payload later thrown away is no payload: it takes back the checks
 * that counted it, newest first. Time spent waiting on speed limits counts as progress: a slow
 * limit is no stall. Recoveries start once it has lasted [RECOVER_AFTER_MS], each at most once
 * per its own cooldown, announcing to trackers less often each time it does not help. Pieces
 * missing while a peer or web seed was connected stay missing for [SOURCE_GRACE_MS] once the last
 * one goes, so a peer that reconnects at once does not flip the reason.
 *
 * Not thread-safe: one swarm loop calls it.
 */
internal class TorrentStallWatchdog(private val nowMs: () -> Long) {
  private var progressAt = nowMs()
  // When the time for a piece to verify started: [progressAt], or payload back after a gap.
  private var pieceAt = progressAt
  private var payloadAt = progressAt
  private var payloadBytes = 0L
  private var wastedBytes = 0L
  private var throttledMs = 0L
  // The checks that took payload lately, oldest first: payload found wasted takes them back.
  private val credits = ArrayDeque<Credit>()
  // Payload rate over the last check, smoothed: how long a piece should take.
  private var rate = 0.0
  private var checkedAt = progressAt
  private var reannounceAt = Long.MIN_VALUE
  private var reannounceEveryMs = REANNOUNCE_MS
  private var reviveAt = Long.MIN_VALUE
  private var relaunchAt = Long.MIN_VALUE
  // When a peer or web seed was last connected, and what was missing then.
  private var sourcedAt = Long.MIN_VALUE / 2
  private var sourcedMissing = 0
  private var sourcedFiles = emptyList<String>()

  /** [bytes] of payload a check took, and the clocks as they were before it did. */
  private class Credit(var bytes: Long, var payloadAt: Long, var pieceAt: Long)

  /** Why the download makes no progress now; null while it does. */
  var stall: TorrentStall? = null
    private set

  /** A piece verified, or the download (re)started or changed its selection. */
  fun progressed() {
    progressAt = nowMs()
    pieceAt = progressAt
    payloadAt = progressAt
    stall = null
    reannounceEveryMs = REANNOUNCE_MS
    // Payload before a verified piece is never taken back.
    credits.clear()
  }

  /** Checks the swarm, about once a second; returns the recoveries due now. */
  fun check(signals: StallSignals): Set<StallRecovery> {
    val now = nowMs()
    val elapsed = (now - checkedAt).coerceAtLeast(1)
    checkedAt = now
    val received = (signals.payloadBytes - payloadBytes).coerceAtLeast(0)
    payloadBytes = signals.payloadBytes
    val wasted = (signals.wastedBytes - wastedBytes).coerceAtLeast(0)
    wastedBytes = signals.wastedBytes
    // Peers wait on the limits side by side, each adding its own wait: at most the time that
    // passed counts.
    val throttled = (signals.throttledMs - throttledMs).coerceIn(0, elapsed)
    throttledMs = signals.throttledMs
    rate = (rate + (received - wasted).coerceAtLeast(0) * 1000.0 / elapsed) / 2
    if (throttled > 0) waited(throttled, now)
    if (received > 0) took(received, now)
    if (wasted > 0) takeBack(wasted)
    var missing = signals.missingPieces
    var missingFiles = signals.missingFileIds
    if (signals.peers > 0 || signals.webSeeds > 0) {
      sourcedAt = now
      sourcedMissing = missing
      sourcedFiles = missingFiles
    } else if (missing == 0 && now - sourcedAt < SOURCE_GRACE_MS) {
      missing = sourcedMissing
      missingFiles = sourcedFiles
    }
    if (!signals.downloading) {
      progressed()
      return emptySet()
    }
    val pieceMs = (4_000.0 * signals.pieceLength / maxOf(rate, MIN_RATE.toDouble())).toLong()
      .coerceIn(MIN_PIECE_MS, MAX_PIECE_MS)
    val stalled = now - payloadAt >= NO_PAYLOAD_MS || now - pieceAt >= pieceMs
    if (!stalled) {
      stall = null
      return emptySet()
    }
    val reason = when {
      missing > 0 -> TorrentStallReason.MISSING_PIECES
      signals.peers == 0 && signals.webSeeds == 0 -> TorrentStallReason.NO_PEERS
      signals.unchokingPeers == 0 && signals.webSeeds == 0 -> TorrentStallReason.CHOKED
      signals.lateBlocks > 0 -> TorrentStallReason.SLOW_PEERS
      else -> TorrentStallReason.UNKNOWN
    }
    val lasted = (now - progressAt).coerceAtLeast(0)
    stall = TorrentStall(
      reason = reason,
      seconds = lasted / 1000,
      missingPieces = missing,
      missingFileIds = missingFiles.take(TorrentStall.MAX_MISSING_FILES),
      peers = signals.peers,
      unchokingPeers = signals.unchokingPeers.coerceIn(0, signals.peers),
    )
    if (lasted < RECOVER_AFTER_MS) return emptySet()
    val due = mutableSetOf<StallRecovery>()
    // Slow peers recover on their own: other peers take over what they are late with.
    if (reason != TorrentStallReason.SLOW_PEERS) {
      if (now >= reannounceAt) {
        due += StallRecovery.REANNOUNCE
        reannounceAt = now + reannounceEveryMs
        reannounceEveryMs = minOf(reannounceEveryMs * 2, MAX_REANNOUNCE_MS)
      }
      if (now >= reviveAt) {
        due += StallRecovery.REVIVE_PEERS
        reviveAt = now + REVIVE_MS
      }
    }
    if (signals.droppedWebSeeds > 0 && lasted >= RELAUNCH_AFTER_MS && now >= relaunchAt) {
      due += StallRecovery.RELAUNCH_WEB_SEEDS
      relaunchAt = now + RELAUNCH_MS
    }
    return due
  }

  /** Time spent waiting on speed limits counts as progress, also for the checks taken back. */
  private fun waited(throttled: Long, now: Long) {
    progressAt = minOf(progressAt + throttled, now)
    pieceAt = minOf(pieceAt + throttled, now)
    payloadAt = minOf(payloadAt + throttled, now)
    for (credit in credits) {
      credit.payloadAt = minOf(credit.payloadAt + throttled, now)
      credit.pieceAt = minOf(credit.pieceAt + throttled, now)
    }
  }

  /** [bytes] of payload arrived; back after a gap, a piece has its full time again. */
  private fun took(bytes: Long, now: Long) {
    credits.addLast(Credit(bytes, payloadAt, pieceAt))
    if (credits.size > MAX_CREDITS) credits.removeFirst()
    if (now - payloadAt >= NO_PAYLOAD_MS) pieceAt = now
    payloadAt = now
  }

  /** [bytes] of payload were thrown away: they were the latest, so the newest checks go back. */
  private fun takeBack(bytes: Long) {
    var left = bytes
    while (left > 0) {
      val credit = credits.lastOrNull() ?: return
      if (credit.bytes > left) {
        credit.bytes -= left
        return
      }
      left -= credit.bytes
      credits.removeLast()
      payloadAt = credit.payloadAt
      pieceAt = credit.pieceAt
    }
  }

  companion object {
    /** No payload for this long is a stall. */
    const val NO_PAYLOAD_MS = 30_000L

    /** The least and most time without a verified piece before it is a stall. */
    const val MIN_PIECE_MS = 60_000L
    const val MAX_PIECE_MS = 600_000L

    /** The rate a piece's time assumes at the least, in bytes a second. */
    private const val MIN_RATE = 16_384L

    /** How long a stall lasts before the swarm tries to recover. */
    const val RECOVER_AFTER_MS = 60_000L

    /** The first time between reannounces, doubled each time to [MAX_REANNOUNCE_MS]. */
    const val REANNOUNCE_MS = 300_000L
    private const val MAX_REANNOUNCE_MS = 1_800_000L

    /** How often peers given up on are dialed again. */
    const val REVIVE_MS = 300_000L

    /** How long a stall lasts before dropped web seeds start again, and how often they do. */
    const val RELAUNCH_AFTER_MS = 120_000L
    const val RELAUNCH_MS = 600_000L

    /** How long pieces missing while something was connected stay missing once nothing is. */
    const val SOURCE_GRACE_MS = 10_000L

    /** The most checks that took payload kept to be taken back, about a minute of them. */
    private const val MAX_CREDITS = 64
  }
}

/** One line for logs: the reason, how long, what is missing and the peers. */
internal fun TorrentStall.describe(): String =
  "${reason.wireName} for ${seconds}s, missingPieces=$missingPieces" +
    (if (missingFileIds.isEmpty()) "" else " (files ${missingFileIds.joinToString()})") +
    ", peers=$peers unchoking=$unchokingPeers"
