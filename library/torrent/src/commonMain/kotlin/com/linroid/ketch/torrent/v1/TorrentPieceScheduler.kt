package com.linroid.ketch.torrent.v1

import com.linroid.ketch.torrent.engine.TorrentBufferBudget
import com.linroid.ketch.torrent.monotonicClock
import com.linroid.ketch.torrent.peer.PeerMessage
import com.linroid.ketch.torrent.peer.PeerWire
import com.linroid.ketch.torrent.storage.CommitOutcome
import com.linroid.ketch.torrent.storage.SelectionView
import com.linroid.ketch.torrent.webseed.WebSeedPiece
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.concurrent.Volatile

/**
 * Serialized rarity, interest and download state. No disk or network operation runs under its
 * lock, except the store's brief selection snapshot in [retarget].
 *
 * Peers download blocks of *open* pieces. A piece opens, rarest first, when a peer asks for blocks
 * and none of the open pieces it has has a free one; its buffer is reserved whole from [budget],
 * and every peer that has the piece shares it block by block. The budget so bounds the pieces
 * being assembled rather than the peers transferring, and a peer's requests run on across piece
 * ends. Blocks a peer gives back (rejected, choked or gone) are free again, while those received
 * stay. A partial piece nobody fetches from gives its buffer up when another piece needs it after
 * [STALE_PIECE_MS], after [ABANDONED_PIECE_MS] in any case, and at once when no connected peer has
 * it; [close] frees what is left when the swarm stops.
 *
 * The peer that delivers the last block commits the piece ([Arrival.Complete], then [finish]). A
 * piece that fails its hash check is fetched again; when several peers had sent it, from one peer
 * alone, so a peer that keeps sending bad data ends up alone with it and is blamed. Once every
 * wanted piece is open (the endgame), a peer with nothing else to fetch also asks for blocks
 * other peers have outstanding: those they are late with, and of the others about as many as it
 * receives in a second, those they would send last ([endgame]); the first copy to arrive cancels
 * the other ([poll]). Web seeds claim runs of whole pieces instead ([claimRun]), each with its own
 * buffer.
 */
internal class TorrentPieceScheduler(
  wanted: BooleanArray,
  verified: BooleanArray,
  private val pieceSize: (Int) -> Int,
  private val budget: TorrentBufferBudget,
  private val nowMs: () -> Long = monotonicClock(),
  /**
   * Payload handed to [received] that is thrown away rather than verified: a block not needed,
   * a piece that fails its hash check or whose commit does not finish, and the blocks of a
   * partial piece that closes. Called under the lock.
   */
  private val onDiscard: (Long) -> Unit = {},
) {
  private val mutex = Mutex()
  private var wanted = wanted.copyOf()
  private var verified = verified.copyOf()
  private val pieceCount = wanted.size

  /** Raised by every [retarget]; a seed that downloads again judges its peers from then on. */
  @Volatile var wantVersion: Long = 0
    private set
  private val availability = mutableMapOf<Int, BooleanArray>()
  /** Per peer: the wanted, unverified pieces it has, which our interest in it follows. */
  private val wantedFrom = mutableMapOf<Int, Int>()
  /** Per peer: the pieces it has. */
  private val held = mutableMapOf<Int, Int>()
  private val rarity = IntArray(pieceCount)
  /** Blocks of a full piece, which sizes how many pieces a request opens. */
  private val blocksPerPiece = if (pieceCount == 0) 1 else (pieceSize(0) + BLOCK - 1) / BLOCK

  /** Open pieces in the order they opened, so requests finish the oldest first. */
  private val open = LinkedHashMap<Int, OpenPiece>()
  private val openPieces = arrayOfNulls<OpenPiece>(pieceCount)
  /** Open pieces with a block nobody asked for yet. */
  private val withFree = LinkedHashSet<OpenPiece>()
  /** Requests each peer should cancel: copies another peer delivered, pieces done or dropped. */
  private val cancels = mutableMapOf<Int, MutableList<PeerMessage.Request>>()

  /** Per piece: the web seeds fetching it. */
  private val seedOwned = IntArray(pieceCount)
  private val seedOwners = mutableMapOf<Int, MutableSet<Int>>()
  // A web seed's run of whole pieces.
  private val claims = mutableMapOf<Int, MutableList<Claim>>()

  /**
   * Bytes of [budget] this scheduler's pieces hold, open ones and web seeds' claims: buffers its
   * peers finish and give back, read without the lock to admit peers.
   */
  @Volatile var heldBytes: Int = 0
    private set

  /** Wanted, unverified pieces nobody fetches: neither open nor claimed by a web seed. */
  private var unopened = 0
  /** Wanted, unverified pieces. */
  private var remaining = 0
  private var verifiedCount = 0
  /** Raised whenever a piece may have become openable, or a buffer freed. */
  private var openings = 0L
  /** Raised for every block a peer is asked for, owed or copied: the order peers were asked in. */
  private var asks = 0L
  /** When idle partial pieces were last looked for. */
  private var sweptAt = 0L
  /** When each peer last looked for pieces to open and could not open all it wanted. */
  private val searches = mutableMapOf<Int, Search>()
  // When each peer last looked for late blocks.
  private val lateScans = mutableMapOf<Int, Long>()
  /**
   * Per peer: the deadline of the blocks it was last handed. It sends what it is asked for in
   * order, so it is never late with a block before those it was asked for earlier, while it still
   * owes any of them ([dueFor]).
   */
  private val lastDue = mutableMapOf<Int, Long>()
  // Wanted pieces no connected peer or seed has, and when they were counted ([sourceless]).
  private var sourcelessCount = 0
  private var sourcelessAt = Long.MIN_VALUE / 2

  /** Verified pieces in the order they verified, each once, for HAVEs ([poll]). */
  private var haveLog = IntArray(16)
  private var haveCount = 0
  private val logged = BooleanArray(pieceCount)

  /** A web seed's piece, with a buffer of its own. */
  class Claim(override val index: Int, val lease: TorrentBufferBudget.Lease) : WebSeedPiece {
    override val bytes: ByteArray = ByteArray(lease.bytes)
  }

  /** What [request] hands a peer: blocks to ask for, and whether the budget held some back. */
  class Grant(val requests: List<PeerMessage.Request>, val starved: Boolean)

  /** How a block a peer delivered was taken. */
  sealed interface Arrival {
    /** Stored; its piece still lacks blocks. */
    data object Kept : Arrival

    /** Not needed: its piece is verified or dropped, or another copy came first. */
    data object Unneeded : Arrival

    /** The piece's last block: the caller commits [bytes], then calls [finish]. */
    class Complete(val index: Int, val bytes: ByteArray) : Arrival
  }

  /** What [poll] tells a peer's worker. */
  class Poll(
    /** Pieces verified since the worker's cursor, to announce; null when none. */
    val haves: IntArray?,
    /** The cursor to pass next time. */
    val haveCursor: Int,
    /** Requests to cancel on the wire; their blocks are no longer the peer's. */
    val cancels: List<PeerMessage.Request>,
    /** The peer has a piece we want and have not verified (BEP 3 interest). */
    val wanted: Boolean,
    /**
     * The peer and we both have every piece, so neither will ever want anything of the other,
     * whatever the selection becomes.
     */
    val bothSeeds: Boolean,
  )

  private class Search(val openings: Long, val at: Long)

  /**
   * A block's second requester in the endgame, with the [OpenPiece.dueAt] and [OpenPiece.askedAt]
   * it takes over should the block's owner give it back.
   */
  private class Copy(val peer: Int, val dueAt: Long, val askedAt: Long)

  private class OpenPiece(val index: Int, val lease: TorrentBufferBudget.Lease, now: Long) {
    val bytes = ByteArray(lease.bytes)
    val blocks = (bytes.size + BLOCK - 1) / BLOCK
    /** Per block: [FREE], [RECEIVED], or the peer that asked for it. */
    val owner = IntArray(blocks) { FREE }
    /** Per block asked for: when its first requester is late, so another may ask too. */
    val dueAt = LongArray(blocks)
    /** Per block asked for: the [asks] count when the peer that owes it was asked for it. */
    val askedAt = LongArray(blocks)
    /** Second requesters of blocks duplicated in the endgame. */
    var duplicates: MutableMap<Int, Copy>? = null
    var free = blocks
    var received = 0
    /** The first block that may be free. */
    var cursor = 0
    /** Peers whose blocks it holds. */
    val contributors = mutableSetOf<Int>()
    /** [ANYONE], [UNASSIGNED] after a failed hash check, or the one peer that fetches it. */
    var single = ANYONE
    var committing = false
    var activeAt = now

    fun length(block: Int) = minOf(BLOCK, bytes.size - block * BLOCK)

    fun request(block: Int) = PeerMessage.Request(index, block * BLOCK, length(block))

    /** Peers' requests outstanding on it. */
    val outstanding: Int get() = blocks - free - received

    /** The bytes of the blocks it holds. */
    fun receivedBytes(): Long {
      var bytes = 0L
      for (block in 0 until blocks) if (owner[block] == RECEIVED) bytes += length(block)
      return bytes
    }
  }

  init {
    require(wanted.size == verified.size)
    for (index in 0 until pieceCount) {
      if (this.wanted[index] && !this.verified[index]) {
        unopened++
        remaining++
      }
      if (this.verified[index]) {
        verifiedCount++
        logHave(index)
      }
    }
  }

  suspend fun availability(peer: Int, pieces: BooleanArray): Unit = mutex.withLock {
    require(pieces.size == pieceCount)
    val previous = availability.put(peer, pieces.copyOf())
    var count = 0
    var wantedCount = 0
    for (index in pieces.indices) {
      if (previous?.get(index) == true) rarity[index]--
      if (pieces[index]) {
        rarity[index]++
        count++
        if (wanted[index] && !verified[index]) wantedCount++
      }
    }
    held[peer] = count
    wantedFrom[peer] = wantedCount
    searches.remove(peer)
  }

  /** Whether [peer] has a wanted piece that is not verified yet. */
  suspend fun hasWanted(peer: Int): Boolean = mutex.withLock { (wantedFrom[peer] ?: 0) > 0 }

  /** [peer] no longer has [indices]: a web seed that cannot serve them. */
  suspend fun withdraw(peer: Int, indices: Collection<Int>): Unit = mutex.withLock {
    val pieces = availability[peer] ?: return@withLock
    for (index in indices) {
      if (index !in 0 until pieceCount || !pieces[index]) continue
      pieces[index] = false
      rarity[index]--
      held[peer] = (held[peer] ?: 1) - 1
      if (wanted[index] && !verified[index]) wantedFrom[peer] = (wantedFrom[peer] ?: 1) - 1
    }
    searches.remove(peer)
  }

  /**
   * Applies a single HAVE without rebuilding this peer's availability. A peer may announce
   * before any bitfield, and may repeat announcements, so absent state starts empty and a
   * duplicate index is ignored rather than counted twice.
   */
  suspend fun announce(peer: Int, index: Int): Unit = mutex.withLock {
    require(index in 0 until pieceCount)
    val pieces = availability.getOrPut(peer) { BooleanArray(pieceCount) }
    if (!pieces[index]) {
      pieces[index] = true
      rarity[index]++
      held[peer] = (held[peer] ?: 0) + 1
      if (wanted[index] && !verified[index]) {
        wantedFrom[peer] = (wantedFrom[peer] ?: 0) + 1
        searches.remove(peer)
      }
    }
  }

  /**
   * Blocks for [peer] to ask for, at most [max] and only of pieces it has that [eligible]
   * accepts: free blocks of open pieces first, then those of pieces it opens, rarest first, then
   * blocks other peers are late with; in the endgame, also some blocks other peers have
   * outstanding ([endgame]).
   * [lateAfterMs] is how long [peer] may take to deliver what it is handed before another peer
   * may ask for it too, though never less than what it was handed before has left, and [copies]
   * the most blocks others owe on time it may hold in the endgame ([endgameCopies]).
   */
  suspend fun request(
    peer: Int,
    max: Int,
    lateAfterMs: Long = DEFAULT_LATE_MS,
    copies: Int = ENDGAME_DUPLICATES,
    eligible: (Int) -> Boolean = { true },
  ): Grant = mutex.withLock {
    val pieces = availability[peer] ?: return@withLock Grant(emptyList(), false)
    val requests = ArrayList<PeerMessage.Request>(minOf(max, 64))
    val now = nowMs()
    val dueAt = dueFor(peer, now + lateAfterMs)
    val iterator = withFree.iterator()
    while (requests.size < max && iterator.hasNext()) {
      val piece = iterator.next()
      if (!takes(piece, peer, pieces, eligible)) continue
      take(piece, peer, max, requests, now, dueAt)
      if (piece.free == 0) iterator.remove()
    }
    var starved = false
    if (requests.size < max && searchDue(peer, now)) {
      starved = openFor(peer, pieces, eligible, max, requests, now, dueAt)
      if (requests.size < max) searches[peer] = Search(openings, now)
    }
    // Pieces no connected peer or seed has cannot be opened: they never hold the endgame back.
    if (requests.size < max && unopened <= sourceless(now)) {
      if (endgame(peer, pieces, eligible, max, copies, requests, now, dueAt, late = false)) {
        starved = true
      }
    } else if (requests.size < max && lateScanDue(peer, now)) {
      // An idle peer takes over blocks a slow one sits on, which also frees their buffers.
      endgame(peer, pieces, eligible, max, copies, requests, now, dueAt, late = true)
    }
    if (requests.isNotEmpty()) lastDue[peer] = dueAt
    Grant(requests, starved)
  }

  /**
   * What a stalled swarm reports: [missingCount] wanted pieces no connected peer or running web
   * seed has, the first [missing] of them, and [lateBlocks] requested blocks their peer is late
   * with.
   */
  class Health(val missingCount: Int, val missing: IntArray, val lateBlocks: Int)

  /**
   * The swarm's [Health] now, naming at most [limit] missing pieces; with [among], only those of
   * its pieces can be missing. The web seeds in [ignoring] count as having and fetching none.
   */
  suspend fun health(
    limit: Int,
    among: Set<Int>? = null,
    ignoring: Set<Int> = emptySet(),
  ): Health = mutex.withLock {
    val now = nowMs()
    val ignored = ignoring.mapNotNull { availability[it] }
    // A peer or seed not ignored has piece [index], or such a seed fetches it.
    fun sourced(index: Int): Boolean = rarity[index] > ignored.count { it[index] } ||
      seedOwned[index] > (seedOwners[index]?.count { it in ignoring } ?: 0)
    val missing = ArrayList<Int>()
    var count = 0
    for (index in among?.sorted() ?: (0 until pieceCount)) {
      if (index !in 0 until pieceCount) continue
      if (wanted[index] && !verified[index] && !sourced(index)) {
        count++
        if (missing.size < limit) missing += index
      }
    }
    var late = 0
    for (piece in open.values) {
      if (piece.committing) continue
      for (block in 0 until piece.blocks) {
        if (piece.owner[block] >= 0 && now >= piece.dueAt[block]) late++
      }
    }
    Health(count, missing.toIntArray(), late)
  }

  /** Whether [peer] may look for late blocks now: at most every [LATE_SCAN_MS]. */
  private fun lateScanDue(peer: Int, now: Long): Boolean {
    val last = lateScans[peer]
    if (last != null && now - last < LATE_SCAN_MS) return false
    lateScans[peer] = now
    return true
  }

  /**
   * Wanted pieces nobody fetches that no connected peer or seed has, counted at most every
   * [SOURCELESS_SCAN_MS]: while they are all that is left unopened, the endgame runs.
   */
  private fun sourceless(now: Long): Int {
    if (unopened == 0) return 0
    // Recounted once a second: peers come and go far less often than pieces open, and a piece
    // nobody has never opens, so opening one leaves the count as it was.
    if (now - sourcelessAt < SOURCELESS_SCAN_MS) return sourcelessCount
    var count = 0
    for (index in 0 until pieceCount) {
      if (wanted[index] && !verified[index] && openPieces[index] == null &&
        seedOwned[index] == 0 && rarity[index] == 0) count++
    }
    sourcelessCount = count
    sourcelessAt = now
    return count
  }

  /**
   * Stores block [begin] of piece [index] from [peer]. Whoever else asked for it is told to
   * cancel; the last block completes the piece, which then waits for [finish].
   */
  suspend fun received(peer: Int, index: Int, begin: Int, data: ByteArray): Arrival =
    mutex.withLock {
      fun unneeded(): Arrival {
        onDiscard(data.size.toLong())
        return Arrival.Unneeded
      }
      val piece = openPieces.getOrNull(index) ?: return@withLock unneeded()
      if (piece.committing || begin % BLOCK != 0) return@withLock unneeded()
      val block = begin / BLOCK
      if (block >= piece.blocks || data.size != piece.length(block)) {
        return@withLock unneeded()
      }
      val owner = piece.owner[block]
      if (owner == RECEIVED) return@withLock unneeded()
      data.copyInto(piece.bytes, begin)
      piece.owner[block] = RECEIVED
      piece.received++
      if (owner == FREE) {
        piece.free--
        if (piece.free == 0) withFree.remove(piece)
      }
      piece.contributors += peer
      piece.activeAt = nowMs()
      val second = piece.duplicates?.remove(block)?.peer
      if (owner >= 0 && owner != peer) cancel(owner, piece.request(block))
      if (second != null && second != peer) cancel(second, piece.request(block))
      if (piece.received < piece.blocks) return@withLock Arrival.Kept
      piece.committing = true
      Arrival.Complete(index, piece.bytes)
    }

  /**
   * Ends the commit of piece [index], which [peer] completed: [outcome] is null when the commit
   * did not finish, as when the peer stopped meanwhile, and its blocks are fetched again. Returns
   * whether the piece failed its hash check with [peer] the only peer that sent it.
   */
  suspend fun finish(peer: Int, index: Int, outcome: CommitOutcome?): Boolean = mutex.withLock {
    val piece = openPieces.getOrNull(index)?.takeIf { it.committing } ?: return@withLock false
    when (outcome) {
      CommitOutcome.VERIFIED -> {
        close(piece, discard = false)
        markVerified(index)
        false
      }
      CommitOutcome.NOT_WANTED -> {
        close(piece)
        false
      }
      CommitOutcome.CORRUPT, null -> {
        val alone = outcome != null && piece.contributors.singleOrNull() == peer
        // Every block of it came; all of them are fetched again.
        onDiscard(piece.bytes.size.toLong())
        piece.owner.fill(FREE)
        piece.duplicates = null
        piece.free = piece.blocks
        piece.received = 0
        piece.cursor = 0
        piece.contributors.clear()
        piece.committing = false
        // Fetched again from one peer, unless the one that sent it is dropped for it.
        piece.single = if (alone || outcome == null) ANYONE else UNASSIGNED
        piece.activeAt = nowMs()
        withFree += piece
        openings++
        alone
      }
    }
  }

  /** Frees blocks [peer] will not deliver: rejected, or canceled by us. */
  suspend fun release(peer: Int, requests: Collection<PeerMessage.Request>) = mutex.withLock {
    for (request in requests) releaseBlock(peer, request.index, request.begin)
  }

  /** Frees every block [peer] has outstanding, as when it chokes us; received blocks stay. */
  suspend fun releasePeer(peer: Int): Unit = mutex.withLock { releasePeerLocked(peer) }

  /**
   * What [peer]'s worker acts on in each pass: newly verified pieces since [haveCursor], requests
   * to cancel, its interest and whether it is a seed.
   */
  suspend fun poll(peer: Int, haveCursor: Int): Poll = mutex.withLock {
    val now = nowMs()
    if (now - sweptAt >= SWEEP_MS) {
      sweptAt = now
      while (evictIdle(now, ABANDONED_PIECE_MS)) { }
    }
    Poll(
      haves = if (haveCursor == haveCount) null else haveLog.copyOfRange(haveCursor, haveCount),
      haveCursor = haveCount,
      cancels = cancels.remove(peer) ?: emptyList(),
      wanted = (wantedFrom[peer] ?: 0) > 0,
      bothSeeds = held[peer] == pieceCount && verifiedCount == pieceCount,
    )
  }

  /** The end of the verified log now: pieces verified later appear past it in [poll]. */
  suspend fun haveCursor(): Int = mutex.withLock { haveCount }

  /**
   * Claims a run for web seed [peer]: the first wanted piece nobody fetches, then the wanted
   * pieces that follow it while nobody fetches them, at most [maxPieces] and [maxBytes] in all, so
   * its HTTP reads follow the files. A run takes no more than its share of the pieces nobody
   * fetches, split between every peer and seed, and its later pieces only buffers that leave half
   * the budget free, so peers keep pieces to fetch and buffers to fetch them with. In the endgame
   * a seed may fetch a piece one other fetcher has, and such a duplicate is never extended.
   */
  suspend fun claimRun(peer: Int, maxPieces: Int, maxBytes: Int): List<Claim> = mutex.withLock {
    check(peer !in claims)
    // A seed has every piece but those it could not serve ([withdraw]).
    val has = availability[peer]
    var first = -1
    for (index in 0 until pieceCount) {
      if (wanted[index] && !verified[index] && openPieces[index] == null &&
        seedOwned[index] == 0 && has?.get(index) != false) {
        first = index
        break
      }
    }
    if (first < 0 && remaining <= availability.size) {
      for (index in 0 until pieceCount) {
        val fetchers = seedOwned[index] + if (openPieces[index] != null) 1 else 0
        if (wanted[index] && !verified[index] && fetchers < 2 && has?.get(index) != false) {
          first = index
          break
        }
      }
    }
    if (first < 0) return@withLock emptyList()
    val duplicate = openPieces[first] != null || seedOwned[first] > 0
    val claim = seedClaim(peer, first) ?: return@withLock emptyList()
    val run = mutableListOf(claim)
    claims[peer] = run
    if (duplicate) return@withLock run.toList()
    val share = maxOf(1, (unopened + 1) / maxOf(1, availability.size))
    var bytes = claim.lease.bytes
    var index = first + 1
    while (run.size < minOf(maxPieces, share) && index < pieceCount) {
      if (!wanted[index] || verified[index] || openPieces[index] != null ||
        seedOwned[index] > 0 || has?.get(index) == false) break
      val size = pieceSize(index)
      if (bytes + size > maxBytes ||
        budget.capacity - budget.allocated - size < budget.capacity / 2) break
      run += seedClaim(peer, index) ?: break
      bytes += size
      index++
    }
    run.toList()
  }

  private fun seedClaim(peer: Int, index: Int): Claim? {
    val lease = budget.reserve(pieceSize(index)) ?: return null
    val claim = try { Claim(index, lease) } catch (e: Throwable) { lease.close(); throw e }
    heldBytes += lease.bytes
    track(index) {
      seedOwned[index]++
      seedOwners.getOrPut(index) { mutableSetOf() }.add(peer)
    }
    return claim
  }

  suspend fun isVerified(index: Int): Boolean = mutex.withLock { verified[index] }

  suspend fun isWanted(index: Int): Boolean = mutex.withLock { wanted[index] }

  /**
   * Takes the store's selection from [read], which runs under this lock: a commit that finishes
   * meanwhile publishes its piece here only afterwards, so it is never lost. Newly verified
   * pieces are announced; open pieces no longer wanted close, and their requests are canceled.
   */
  suspend fun retarget(read: suspend () -> SelectionView): SelectionView = mutex.withLock {
    val view = read()
    require(view.wanted.size == pieceCount && view.verified.size == pieceCount)
    for (index in 0 until pieceCount) {
      if (view.verified[index] && !verified[index]) logHave(index)
    }
    wanted = view.wanted.copyOf()
    verified = view.verified.copyOf()
    verifiedCount = verified.count { it }
    // A piece being committed is left to its commit, which the store judges.
    for (piece in open.values.toList()) {
      if (!piece.committing && (!wanted[piece.index] || verified[piece.index])) close(piece)
    }
    unopened = 0
    remaining = 0
    for (index in 0 until pieceCount) {
      if (wanted[index] && !verified[index]) {
        remaining++
        if (openPieces[index] == null && seedOwned[index] == 0) unopened++
      }
    }
    for ((peer, pieces) in availability) {
      wantedFrom[peer] = pieces.indices.count { pieces[it] && wanted[it] && !verified[it] }
    }
    openings++
    wantVersion++
    view
  }

  /** Forgets a piece the store could no longer read; it downloads again if still wanted. */
  suspend fun revoke(index: Int) = mutex.withLock {
    if (verified[index]) {
      track(index) { verified[index] = false }
      verifiedCount--
      countWanted(index, 1)
      openings++
    }
  }

  /** A piece verified elsewhere, such as by a web seed: peers assembling it cancel. */
  suspend fun verified(index: Int) = mutex.withLock {
    openPieces[index]?.takeIf { !it.committing }?.let(::close)
    markVerified(index)
  }

  /** Gives back piece [index] of the run web seed [peer] fetches, keeping the rest of it. */
  suspend fun release(peer: Int, index: Int) = mutex.withLock {
    val run = claims[peer] ?: return@withLock
    val claim = run.firstOrNull { it.index == index } ?: return@withLock
    run.remove(claim)
    if (run.isEmpty()) claims.remove(peer)
    releaseClaim(peer, claim)
  }

  /**
   * [peer] is gone: its blocks and claims are freed, its availability forgotten, and partial
   * pieces no connected peer has give their buffers up.
   */
  suspend fun remove(peer: Int): Unit = mutex.withLock {
    releasePeerLocked(peer)
    claims.remove(peer)?.forEach { releaseClaim(peer, it) }
    availability.remove(peer)?.forEachIndexed { index, present -> if (present) rarity[index]-- }
    wantedFrom.remove(peer)
    held.remove(peer)
    cancels.remove(peer)
    searches.remove(peer)
    lateScans.remove(peer)
    lastDue.remove(peer)
    for (piece in open.values.toList()) {
      if (!piece.committing && piece.outstanding == 0 &&
        availability.values.none { it[piece.index] }) close(piece)
    }
  }

  /**
   * The swarm stopped: every buffer still held goes back to the budget. A piece being committed
   * is left to its commit, which may still verify it.
   */
  suspend fun close(): Unit = mutex.withLock {
    for (piece in open.values.toList()) close(piece, discard = !piece.committing)
    for ((peer, run) in claims.toList()) run.forEach { releaseClaim(peer, it) }
    claims.clear()
    cancels.clear()
  }

  private fun takes(
    piece: OpenPiece,
    peer: Int,
    pieces: BooleanArray,
    eligible: (Int) -> Boolean,
  ): Boolean = !piece.committing && piece.free > 0 && pieces[piece.index] &&
    (piece.single == ANYONE || piece.single == UNASSIGNED || piece.single == peer) &&
    eligible(piece.index)

  /** Hands [peer] free blocks of [piece], in order, until [requests] holds [max]. */
  private fun take(
    piece: OpenPiece,
    peer: Int,
    max: Int,
    requests: MutableList<PeerMessage.Request>,
    now: Long,
    dueAt: Long,
  ) {
    if (piece.single == UNASSIGNED) piece.single = peer
    var block = piece.cursor
    while (block < piece.blocks && requests.size < max) {
      if (piece.owner[block] == FREE) {
        piece.owner[block] = peer
        piece.dueAt[block] = dueAt
        piece.askedAt[block] = ++asks
        piece.free--
        requests += piece.request(block)
      }
      block++
    }
    while (piece.cursor < piece.blocks && piece.owner[piece.cursor] != FREE) piece.cursor++
    piece.activeAt = now
  }

  /** Whether [peer] should look for pieces to open: something changed, or a while passed. */
  private fun searchDue(peer: Int, now: Long): Boolean {
    val last = searches[peer] ?: return true
    return last.openings != openings || now - last.at >= RESEARCH_MS
  }

  /**
   * Opens pieces [peer] has, rarest first, then lowest index, and hands it their blocks until
   * [requests] holds [max]. Returns whether the budget had no buffer for one.
   */
  private fun openFor(
    peer: Int,
    pieces: BooleanArray,
    eligible: (Int) -> Boolean,
    max: Int,
    requests: MutableList<PeerMessage.Request>,
    now: Long,
    dueAt: Long,
  ): Boolean {
    val wantedPieces = (max - requests.size + blocksPerPiece - 1) / blocksPerPiece
    // One pass counts candidates per rarity, so the pieces to open are found without sorting.
    val counts = IntArray(availability.size + 2)
    for (index in 0 until pieceCount) {
      if (opens(index, pieces, eligible)) counts[minOf(rarity[index], counts.size - 1)]++
    }
    var threshold = 0
    var below = 0
    while (threshold < counts.size && below + counts[threshold] < wantedPieces) {
      below += counts[threshold]
      threshold++
    }
    if (below + counts.getOrElse(threshold) { 0 } == 0) return false
    val chosen = IntArray(minOf(wantedPieces, below + counts.getOrElse(threshold) { 0 }))
    var size = 0
    var atThreshold = chosen.size - below
    for (index in 0 until pieceCount) {
      if (size == chosen.size) break
      if (!opens(index, pieces, eligible)) continue
      val level = minOf(rarity[index], counts.size - 1)
      if (level < threshold || level == threshold && atThreshold-- > 0) chosen[size++] = index
    }
    // Rarest first, then lowest index.
    val order = chosen.copyOf(size).sortedWith(compareBy({ rarity[it] }, { it }))
    for (index in order) {
      if (requests.size >= max) break
      val piece = openPiece(index, now) ?: return true
      take(piece, peer, max, requests, now, dueAt)
      if (piece.free > 0) withFree += piece
    }
    return false
  }

  private fun opens(index: Int, pieces: BooleanArray, eligible: (Int) -> Boolean): Boolean =
    wanted[index] && !verified[index] && openPieces[index] == null && seedOwned[index] == 0 &&
      pieces[index] && eligible(index)

  /**
   * Opens piece [index] with a buffer from the budget, making room by dropping a stale partial
   * piece if it must; null when there is none.
   */
  private fun openPiece(index: Int, now: Long): OpenPiece? {
    val size = pieceSize(index)
    var lease = budget.reserve(size)
    while (lease == null && evictIdle(now, STALE_PIECE_MS)) lease = budget.reserve(size)
    if (lease == null) return null
    val piece = try { OpenPiece(index, lease, now) } catch (e: Throwable) { lease.close(); throw e }
    heldBytes += lease.bytes
    track(index) {
      open[index] = piece
      openPieces[index] = piece
    }
    return piece
  }

  /** Drops the partial piece idle longest, if one nobody fetched from for [idleMs]. */
  private fun evictIdle(now: Long, idleMs: Long): Boolean {
    val stale = open.values.filter {
      !it.committing && it.outstanding == 0 && now - it.activeAt >= idleMs
    }.minByOrNull { it.activeAt } ?: return false
    close(stale)
    return true
  }

  /**
   * In the endgame, [peer] also asks for blocks one other peer has outstanding, and assembles
   * pieces web seeds fetch alone. Returns whether the budget had no buffer for one.
   *
   * It asks for every block whose owner is late with it, as [max] allows. Of the others, only once
   * it owes no block of its own, and at most [copies] at once, due last first: an owner sends what
   * it is asked for in order, so the copy of a block far back in its queue arrives first and
   * cancels it before it is sent, while the copy of one at its head arrives as well. [peer] is
   * late with what it is handed at [dueAt].
   */
  private fun endgame(
    peer: Int,
    pieces: BooleanArray,
    eligible: (Int) -> Boolean,
    max: Int,
    copies: Int,
    requests: MutableList<PeerMessage.Request>,
    now: Long,
    dueAt: Long,
    late: Boolean,
  ): Boolean {
    var held = 0
    var owes = false
    for (piece in open.values) {
      piece.duplicates?.values?.forEach { if (it.peer == peer) held++ }
      if (!owes && !piece.committing && peer in piece.owner) owes = true
    }
    val onTime = if (late || owes) null else DueLast(copies - held)
    for (piece in open.values) {
      if (requests.size >= max) break
      if (piece.committing || !pieces[piece.index] || !eligible(piece.index) ||
        piece.single != ANYONE && piece.single != peer) continue
      for (block in 0 until piece.blocks) {
        if (requests.size >= max) break
        val owner = piece.owner[block]
        if (owner < 0 || owner == peer || piece.duplicates?.containsKey(block) == true) continue
        if (now >= piece.dueAt[block]) {
          duplicate(piece, block, peer, requests, now, dueAt)
          held++
        } else {
          onTime?.offer(piece, block)
        }
      }
    }
    if (onTime != null) {
      val room = minOf(onTime.size, copies - held, max - requests.size)
      for (i in 0 until room) {
        duplicate(onTime.piece(i), onTime.block(i), peer, requests, now, dueAt)
      }
    }
    // Outside the endgame, only late blocks: pieces web seeds fetch alone wait for it.
    if (late) return false
    for (index in 0 until pieceCount) {
      if (requests.size >= max) break
      if (!wanted[index] || verified[index] || openPieces[index] != null ||
        seedOwned[index] != 1 || !pieces[index] || !eligible(index)) continue
      val piece = openPiece(index, now) ?: return true
      take(piece, peer, max, requests, now, dueAt)
      if (piece.free > 0) withFree += piece
    }
    return false
  }

  /**
   * Asks [peer] for [block] of [piece] too, beside the peer that owes it, by [dueAt]. Should that
   * peer give the block back, [peer] owes it as it was asked for it: late at [dueAt], and sent in
   * the order of its asks, not of the old owner's blocks.
   */
  private fun duplicate(
    piece: OpenPiece,
    block: Int,
    peer: Int,
    requests: MutableList<PeerMessage.Request>,
    now: Long,
    dueAt: Long,
  ) {
    val duplicates = piece.duplicates ?: mutableMapOf<Int, Copy>().also { piece.duplicates = it }
    duplicates[block] = Copy(peer, dueAt, ++asks)
    requests += piece.request(block)
    piece.activeAt = now
  }

  /**
   * The [capacity] blocks offered that are due last, latest first; of blocks due together, the
   * one asked for later ([OpenPiece.askedAt]), which its owner sends later. That is not always
   * the one offered later: a piece whose blocks are free again is handed out after pieces that
   * opened after it. An owner is never due sooner for a block it was asked for later
   * ([lastDue]), so of its blocks those it sends last come first.
   */
  private class DueLast(capacity: Int) {
    private val pieces = arrayOfNulls<OpenPiece>(maxOf(0, capacity))
    private val blocks = IntArray(pieces.size)
    private val dues = LongArray(pieces.size)
    private val asked = LongArray(pieces.size)
    var size = 0
      private set

    fun piece(i: Int): OpenPiece = checkNotNull(pieces[i])

    fun block(i: Int): Int = blocks[i]

    fun offer(piece: OpenPiece, block: Int) {
      val due = piece.dueAt[block]
      val askedAt = piece.askedAt[block]
      // Its place: after the blocks due later, or due with it and asked for later. Held ones may
      // be hundreds, so the place is searched for and the rest moved down at once.
      var at = 0
      var end = size
      while (at < end) {
        val middle = (at + end) ushr 1
        if (dues[middle] > due || dues[middle] == due && asked[middle] > askedAt) {
          at = middle + 1
        } else {
          end = middle
        }
      }
      if (at == pieces.size) return
      val kept = minOf(size, pieces.size - 1)
      pieces.copyInto(pieces, at + 1, at, kept)
      blocks.copyInto(blocks, at + 1, at, kept)
      dues.copyInto(dues, at + 1, at, kept)
      asked.copyInto(asked, at + 1, at, kept)
      pieces[at] = piece
      blocks[at] = block
      dues[at] = due
      asked[at] = askedAt
      if (size < pieces.size) size++
    }
  }

  private fun releaseBlock(peer: Int, index: Int, begin: Int) {
    val piece = openPieces.getOrNull(index) ?: return
    if (piece.committing || begin % BLOCK != 0) return
    val block = begin / BLOCK
    if (block >= piece.blocks) return
    val second = piece.duplicates?.get(block)
    if (piece.owner[block] == peer) {
      if (second != null) {
        piece.owner[block] = second.peer
        piece.dueAt[block] = second.dueAt
        piece.askedAt[block] = second.askedAt
        piece.duplicates?.remove(block)
      } else {
        piece.owner[block] = FREE
        piece.free++
        piece.cursor = minOf(piece.cursor, block)
        withFree += piece
      }
    } else if (second?.peer == peer) {
      piece.duplicates?.remove(block)
    }
  }

  /**
   * When [peer] is late with blocks it is handed now, given [due]: no sooner than those it was
   * handed before, as it sends them first, unless it owes none of them any more.
   */
  private fun dueFor(peer: Int, due: Long): Long {
    val last = lastDue[peer] ?: return due
    if (last <= due) return due
    for (piece in open.values) {
      if (piece.committing) continue
      if (peer in piece.owner || piece.duplicates?.values?.any { it.peer == peer } == true) {
        return last
      }
    }
    lastDue.remove(peer)
    return due
  }

  private fun releasePeerLocked(peer: Int) {
    for (piece in open.values) {
      if (piece.committing) continue
      for (block in 0 until piece.blocks) {
        if (piece.owner[block] == peer || piece.duplicates?.get(block)?.peer == peer) {
          releaseBlock(peer, piece.index, block * BLOCK)
        }
      }
      if (piece.single == peer) piece.single = UNASSIGNED
    }
    searches.remove(peer)
    lastDue.remove(peer)
  }

  /**
   * Closes [piece], canceling every request outstanding on it, and frees its buffer; the blocks
   * it holds are discarded, unless they were not ([discard] false), as when it verified.
   */
  private fun close(piece: OpenPiece, discard: Boolean = true) {
    if (discard) piece.receivedBytes().takeIf { it > 0 }?.let(onDiscard)
    for (block in 0 until piece.blocks) {
      val owner = piece.owner[block]
      if (owner >= 0) cancel(owner, piece.request(block))
    }
    piece.duplicates?.forEach { (block, copy) -> cancel(copy.peer, piece.request(block)) }
    track(piece.index) {
      open.remove(piece.index)
      openPieces[piece.index] = null
    }
    withFree.remove(piece)
    piece.lease.close()
    heldBytes -= piece.lease.bytes
    openings++
  }

  private fun releaseClaim(peer: Int, claim: Claim) {
    track(claim.index) {
      seedOwners[claim.index]?.let { seeds ->
        if (seeds.remove(peer)) seedOwned[claim.index]--
        if (seeds.isEmpty()) seedOwners.remove(claim.index)
      }
    }
    claim.lease.close()
    heldBytes -= claim.lease.bytes
    openings++
  }

  private fun cancel(peer: Int, request: PeerMessage.Request) {
    cancels.getOrPut(peer) { mutableListOf() } += request
  }

  private fun markVerified(index: Int) {
    if (verified[index]) return
    track(index) { verified[index] = true }
    verifiedCount++
    logHave(index)
    countWanted(index, -1)
  }

  private fun logHave(index: Int) {
    if (logged[index]) return
    logged[index] = true
    if (haveCount == haveLog.size) haveLog = haveLog.copyOf(minOf(pieceCount, haveCount * 2))
    haveLog[haveCount++] = index
  }

  /** Runs [change] on piece [index], keeping [unopened] and [remaining] counted. */
  private inline fun track(index: Int, change: () -> Unit) {
    val wasRemaining = wanted[index] && !verified[index]
    val wasUnopened = wasRemaining && openPieces[index] == null && seedOwned[index] == 0
    change()
    val isRemaining = wanted[index] && !verified[index]
    val isUnopened = isRemaining && openPieces[index] == null && seedOwned[index] == 0
    remaining += (if (isRemaining) 1 else 0) - (if (wasRemaining) 1 else 0)
    unopened += (if (isUnopened) 1 else 0) - (if (wasUnopened) 1 else 0)
  }

  /** Adds [change] to the wanted count of every peer that has piece [index], if it is wanted. */
  private fun countWanted(index: Int, change: Int) {
    if (!wanted[index]) return
    for ((peer, pieces) in availability) {
      if (pieces[index]) wantedFrom[peer] = (wantedFrom[peer] ?: 0) + change
    }
  }

  internal companion object {
    private const val BLOCK = PeerWire.BLOCK_SIZE
    private const val FREE = -1
    private const val RECEIVED = -2
    private const val ANYONE = -1
    private const val UNASSIGNED = -2

    /** How long a peer may take to deliver a block before another may ask for it too. */
    const val DEFAULT_LATE_MS = 6_000L

    /**
     * The most blocks a peer holds in the endgame that other peers owe and are not late with
     * while its rate is unknown or slow (256 KiB), or as many as it keeps outstanding if that is
     * fewer: few enough that copies arriving twice waste little. A faster peer holds more
     * ([endgameCopies]).
     */
    const val ENDGAME_DUPLICATES = 16

    /** How much of its own rate a peer copies on time in the endgame ([endgameCopies]). */
    private const val ENDGAME_COPY_MS = 1_000L

    /**
     * The most blocks other peers owe on time that a peer receiving [bytesPerSecond] holds in the
     * endgame: about [ENDGAME_COPY_MS] of its rate, so a fast peer far away takes a slower or
     * stalled peer's queue over at its own pace rather than a few blocks a round trip; at least
     * [ENDGAME_DUPLICATES], or the [pipeline] it keeps if that is fewer, which it never exceeds.
     */
    fun endgameCopies(bytesPerSecond: Double, pipeline: Int): Int =
      (bytesPerSecond * ENDGAME_COPY_MS / 1000 / BLOCK).toInt()
        .coerceAtLeast(ENDGAME_DUPLICATES).coerceAtMost(pipeline)

    /** How often a peer looks for blocks others are late with. */
    private const val LATE_SCAN_MS = 500L

    /** How often pieces nobody has are counted again. */
    private const val SOURCELESS_SCAN_MS = 1_000L

    /** How long a peer that found nothing to open waits before looking again unprompted. */
    private const val RESEARCH_MS = 250L

    /** A partial piece nobody fetched from this long gives its buffer to a piece that needs it. */
    private const val STALE_PIECE_MS = 10_000L

    /** A partial piece nobody fetched from this long gives its buffer up in any case. */
    private const val ABANDONED_PIECE_MS = 60_000L

    /** How often [poll] looks for abandoned pieces. */
    private const val SWEEP_MS = 1_000L
  }
}
