package com.linroid.ketch.torrent.v2

import com.linroid.ketch.torrent.engine.TorrentBufferBudget
import com.linroid.ketch.torrent.metainfo.TorrentContentLayout
import com.linroid.ketch.torrent.peer.PeerIdentityHandshake
import com.linroid.ketch.torrent.peer.PeerMessage
import com.linroid.ketch.torrent.peer.TorrentChoker
import com.linroid.ketch.torrent.peer.UPLOAD_SLOTS
import com.linroid.ketch.torrent.peer.allowedFastOffer
import com.linroid.ketch.torrent.storage.TorrentStorageException
import com.linroid.ketch.torrent.storage.TorrentV2PieceStore
import com.linroid.ketch.torrent.v2.peer.PeerV2DownloadActor
import com.linroid.ketch.torrent.v2.peer.PeerV2Pool
import okio.IOException

/**
 * The upload side of one swarm loop: what the session tells peers it has, whom it unchokes, and
 * serving their requests from verified pieces. Owned and called by the loop only; reads go to
 * the serve worker, and blocks leave through each peer's actor.
 *
 * Every peer learns our pieces from one Bitfield (only when we have any) and then from a shared
 * log of the pieces verified since, each peer with its own cursor into it. Requests are served
 * only to peers we unchoke, only for verified pieces and only within the piece as the peer's
 * swarm knows it: bytes past a file's end inside that piece (a hybrid's padding, or a v2 piece's
 * alignment) are zeros.
 *
 * With the Fast extension (BEP 6), peers learn our pieces as Have All or Have None when that is
 * all of them or none, hear back about every request we will not serve (a Reject), and while we
 * upload are offered the canonical allowed-fast pieces, which they may download while choked.
 */
internal class TorrentV2Uploader private constructor(
  private val layout: TorrentContentLayout,
  private val swarm: TorrentV2Swarm,
  private val store: TorrentV2PieceStore,
  private val buffers: TorrentBufferBudget,
  private val nowMs: () -> Long,
  private val haveLease: TorrentBufferBudget.Lease,
) {
  /** What the loop does with a peer after one of its messages or events. */
  enum class Verdict {
    KEEP,

    /** Stop the peer; it did nothing wrong. */
    STOP,

    /** Stop the peer and ban it: it broke the protocol. */
    BAN,
  }

  private val runtime = swarm.runtime
  private val serve = swarm.serve
  private val choker = TorrentChoker<PeerV2Pool.Peer>(nowMs)
  // Cached pieces and the reads that will join them share this, so remote requests never make a
  // session hold more than it.
  private val cacheBytes = TorrentV2UploadCache.capacity(layout.pieceLength, runtime.uploadBuffers)
  private val cache = TorrentV2UploadCache(cacheBytes, nowMs)
  private val haveLog = IntArray(layout.pieceCount.toInt())
  private var haveCount = 0
  // Pieces already in the log: a piece revoked and verified again is not told twice.
  private val announced = BooleanArray(layout.pieceCount.toInt())
  private val loading = mutableSetOf<Int>()
  // Pieces peers wait to have read, in the order they first asked: reads start in that order.
  private val waiting = LinkedHashSet<Int>()
  private var loadRetryAt = 0L
  private var turn = 0
  private var closed = false
  /** The upload policy lets us upload, as the last [pump] heard; allowed-fast pieces need it. */
  private var uploading = false

  /**
   * A peer became ready: it learns what we have before anything else, then, with the Fast
   * extension and while we upload, the pieces it may download while we choke it.
   */
  fun ready(peer: PeerV2Pool.Peer, view: TorrentV2PeerView, verified: ByteArray?): Verdict {
    choker.add(peer)
    view.haveCursor = haveCount
    val availability = when {
      // An empty bitfield may be left out (BEP 3); peers that see none assume we have nothing.
      !view.fast -> verified?.let { PeerMessage.Bitfield(it) }
      // A Fast peer always hears it, in a message of its own for all or nothing (BEP 6).
      verified == null -> PeerMessage.HaveNone
      (0 until layout.pieceCount.toInt()).all {
        verified[it / 8].toInt() and (128 ushr (it % 8)) != 0
      } -> PeerMessage.HaveAll
      else -> PeerMessage.Bitfield(verified)
    }
    if (availability != null &&
      view.commands.trySend(PeerV2DownloadActor.Command.Send(availability)).isFailure) {
      return Verdict.STOP
    }
    if (view.fast && uploading) offerFast(view)
    return Verdict.KEEP
  }

  /**
   * Tells an IPv4 peer the canonical allowed-fast pieces (BEP 6) of the swarm it joined, held or
   * not: the set stays the same for the connection, and a piece verified later is served once
   * its Have went out.
   */
  private fun offerFast(view: TorrentV2PeerView) {
    val info = view.info ?: return
    val pieces = allowedFastOffer(info.host, swarm.document.identity.wireTag(info.mode),
      layout.pieceCount.toInt())
    if (pieces.isEmpty()) return
    if (view.commands.trySend(PeerV2DownloadActor.Command.AllowFast(pieces)).isSuccess) {
      view.offeredFast = pieces.toSet()
    }
  }

  /** We serve [request] to a peer we choke: an allowed-fast piece, while we upload (BEP 6). */
  private fun servesChoked(view: TorrentV2PeerView, request: PeerMessage.Request): Boolean =
    view.fast && uploading && request.index in view.offeredFast

  /** Whether [view]'s queued requests may be served: unchoked, or allowed fast (BEP 6). */
  private fun servable(view: TorrentV2PeerView): Boolean =
    !view.amChoking || view.fast && uploading

  /** A Fast peer hears about a request we will not serve (BEP 6); others are left to time out. */
  private fun reject(view: TorrentV2PeerView, request: PeerMessage.Request) {
    if (view.fast && view.rejectQueue.size < MAX_QUEUED_REJECTS) view.rejectQueue.addLast(request)
  }

  /** Drops the queued requests of [view] that [which] picks, rejecting them to a Fast peer. */
  private fun dropUploads(
    view: TorrentV2PeerView,
    which: (PeerMessage.Request) -> Boolean = { true },
  ) {
    val dropped = view.uploads.filter(which)
    if (dropped.isEmpty()) return
    view.uploads.removeAll(dropped)
    for (request in dropped) reject(view, request)
  }

  fun closed(peer: PeerV2Pool.Peer) = choker.remove(peer)

  /** A verified piece joins the log every peer is told from. */
  fun committed(index: Int) {
    if (announced[index]) return
    check(haveCount < haveLog.size) { "Have log overflow" }
    announced[index] = true
    haveLog[haveCount++] = index
  }

  /**
   * Piece [index], outside the selection, could not be read back and is no longer ours: what
   * peers asked of it is dropped. Peers that heard we have it are refused it from now on.
   */
  fun revoked(index: Int, peers: Map<PeerV2Pool.Peer, TorrentV2PeerView>) {
    loading.remove(index)
    waiting.remove(index)
    for (view in peers.values) dropUploads(view) { it.index == index }
  }

  /** Payload [peer] sent us, which earns it a regular slot while we download. */
  fun received(peer: PeerV2Pool.Peer, bytes: Int) = choker.received(peer, bytes.toLong())

  /** An ordinary message from [peer] that concerns uploading. */
  fun update(
    peer: PeerV2Pool.Peer,
    view: TorrentV2PeerView,
    message: PeerMessage,
    verified: (Int) -> Boolean,
  ): Verdict {
    when (message) {
      is PeerMessage.Control -> when (message.signal) {
        PeerMessage.Signal.INTERESTED -> {
          view.remoteInterested = true
          choker.interested(peer, true)
        }
        PeerMessage.Signal.NOT_INTERESTED -> {
          view.remoteInterested = false
          // A peer that wants nothing more has no requests left (BEP 3).
          dropUploads(view)
          choker.interested(peer, false)
        }
        else -> Unit
      }
      is PeerMessage.Request -> return request(view, message, verified)
      is PeerMessage.Cancel -> {
        val request = PeerMessage.Request(message.index, message.begin, message.length)
        // A Fast peer hears back about each queued request it cancels (BEP 6).
        if (view.uploads.remove(request)) reject(view, request)
      }
      else -> Unit
    }
    return Verdict.KEEP
  }

  private fun request(
    view: TorrentV2PeerView,
    request: PeerMessage.Request,
    verified: (Int) -> Boolean,
  ): Verdict {
    // The wire already bounds the index, begin and length; the piece bounds the end.
    if (request.begin.toLong() + request.length >
      layout.protocolPieceLength(request.index.toLong())) return Verdict.BAN
    if (request in view.uploads) return strike(view)
    val slot = !view.amChoking && view.remoteInterested
    if ((slot || servesChoked(view, request)) && verified(request.index) &&
      view.uploads.size < MAX_QUEUED_UPLOADS) {
      view.uploads.addLast(request)
      return Verdict.KEEP
    }
    reject(view, request)
    // Requests racing our CHOKE are dropped without holding them against the peer.
    return if (view.amChoking) Verdict.KEEP else strike(view)
  }

  private fun strike(view: TorrentV2PeerView): Verdict =
    if (++view.strikes > MAX_STRIKES) Verdict.STOP else Verdict.KEEP

  /** What became of a [PeerV2DownloadActor.Command.Send]. */
  fun sent(view: TorrentV2PeerView, event: PeerV2DownloadActor.Event.Sent): Verdict {
    when (val message = event.message) {
      is PeerMessage.Control -> {
        view.chokeInFlight = false
        // Unsent, the peer keeps what it knew; the next pump tells it again.
        if (!event.sent) view.amChoking = message.signal != PeerMessage.Signal.CHOKE
      }
      // A peer that missed our availability would never ask for those pieces.
      is PeerMessage.Bitfield, is PeerMessage.Have, PeerMessage.HaveAll, PeerMessage.HaveNone ->
        if (!event.sent) return Verdict.STOP
      else -> Unit
    }
    return Verdict.KEEP
  }

  /** What became of an uploaded block. */
  fun served(
    peer: PeerV2Pool.Peer,
    view: TorrentV2PeerView,
    event: PeerV2DownloadActor.Event.Served,
  ) {
    check(view.uploadsInFlight > 0) { "Unknown upload confirmation" }
    view.uploadsInFlight--
    val length = event.request.length
    if (event.sent) {
      store.recordUploaded(length)
      if (peer.info?.mode == PeerIdentityHandshake.Mode.V1) swarm.uploadedOverV1(length)
      choker.uploaded(peer, length.toLong())
    } else if ((!view.amChoking || servesChoked(view, event.request)) &&
      view.uploads.size < MAX_QUEUED_UPLOADS) {
      // No frame credit: the block goes first once the actor has room again.
      view.uploads.addFirst(event.request)
      view.uploadRetryAt = nowMs() + RETRY_MS
    }
  }

  /**
   * A read the serve worker finished, for uploading or for a hash proof; the result is closed
   * here unless the cache keeps it.
   */
  fun result(
    result: TorrentV2ServeWorker.Result.Piece,
    peers: Map<PeerV2Pool.Peer, TorrentV2PeerView>,
  ) {
    loading.remove(result.index)
    when (val outcome = result.outcome) {
      is TorrentV2PieceStore.ReadOutcome.Read -> {
        cache.put(result.index, outcome.buffer)
        for (view in peers.values) {
          if (view.uploads.firstOrNull()?.index == result.index) view.refusals = 0
        }
      }
      TorrentV2PieceStore.ReadOutcome.NoBudget -> {
        val now = nowMs()
        loadRetryAt = now + RETRY_MS
        for ((peer, view) in peers) {
          if (view.uploads.firstOrNull()?.index != result.index) continue
          view.uploadRetryAt = now + RETRY_MS
          // A peer we keep failing for lets another take its slot.
          if (++view.refusals >= MAX_REFUSALS) {
            view.refusals = 0
            choker.refused(peer)
          }
        }
      }
      TorrentV2PieceStore.ReadOutcome.NotCommitted ->
        for (view in peers.values) dropUploads(view) { it.index == result.index }
      is TorrentV2PieceStore.ReadOutcome.Revoked -> {
        val cause = outcome.cause
        // Fails the session like a v1 storage failure: progress and peers no longer agree.
        throw TorrentStorageException(IOException(
          "Verified piece ${result.index} changed on disk", cause))
      }
    }
  }

  /**
   * Tells every peer what the choker decided and the pieces verified since, then serves queued
   * requests, taking peers in turn. [allowed] is whether the upload policy lets us upload now.
   */
  suspend fun pump(
    peers: Map<PeerV2Pool.Peer, TorrentV2PeerView>,
    allowed: Boolean,
    seeding: Boolean,
  ) {
    uploading = allowed && serve != null
    choker.update(uploading, seeding)
    cache.expire()
    for ((peer, view) in peers) {
      val choking = !choker.isUnchoked(peer)
      if (choking != view.amChoking && !view.chokeInFlight) {
        val signal = if (choking) PeerMessage.Signal.CHOKE else PeerMessage.Signal.UNCHOKE
        val command = PeerV2DownloadActor.Command.Send(PeerMessage.Control(signal))
        if (view.commands.trySend(command).isSuccess) {
          view.amChoking = choking
          view.chokeInFlight = true
        }
      }
      // Choking a peer discards what it asked for (BEP 3), rejected after the CHOKE to a Fast
      // peer, except allowed-fast pieces while we still upload (BEP 6).
      if (view.amChoking) dropUploads(view) { !servesChoked(view, it) }
      while (view.haveCursor < haveCount) {
        val have = PeerV2DownloadActor.Command.Send(PeerMessage.Have(haveLog[view.haveCursor]))
        if (!view.commands.trySend(have).isSuccess) break
        view.haveCursor++
      }
      if (view.rejectQueue.isNotEmpty() && view.commands.trySend(
          PeerV2DownloadActor.Command.Reject(view.rejectQueue.toList())).isSuccess) {
        view.rejectQueue.clear()
      }
    }
    if (serve == null) return
    val now = nowMs()
    val serving = peers.entries.filter { (_, view) -> canServe(view, now) }
    if (serving.isEmpty()) return
    val start = turn++ % serving.size
    for (offset in serving.indices) {
      val view = serving[(start + offset) % serving.size].value
      while (canServe(view, now) && dispatch(view, now, peers)) Unit
    }
  }

  private fun canServe(view: TorrentV2PeerView, now: Long): Boolean = servable(view) &&
    view.uploads.isNotEmpty() && view.uploadsInFlight < MAX_UPLOADS_IN_FLIGHT &&
    view.uploadRetryAt <= now

  /** Queues [view]'s oldest request with its actor; false when it has to wait. */
  private suspend fun dispatch(
    view: TorrentV2PeerView,
    now: Long,
    peers: Map<PeerV2Pool.Peer, TorrentV2PeerView>,
  ): Boolean {
    val request = view.uploads.first()
    val piece = cache[request.index]
    if (piece == null) {
      load(request.index, now, peers)
      return false
    }
    val lease = buffers.reserve(request.length + 256)
    if (lease == null) {
      view.uploadRetryAt = now + RETRY_MS
      return false
    }
    // The cached piece holds the v2 extent; the rest of the protocol piece is zeros.
    val bytes = ByteArray(request.length)
    if (request.begin < piece.size) {
      piece.copyInto(bytes, 0, request.begin, minOf(request.begin + request.length, piece.size))
    }
    val command = PeerV2DownloadActor.Command.Upload(
      PeerMessage.Piece(request.index, request.begin, bytes), lease)
    var queued = false
    val delay = runtime.uploadRate.requestDelay(request.length, swarm.sessionUploadRate) {
      view.commands.trySend(command).isSuccess.also { queued = it }
    }
    if (delay > 0 || !queued) {
      lease.close()
      view.uploadRetryAt = now + if (delay > 0) delay else RETRY_MS
      return false
    }
    view.uploads.removeFirst()
    view.uploadsInFlight++
    return true
  }

  /**
   * Reads piece [index] for a peer whose next request needs it, once it fits. Pieces an unchoked
   * peer is being served from (its next request's) stay cached: a read waits for room beside
   * them and the reads already out, rather than evicting a piece another peer still takes blocks
   * from, so peers take turns piece by piece. Reads start in the order peers asked for them.
   */
  private fun load(index: Int, now: Long, peers: Map<PeerV2Pool.Peer, TorrentV2PeerView>) {
    val worker = serve ?: return
    if (index in loading) return
    val pinned = peers.values.mapNotNullTo(mutableSetOf()) { view ->
      view.uploads.firstOrNull()?.index?.takeIf { servable(view) }
    }
    // Pieces nobody waits for any more give their turn up.
    waiting.retainAll(pinned)
    waiting += index
    if (waiting.first() != index || loading.size >= MAX_LOADS || now < loadRetryAt) return
    val reading = loading.sumOf(::pieceBytes)
    val needed = pieceBytes(index)
    // One piece always fits beside nothing; the pieces peers still take from are kept.
    if (cache.bytes - cache.evictableBytes(pinned) + reading + needed > cacheBytes) return
    if (!worker.trySubmit(TorrentV2ServeWorker.Job.ReadPiece(index))) return
    waiting -= index
    loading += index
    // Older pieces make room for the new one before its read holds any budget.
    cache.shrink(cacheBytes - reading - needed, keep = pinned)
  }

  private fun pieceBytes(index: Int): Long = layout.v2Piece(index.toLong()).length

  /** Milliseconds until timed upload work is due, or null when only events can bring work. */
  fun nextDueMs(peers: Map<PeerV2Pool.Peer, TorrentV2PeerView>): Long? {
    val now = nowMs()
    val waits = peers.values.filter {
      servable(it) && it.uploads.isNotEmpty() && it.uploadsInFlight < MAX_UPLOADS_IN_FLIGHT
    }.map { it.uploadRetryAt }
    // Rejects the actor's queue had no room for go out again shortly.
    val rejects = if (peers.values.any { it.rejectQueue.isNotEmpty() }) RETRY_MS else null
    val retry = waits.filter { it > now }.minOrNull()?.let { it - now }
    // Peers due now wait for a read: its result wakes the loop, unless reads must back off.
    val reads = if (loadRetryAt > now && waits.any { it <= now }) loadRetryAt - now else null
    return listOfNotNull(choker.nextDueMs(), cache.nextExpiryMs(), retry, reads, rejects)
      .minOrNull()
  }

  /** Downloads come first: gives the cached pieces' reservations back. */
  fun trim() = cache.trim()

  /** For the swarm summary: peers we unchoke, peers that want our pieces, bytes uploaded. */
  fun summary(): String = "uploadSlots=${choker.unchokedCount}/$UPLOAD_SLOTS " +
    "interestedPeers=${choker.interestedCount} uploaded=${store.uploadedBytes()}"

  fun close() {
    if (closed) return
    closed = true
    try { cache.close() } finally { haveLease.close() }
  }

  companion object {
    private const val MAX_STRIKES = 64
    private const val MAX_REFUSALS = 3
    private const val MAX_LOADS = 2
    private const val RETRY_MS = 250L

    /** Null when the Have log cannot be charged to [state]. */
    fun create(
      layout: TorrentContentLayout,
      swarm: TorrentV2Swarm,
      store: TorrentV2PieceStore,
      buffers: TorrentBufferBudget,
      state: TorrentBufferBudget,
      nowMs: () -> Long,
    ): TorrentV2Uploader? {
      require(layout.pieceCount in 0..1_000_000)
      val lease = state.reserve(layout.pieceCount.toInt() * 5 + 1024) ?: return null
      try {
        return TorrentV2Uploader(layout, swarm, store, buffers, nowMs, lease)
      } catch (error: Throwable) {
        lease.close()
        throw error
      }
    }
  }
}
