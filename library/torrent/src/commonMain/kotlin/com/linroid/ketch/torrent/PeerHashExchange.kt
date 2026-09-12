package com.linroid.ketch.torrent

import okio.ByteString

/** Single connection-event-loop owner; independent of choke state and the payload request queue. */
internal class PeerHashExchange(
  private val budget: TorrentBufferBudget,
  private val fileLength: (ByteString) -> Long?,
  private val maxPending: Int = 8,
  private val timeoutMs: Long = 30_000,
  private val clock: () -> Long = monotonicClock(),
) {
  class Ticket internal constructor(val selector: PeerHashSelector)
  class Verified internal constructor(
    val selector: PeerHashSelector,
    val hashes: ByteString,
    private val lease: TorrentBufferBudget.Lease,
  ) {
    /** The consumer keeps the reservation until it has finished retaining these hash bytes. */
    fun close() = lease.close()
  }

  private class Pending(
    val ticket: Ticket,
    val length: Long,
    val started: Long,
    val lease: TorrentBufferBudget.Lease,
  )
  private val pending = mutableMapOf<PeerHashSelector, Pending>()
  private var closed = false
  val pendingCount: Int get() = pending.size

  init { require(maxPending in 1..64 && timeoutMs in 1..180_000) }

  /** Reserve before enqueueing a request; null means pipeline saturation or exhausted credit. */
  fun request(selector: PeerHashSelector): Ticket? {
    check(!closed)
    val length = requireNotNull(fileLength(selector.root)) { "Unknown hash request root" }
    require(peerHashProofHeight(selector, length) != null) { "Invalid hash request tree bounds" }
    if (selector in pending || pending.size == maxPending) return null
    // Allow raw frame, decoded hash bytes and a retained result, plus bounded proof scratch.
    // Parser admission for unsolicited/other frame types remains the connection reader's duty.
    val bytes = selector.hashCount * 32 * 3 + 8192
    val lease = budget.reserve(bytes) ?: return null
    try {
      val ticket = Ticket(selector)
      pending[selector] = Pending(ticket, length, clock(), lease)
      return ticket
    } catch (error: Throwable) {
      lease.close()
      throw error
    }
  }

  /** A stale send/cancel callback cannot release a newer request for the same coordinates. */
  fun cancel(ticket: Ticket) {
    val current = pending[ticket.selector] ?: return
    if (current.ticket !== ticket) return
    pending.remove(ticket.selector)
    current.lease.close()
  }

  fun reject(message: PeerHashMessage.Reject): Boolean {
    val current = pending.remove(message.selector) ?: return false
    current.lease.close()
    return true
  }

  fun receive(message: PeerHashMessage.Hashes): Verified {
    check(!closed)
    val current = requireNotNull(pending.remove(message.selector)) { "Unsolicited hash response" }
    try {
      val now = clock()
      require(now >= current.started && now - current.started < timeoutMs) {
        "Expired hash response"
      }
      require(verifyPeerHashes(message, current.ticket.selector, current.length,
        current.ticket.selector.root)) { "Invalid peer hash proof" }
      return Verified(message.selector, message.hashes, current.lease)
    } catch (error: Throwable) {
      current.lease.close()
      throw error
    }
  }

  /** Called by the connection timer, including while the peer is choking payload requests. */
  fun expire(): List<Ticket> {
    val now = clock()
    val expired = pending.values.filter { now >= it.started && now - it.started >= timeoutMs }
      .map { it.ticket }
    expired.forEach(::cancel)
    return expired
  }

  /** Pending credit is released; already delivered results remain charged to their consumers. */
  fun close() {
    closed = true
    pending.values.forEach { it.lease.close() }
    pending.clear()
  }
}
