package com.linroid.ketch.torrent

/** One session actor owns assignments; peers and the commit worker retain their separate leases. */
internal class TorrentV2PieceScheduler private constructor(
  private val layout: TorrentContentLayout,
  private var wanted: BooleanArray,
  private var verified: BooleanArray,
  private val buffers: TorrentBufferBudget,
  private val maxActive: Int,
  private val lease: TorrentBufferBudget.Lease,
) {
  class RequestPlan internal constructor(
    val peer: PeerBlockExchange,
    val request: PeerMessage.Request,
  )
  private class Assignment(val plan: RequestPlan, var ticket: PeerBlockExchange.Ticket? = null)
  private val assemblies = linkedMapOf<Int, TorrentV2PieceAssembly>()
  private val assignments = mutableMapOf<PeerMessage.Request, Assignment>()
  private val commits = mutableMapOf<TorrentV2CommitWorker.Ticket, Int>()
  private var busy = ByteArray((verified.size + 7) / 8)
  private var closed = false
  private var remaining = wanted.indices.count { wanted[it] && !verified[it] }
  val pendingCommitCount: Int get() = commits.size
  val activeCount: Int get() = assemblies.size + commits.size

  fun isVerified(index: Int): Boolean {
    check(!closed)
    return verified[index]
  }

  fun canBegin(index: Int): Boolean {
    check(!closed)
    return index in verified.indices && wanted[index] && !verified[index] &&
      busy[index / 8].toInt() and (128 ushr (index % 8)) == 0 && activeCount < maxActive
  }

  fun beginNext(
    peer: PeerBlockExchange,
    picker: TorrentV2RarityPicker<PeerBlockExchange>,
  ): Boolean {
    check(!closed)
    require(picker.pieceCount == verified.size)
    if (activeCount == maxActive) return false
    val index = picker.pick(peer) { canBegin(it) && peer.canRequest(it) } ?: return false
    return begin(index)
  }

  /** False means no eligible piece or no assembly admission; caller retries after state changes. */
  fun begin(index: Int): Boolean {
    check(!closed)
    require(index in verified.indices)
    if (!canBegin(index)) return false
    val assembly = TorrentV2PieceAssembly.create(layout, index, buffers) ?: return false
    assemblies[index] = assembly
    markBusy(index, true)
    return true
  }

  /** Interest ignores choke/pipeline state so an available choked peer can choose to unchoke us. */
  fun needsPeer(peer: PeerBlockExchange): Boolean {
    check(!closed)
    return verified.indices.any { wanted[it] && !verified[it] && peer.hasPiece(it) }
  }

  fun isNeeded(index: Int): Boolean {
    check(!closed)
    return wanted[index] && !verified[index]
  }

  fun neededCount(available: (Int) -> Boolean): Int {
    check(!closed)
    return verified.indices.count { wanted[it] && !verified[it] && available(it) }
  }

  fun completed(): Boolean {
    check(!closed)
    return remaining == 0
  }

  /** Call after availability/verification changes; false means retry when frame credit returns. */
  suspend fun updateInterest(peer: PeerBlockExchange): Boolean {
    check(!closed)
    try {
      return peer.setInterested(needsPeer(peer))
    } catch (error: Throwable) {
      try { removePeer(peer) } catch (cleanup: Throwable) { error.addSuppressed(cleanup) }
      throw error
    }
  }

  /** Reserve without network I/O. Availability must be an actor-owned view of the peer's state. */
  fun planNext(peer: PeerBlockExchange, available: (Int) -> Boolean): RequestPlan? {
    check(!closed)
    for (assembly in assemblies.values) {
      if (!available(assembly.index)) continue
      for (slot in assembly.missingBlocks()) {
        val request = assembly.request(slot)
        if (request !in assignments) return reserve(peer, request)
      }
    }
    return null
  }

  private fun reserve(peer: PeerBlockExchange, request: PeerMessage.Request): RequestPlan {
    val plan = RequestPlan(peer, request)
    assignments[request] = Assignment(plan)
    return plan
  }

  /**
   * Null acknowledges an unsent/unadmitted request. A stale acknowledgement cannot bind a newer
   * plan; false means the issuing peer actor must cancel or close any ticket it already created.
   * The peer actor must enqueue this acknowledgement before forwarding responses for the ticket.
   */
  fun resolve(plan: RequestPlan, ticket: PeerBlockExchange.Ticket?): Boolean {
    if (closed) return false
    val assignment = assignments[plan.request] ?: return false
    if (assignment.plan !== plan || assignment.ticket != null) return false
    if (ticket == null) assignments.remove(plan.request) else {
      require(ticket.request == plan.request && plan.peer.owns(ticket)) { "Wrong request issuer" }
      assignment.ticket = ticket
    }
    return true
  }

  /** Synchronous adapter for an owner of both scheduler and peer; session actors use plan/resolve. */
  suspend fun requestNext(peer: PeerBlockExchange): PeerBlockExchange.Ticket? {
    check(!closed)
    if (!peer.localInterested && !updateInterest(peer)) return null
    var attempts = 0
    for (assembly in assemblies.values) {
      if (!peer.canRequest(assembly.index)) continue
      for (slot in assembly.missingBlocks()) {
        val request = assembly.request(slot)
        if (request in assignments) continue
        if (attempts++ == 128) return null
        val plan = reserve(peer, request)
        val ticket = try {
          peer.request(request)
        } catch (error: Throwable) {
          try { removePeer(peer) } catch (cleanup: Throwable) { error.addSuppressed(cleanup) }
          throw error
        }
        check(resolve(plan, ticket)) { "Request plan changed during synchronous dispatch" }
        if (ticket == null) continue
        return ticket
      }
    }
    return null
  }

  /** Returns false for a stale callback; it still consumes any delivered block buffer. */
  fun receive(peer: PeerBlockExchange, response: PeerBlockExchange.Response): Boolean {
    val ticket = when (response) {
      is PeerBlockExchange.Response.Block -> response.ticket
      is PeerBlockExchange.Response.Rejected -> response.ticket
      is PeerBlockExchange.Response.Canceled -> response.ticket
    }
    val assignment = assignments[ticket.request]
    if (closed || assignment == null || assignment.plan.peer !== peer || assignment.ticket !== ticket) {
      if (response is PeerBlockExchange.Response.Block) response.close()
      return false
    }
    assignments.remove(ticket.request)
    if (response is PeerBlockExchange.Response.Block) {
      val assembly = assemblies[ticket.request.index]
      if (assembly == null) {
        response.close()
        error("Assigned piece has no assembly")
      }
      assembly.accept(response)
    }
    return true
  }

  /** Session actors call this after the peer actor has closed and joined its pipeline/reader. */
  fun detachPeer(peer: PeerBlockExchange) {
    val abandoned = assignments.filterValues { it.plan.peer === peer }.keys
    abandoned.forEach(assignments::remove)
  }

  /** Convenience cleanup for callers that also own this peer's pipeline. */
  fun removePeer(peer: PeerBlockExchange) {
    try { peer.close() } finally { detachPeer(peer) }
  }

  /** Queue pressure leaves completed assemblies here for retry, without accepting more blocks. */
  fun submitReady(worker: TorrentV2CommitWorker): Int {
    check(!closed)
    var count = 0
    val ready = assemblies.filterValues { it.complete }.keys
    for (index in ready) {
      val ticket = worker.trySubmit(assemblies.getValue(index)) ?: break
      assemblies.remove(index)
      commits[ticket] = index
      count++
    }
    return count
  }

  /** Only matching tickets publish state; failed or corrupt commits make the piece retryable. */
  fun completed(completion: TorrentV2CommitWorker.Completion): Boolean {
    if (closed) return false
    val index = commits.remove(completion.ticket) ?: return false
    markBusy(index, false)
    verified[index] = completion is TorrentV2CommitWorker.Completion.Committed &&
      completion.verified
    if (verified[index]) remaining--
    return true
  }

  private fun markBusy(index: Int, value: Boolean) {
    val byte = index / 8
    val mask = 128 ushr (index % 8)
    busy[byte] = if (value) (busy[byte].toInt() or mask).toByte() else
      (busy[byte].toInt() and mask.inv()).toByte()
  }

  /** The outer runtime joins peers and the worker; this closes only actor-owned assembly/state. */
  fun close() {
    if (closed) return
    closed = true
    assemblies.values.forEach { it.close() }
    assemblies.clear()
    assignments.clear()
    commits.clear()
    busy = ByteArray(0)
    wanted = BooleanArray(0)
    verified = BooleanArray(0)
    lease.close()
  }

  companion object {
    /** Initial bits must come from current store verification, not unchecked checkpoint hints. */
    fun create(
      layout: TorrentContentLayout,
      selectedIds: Set<String>,
      verified: BooleanArray,
      buffers: TorrentBufferBudget,
      state: TorrentBufferBudget,
      maxActive: Int = 2,
    ): TorrentV2PieceScheduler? {
      require(layout.pieceCount in 0..1_000_000 && layout.pieceLength <= 16 * 1024 * 1024)
      require(verified.size.toLong() == layout.pieceCount && maxActive in 1..1024)
      require(layout.files.size <= 100_000 && selectedIds.size <= layout.files.size)
      // Wanted/verified/busy indexes, selection validation and actual maximum blocks per piece.
      val blockCount = (layout.pieceLength / PeerWire.BLOCK_SIZE).toInt()
      val perPiece = 256 + blockCount * 256
      val bytes = verified.size * 2 + (verified.size + 7) / 8 +
        layout.files.size * 64 + selectedIds.size * 64 +
        maxActive * perPiece + 1024
      val lease = state.reserve(bytes) ?: return null
      try {
        val fileIds = layout.files.map { it.id }.toSet()
        require(fileIds.containsAll(selectedIds)) {
          "Unknown selected file"
        }
        val wanted = BooleanArray(verified.size)
        for (file in layout.files) {
          if (selectedIds.isNotEmpty() && file.id !in selectedIds) continue
          val first = (file.offset / layout.pieceLength).toInt()
          val count = file.length / layout.pieceLength +
            if (file.length % layout.pieceLength == 0L) 0 else 1
          wanted.fill(true, first, first + count.toInt())
        }
        return TorrentV2PieceScheduler(layout, wanted, verified.copyOf(), buffers, maxActive, lease)
      } catch (error: Throwable) {
        lease.close()
        throw error
      }
    }
  }
}
