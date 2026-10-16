package com.linroid.ketch.torrent.v2.peer

import com.linroid.ketch.torrent.engine.TorrentBufferBudget
import com.linroid.ketch.torrent.metainfo.TorrentContentLayout
import com.linroid.ketch.torrent.monotonicClock
import com.linroid.ketch.torrent.peer.FAST_REJECT_GRACE_MS
import com.linroid.ketch.torrent.peer.PeerIdentityHandshake
import com.linroid.ketch.torrent.peer.PeerMessage
import com.linroid.ketch.torrent.peer.PeerProtocolState
import com.linroid.ketch.torrent.peer.PeerWire
import com.linroid.ketch.torrent.peer.isFastExtension
import kotlinx.coroutines.withTimeout

/**
 * One actor owns this v2 download pipeline and its transport. The session admits availability
 * state before construction; pending/delivered blocks retain their own buffer reservations.
 * A separate reader hands frames to receive. The actor must schedule expire at nextDeadlineMs.
 *
 * A peer that serves slowly is kept: its requests expire only once it answered none of them for
 * [timeoutMs], or one of them has waited [MAX_AGE_TIMEOUTS] times that long, and the scheduler
 * asks other peers for what it is late with meanwhile. A request we [cancel] is forgotten once
 * the CANCEL is written, so a late block or reject for it is ignored, never a violation.
 *
 * Without the Fast extension (BEP 6) a CHOKE silently discards every outstanding request (BEP 3).
 * The actor collects those with [takeDropped] and returns them to the scheduler. With it ([fast])
 * a CHOKE keeps our requests: the peer rejects those it will not serve, and may serve its
 * allowed-fast pieces while it chokes us. Requests it leaves unanswered for [fastRejectGraceMs]
 * after choking us are canceled by [releaseChoked] and dropped the same way, allowed-fast ones
 * excepted. Fast messages on a connection that did not negotiate it fail the peer.
 *
 * On a hybrid's v1 route ([mode] V1) the peer only knows v1 pieces, which run on through the
 * zero padding after a file's tail. A request that ends a file tail inside such a piece goes on
 * the wire as the canonical v1 block instead; the padding that comes back must be zero and is
 * stripped, so the scheduler and the store only ever see bytes of the v2 extent.
 */
internal class PeerBlockExchange(
  private val layout: TorrentContentLayout,
  private val transport: PeerHashTransport,
  private val budget: TorrentBufferBudget,
  private val maxPending: Int = 32,
  private val timeoutMs: Long = 60_000,
  private val clock: () -> Long = monotonicClock(),
  private val mode: PeerIdentityHandshake.Mode = PeerIdentityHandshake.Mode.V2,
  /** Both sides set the Fast extension bit (BEP 6). */
  val fast: Boolean = false,
  private val fastRejectGraceMs: Long = FAST_REJECT_GRACE_MS,
) {
  class Ticket internal constructor(
    /** The scheduler's block, within the piece's v2 extent. */
    val request: PeerMessage.Request,
    internal val owner: Any? = null,
    /** The block on the wire: [request], or on a v1 route its canonical block with padding. */
    internal val wire: PeerMessage.Request = request,
  )
  sealed interface Response {
    class Block internal constructor(
      val ticket: Ticket,
      val bytes: ByteArray,
      private val lease: TorrentBufferBudget.Lease,
    ) : Response {
      /** Bytes are unverified; the consumer retains credit until verification/copying is done. */
      fun close() = lease.close()
    }
    data class Rejected(val ticket: Ticket) : Response
  }
  private class Pending(
    val ticket: Ticket,
    val started: Long,
    val lease: TorrentBufferBudget.Lease,
  )

  init {
    require(layout.pieceCount in 0..1_000_000 && layout.pieceLength <= 16 * 1024 * 1024)
    require(maxPending in 1..256 && timeoutMs in 1..180_000 && fastRejectGraceMs > 0)
  }
  private var protocol: PeerProtocolState? = PeerProtocolState(
    layout.pieceCount.toInt(), maxPending, fast = fast)
  private val state: PeerProtocolState get() = checkNotNull(protocol)
  // Keyed by the wire block, which is what the peer's pieces, rejects and our cancels name.
  private val pending = mutableMapOf<PeerMessage.Request, Pending>()
  private val dropped = mutableListOf<Response.Rejected>()
  private val identity = Any()
  private var closed = false
  /** When a Fast peer last choked us, on [clock]; null while it unchokes us. */
  private var chokedAt: Long? = null
  /** When the peer last answered a request of ours, with a block or a reject, on [clock]. */
  private var answeredAt = Long.MIN_VALUE
  var localInterested: Boolean = false
    private set
  val pendingCount: Int get() = pending.size

  /** Unchoked, or choked by a Fast peer that allows [index] fast (BEP 6). */
  fun canRequest(index: Int): Boolean = !closed && pending.size < maxPending &&
    state.canRequest(index)

  fun owns(ticket: Ticket): Boolean = ticket.owner === identity

  fun hasPiece(index: Int): Boolean = !closed && state.hasPiece(index)

  /** Admission failure leaves interest unchanged for retry; repeated updates emit no frame. */
  suspend fun setInterested(value: Boolean): Boolean {
    checkLive()
    if (localInterested == value) return true
    try {
      val time = nextDeadlineMs() ?: timeoutMs
      check(time > 0) { "Peer block expired before interest write" }
      val started = clock()
      val sent = withTimeout(time) {
        transport.trySend(PeerMessage.Control(if (value) PeerMessage.Signal.INTERESTED else
          PeerMessage.Signal.NOT_INTERESTED))
      }
      checkLive()
      if (sent) {
        val finished = clock()
        val elapsed = finished - started
        check(finished >= started && elapsed >= 0 && elapsed < time) {
          "Peer interest write expired"
        }
        localInterested = value
      }
      return sent
    } catch (error: Throwable) {
      close()
      throw error
    }
  }

  /** Relative delay for the actor's timer; control traffic and cancels never extend deadlines. */
  fun nextDeadlineMs(): Long? = pending.values.minOfOrNull { remaining(it) }

  private fun remaining(value: Pending): Long {
    val now = clock()
    // Each answer gives every request out another [timeoutMs]; none waits past its maximum age.
    val since = maxOf(value.started, answeredAt)
    val elapsed = now - since
    val age = now - value.started
    if (now < since || elapsed < 0 || age < 0) return 0
    return maxOf(0, minOf(timeoutMs - elapsed, timeoutMs * MAX_AGE_TIMEOUTS - age))
  }

  private fun checkLive() {
    check(!closed) { "Block exchange is closed" }
    check(!expire()) { "Peer block response deadline expired" }
  }

  /** Null means no peer availability, a full pipeline, duplicate request or exhausted credit. */
  suspend fun request(request: PeerMessage.Request): Ticket? {
    checkLive()
    PeerWire.encodedSize(request, layout.pieceCount.toInt())
    val length = layout.v2Piece(request.index.toLong()).length
    require(request.begin.toLong() + request.length <= length) { "Block crosses v2 file tail" }
    val wire = wire(request, length)
    if (!state.canRequest(request.index) || wire in pending || pending.size == maxPending) {
      return null
    }
    val lease = budget.reserve(wire.length * 2 + 256) ?: return null
    try {
      val value = Pending(Ticket(request, identity, wire), clock(), lease)
      val sent = write(value) {
        transport.trySend(wire) {
          state.requested(wire)
          pending[wire] = value
        }
      }
      if (!sent) {
        lease.close()
        return null
      }
      return value.ticket
    } catch (error: Throwable) {
      lease.close()
      close()
      throw error
    }
  }

  /**
   * The block to put on the wire for [request], whose piece has a v2 extent of [extent] bytes.
   * A v1 peer only knows canonical blocks, which run on through a file tail's padding to the end
   * of the v1 piece; blocks wholly inside padding are never planned, so none is requested.
   */
  private fun wire(request: PeerMessage.Request, extent: Long): PeerMessage.Request {
    if (mode != PeerIdentityHandshake.Mode.V1) return request
    val protocol = layout.protocolPieceLength(request.index.toLong())
    if (request.begin.toLong() + request.length != extent || extent >= protocol) return request
    val length = minOf(PeerWire.BLOCK_SIZE.toLong(), protocol - request.begin).toInt()
    return PeerMessage.Request(request.index, request.begin, length)
  }

  /**
   * Cancels [ticket]'s request and forgets it once the CANCEL is written: no response for it
   * follows, and a late block or reject for it is ignored. False means stale ownership, as when
   * it was already answered, or no frame credit, which leaves the request out as it was.
   */
  suspend fun cancel(ticket: Ticket): Boolean {
    checkLive()
    val value = pending[ticket.wire] ?: return false
    if (value.ticket !== ticket) return false
    try {
      return write(value) {
        val wire = ticket.wire
        transport.trySend(PeerMessage.Cancel(wire.index, wire.begin, wire.length)) {
          pending.remove(wire)
          state.cancel(wire)
          value.lease.close()
        }
      }
    } catch (error: Throwable) {
      close()
      throw error
    }
  }

  private suspend fun <T> write(value: Pending, block: suspend () -> T): T {
    val remaining = remaining(value)
    val time = minOf(remaining, nextDeadlineMs() ?: remaining)
    check(time > 0) { "Peer block expired before write" }
    val result = withTimeout(time) { block() }
    check(!expire()) { "Peer block expired during write" }
    return result
  }

  /**
   * Caller closes the frame after dispatch; delivered blocks own separate retained credit. Null
   * means an ordinary update, including a late block for a request that a CHOKE already dropped.
   */
  fun receive(frame: PeerHashTransport.Frame): Response? {
    try {
      checkLive()
      val message = frame.message
      require(fast || !message.isFastExtension()) { "Fast extension message without BEP 6" }
      val wasChoking = state.choking
      val request = when (message) {
        is PeerMessage.Piece ->
          PeerMessage.Request(message.index, message.begin, message.bytes.size)
        is PeerMessage.Reject -> PeerMessage.Request(message.index, message.begin, message.length)
        else -> null
      }
      val accepted = state.received(message)
      if (message is PeerMessage.Control && message.signal == PeerMessage.Signal.CHOKE) {
        if (fast) {
          // The requests stand until the peer rejects them; a repeated CHOKE keeps the clock,
          // or a peer repeating it would hold them forever.
          if (!wasChoking) chokedAt = clock()
        } else {
          for (value in pending.values) {
            value.lease.close()
            dropped += Response.Rejected(value.ticket)
          }
          pending.clear()
        }
      }
      if (message is PeerMessage.Control && message.signal == PeerMessage.Signal.UNCHOKE) {
        chokedAt = null
      }
      if (request == null) return null
      val value = pending.remove(request) ?: run {
        // The protocol state remembers requests dropped by CHOKE or cancel; anything else is
        // unsolicited and has already failed there.
        check(!accepted) { "Missing block request ownership" }
        return null
      }
      // An answer, however slow: the peer still serves us.
      answeredAt = clock()
      if (message is PeerMessage.Piece && accepted) {
        val ticket = value.ticket
        val length = ticket.request.length
        if (ticket.wire.length == length) return Response.Block(ticket, message.bytes, value.lease)
        // The canonical v1 block ran on through padding, which must be zero to match v1 hashes.
        if ((length until message.bytes.size).any { message.bytes[it] != 0.toByte() }) {
          value.lease.close()
          throw IllegalArgumentException("Nonzero hybrid padding")
        }
        return Response.Block(ticket, message.bytes.copyOf(length), value.lease)
      }
      // Pending requests are live ones: the protocol state accepts any answer to them.
      check(message is PeerMessage.Reject) { "Unaccepted block for a live request" }
      value.lease.close()
      return Response.Rejected(value.ticket)
    } catch (error: Throwable) {
      close()
      throw error
    }
  }

  /**
   * Milliseconds until requests a Fast peer that choked us left unanswered are due for
   * [releaseChoked]; null when there are none. Unlike [nextDeadlineMs], this bounds no write.
   */
  fun nextReleaseMs(): Long? {
    val since = chokedAt ?: return null
    if (closed || !state.choking || choked().isEmpty()) return null
    val now = clock()
    val elapsed = now - since
    return if (now < since || elapsed < 0) 0 else maxOf(0, fastRejectGraceMs - elapsed)
  }

  /** Live requests a choking peer does not allow fast: those it should reject (BEP 6). */
  private fun choked(): List<Pending> = pending.values.filter {
    !state.allowsFast(it.ticket.wire.index)
  }

  /**
   * Cancels the requests a Fast peer that choked us neither served nor rejected within
   * [fastRejectGraceMs], forgetting them at once: [takeDropped] returns them to the scheduler,
   * without holding them against the peer, and a late block or reject for them is ignored.
   * Allowed-fast requests keep waiting. True when any were dropped.
   */
  suspend fun releaseChoked(): Boolean {
    if (nextReleaseMs() != 0L) return false
    val released = choked()
    try {
      for (value in released) {
        val wire = value.ticket.wire
        pending.remove(wire)
        state.cancel(wire)
        value.lease.close()
        dropped += Response.Rejected(value.ticket)
        // Best effort: without frame credit the peer just keeps a request we no longer await.
        transport.trySend(PeerMessage.Cancel(wire.index, wire.begin, wire.length))
      }
    } catch (error: Throwable) {
      close()
      throw error
    }
    return released.isNotEmpty()
  }

  /** Requests the peer discarded by choking us; each must be returned to the scheduler. */
  fun takeDropped(): List<Response.Rejected> = dropped.toList().also { dropped.clear() }

  /**
   * A timed-out request cannot be forgotten on a reusable v2 stream; tear down the connection.
   * Requests time out once the peer answered none for the response timeout, or the oldest one
   * waited [MAX_AGE_TIMEOUTS] times that long.
   */
  fun expire(): Boolean {
    if (closed || pending.values.none { remaining(it) == 0L }) return false
    close()
    return true
  }

  /** Runtime joins its reader after closing; already delivered blocks remain consumer-owned. */
  fun close() {
    if (closed) return
    closed = true
    try { transport.close() } finally {
      pending.values.forEach { it.lease.close() }
      pending.clear()
      protocol = null
    }
  }

  companion object {
    /**
     * How many times the response timeout one request may wait at most, however often the peer
     * answers others: a request it dropped without a word must not hold its block for good.
     */
    const val MAX_AGE_TIMEOUTS: Int = 4
  }
}
