package com.linroid.ketch.torrent

import kotlinx.coroutines.TimeoutCancellationException

/** BEP 11: the peer prefers encrypted connections (MSE/PE). */
internal const val PEX_FLAG_ENCRYPTION = 0x01

/** BEP 11: the peer supports uTP. */
internal const val PEX_FLAG_UTP = 0x04

/** How far one peer connection got: dialing, then the BitTorrent handshake, then connected. */
internal enum class PeerStage { DIALING, HANDSHAKING, CONNECTED }

/**
 * The stage of one connection and the payload it brought, written by one owner at a time: its
 * worker, then (in a v2 session) the loop once the peer attached.
 */
internal class PeerAttempt(
  incoming: Boolean,
  /** The handshake the connection offered or answered; `null` while unknown (incoming). */
  var mode: PeerIdentityHandshake.Mode? = null,
) {
  var stage: PeerStage = if (incoming) PeerStage.HANDSHAKING else PeerStage.DIALING
  var payload: Long = 0
  /** The connection negotiated protocol encryption. */
  var encrypted: Boolean = false
  /** The connection runs over uTP. */
  var utp: Boolean = false
}

/**
 * How a peer connection ended, in order of how far it got, so an endpoint keeps its best one.
 * [HANDSHAKE_CLOSED] is a peer that accepted the connection and closed it on our plaintext
 * handshake, as peers requiring encryption do.
 */
internal enum class PeerOutcome(val label: String) {
  CONNECT_FAILED("connectFailed"),
  UNREACHABLE("unreachable"),
  CONNECT_TIMEOUT("connectTimeout"),
  REFUSED("refused"),
  HANDSHAKE_REJECTED("handshakeRejected"),
  HANDSHAKE_TIMEOUT("handshakeTimeout"),
  HANDSHAKE_CLOSED("handshakeClosed"),
  CONNECTED("connected"),
  RECEIVED("received"),
  ;

  /** The connection never completed the BitTorrent handshake. */
  val failed: Boolean get() = this < CONNECTED
}

/** One connection's outcome, as the swarm records it. */
internal data class PeerOutcomeReport(
  val endpoint: PeerEndpoint,
  val outcome: PeerOutcome,
  val incoming: Boolean,
  /** The BEP 11 flags other peers advertised for [endpoint], 0 when none did. */
  val flags: Int,
  /** The connection negotiated protocol encryption. */
  val encrypted: Boolean = false,
  /** The connection ran over uTP. */
  val utp: Boolean = false,
  /** The handshake it ran, v1 or v2 (a hybrid's v1-swarm peers are v1); `null` when unknown. */
  val mode: PeerIdentityHandshake.Mode? = null,
)

/**
 * A connection that failed before its handshake completed, outside the loop that owns the
 * diagnostics: a v2 session's responders queue them for its loop.
 */
internal class PeerAttemptFailure(
  val endpoint: PeerEndpoint,
  val attempt: PeerAttempt,
  val failure: Throwable,
)

/**
 * Classifies how a connection ended, or `null` when the swarm stopped it before it ended on its
 * own. Socket errors differ by platform, so their kind is read from the names and messages of
 * the cause chain.
 */
internal fun classifyPeerOutcome(attempt: PeerAttempt, failure: Throwable?): PeerOutcome? {
  if (attempt.stage == PeerStage.CONNECTED) {
    return if (attempt.payload > 0) PeerOutcome.RECEIVED else PeerOutcome.CONNECTED
  }
  if (failure == null) return null
  val timeout = failure is TimeoutCancellationException || failure.mentions("timeout", "timed out")
  return when (attempt.stage) {
    PeerStage.DIALING -> when {
      timeout -> PeerOutcome.CONNECT_TIMEOUT
      failure.mentions("refused") -> PeerOutcome.REFUSED
      failure.mentions("unreachable", "no route") -> PeerOutcome.UNREACHABLE
      else -> PeerOutcome.CONNECT_FAILED
    }
    else -> when {
      failure is IllegalArgumentException -> PeerOutcome.HANDSHAKE_REJECTED
      timeout -> PeerOutcome.HANDSHAKE_TIMEOUT
      else -> PeerOutcome.HANDSHAKE_CLOSED
    }
  }
}

private fun Throwable.mentions(vararg words: String): Boolean =
  generateSequence(this) { it.cause }.take(8).any { error ->
    val text = "${error::class.simpleName} ${error.message}".lowercase()
    words.any { it in text }
  }

/**
 * Each endpoint's best outcome and the flags advertised for it, owned by a v1 swarm's loop or a
 * v2 session's loop (`TorrentSwarm`, `TorrentV2SessionLoop`), never shared between threads. It
 * explains a swarm that finds peers but cannot use them: refused or timed out dials point at
 * peers behind NAT, closed handshakes at peers requiring encryption, and the uTP flag at peers
 * reachable another way.
 */
internal class PeerDiagnostics(
  private val capacity: Int = 4096,
  private val listener: (PeerOutcomeReport) -> Unit = {},
) {
  private val dialed = LinkedHashMap<PeerEndpoint, Pair<PeerOutcome, Int>>()
  private val incoming = IntArray(PeerOutcome.entries.size)
  /** Connections, either way, that completed the BitTorrent handshake encrypted. */
  private var encrypted = 0
  /** Connections, either way, that completed the BitTorrent handshake over uTP. */
  private var overUtp = 0
  /** Connections recorded so far, either way. */
  var recorded: Int = 0
    private set

  fun record(endpoint: PeerEndpoint, attempt: PeerAttempt, failure: Throwable?, incoming: Boolean,
    flags: Int) {
    val outcome = classifyPeerOutcome(attempt, failure) ?: return
    recorded++
    listener(PeerOutcomeReport(endpoint, outcome, incoming, flags, attempt.encrypted,
      attempt.utp, attempt.mode))
    if (!outcome.failed && attempt.encrypted) encrypted++
    if (!outcome.failed && attempt.utp) overUtp++
    if (incoming) {
      this.incoming[outcome.ordinal]++
      return
    }
    val previous = dialed.remove(endpoint)
    val best = if (previous != null && previous.first > outcome) previous.first else outcome
    dialed[endpoint] = best to (flags or (previous?.second ?: 0))
    if (dialed.size > capacity) dialed.remove(dialed.keys.first())
  }

  /** Dialed endpoints by best outcome, then incoming connections and the advertised flags. */
  fun summary(): String {
    val counts = IntArray(PeerOutcome.entries.size)
    var utp = 0
    var encryption = 0
    var failedUtp = 0
    var failedEncryption = 0
    for ((outcome, flags) in dialed.values) {
      counts[outcome.ordinal]++
      val prefersUtp = flags and PEX_FLAG_UTP != 0
      val prefersEncryption = flags and PEX_FLAG_ENCRYPTION != 0
      if (prefersUtp) utp++
      if (prefersEncryption) encryption++
      if (outcome.failed && prefersUtp) failedUtp++
      if (outcome.failed && prefersEncryption) failedEncryption++
    }
    fun table(values: IntArray) = PeerOutcome.entries.filter { values[it.ordinal] > 0 }
      .joinToString(" ") { "${it.label}=${values[it.ordinal]}" }.ifEmpty { "none" }
    return "dialed ${dialed.size} [${table(counts)}], incoming [${table(incoming)}], " +
      "encrypted $encrypted, " +
      "utp $overUtp, " +
      "pex flags utp=$utp (failed $failedUtp) encryption=$encryption (failed $failedEncryption)"
  }
}
