package com.linroid.ketch.torrent.v2

import com.linroid.ketch.torrent.engine.TorrentBufferBudget
import com.linroid.ketch.torrent.metainfo.TorrentContentLayout
import com.linroid.ketch.torrent.peer.PeerMessage
import com.linroid.ketch.torrent.peer.PeerWire
import com.linroid.ketch.torrent.peer.pieceBitfield
import com.linroid.ketch.torrent.v2.peer.PeerBlockExchange
import com.linroid.ketch.torrent.webseed.WEB_SEED_RUN_BYTES
import com.linroid.ketch.torrent.webseed.WEB_SEED_RUN_PIECES
import com.linroid.ketch.torrent.webseed.WebSeedPiece

/**
 * One session actor owns assignments; peers and the commit worker retain their separate leases.
 * The wanted pieces follow the selection through [retarget]; unwanted pieces are never begun,
 * requested or committed, and their assemblies go once no request is out for them. Web seeds
 * fetch runs of whole pieces it hands out with [claimWebSeedRun], each kept from peers until its
 * [webSeedDone].
 */
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
  // Pieces web seeds fetch, with how many fetch each; more than one only in the endgame.
  private val webSeeds = mutableMapOf<Int, Int>()
  private var webSeedCursor = 0
  val pendingCommitCount: Int get() = commits.size
  val activeCount: Int get() = assemblies.size + commits.size + webSeeds.values.sum()

  /** Raised whenever a piece may have become free to fetch, or stopped being. */
  var version: Long = 0
    private set

  /** What [retarget] changed. */
  class Retarget internal constructor(
    /** Unwanted assemblies closed because no request was out for them. */
    val evicted: Int,
    /** Pieces needed now that were not before. */
    val gained: IntArray,
    /** Pieces needed before that are not now. */
    val lost: IntArray,
    /** Pieces the store verified that this scheduler did not know of. */
    val verified: IntArray,
  )

  fun isVerified(index: Int): Boolean {
    check(!closed)
    return verified[index]
  }

  fun isWanted(index: Int): Boolean {
    check(!closed)
    return wanted[index]
  }

  /**
   * Follows a new selection: wants the pieces of [selectedIds], and takes the store's verified
   * bits, [storeVerified], for every piece not being assembled or committed. Unwanted assemblies
   * without requests out are closed; the rest go once their requests are answered.
   */
  fun retarget(selectedIds: Set<String>, storeVerified: BooleanArray): Retarget {
    check(!closed)
    require(storeVerified.size == verified.size)
    val next = wantedPieces(layout, selectedIds, verified.size)
    val gained = mutableListOf<Int>()
    val lost = mutableListOf<Int>()
    val newlyVerified = mutableListOf<Int>()
    for (index in verified.indices) {
      val before = wanted[index] && !verified[index]
      if (!isBusy(index)) {
        if (storeVerified[index] && !verified[index]) newlyVerified += index
        verified[index] = storeVerified[index]
      }
      wanted[index] = next[index]
      val after = wanted[index] && !verified[index]
      if (after && !before) gained += index else if (before && !after) lost += index
    }
    remaining = wanted.indices.count { wanted[it] && !verified[it] }
    version++
    return Retarget(evictUnwanted(), gained.toIntArray(), lost.toIntArray(),
      newlyVerified.toIntArray())
  }

  /** A verified piece could no longer be read back; it is needed again if it is wanted. */
  fun revoke(index: Int) {
    check(!closed)
    if (!verified[index]) return
    verified[index] = false
    if (wanted[index]) remaining++
    version++
  }

  /**
   * Closes the assemblies of unwanted pieces, and of pieces a web seed verified meanwhile, that no
   * request is out for; returns how many.
   */
  fun evictUnwanted(): Int {
    check(!closed)
    val unwanted = assemblies.keys.filter { !wanted[it] || verified[it] }
    if (unwanted.isEmpty()) return 0
    val requested = assignments.keys.mapTo(HashSet()) { it.index }
    var count = 0
    for (index in unwanted) {
      if (index in requested) continue
      checkNotNull(assemblies.remove(index)).close()
      refreshBusy(index)
      count++
    }
    if (count > 0) version++
    return count
  }

  /** Verified pieces as a wire bitfield, spare bits zero. */
  fun verifiedBits(): ByteArray {
    check(!closed)
    return pieceBitfield(verified)
  }

  fun anyVerified(): Boolean {
    check(!closed)
    return verified.any { it }
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
    if (activeCount >= maxActive) return false
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
    version++
    return true
  }

  /**
   * A whole piece for a web seed, with its buffer reserved: the first wanted piece nobody fetches,
   * from where the last claim was, so its HTTP reads follow the files. In the endgame, when every
   * piece left is being fetched, a piece peers assemble that no web seed fetches yet; whichever
   * verifies it first stores it. Null when there is none, or no buffer is free.
   */
  fun claimWebSeed(): WebSeedClaim? {
    check(!closed)
    var free = -1
    var anyFree = false
    var duplicate = -1
    val count = verified.size
    for (step in 0 until count) {
      val index = (webSeedCursor + step) % count
      if (!wanted[index] || verified[index]) continue
      if (!isBusy(index)) {
        anyFree = true
        if (activeCount < maxActive) free = index
        break
      }
      if (duplicate < 0 && index in assemblies && index !in webSeeds) duplicate = index
    }
    val index = if (free >= 0) free else if (!anyFree && duplicate >= 0) duplicate else
      return null
    val length = layout.v2Piece(index.toLong()).length.toInt()
    val lease = buffers.reserve(length + 512) ?: return null
    val claim = try { WebSeedClaim(index, ByteArray(length), lease) } catch (error: Throwable) {
      lease.close()
      throw error
    }
    webSeeds[index] = (webSeeds[index] ?: 0) + 1
    markBusy(index, true)
    webSeedCursor = index
    version++
    return claim
  }

  /**
   * A run of whole pieces for a web seed, each with its buffer reserved: the piece [claimWebSeed]
   * takes, then the wanted pieces that follow it while nobody fetches them, at most [maxPieces]
   * and [maxBytes] in all. A run takes no more than its share of the free pieces, split between
   * the [fetchers] (peers and seeds), and its later pieces only buffers that leave half the budget
   * free and active slots below the limit, so peers keep pieces to fetch and buffers to fetch them
   * with. An endgame duplicate is never extended, and the cursor stays on the run's first piece,
   * so pieces a failed run gives back are fetched next. Empty when there is nothing to fetch.
   */
  fun claimWebSeedRun(
    fetchers: Int,
    maxPieces: Int = WEB_SEED_RUN_PIECES,
    maxBytes: Int = WEB_SEED_RUN_BYTES,
  ): List<WebSeedClaim> {
    val first = claimWebSeed() ?: return emptyList()
    // A duplicate of a piece peers assemble: the endgame.
    if (first.index in assemblies) return listOf(first)
    val run = mutableListOf(first)
    val free = verified.indices.count { wanted[it] && !verified[it] && !isBusy(it) }
    val share = maxOf(1, (free + 1) / maxOf(1, fetchers))
    var bytes = first.bytes.size
    var index = first.index + 1
    while (run.size < minOf(maxPieces, share) && index < verified.size) {
      if (!wanted[index] || verified[index] || isBusy(index) || activeCount >= maxActive) break
      val length = layout.v2Piece(index.toLong()).length.toInt()
      val reserved = length + 512
      if (bytes + length > maxBytes ||
        buffers.capacity - buffers.allocated - reserved < buffers.capacity / 2) break
      val lease = buffers.reserve(reserved) ?: break
      val claim = try { WebSeedClaim(index, ByteArray(length), lease) } catch (error: Throwable) {
        lease.close()
        throw error
      }
      webSeeds[index] = 1
      markBusy(index, true)
      run += claim
      bytes += length
      index++
    }
    return run
  }

  /** A web seed gave back [index]: [verified] when the store holds it now. */
  fun webSeedDone(index: Int, verified: Boolean) {
    if (closed) return
    val held = checkNotNull(webSeeds[index]) { "No web seed fetches piece $index" }
    if (held == 1) webSeeds.remove(index) else webSeeds[index] = held - 1
    if (verified && !this.verified[index]) {
      this.verified[index] = true
      if (wanted[index]) remaining--
    }
    refreshBusy(index)
    version++
  }

  /** A web seed's piece: its buffer stays reserved until the seed closes [lease]. */
  class WebSeedClaim internal constructor(
    override val index: Int,
    override val bytes: ByteArray,
    val lease: TorrentBufferBudget.Lease,
  ) : WebSeedPiece

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
      val index = assembly.index
      if (!wanted[index] || verified[index] || !available(index)) continue
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
      if (!wanted[assembly.index] || !peer.canRequest(assembly.index)) continue
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

  /** Evict unavailable partial pieces, preserving live assignments and completed assemblies. */
  fun evictUnavailable(available: (Int) -> Boolean): Int {
    check(!closed)
    val discarded = mutableSetOf<Int>()
    for ((index, assembly) in assemblies) {
      if (!assembly.complete && !available(index)) discarded += index
    }
    // Scan assignments once rather than once per active piece.
    for (request in assignments.keys) discarded.remove(request.index)
    for (index in discarded) {
      checkNotNull(assemblies.remove(index)).close()
      refreshBusy(index)
    }
    if (discarded.isNotEmpty()) version++
    return discarded.size
  }

  /** Convenience cleanup for callers that also own this peer's pipeline. */
  fun removePeer(peer: PeerBlockExchange) {
    try { peer.close() } finally { detachPeer(peer) }
  }

  /** Queue pressure leaves completed assemblies here for retry, without accepting more blocks. */
  fun submitReady(worker: TorrentV2CommitWorker): Int {
    check(!closed)
    var count = 0
    // An unwanted piece is not written; its assembly waits to be evicted.
    val ready = assemblies.filter { (index, assembly) ->
      assembly.complete && wanted[index] && !verified[index]
    }.keys
    for (index in ready) {
      val ticket = worker.trySubmit(assemblies.getValue(index)) ?: break
      assemblies.remove(index)
      commits[ticket] = index
      count++
    }
    return count
  }

  /**
   * Only matching tickets publish state; failed, corrupt or discarded commits make the piece
   * retryable. A verified piece counts against [completed] only while it is wanted.
   */
  fun completed(completion: TorrentV2CommitWorker.Completion): Boolean {
    if (closed) return false
    val index = commits.remove(completion.ticket) ?: return false
    refreshBusy(index)
    version++
    if (completion is TorrentV2CommitWorker.Completion.Committed && completion.verified &&
      !verified[index]) {
      verified[index] = true
      if (wanted[index]) remaining--
    }
    return true
  }

  private fun isBusy(index: Int): Boolean = busy[index / 8].toInt() and (128 ushr (index % 8)) != 0

  /** A piece stays busy while it is assembled, committed or fetched by a web seed. */
  private fun refreshBusy(index: Int) {
    markBusy(index, index in assemblies || index in webSeeds || commits.containsValue(index))
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
    webSeeds.clear()
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
      // Every file is charged: the selection may grow to all of them while the session runs.
      val blockCount = (layout.pieceLength / PeerWire.BLOCK_SIZE).toInt()
      val perPiece = 256 + blockCount * 256
      val bytes = verified.size * 2 + (verified.size + 7) / 8 +
        layout.files.size * 128 + maxActive * perPiece + 1024
      val lease = state.reserve(bytes) ?: return null
      try {
        val fileIds = layout.files.map { it.id }.toSet()
        require(fileIds.containsAll(selectedIds)) {
          "Unknown selected file"
        }
        val wanted = wantedPieces(layout, selectedIds, verified.size)
        return TorrentV2PieceScheduler(layout, wanted, verified.copyOf(), buffers, maxActive, lease)
      } catch (error: Throwable) {
        lease.close()
        throw error
      }
    }

    /** The pieces of [selectedIds]' files, every file's when it is empty. */
    private fun wantedPieces(
      layout: TorrentContentLayout,
      selectedIds: Set<String>,
      count: Int,
    ): BooleanArray {
      val wanted = BooleanArray(count)
      for (file in layout.files) {
        if (selectedIds.isNotEmpty() && file.id !in selectedIds) continue
        val first = (file.offset / layout.pieceLength).toInt()
        val pieces = file.length / layout.pieceLength +
          if (file.length % layout.pieceLength == 0L) 0 else 1
        wanted.fill(true, first, first + pieces.toInt())
      }
      return wanted
    }
  }
}
