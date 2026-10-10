package com.linroid.ketch.torrent

import com.linroid.ketch.api.log.KetchLogger
import com.linroid.ketch.core.engine.ConnectionReporter
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.ChannelResult
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.channels.SendChannel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.selects.onTimeout
import kotlinx.coroutines.selects.select
import okio.ByteString

/**
 * What a session loop needs to run a swarm rather than a fixed set of peers: discovered
 * endpoints, the dial queue and its failures, live controls and the session's generation. The
 * loop owns the candidate book these feed; dial and respond workers never read it. A swarm also
 * tells peers what it has and, as the runtime's upload policy allows, uploads to them, proves
 * hashes to them and serves them the info dictionary; with peers that negotiated extensions it
 * exchanges peers.
 */
internal class TorrentV2Swarm(
  val document: TorrentV2Document,
  val runtime: TorrentV2Runtime,
  /** Private metainfo or tracker-only privacy: tracker peers only. */
  val restricted: Boolean,
  /** Keep waiting while no peer, candidate or discovery is left, as engine owners do. */
  val waitForPeers: Boolean,
  val discovered: ReceiveChannel<TorrentV2Discovered>,
  val dial: SendChannel<TorrentV2DialTarget>,
  val dialFailures: ReceiveChannel<PeerV2Dialer.Failure<TorrentV2DialTarget>>,
  val controls: ReceiveChannel<TorrentV2SessionLoop.Control>,
  /** The peer limit when the loop starts; [TorrentV2SessionLoop.Control.SetLimit] changes it. */
  val connectionLimit: () -> Int,
  /** The session's current generation; connections from an older one are closed. */
  val generation: () -> Long,
  /**
   * Reads pieces to upload and builds hash proofs; without it the swarm announces what it has
   * but never unchokes, and rejects every hash request.
   */
  val serve: TorrentV2ServeWorker? = null,
  /** The session's own upload bucket, applied after the runtime's. */
  val sessionUploadRate: TorrentRateLimiter = TorrentRateLimiter(),
  /**
   * Runs when every selected piece is verified (possibly at start), and again each time a
   * selection that grew is complete. True keeps the loop serving peers as a seed until the
   * upload policy leaves seeding; false ends it.
   */
  val onCompleted: suspend () -> Boolean = { false },
  /** Counts payload bytes a peer on a v1 route confirmed, as a hybrid's v1 swarm's share. */
  val uploadedOverV1: (Int) -> Unit = {},
  /**
   * Told whether the pool has room for another peer, whenever the loop is about to wait; while
   * it has none, peers that dial us are closed unanswered, as a v1 swarm does.
   */
  val onRoom: (Boolean) -> Unit = {},
  /**
   * A selection change left a complete swarm with pieces to download: it downloads again, and
   * uploads as the policy allows while downloading, until [onCompleted] runs once more.
   */
  val onIncomplete: suspend () -> Unit = {},
)

/** Serializes session decisions while peer actors and the storage worker perform their own I/O. */
internal object TorrentV2SessionLoop {
  private val log = KetchLogger("TorrentSwarm")

  /** Live changes a swarm loop applies without restarting its pool, dialer or workers. */
  sealed interface Control {
    /** At most [peers] connections; the excess are stopped, least useful first. */
    data class SetLimit(val peers: Int) : Control

    /** Drops every peer and candidate of earlier generations; [done] once those peers closed. */
    class Reset(val done: CompletableDeferred<Unit>, val generation: Long) : Control

    /** The runtime's upload policy changed; the loop reads it again. */
    data object PolicyChanged : Control

    /**
     * The store's selection changed: the loop wants the pieces it now selects, without
     * dropping a peer, and completes [done] once it does. A loop that stops first never does.
     */
    class Select(val done: CompletableDeferred<Unit>) : Control
  }

  private sealed interface Event {
    data class Peer(val value: PeerV2Pool.Event) : Event
    data class Commit(val value: TorrentV2CommitWorker.Completion) : Event
    data class Connection(val value: ChannelResult<PeerV2Connector.Connected>) : Event
    data class Discovered(val value: ChannelResult<TorrentV2Discovered>) : Event
    data class DialFailed(val value: ChannelResult<PeerV2Dialer.Failure<TorrentV2DialTarget>>) :
      Event
    data class Controlled(val value: ChannelResult<Control>) : Event
    data class Served(val value: ChannelResult<TorrentV2ServeWorker.Result>) : Event
    data object Retry : Event

    /** The commit worker or the pool closed its queue, which they only do as the loop stops. */
    data class Stopped(val cause: Throwable?) : Event
  }

  /**
   * A swarm peer from attach until it closes. Its [peerId] is only what it claims; [host], where
   * its connection came from or went to, is what bans and learned endpoints are tied to.
   */
  private class Member(val peerId: ByteString, val host: String, val order: Long) {
    /** Where the peer listens, when known; always on [host]. */
    var listen: PeerEndpoint? = null
    /** [listen] is an endpoint we dialed, so it returns to the candidates when this closes. */
    var listenDialed = false
    /** It broke the protocol; it is banned once it closes. */
    var violation = false
  }

  /**
   * One peer as far as duplicates go: the ID it claims, on the host it came from. A peer ID is
   * only a claim, so another host claiming it is another peer and never displaces this one.
   */
  private data class PeerKey(val host: String, val peerId: ByteString)

  private class PendingReset(
    val peers: MutableSet<PeerV2Pool.Peer>,
    val done: CompletableDeferred<Unit>,
  )

  /**
   * Downloads selected files using matching storage and attached or asynchronously arriving peers.
   * Caller joins pool/worker/dialer scopes on return or failure. The optional connection
   * stream must reclaim undelivered handles on cancellation. Initial verification comes
   * only from the store. This full-metainfo path does not resolve magnet metadata.
   *
   * With a [swarm], the loop also owns the candidate book: it dials discovered endpoints, keeps
   * the peer limit and resets live, and closes connections it cannot use (another torrent, an
   * older generation, a banned or duplicate peer) without failing. It tells peers what it has,
   * uploads, proves hashes and serves the info dictionary as the upload policy allows, exchanges
   * peers, and once complete may keep seeding (see [TorrentV2Swarm.onCompleted]). Without one,
   * it downloads from the peers attached to [pool] and those [connections] delivers, and rejects
   * every hash request.
   */
  @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
  suspend fun download(
    layout: TorrentContentLayout,
    selectedIds: Set<String>,
    store: TorrentV2PieceStore,
    pool: PeerV2Pool,
    worker: TorrentV2CommitWorker,
    buffers: TorrentBufferBudget,
    state: TorrentBufferBudget,
    maxPeers: Int = 100,
    maxActive: Int = 2,
    pipeline: Int = 32,
    connections: ReceiveChannel<PeerV2Connector.Connected>? = null,
    onProgress: suspend () -> Unit = {},
    requestDelay: suspend (Int, () -> Boolean) -> Long = { _, admit -> admit(); 0 },
    nowMs: () -> Long = monotonicClock(),
    swarm: TorrentV2Swarm? = null,
    /** Reports each ready peer and its payload among the task's live connections. */
    reporter: ConnectionReporter = ConnectionReporter.None,
  ) {
    require(maxPeers in 1..500 && pipeline in 1..256)
    require(swarm == null || connections != null) { "A swarm needs a connection stream" }
    val lease = checkNotNull(state.reserve(maxPeers * 512 + 1024)) {
      "Session peer state budget exhausted"
    }
    var scheduler: TorrentV2PieceScheduler? = null
    var picker: TorrentV2RarityPicker<PeerV2Pool.Peer>? = null
    var bookLease: TorrentBufferBudget.Lease? = null
    var uploader: TorrentV2Uploader? = null
    val peers = linkedMapOf<PeerV2Pool.Peer, TorrentV2PeerView>()
    val resets = mutableListOf<PendingReset>()
    try {
      val pieces = checkNotNull(TorrentV2PieceScheduler.create(layout, selectedIds,
        store.verifiedPieces(), buffers, state, maxActive)) { "Piece scheduler budget exhausted" }
      scheduler = pieces
      val rarity = checkNotNull(TorrentV2RarityPicker.create<PeerV2Pool.Peer>(
        layout.pieceCount.toInt(), state, maxPeers)) { "Rarity index budget exhausted" }
      picker = rarity
      val book = swarm?.let {
        bookLease = checkNotNull(state.reserve(TorrentV2Candidates.STATE_BYTES)) {
          "Peer candidate state budget exhausted"
        }
        TorrentV2Candidates(it.document.hybrid != null, it.restricted, nowMs)
          .also { candidates -> candidates.reset(it.generation()) }
      }
      val uploads = swarm?.let {
        checkNotNull(TorrentV2Uploader.create(layout, it, store, buffers, state, nowMs)) {
          "Session have-log budget exhausted"
        }.also { created -> uploader = created }
      }
      val extensions = swarm?.let { TorrentV2Extensions(it, checkNotNull(book), nowMs) }
      val hashes = swarm?.let { TorrentV2HashRequests(layout, it) }
      val pieceCount = layout.pieceCount.toInt()
      val members = linkedMapOf<PeerV2Pool.Peer, Member>()
      val byPeerId = mutableMapOf<PeerKey, PeerV2Pool.Peer>()
      val stopping = mutableSetOf<PeerV2Pool.Peer>()
      var limit = swarm?.connectionLimit()?.coerceIn(1, maxPeers) ?: maxPeers
      var dialing = 0
      var attached = 0L
      var discoveryOpen = swarm != null
      var failuresOpen = swarm != null
      var controlsOpen = swarm != null
      var servingOpen = swarm?.serve != null
      // Every selected piece is verified; a seed keeps serving peers after that.
      var complete = false
      var seeding = false
      val label = "taskId=${store.taskId} (${logHash(layout.infoHash.hex)})"

      fun wakeAdmission() { peers.values.forEach { it.admissionBlocked = false } }

      fun stop(peer: PeerV2Pool.Peer) {
        if (!stopping.add(peer)) return
        // Its upload slot goes to another peer at once.
        uploads?.closed(peer)
        pool.stop(peer)
      }

      suspend fun pump() {
        // Unwanted pieces go once their last request is answered.
        pieces.evictUnwanted()
        pieces.submitReady(worker)
        for ((peer, view) in peers) {
          if (view.rateRetryAt <= nowMs()) view.rateRetryAt = 0
          if (view.admissionBlocked || view.rateRetryAt != 0L) continue
          val interested = view.needed > 0
          if (interested != view.interested && !view.interestPending) {
            if (view.commands.trySend(PeerV2DownloadActor.Command.Interest(interested)).isSuccess) {
              view.interestPending = true
            } else view.admissionBlocked = true
          }
          // A peer that told us how many requests it queues (BEP 10 `reqq`) gets no more.
          val window = minOf(pipeline, view.extensions.requestQueue ?: pipeline)
          if (!interested || !view.interested || view.interestPending || view.choked ||
            view.inFlight >= window || view.admissionBlocked) continue
          var plan = pieces.planNext(peer.blocks) { rarity.has(peer, it) }
          if (plan == null) {
            val index = rarity.pick(peer) { pieces.canBegin(it) }
            if (index != null) {
              if (pieces.begin(index)) plan = pieces.planNext(peer.blocks) { rarity.has(peer, it) }
              else {
                view.admissionBlocked = true
                // Downloads come first: the upload cache gives its pieces back.
                uploads?.trim()
              }
            }
          }
          if (plan != null) {
            var sent = false
            val request = plan
            val delay = requestDelay(request.request.length) {
              view.commands.trySend(PeerV2DownloadActor.Command.Request(request)).isSuccess
                .also { sent = it }
            }
            require(delay in 0..50 && (delay == 0L || !sent)) { "Invalid request rate delay" }
            if (delay > 0) {
              check(pieces.resolve(request, null))
              view.rateRetryAt = nowMs() + delay
            } else if (sent) {
              view.inFlight++
            } else {
              check(pieces.resolve(request, null))
              view.admissionBlocked = true
            }
          }
        }
        if (swarm != null && book != null) {
          while (pool.size + dialing < limit) {
            val target = book.next() ?: break
            if (swarm.dial.trySend(target).isSuccess) dialing++ else {
              book.pushBack(target)
              break
            }
          }
        }
        if (swarm != null && uploads != null) {
          // Two seeds have nothing for each other.
          if (complete) for ((peer, view) in peers) {
            if (view.remoteHaveCount == pieceCount && peer !in stopping) {
              log.v { "V2 peer of $label closed: a seed, and so are we" }
              stop(peer)
            }
          }
          val policy = swarm.runtime.uploadPolicy()
          val allowed = policy == TorrentUploadPolicy.SEED_AFTER_COMPLETION ||
            policy == TorrentUploadPolicy.WHILE_DOWNLOADING && !complete
          val live = peers.filterKeys { it !in stopping }
          // Answers peers already wait for go out before new payload and Haves.
          if (hashes != null) live.values.forEach(hashes::flush)
          if (extensions != null) live.values.forEach(extensions::flush)
          uploads.pump(live, allowed, seeding)
          extensions?.pump(live) { members[it]?.listen }
        }
        // What this pass and the events before it changed of each peer's state.
        for (view in peers.values) view.reportState()
      }

      fun verdict(peer: PeerV2Pool.Peer, verdict: TorrentV2Uploader.Verdict, reason: String) {
        if (verdict == TorrentV2Uploader.Verdict.KEEP) return
        if (verdict == TorrentV2Uploader.Verdict.BAN) members[peer]?.violation = true
        log.v { "V2 peer of $label closed: $reason" }
        stop(peer)
      }

      /** An incoming peer told us its listen port; that endpoint, not its source, is its own. */
      fun learnListen(peer: PeerV2Pool.Peer, view: TorrentV2PeerView) {
        val member = members[peer] ?: return
        val origin = view.info?.origin as? PeerV2Origin.Incoming ?: return
        val port = view.extensions.listenPort ?: return
        // An endpoint we dialed to reach the same peer is known to work; keep it.
        if (member.listenDialed) return
        member.listen = PeerEndpoint(origin.remote.host, port)
      }

      suspend fun message(peer: PeerV2Pool.Peer, value: PeerV2DownloadActor.Event) {
        val view = peers[peer] ?: return
        when (value) {
          is PeerV2DownloadActor.Event.Update -> when (val update = value.frame.message) {
            is PeerMessage.Bitfield -> {
              if (!rarity.update(peer, update.bytes)) stop(peer)
              else {
                view.needed = pieces.neededCount { rarity.has(peer, it) }
                view.remoteHaveCount = update.bytes.sumOf { it.toInt().and(255).countOneBits() }
              }
            }
            is PeerMessage.Have -> {
              val known = rarity.has(peer, update.index)
              val newlyNeeded = !known && pieces.isNeeded(update.index)
              if (!rarity.have(peer, update.index)) stop(peer)
              else {
                if (newlyNeeded) view.needed++
                if (!known) view.remoteHaveCount++
              }
            }
            is PeerMessage.Control -> {
              when (update.signal) {
                PeerMessage.Signal.CHOKE -> view.choked = true
                PeerMessage.Signal.UNCHOKE -> view.choked = false
                else -> Unit
              }
              uploads?.update(peer, view, update, pieces::isVerified)
            }
            is PeerMessage.Request, is PeerMessage.Cancel -> if (uploads != null) {
              verdict(peer, uploads.update(peer, view, update, pieces::isVerified),
                "an invalid or excess request")
            }
            is PeerMessage.Extended -> if (extensions != null) {
              verdict(peer, extensions.receive(view, update), "a bad extension message")
              if (update.id == 0) {
                learnListen(peer, view)
                // An incoming peer shows where it listens rather than its source port.
                if (view.info?.origin is PeerV2Origin.Incoming) {
                  view.extensions.listenPort?.let { view.traffic?.listenPort(it) }
                }
              }
            }
            else -> Unit
          }
          is PeerV2DownloadActor.Event.Interest -> {
            view.interestPending = false
            if (value.sent) view.interested = value.interested else view.admissionBlocked = true
          }
          is PeerV2DownloadActor.Event.Requested -> {
            if (!pieces.resolve(value.plan, value.ticket)) stop(peer)
            if (value.ticket == null) {
              check(view.inFlight > 0)
              view.inFlight--
              view.admissionBlocked = true
            }
          }
          is PeerV2DownloadActor.Event.Response -> {
            check(view.inFlight > 0)
            view.inFlight--
            val block = value.value as? PeerBlockExchange.Response.Block
            if (block != null) {
              store.recordReceived(block.ticket.request.length)
              // What crossed the wire: on a v1 route, the canonical block with its padding.
              view.traffic?.received(block.ticket.wire.length)
              view.receivedSinceRechoke += block.ticket.request.length
              uploads?.received(peer, block.ticket.request.length)
            }
            pieces.receive(peer.blocks, value.value)
            wakeAdmission()
          }
          is PeerV2DownloadActor.Event.Hash -> {
            val hash = value.value
            if (hash is PeerHashTransport.Event.Request) {
              val selector = hash.request.selector
              if (hashes != null) {
                verdict(peer, hashes.request(peer, view, selector, pieces::isVerified),
                  "too many hash answers waiting")
              } else if (view.commands.trySend(
                  PeerV2DownloadActor.Command.RejectHashes(selector)).isFailure) stop(peer)
            }
          }
          is PeerV2DownloadActor.Event.HashServed -> if (hashes != null) {
            verdict(peer, hashes.served(view, value), "too many hash answers waiting")
          }
          is PeerV2DownloadActor.Event.Sent -> if (uploads != null) {
            extensions?.sent(view, value)
            verdict(peer, uploads.sent(view, value), "our availability went unsent")
          }
          is PeerV2DownloadActor.Event.Served -> {
            if (value.sent) view.traffic?.sent(value.request.length)
            uploads?.served(peer, view, value)
          }
          else -> Unit
        }
      }

      /** Attaches a swarm connection, or closes it with a reason and carries on. */
      fun admit(connected: PeerV2Connector.Connected) {
        if (swarm == null || book == null) {
          if (connected.route.identity.v2 != layout.infoHash) {
            log.v { "V2 peer of $label closed: another torrent" }
          } else if (connected.attach(pool) == null) {
            log.v { "V2 peer of $label closed: no capacity left" }
          }
          return
        }
        val info = connected.info
        val dialed = (info.origin as? PeerV2Origin.Outgoing)?.endpoint
        val host = when (val origin = info.origin) {
          is PeerV2Origin.Outgoing -> origin.endpoint.host
          is PeerV2Origin.Incoming -> origin.remote.host
        }
        if (dialed != null) dialing = (dialing - 1).coerceAtLeast(0)
        fun refuse(reason: String) {
          log.v { "V2 peer of $label closed: $reason" }
          if (dialed != null) book.closed(dialed, violation = false, complete = complete)
        }
        // An older generation predates a tracker reset; its candidates are already forgotten.
        if (connected.generation < swarm.generation()) {
          log.v { "V2 peer of $label closed: dialed before a reset" }
          return
        }
        if (connected.route.identity.v2 != layout.infoHash) return refuse("another torrent")
        if (book.isBanned(host, info.peerId)) {
          log.v { "V2 peer of $label closed: banned" }
          if (dialed != null) book.ban(dialed)
          return
        }
        val key = PeerKey(host, info.peerId)
        val existing = byPeerId[key]
        var learned: PeerEndpoint? = null
        if (existing != null) {
          val member = members.getValue(existing)
          val existingDialed = (existing.info?.origin as? PeerV2Origin.Outgoing)?.endpoint
          // Two connections of one direction: the first stays. Otherwise both ends keep the same
          // socket, as libtorrent decides it (see keepOutgoing).
          val keepNew = (existingDialed != null) != (dialed != null) &&
            (dialed != null) == keepOutgoing(swarm, checkNotNull(dialed ?: existingDialed),
              info.peerId)
          if (!keepNew) {
            // Also when it told us that endpoint itself (BEP 10 `p`): now we know it works.
            if (dialed != null && (member.listen == null ||
                member.listen == dialed && !member.listenDialed)) {
              // The peer that dialed us listens where we just reached it.
              member.listen = dialed
              member.listenDialed = true
              book.connected(dialed)
            } else if (dialed != null) {
              book.closed(dialed, violation = false, complete = complete)
            }
            log.v { "V2 peer of $label closed: already connected" }
            return
          }
          if (member.listenDialed) {
            learned = member.listen
            member.listen = null
            member.listenDialed = false
          }
          byPeerId.remove(key)
          stop(existing)
        }
        val peer = connected.attach(pool) ?: return refuse("no capacity left")
        // Before the peer hears from us: peers that dial us next see a full pool at once.
        swarm.onRoom(pool.size < limit && pool.remainingCapacity > 0)
        val member = Member(info.peerId, host, attached++)
        member.listen = dialed ?: learned
        member.listenDialed = member.listen != null
        members[peer] = member
        byPeerId[key] = peer
        member.listen?.let(book::connected)
      }

      /**
       * A swarm peer closed: its endpoint backs off while we download, or is banned after a
       * violation. A violation bans the peer's endpoint and its claimed ID on its own host only.
       */
      fun forget(peer: PeerV2Pool.Peer, cause: Throwable?) {
        if (book == null) return
        val member = members.remove(peer) ?: return
        val key = PeerKey(member.host, member.peerId)
        if (byPeerId[key] === peer) byPeerId.remove(key)
        val violation = cause is IllegalArgumentException || member.violation
        if (violation) {
          member.listen?.let(book::ban)
          book.ban(member.host, member.peerId)
        }
        val listen = member.listen
        val info = peer.info
        val origin = info?.origin
        if (info != null && origin is PeerV2Origin.Incoming && !member.listenDialed) {
          val topic = if (info.mode == PeerIdentityHandshake.Mode.V1) PeerTopic.V1 else PeerTopic.V2
          // Its source port is ephemeral; only a listen port it told us may be dialed back.
          if (!violation) book.incomingClosed(origin.remote, listen, topic, complete)
        } else if (listen != null) book.closed(listen, violation, complete)
        resets.removeAll { reset ->
          reset.peers.remove(peer)
          if (reset.peers.isEmpty()) reset.done.complete(Unit)
          reset.peers.isEmpty()
        }
      }

      /** Follows the store's selection and verified pieces; peers learn what we gained. */
      suspend fun retarget() {
        val change = pieces.retarget(store.selectedIds(), store.verifiedPieces())
        if (change.gained.isNotEmpty() || change.lost.isNotEmpty()) {
          for ((peer, view) in peers) {
            for (index in change.gained) if (rarity.has(peer, index)) view.needed++
            for (index in change.lost) if (rarity.has(peer, index)) {
              check(view.needed > 0)
              view.needed--
            }
          }
        }
        change.verified.forEach { uploads?.committed(it) }
        log.d {
          "V2 swarm $label: selection now needs ${change.gained.size} more and " +
            "${change.lost.size} fewer piece(s), ${change.evicted} assembly(ies) dropped"
        }
        wakeAdmission()
      }

      suspend fun control(value: Control) {
        val candidates = checkNotNull(book)
        when (value) {
          is Control.SetLimit -> {
            limit = value.peers.coerceIn(1, maxPeers)
            val excess = members.size - stopping.size - limit
            if (excess > 0) {
              // Peers that give us nothing go first, then the slowest, then the newest.
              fun busy(peer: PeerV2Pool.Peer): Int =
                peers[peer]?.let { if (it.choked && it.inFlight == 0) 0 else 1 } ?: 0
              val victims = members.keys.filter { it !in stopping }.sortedWith(
                compareBy<PeerV2Pool.Peer>(
                  { busy(it) },
                  { peers[it]?.receivedSinceRechoke ?: 0L },
                  { -members.getValue(it).order })).take(excess)
              victims.forEach(::stop)
            }
            log.d { "V2 swarm $label: peer limit $limit, stopping ${maxOf(0, excess)}" }
          }
          is Control.Reset -> {
            candidates.reset(value.generation)
            val current = members.keys.toMutableSet()
            current.forEach(::stop)
            // Connections of the new generation are not duplicates of peers being dropped.
            byPeerId.clear()
            log.d { "V2 swarm $label: disconnecting ${current.size} peer(s) for a tracker change" }
            if (current.isEmpty()) value.done.complete(Unit)
            else resets += PendingReset(current, value.done)
          }
          // Every pass reads the policy; this only wakes the loop to do so.
          Control.PolicyChanged -> Unit
          is Control.Select -> {
            retarget()
            if (complete && !pieces.completed()) {
              // Download again; the next completion runs onCompleted once more.
              complete = false
              seeding = false
              log.d { "V2 swarm $label: incomplete after a selection change" }
              checkNotNull(swarm).onIncomplete()
            }
            onProgress()
            value.done.complete(Unit)
          }
        }
      }

      onProgress()
      var preference = 0
      val branches = if (swarm == null) 3 else 7
      var acceptingConnections = connections != null
      // Event driven: an idle swarm logs nothing, so the loop never wakes just to report.
      var nextSummary = nowMs() + SWARM_SUMMARY_INTERVAL_MS
      while (true) {
        if (swarm == null) {
          if (pieces.completed()) break
        } else {
          // The store decides: a selection it took before this loop heard of it is followed first.
          if (!complete && pieces.completed() && !store.completed()) retarget()
          if (!complete && pieces.completed()) {
            complete = true
            // A seed retries nobody; peers it has not tried are still dialed.
            book?.completed()
            if (swarm.onCompleted()) {
              seeding = true
              log.d { "V2 swarm $label: complete, seeding to ${peers.size} peer(s)" }
            } else break
          }
          // A seed stops once the upload policy no longer seeds.
          if (seeding && swarm.runtime.uploadPolicy() !=
            TorrentUploadPolicy.SEED_AFTER_COMPLETION) break
        }
        pump()
        if (nowMs() >= nextSummary) {
          nextSummary = nowMs() + SWARM_SUMMARY_INTERVAL_MS
          log.d {
            val verified = store.verifiedPieces().count { it }
            val swarmPart = if (book == null) "" else {
              val incoming = members.keys.count { it.info?.origin is PeerV2Origin.Incoming }
              // A hybrid's peers from the v1 swarm, on canonical v1 blocks without hashes.
              val legacy = members.keys.count { it.info?.mode == PeerIdentityHandshake.Mode.V1 }
              " (in=$incoming, v1=$legacy) ${uploads?.summary().orEmpty()}" +
                " hashServed=${hashes?.served ?: 0} hashRejected=${hashes?.rejected ?: 0}" +
                " candidates=${book.pendingCount} dialing=$dialing"
            }
            "V2 swarm $label: pieces $verified/${layout.pieceCount}, peers=${peers.size}" +
              "$swarmPart unchoked=${peers.values.count { !it.choked }}, pendingCommits=" +
              "${pieces.pendingCommitCount}"
          }
        }
        check(seeding || pool.size > 0 || pieces.pendingCommitCount > 0 ||
          acceptingConnections && (swarm == null || dialing > 0 || book?.hasWork() == true ||
            discoveryOpen || swarm.waitForPeers)) {
          "All torrent peers disconnected before completion"
        }
        val room = acceptingConnections && pool.size < limit && pool.remainingCapacity > 0
        swarm?.onRoom?.invoke(room)
        val event = select<Event> {
          repeat(branches) { offset ->
            when ((preference + offset) % branches) {
              0 -> worker.completions.onReceiveCatching { result ->
                result.getOrNull()?.let { Event.Commit(it) }
                  ?: Event.Stopped(result.exceptionOrNull())
              }
              1 -> pool.events.onReceiveCatching { result ->
                result.getOrNull()?.let { Event.Peer(it) }
                  ?: Event.Stopped(result.exceptionOrNull())
              }
              2 -> if (room) {
                checkNotNull(connections).onReceiveCatching { Event.Connection(it) }
              }
              3 -> if (discoveryOpen) {
                checkNotNull(swarm).discovered.onReceiveCatching { Event.Discovered(it) }
              }
              4 -> if (failuresOpen) {
                checkNotNull(swarm).dialFailures.onReceiveCatching { Event.DialFailed(it) }
              }
              5 -> if (controlsOpen) {
                checkNotNull(swarm).controls.onReceiveCatching { Event.Controlled(it) }
              }
              6 -> if (servingOpen) {
                checkNotNull(swarm?.serve).results.onReceiveCatching { Event.Served(it) }
              }
            }
          }
          // Retry only blocked work; healthy idle peers do not poll.
          val memoryDelay = if (peers.values.any { it.admissionBlocked } ||
            hashes?.waiting(peers) == true) 250L else null
          val rateDelay = peers.values.filter { it.rateRetryAt != 0L }
            .minOfOrNull { (it.rateRetryAt - nowMs()).coerceAtLeast(1) }
          val dialDelay = if (book != null && pool.size + dialing < limit) {
            book.nextDueMs()?.coerceAtLeast(1)
          } else null
          val uploadDelay = uploads?.nextDueMs(peers)?.coerceAtLeast(1)
          // Peer exchange, and metadata answers waiting to go out.
          val exchangeDelay = extensions?.nextDueMs(peers)?.coerceAtLeast(1)
          val retryDelay = listOfNotNull(memoryDelay, rateDelay, dialDelay, uploadDelay,
            exchangeDelay).minOrNull()
          if (retryDelay != null) onTimeout(retryDelay) { Event.Retry }
        }
        preference = (preference + 1) % branches
        when (event) {
          is Event.Connection -> {
            val connected = event.value.getOrNull()
            if (connected == null) {
              acceptingConnections = false
              event.value.exceptionOrNull()?.let { throw it }
            } else try { admit(connected) } finally { connected.close() }
          }
          is Event.Discovered -> {
            val found = event.value.getOrNull()
            if (found == null) {
              discoveryOpen = false
              // A failed discovery fails the session, which a later resume retries.
              event.value.exceptionOrNull()?.let { throw it }
            } else if (found.generation < checkNotNull(swarm).generation()) {
              // Found before a tracker reset, it belongs to the old trackers' swarm (BEP 27).
              log.v { "V2 swarm $label: dropped a peer found before a reset" }
            } else checkNotNull(book).offer(found.endpoint, found.topic, found.origin, found.flags)
          }
          is Event.DialFailed -> {
            val failure = event.value.getOrNull()
            if (failure == null) failuresOpen = false else {
              dialing = (dialing - 1).coerceAtLeast(0)
              log.v {
                "V2 dial for $label failed: ${failure.cause.describeWithoutUrls()}"
              }
              checkNotNull(book).failed(failure.endpoint, failure.cause, complete)
            }
          }
          is Event.Controlled -> {
            val value = event.value.getOrNull()
            if (value == null) controlsOpen = false else control(value)
          }
          is Event.Served -> when (val result = event.value.getOrNull()) {
            // The worker only stops with the transfer.
            null -> servingOpen = false
            is TorrentV2ServeWorker.Result.Piece -> if (
              result.outcome is TorrentV2PieceStore.ReadOutcome.Revoked &&
              !pieces.isWanted(result.index)) {
              // Outside the selection, as a deleted deselected file: nobody gets it any more,
              // and the session carries on. A wanted piece fails it below.
              pieces.revoke(result.index)
              checkNotNull(uploads).revoked(result.index, peers)
              log.d { "V2 swarm $label: revoked piece ${result.index} outside the selection" }
            } else checkNotNull(uploads).result(result, peers)
            is TorrentV2ServeWorker.Result.Hashes -> verdict(result.peer,
              checkNotNull(hashes).proved(result, peers), "too many hash answers waiting")
            is TorrentV2ServeWorker.Result.HashesRejected -> verdict(result.peer,
              checkNotNull(hashes).unproved(result, peers), "too many hash answers waiting")
          }
          is Event.Commit -> {
            val index = event.value.ticket.index
            // A piece the selection dropped meanwhile no longer counts for any peer.
            val needed = pieces.isNeeded(index)
            check(pieces.completed(event.value)) { "Unknown commit completion" }
            if (event.value is TorrentV2CommitWorker.Completion.Failed) throw event.value.cause
            if (event.value is TorrentV2CommitWorker.Completion.Committed && event.value.verified) {
              if (needed) for ((peer, view) in peers) if (rarity.has(peer, index)) {
                check(view.needed > 0)
                view.needed--
              }
              uploads?.committed(index)
            }
            onProgress()
            wakeAdmission()
          }
          is Event.Retry -> wakeAdmission()
          is Event.Stopped -> {
            // Cancelling the transfer stops the workers too; whichever is seen first, it is that.
            currentCoroutineContext().ensureActive()
            throw event.cause ?: IllegalStateException("A session worker stopped")
          }
          is Event.Peer -> try {
            when (val peerEvent = event.value) {
              is PeerV2Pool.Event.Ready -> {
                check(peers.size < maxPeers && peerEvent.peer !in peers)
                // A swarm session charges each peer's view; without credit the peer is dropped.
                val viewLease = if (swarm == null) null else state.reserve(PEER_VIEW_BYTES)
                if (swarm != null && viewLease == null) {
                  log.v { "V2 peer of $label closed: no session state left for its view" }
                  stop(peerEvent.peer)
                } else {
                  val info = peerEvent.peer.info
                  val traffic = info?.let {
                    PeerTraffic.open(reporter, it.link.remote,
                      incoming = it.origin is PeerV2Origin.Incoming,
                      v1 = it.mode == PeerIdentityHandshake.Mode.V1)
                  }
                  val view = TorrentV2PeerView(peerEvent.commands, info, viewLease, nowMs,
                    traffic)
                  peers[peerEvent.peer] = view
                  if (uploads != null) {
                    // Our extension handshake comes first, then what we have.
                    val greeted = extensions?.ready(view) ?: true
                    val verified = if (pieces.anyVerified()) pieces.verifiedBits() else null
                    val ready = uploads.ready(peerEvent.peer, view, verified)
                    verdict(peerEvent.peer, if (greeted) ready else TorrentV2Uploader.Verdict.STOP,
                      "our handshake or bitfield could not be queued")
                  }
                }
              }
              is PeerV2Pool.Event.Message -> message(peerEvent.peer, peerEvent.value)
              is PeerV2Pool.Event.Closed -> {
                log.v {
                  "V2 peer of $label closed: " +
                    (peerEvent.cause?.describeWithoutUrls() ?: "without error")
                }
                peers.remove(peerEvent.peer)?.close()
                uploads?.closed(peerEvent.peer)
                rarity.remove(peerEvent.peer)
                pieces.detachPeer(peerEvent.peer.blocks)
                pieces.evictUnavailable { rarity.hasAny(it) }
                check(pool.retire(peerEvent))
                stopping.remove(peerEvent.peer)
                forget(peerEvent.peer, peerEvent.cause)
                wakeAdmission()
              }
            }
          } finally { event.value.close() }
        }
      }
      // A swarm's selection may grow while it ends; its owner sees that and downloads again.
      if (swarm == null) {
        check(store.completed()) { "Selected store completion does not match session state" }
      }
    } finally {
      peers.values.forEach { it.close() }
      resets.forEach { it.done.complete(Unit) }
      uploader?.close()
      bookLease?.close()
      scheduler?.close()
      picker?.close()
      lease.close()
    }
  }

  /**
   * Of two connections to one peer on one host, exactly one of which we dialed ([outgoing]),
   * whether we keep that one; the peer, deciding the same way, keeps the same socket. libtorrent
   * resolves such duplicates by listen port: the side whose listen port is lower keeps the
   * connection it dialed, so we compare the port we advertise with the one we dialed. With the
   * ports equal or ours unknown, the connection the higher peer ID dialed stays, as libtorrent
   * decides duplicates it does not match by address.
   */
  private fun keepOutgoing(
    swarm: TorrentV2Swarm,
    outgoing: PeerEndpoint,
    peerId: ByteString,
  ): Boolean {
    val ours = swarm.runtime.listenPortFor(outgoing)
    if (ours != 0 && ours != outgoing.port) return ours < outgoing.port
    return higherPeerId(swarm.runtime.peerId, peerId)
  }

  /** True when [local] sorts after [remote] as unsigned bytes. */
  private fun higherPeerId(local: ByteString, remote: ByteString): Boolean {
    for (index in 0 until minOf(local.size, remote.size)) {
      val left = local[index].toInt() and 0xff
      val right = remote[index].toInt() and 0xff
      if (left != right) return left > right
    }
    return local.size > remote.size
  }
}
