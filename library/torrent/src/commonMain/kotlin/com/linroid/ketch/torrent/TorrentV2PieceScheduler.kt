package com.linroid.ketch.torrent

/** One session actor owns assignments; peers and the commit worker retain their separate leases. */
internal class TorrentV2PieceScheduler private constructor(
  private val layout: TorrentContentLayout,
  private var selected: Set<String>,
  private var verified: BooleanArray,
  private val buffers: TorrentBufferBudget,
  private val maxActive: Int,
  private val lease: TorrentBufferBudget.Lease,
) {
  private class Assignment(val peer: PeerBlockExchange, val ticket: PeerBlockExchange.Ticket)
  private val assemblies = linkedMapOf<Int, TorrentV2PieceAssembly>()
  private val assignments = mutableMapOf<PeerMessage.Request, Assignment>()
  private val commits = mutableMapOf<TorrentV2CommitWorker.Ticket, Int>()
  private var closed = false
  val activeCount: Int get() = assemblies.size + commits.size

  fun isVerified(index: Int): Boolean {
    check(!closed)
    return verified[index]
  }

  /** The piece-selection policy calls this after choosing a candidate; false means no admission. */
  fun begin(index: Int): Boolean {
    check(!closed)
    require(index in verified.indices)
    val file = layout.v2Piece(index.toLong()).fileId
    if ((selected.isNotEmpty() && file !in selected) || verified[index] ||
      index in assemblies || index in commits.values || activeCount == maxActive) return false
    val assembly = TorrentV2PieceAssembly.create(layout, index, buffers) ?: return false
    assemblies[index] = assembly
    return true
  }

  /** One assignment per canonical block; peers can fill different blocks in parallel. */
  suspend fun requestNext(peer: PeerBlockExchange): PeerBlockExchange.Ticket? {
    check(!closed)
    var attempts = 0
    for (assembly in assemblies.values) {
      if (!peer.canRequest(assembly.index)) continue
      for (slot in assembly.missingBlocks()) {
        val request = assembly.request(slot)
        if (request in assignments) continue
        if (attempts++ == 128) return null
        val ticket = peer.request(request) ?: continue
        assignments[request] = Assignment(peer, ticket)
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
    if (closed || assignment == null || assignment.peer !== peer || assignment.ticket !== ticket) {
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

  /** Close the departing pipeline before making its unanswered blocks available to other peers. */
  fun removePeer(peer: PeerBlockExchange) {
    try { peer.close() } finally {
      val abandoned = assignments.filterValues { it.peer === peer }.keys
      abandoned.forEach(assignments::remove)
    }
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
    verified[index] = completion is TorrentV2CommitWorker.Completion.Committed &&
      completion.verified
    return true
  }

  /** The outer runtime joins peers and the worker; this closes only actor-owned assembly/state. */
  fun close() {
    if (closed) return
    closed = true
    assemblies.values.forEach { it.close() }
    assemblies.clear()
    assignments.clear()
    commits.clear()
    selected = emptySet()
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
      require(verified.size.toLong() == layout.pieceCount && maxActive in 1..16)
      require(layout.files.size <= 100_000 && selectedIds.size <= layout.files.size)
      // Availability copy, selection validation/copy, and up to 1024 assignment records per piece.
      val bytes = verified.size * 2 + layout.files.size * 64 + selectedIds.size * 64 +
        maxActive * 262_144 + 1024
      val lease = state.reserve(bytes) ?: return null
      try {
        val fileIds = layout.files.map { it.id }.toSet()
        require(fileIds.containsAll(selectedIds)) {
          "Unknown selected file"
        }
        return TorrentV2PieceScheduler(layout, selectedIds.toSet(), verified.copyOf(), buffers,
          maxActive, lease)
      } catch (error: Throwable) {
        lease.close()
        throw error
      }
    }
  }
}
