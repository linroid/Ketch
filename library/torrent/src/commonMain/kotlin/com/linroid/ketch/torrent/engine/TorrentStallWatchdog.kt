package com.linroid.ketch.torrent.engine

import com.linroid.ketch.torrent.api.TorrentStall
import com.linroid.ketch.torrent.api.TorrentStallReason
import com.linroid.ketch.torrent.webseed.WebSeedLedger

/**
 * What a swarm knows when its [TorrentStallWatchdog] checks it.
 *
 * @property downloading pieces are wanted and the swarm fetches them; only then can it stall.
 * @property peers peers connected now.
 * @property unchokingPeers connected peers that let us download.
 * @property missingPieces wanted pieces that no connected peer or running web seed has and every
 *   web seed that stopped found it could not serve, as the rest wait for a seed started again;
 *   none with nothing connected and no such seed, as they wait for peers. A seed started again
 *   counts as stopped, by what it could not serve before, until it answers (sends payload, or
 *   verifies or refuses a piece), as it offers every piece meanwhile. A seed dropped again once
 *   started again, without answering in between, is left out until a later start finds it
 *   answering: its mirror is down, which says nothing of what it serves.
 * @property missingFileIds the files those pieces belong to.
 * @property lateBlocks requested blocks their peer is late with.
 * @property webSeeds web seeds running now, fetching or not, but those started again that have yet
 *   to answer, which count as stopped.
 * @property droppedWebSeeds web seeds that stopped, which [StallRecovery.RELAUNCH_WEB_SEEDS] starts
 *   again.
 * @property peerPayloadBytes payload received so far from peers, which checks never take back.
 * @property webSeedPayload web seed payload since the check before
 *   ([com.linroid.ketch.torrent.webseed.WebSeedStats.checked], which also marks this check): what
 *   arrived since, and what was stored or thrown away of what arrived before, by the check that
 *   counted it.
 * @property throttledMs time so far during which payload waited on speed limits, which is never
 *   a stall ([ThrottleClock]): waits that overlap count once, and a wait counts as it passes.
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
  val peerPayloadBytes: Long,
  val webSeedPayload: WebSeedLedger,
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
 * as the gap was no slow piece, once some of that payload is kept: at once from peers, once its
 * piece is stored from web seeds. It does so once until the next piece verifies, so payload that
 * comes and goes never keeps a stall from showing. Payload later thrown away is no payload: web
 * seeds tell each check what it counted ([StallSignals.webSeedPayload]), so the bytes of a piece
 * thrown away are taken back from the checks that counted them and no other. But a gap is told
 * from when any payload last arrived, so payload thrown away never makes the next look back after
 * one. Time spent waiting on speed limits counts as progress, as it passes, also for payload
 * later thrown away ([waited]): a slow limit is no stall.
 * Recoveries start once it has lasted [RECOVER_AFTER_MS], each at most once per its own cooldown,
 * announcing to trackers less often each time it does not help. Pieces missing while a peer or
 * web seed was connected stay missing for [SOURCE_GRACE_MS] once the last one goes, so a peer that
 * reconnects at once does not flip the reason. Web seeds never decide the reason otherwise: a
 * stall stores nothing from them.
 *
 * Not thread-safe: one swarm loop calls it.
 */
internal class TorrentStallWatchdog(private val nowMs: () -> Long) {
  private var progressAt = nowMs()
  // When the time for a piece to verify started: [progressAt], or payload back after a gap.
  private var pieceAt = progressAt
  // When payload nothing can throw away any more last arrived: from peers, or stored.
  private var keptAt = progressAt
  // When payload last arrived, thrown away or not: it never moves back.
  private var seenAt = progressAt
  // Whether payload back after a gap gave a piece its time again since the last verified one.
  private var restarted = false
  private var peerPayloadBytes = 0L
  private var throttledMs = 0L
  // Web seed payload checks counted that may still be thrown away, oldest first.
  private val credits = ArrayDeque<Credit>()
  // Payload rate over the last check, smoothed: how long a piece should take.
  private var rate = 0.0
  private var checkedAt = progressAt
  private var reannounceAt = Long.MIN_VALUE
  private var reannounceEveryMs = REANNOUNCE_MS
  private var revivedAt: Long? = null
  private var relaunchAt = Long.MIN_VALUE
  // When a peer or web seed was last connected, and what was missing then.
  private var sourcedAt = Long.MIN_VALUE / 2
  private var sourcedMissing = 0
  private var sourcedFiles = emptyList<String>()

  /**
   * Web seed payload a check counted, [at], of which [atRisk] bytes may still be thrown away:
   * those that arrived after [last] checks, and after [first] to [last] once older credits merge
   * into it. Back after a gap, it gives a piece its time again from [restartAt] once some is kept.
   */
  private class Credit(
    var first: Long,
    val last: Long,
    var at: Long,
    var atRisk: Long,
    var restartAt: Long?,
  )

  /** When payload that was not thrown away last arrived: kept, or what may still be. */
  private val payloadAt: Long get() = maxOf(keptAt, credits.lastOrNull()?.at ?: keptAt)

  /** Why the download makes no progress now; null while it does. */
  var stall: TorrentStall? = null
    private set

  /** A piece verified, or the download (re)started or changed its selection. */
  fun progressed() {
    progressAt = nowMs()
    pieceAt = progressAt
    keptAt = progressAt
    seenAt = progressAt
    restarted = false
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
    val fromPeers = (signals.peerPayloadBytes - peerPayloadBytes).coerceAtLeast(0)
    peerPayloadBytes = signals.peerPayloadBytes
    val seeds = signals.webSeedPayload
    val received = fromPeers + seeds.arrived
    val wasted = seeds.wasted.values.sum()
    // Waits count as they pass, so this is the time since the last check that something waited;
    // never more than the time that passed.
    val throttled = (signals.throttledMs - throttledMs).coerceIn(0, elapsed)
    throttledMs = signals.throttledMs
    rate = (rate + (received - wasted).coerceAtLeast(0) * 1000.0 / elapsed) / 2
    if (throttled > 0) waited(throttled, now)
    // Web seed payload earlier checks counted that was since stored or thrown away settles them.
    for (checks in seeds.kept.keys + seeds.wasted.keys) {
      if (checks < seeds.checks) settle(checks, seeds.kept[checks] ?: 0, seeds.wasted[checks] ?: 0)
    }
    took(fromPeers, seeds, now)
    if (received > 0) seenAt = now
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
    // A stalled download stores nothing from its web seeds either, whether they fetch, back off
    // or wait, so only the pieces they cannot serve have a say in why.
    val reason = when {
      missing > 0 -> TorrentStallReason.MISSING_PIECES
      signals.peers == 0 -> TorrentStallReason.NO_PEERS
      signals.unchokingPeers == 0 -> TorrentStallReason.CHOKED
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
      // Without a single peer, nothing is lost by dialing every known one again soon.
      val reviveEvery = if (signals.peers == 0) LONELY_REVIVE_MS else REVIVE_MS
      if (revivedAt.let { it == null || now - it >= reviveEvery }) {
        due += StallRecovery.REVIVE_PEERS
        revivedAt = now
      }
    }
    if (signals.droppedWebSeeds > 0 && lasted >= RELAUNCH_AFTER_MS && now >= relaunchAt) {
      due += StallRecovery.RELAUNCH_WEB_SEEDS
      relaunchAt = now + RELAUNCH_MS
    }
    return due
  }

  /**
   * Time spent waiting on speed limits counts as progress, also for the checks taken back. It
   * counts as it passes, before anything shows whether what waits is kept: a web seed's bytes
   * wait once they arrived, a v2 peer's requests before they are sent. It is not taken back when
   * that payload is thrown away or never comes: waits overlap, a peer's among them, and these
   * times moved since, so nothing says how much to take back, and too much would show a slow
   * limit as a stall again. So a web seed sending only corrupt pieces under a limit that holds
   * each for T shows a stall once it is dropped after
   * [MAX_FAILURES][com.linroid.ketch.torrent.webseed.WebSeedWorker.MAX_FAILURES] runs, about 5 T,
   * and within [NO_PAYLOAD_MS] of that: some 7.5 minutes for 256 KiB pieces at 3 KiB/s, though
   * its first corrupt piece is known within 1.5. While a seed started again waits, the stall does
   * not age. A v2 download whose peer stops answering stalls once no request waits: once the peer
   * is dropped for answering none of its requests for a minute, or sooner when nothing is left to
   * ask it. A v2 peer's requests count as waiting until it rejected a piece's worth of blocks since
   * the last one it delivered.
   */
  private fun waited(throttled: Long, now: Long) {
    progressAt = minOf(progressAt + throttled, now)
    pieceAt = minOf(pieceAt + throttled, now)
    keptAt = minOf(keptAt + throttled, now)
    seenAt = minOf(seenAt + throttled, now)
    for (credit in credits) {
      credit.at = minOf(credit.at + throttled, now)
      credit.restartAt = credit.restartAt?.let { minOf(it + throttled, now) }
    }
  }

  /**
   * This check's payload: [fromPeers] bytes, kept now, and the bytes web seeds sent since the check
   * before ([WebSeedLedger.arrived]) but those they threw away meanwhile, kept once stored. Back
   * after a gap, a piece gets its full time again from now once some of it is kept.
   */
  private fun took(fromPeers: Long, seeds: WebSeedLedger, now: Long) {
    val payload = fromPeers + seeds.arrived - (seeds.wasted[seeds.checks] ?: 0)
    if (payload <= 0) return
    val restartAt = if (!restarted && now - seenAt >= NO_PAYLOAD_MS) now else null
    val atRisk = payload - fromPeers - (seeds.kept[seeds.checks] ?: 0)
    if (atRisk > 0) {
      credits.addLast(Credit(seeds.checks, seeds.checks, now, atRisk, restartAt))
      if (credits.size > MAX_CREDITS) {
        // The oldest two become one, at the later one's time.
        val oldest = credits.removeFirst()
        val next = credits.first()
        next.first = oldest.first
        next.atRisk += oldest.atRisk
        next.restartAt = oldest.restartAt ?: next.restartAt
      }
    }
    if (atRisk < payload) kept(now, restartAt)
  }

  /**
   * Of the web seed payload that arrived after [checks] checks, [kept] bytes were since stored and
   * [wasted] thrown away. The check that counted them keeps what was stored and is no payload any
   * more once all of the rest was thrown away. Payload from before a verified piece has no check
   * left to settle.
   */
  private fun settle(checks: Long, kept: Long, wasted: Long) {
    val credit = credits.lastOrNull { checks in it.first..it.last } ?: return
    credit.atRisk -= kept + wasted
    if (credit.atRisk <= 0) credits.remove(credit)
    if (kept > 0) kept(credit.at, credit.restartAt)
  }

  /**
   * Payload that arrived [at] is kept: back after a gap from [restartAt], a piece gets its full
   * time again, the first time since the last verified piece.
   */
  private fun kept(at: Long, restartAt: Long?) {
    keptAt = maxOf(keptAt, at)
    if (restartAt == null || restarted) return
    restarted = true
    pieceAt = maxOf(pieceAt, restartAt)
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

    /** How often peers given up on are dialed again; every [LONELY_REVIVE_MS] without a peer. */
    const val REVIVE_MS = 300_000L
    const val LONELY_REVIVE_MS = 60_000L

    /** How long a stall lasts before dropped web seeds start again, and how often they do. */
    const val RELAUNCH_AFTER_MS = 120_000L
    const val RELAUNCH_MS = 600_000L

    /** How long pieces missing while something was connected stay missing once nothing is. */
    const val SOURCE_GRACE_MS = 10_000L

    /**
     * The most checks whose payload may still be thrown away kept apart, the longest a piece may
     * take ([MAX_PIECE_MS]) at one check a second; older ones merge.
     */
    private const val MAX_CREDITS = 600
  }
}

/** One line for logs: the reason, how long, what is missing and the peers. */
internal fun TorrentStall.describe(): String =
  "${reason.wireName} for ${seconds}s, missingPieces=$missingPieces" +
    (if (missingFileIds.isEmpty()) "" else " (files ${missingFileIds.joinToString()})") +
    ", peers=$peers unchoking=$unchokingPeers"
