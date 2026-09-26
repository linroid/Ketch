package com.linroid.ketch.torrent

import kotlinx.coroutines.withTimeout

/**
 * One actor owns this v2 download pipeline and its transport. The session admits availability
 * state before construction; pending/delivered blocks retain their own buffer reservations.
 * A separate reader hands frames to receive. The actor must schedule expire at nextDeadlineMs.
 *
 * The Fast extension (BEP 6) is not negotiated, so a CHOKE silently discards every outstanding
 * request (BEP 3). The actor collects those with [takeDropped] and returns them to the scheduler.
 */
internal class PeerBlockExchange(
  private val layout: TorrentContentLayout,
  private val transport: PeerHashTransport,
  private val budget: TorrentBufferBudget,
  private val maxPending: Int = 32,
  private val timeoutMs: Long = 30_000,
  private val clock: () -> Long = monotonicClock(),
) {
  class Ticket internal constructor(
    val request: PeerMessage.Request,
    internal val owner: Any? = null,
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
    data class Canceled(val ticket: Ticket) : Response
  }
  private class Pending(
    val ticket: Ticket,
    val started: Long,
    val lease: TorrentBufferBudget.Lease,
    var canceled: Boolean = false,
  )

  init {
    require(layout.pieceCount in 0..1_000_000 && layout.pieceLength <= 16 * 1024 * 1024)
    require(maxPending in 1..256 && timeoutMs in 1..180_000)
  }
  private var protocol: PeerProtocolState? = PeerProtocolState(
    layout.pieceCount.toInt(), maxPending)
  private val state: PeerProtocolState get() = checkNotNull(protocol)
  private val pending = mutableMapOf<PeerMessage.Request, Pending>()
  private val dropped = mutableListOf<Response.Rejected>()
  private val identity = Any()
  private var closed = false
  var localInterested: Boolean = false
    private set
  val pendingCount: Int get() = pending.size

  fun canRequest(index: Int): Boolean = !closed && pending.size < maxPending && !state.choking &&
    state.hasPiece(index)

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
    val elapsed = now - value.started
    return if (now < value.started || elapsed < 0) 0 else maxOf(0, timeoutMs - elapsed)
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
    if (state.choking || !state.hasPiece(request.index) || request in pending ||
      pending.size == maxPending) return null
    val lease = budget.reserve(request.length * 2 + 256) ?: return null
    try {
      val value = Pending(Ticket(request, identity), clock(), lease)
      val sent = write(value) {
        transport.trySend(request) {
          state.requested(request)
          pending[request] = value
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

  /** False means stale ownership or no frame credit; retry a live ticket when credit returns. */
  suspend fun cancel(ticket: Ticket): Boolean {
    checkLive()
    val value = pending[ticket.request] ?: return false
    if (value.ticket !== ticket) return false
    if (value.canceled) return true
    try {
      return write(value) {
        val request = ticket.request
        transport.trySend(PeerMessage.Cancel(request.index, request.begin, request.length)) {
          value.canceled = true
          state.cancel(ticket.request)
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
      val request = when (message) {
        is PeerMessage.Piece ->
          PeerMessage.Request(message.index, message.begin, message.bytes.size)
        is PeerMessage.Reject -> PeerMessage.Request(message.index, message.begin, message.length)
        else -> null
      }
      // Tolerate a Reject for a live request even though Fast was never negotiated.
      if (message is PeerMessage.Reject && request in pending) state.cancel(checkNotNull(request))
      val accepted = state.received(message)
      if (message is PeerMessage.Control && message.signal == PeerMessage.Signal.CHOKE) {
        for (value in pending.values) {
          value.lease.close()
          dropped += Response.Rejected(value.ticket)
        }
        pending.clear()
      }
      if (request == null) return null
      val value = pending.remove(request) ?: run {
        // The protocol state remembers requests dropped by CHOKE or cancel; anything else is
        // unsolicited and has already failed there.
        check(!accepted) { "Missing block request ownership" }
        return null
      }
      if (message is PeerMessage.Piece && accepted) {
        return Response.Block(value.ticket, message.bytes, value.lease)
      }
      value.lease.close()
      return if (message is PeerMessage.Reject) Response.Rejected(value.ticket) else
        Response.Canceled(value.ticket)
    } catch (error: Throwable) {
      close()
      throw error
    }
  }

  /** Requests the peer discarded by choking us; each must be returned to the scheduler. */
  fun takeDropped(): List<Response.Rejected> = dropped.toList().also { dropped.clear() }

  /** A timed-out request cannot be forgotten on a reusable v2 stream; tear down the connection. */
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
}
