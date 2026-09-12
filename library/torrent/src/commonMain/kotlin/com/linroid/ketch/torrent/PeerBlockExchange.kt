package com.linroid.ketch.torrent

import kotlinx.coroutines.withTimeout

/**
 * One actor owns this v2 download pipeline and its transport. The session admits availability
 * state before construction; pending/delivered blocks retain their own buffer reservations.
 * A separate reader hands frames to receive. The actor must schedule expire at nextDeadlineMs.
 */
internal class PeerBlockExchange(
  private val layout: TorrentContentLayout,
  private val transport: PeerHashTransport,
  private val budget: TorrentBufferBudget,
  private val maxPending: Int = 32,
  private val timeoutMs: Long = 30_000,
  private val clock: () -> Long = monotonicClock(),
) {
  class Ticket internal constructor(val request: PeerMessage.Request)
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
  private val state = PeerProtocolState(layout.pieceCount.toInt(), maxPending,
    explicitRejects = true)
  private val pending = mutableMapOf<PeerMessage.Request, Pending>()
  private var closed = false
  val pendingCount: Int get() = pending.size

  fun canRequest(index: Int): Boolean = !closed && pending.size < maxPending && !state.choking &&
    index in state.available.indices && state.available[index]

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
    if (state.choking || !state.available[request.index] || request in pending ||
      pending.size == maxPending) return null
    val lease = budget.reserve(request.length * 2 + 256) ?: return null
    try {
      val value = Pending(Ticket(request), clock(), lease)
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

  /** Caller closes the frame after dispatch; delivered blocks own separate retained credit. */
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
      val accepted = state.received(message)
      if (request == null) return null
      val value = checkNotNull(pending.remove(request)) { "Missing block request ownership" }
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
    }
  }
}
