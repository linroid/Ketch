package com.linroid.ketch.torrent

/** Peers a session uploads to at once: three regular slots and one optimistic. */
internal const val UPLOAD_SLOTS = 4

/**
 * Decides which interested peers a session uploads to; pure and owned by one caller, with an
 * injected clock. Every [rechokeMs] the regular slots go to the peers that sent us the most
 * since the last rechoke (while downloading) or took the most from us (while seeding), ties to
 * the peer choked longest. Every [optimisticMs] the optimistic slot moves to the peer choked
 * longest outside the regular slots, so newcomers get a chance to reciprocate. A slot that
 * frees, or a peer that becomes interested while one is free, is filled at once.
 */
internal class TorrentChoker<P : Any>(
  private val nowMs: () -> Long,
  private val slots: Int = UPLOAD_SLOTS,
  private val rechokeMs: Long = 10_000,
  private val optimisticMs: Long = 30_000,
) {
  /** The peers [update] unchoked and choked. */
  class Changes<P>(val unchoke: List<P>, val choke: List<P>)

  private class State(var chokedSince: Long) {
    var interested = false
    var unchoked = false
    var received = 0L
    var uploaded = 0L
    /** Refused to serve it until the next rechoke (no budget or frame credit). */
    var refused = false
  }

  init { require(slots >= 1 && rechokeMs > 0 && optimisticMs > 0) }

  private val peers = LinkedHashMap<P, State>()
  private var optimistic: P? = null
  private var nextRechoke = nowMs() + rechokeMs
  private var nextOptimistic = nowMs() + optimisticMs
  private var lastAllowed: Boolean? = null
  /** Something changed that the next [update] must look at before its timers are due. */
  private var dirty = false

  val unchokedCount: Int get() = peers.values.count { it.unchoked }
  val interestedCount: Int get() = peers.values.count { it.interested }

  fun add(peer: P) {
    if (peer in peers) return
    peers[peer] = State(nowMs())
    dirty = true
  }

  fun remove(peer: P) {
    peers.remove(peer) ?: return
    if (optimistic == peer) optimistic = null
    dirty = true
  }

  fun interested(peer: P, value: Boolean) {
    val state = peers[peer] ?: return
    if (state.interested == value) return
    state.interested = value
    dirty = true
  }

  /** Payload [peer] sent us since the last rechoke. */
  fun received(peer: P, bytes: Long) {
    peers[peer]?.let { it.received += bytes }
  }

  /** Payload we sent [peer] since the last rechoke. */
  fun uploaded(peer: P, bytes: Long) {
    peers[peer]?.let { it.uploaded += bytes }
  }

  /** Chokes [peer] at the next [update], which cannot serve it now; it may return at a rechoke. */
  fun refused(peer: P) {
    val state = peers[peer] ?: return
    state.refused = true
    dirty = true
  }

  fun isUnchoked(peer: P): Boolean = peers[peer]?.unchoked == true

  /**
   * Applies what changed and any due rechoke or optimistic rotation. Without [allowed] (upload
   * off, or finished while uploading only during downloads) every peer is choked. [seeding] ranks
   * peers by what they took from us rather than what they gave.
   */
  fun update(allowed: Boolean, seeding: Boolean): Changes<P> {
    val now = nowMs()
    val unchoke = mutableListOf<P>()
    val choke = mutableListOf<P>()
    fun choke(peer: P, state: State) {
      if (state.unchoked) {
        state.unchoked = false
        state.chokedSince = now
        choke += peer
      }
      if (optimistic == peer) optimistic = null
    }
    fun unchoke(peer: P, state: State) {
      if (!state.unchoked) {
        state.unchoked = true
        unchoke += peer
      }
    }
    val changedPolicy = lastAllowed != allowed
    lastAllowed = allowed
    if (!allowed) {
      if (changedPolicy || dirty) for ((peer, state) in peers) choke(peer, state)
      dirty = false
      return Changes(unchoke, choke)
    }
    if (!changedPolicy && !dirty && now < nextRechoke && now < nextOptimistic) {
      return Changes(unchoke, choke)
    }
    dirty = false
    // A peer that is not interested, or that we could not serve, gives its slot up at once.
    for ((peer, state) in peers) if (state.unchoked && (!state.interested || state.refused)) {
      choke(peer, state)
    }
    if (now >= nextRechoke) {
      nextRechoke = now + rechokeMs
      val interested = peers.entries.filter { it.value.interested }
      val regular = interested.sortedWith(
        compareByDescending<Map.Entry<P, State>> {
          if (seeding) it.value.uploaded else it.value.received
        }.thenBy { if (it.value.unchoked) Long.MAX_VALUE else it.value.chokedSince },
      ).take(if (interested.size <= slots) slots else slots - 1).map { it.key }.toSet()
      for (state in peers.values) {
        state.received = 0
        state.uploaded = 0
        state.refused = false
      }
      if (optimistic in regular) optimistic = null
      for ((peer, state) in peers) {
        if (state.unchoked && peer !in regular && peer != optimistic) choke(peer, state)
      }
      for (peer in regular) unchoke(peer, peers.getValue(peer))
    }
    if (now >= nextOptimistic) {
      nextOptimistic = now + optimisticMs
      val candidate = longestChoked()
      if (candidate != null) {
        optimistic?.let { previous -> choke(previous, peers.getValue(previous)) }
        if (peers.values.count { it.unchoked } >= slots) {
          // Every slot is regular: the slowest of them makes room.
          peers.entries.filter { it.value.unchoked }
            .minByOrNull { if (seeding) it.value.uploaded else it.value.received }
            ?.let { choke(it.key, it.value) }
        }
        optimistic = candidate
        unchoke(candidate, peers.getValue(candidate))
      }
    }
    // Free slots are filled at once; the first newcomer takes the optimistic slot.
    while (peers.values.count { it.unchoked } < slots) {
      val candidate = longestChoked() ?: break
      if (optimistic == null && peers.values.count { it.unchoked } == slots - 1) {
        optimistic = candidate
      }
      unchoke(candidate, peers.getValue(candidate))
    }
    return Changes(unchoke, choke)
  }

  /**
   * Milliseconds until [update] has timed work, or null while no peer is interested or uploading
   * is not allowed (the last [update] decides).
   */
  fun nextDueMs(): Long? {
    if (lastAllowed != true || peers.values.none { it.interested }) return null
    return (minOf(nextRechoke, nextOptimistic) - nowMs()).coerceAtLeast(0)
  }

  private fun longestChoked(): P? = peers.entries
    .filter { it.value.interested && !it.value.unchoked && !it.value.refused }
    .minByOrNull { it.value.chokedSince }?.key
}
