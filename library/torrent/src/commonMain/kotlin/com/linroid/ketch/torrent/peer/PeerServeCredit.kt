package com.linroid.ketch.torrent.peer

import kotlin.math.min

/**
 * The work beyond its payload one peer may make us do: reading a whole piece back from disk,
 * and hashing it, to send it a block, or folding hashes to answer a BEP 52 hash request. A peer
 * starts with [capacity] bytes of it, refilled over [refillMs] and by twice the payload it is
 * sent, so a peer taking whole pieces never runs short while one asking a block of each piece,
 * or costly hashes, is refused once it has spent its share. Upload limits, unlimited by default,
 * never bound this work, as it moves no payload. Not thread safe: one owner (a peer's worker or
 * the session loop) uses it.
 */
internal class PeerServeCredit(
  val capacity: Long,
  private val nowMs: () -> Long,
  private val refillMs: Long = 30_000,
) {
  private var balance = capacity
  private var updatedAt = nowMs()

  init {
    require(capacity > 0 && refillMs > 0)
  }

  /** Bytes of work the peer may make us do now. */
  val available: Long
    get() {
      refill()
      return balance.coerceAtLeast(0)
    }

  /** Takes [bytes] of work from the peer's share; false, taking nothing, when it has too little. */
  fun tryCharge(bytes: Long): Boolean {
    refill()
    if (bytes > balance) return false
    balance -= bytes
    return true
  }

  /** Takes [bytes] of work already done, which may leave the share owing. */
  fun charge(bytes: Long) {
    refill()
    balance -= bytes
  }

  /** Gives back [bytes] taken for work that did not happen. */
  fun refund(bytes: Long) {
    refill()
    balance = min(capacity, balance + bytes)
  }

  /** The peer was sent [payload] bytes: whole pieces pay for their reads twice over. */
  fun earn(payload: Long) {
    refill()
    balance = min(capacity, balance + 2 * payload)
  }

  private fun refill() {
    val now = nowMs()
    val elapsed = now - updatedAt
    if (elapsed <= 0) return
    // Advanced only by what it added, so frequent calls never round the refill away.
    val added = capacity * min(elapsed, refillMs) / refillMs
    if (added == 0L && balance < capacity) return
    balance = min(capacity, balance + added)
    updatedAt = now
  }

  companion object {
    /** Bytes of folding work one computed hash costs: the two child hashes it reads. */
    const val HASH_COST = 64L

    /** A share holding two pieces of [pieceLength] and 4 MiB more for hash requests. */
    fun capacityFor(pieceLength: Long): Long = 2 * pieceLength + 4L * 1024 * 1024
  }
}
