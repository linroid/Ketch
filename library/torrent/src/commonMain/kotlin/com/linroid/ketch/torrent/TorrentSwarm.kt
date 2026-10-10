package com.linroid.ketch.torrent

import com.linroid.ketch.api.log.KetchLogger
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.channels.SendChannel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.onTimeout
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okio.IOException
import kotlin.concurrent.atomics.AtomicLong
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.time.TimeSource

/**
 * Bounded peer workers around verified storage. Each worker owns its wire and request state.
 * One [TorrentChoker] picks the peers to upload to; workers apply its decisions to their wires.
 * Upload limits never hold a worker: requests wait in its queue until the buckets admit them,
 * while it keeps reading, downloading and following the choker.
 */
@OptIn(ExperimentalAtomicApi::class)
internal class TorrentSwarm(
  private val store: TorrentPieceStore,
  private val network: TorrentNetwork,
  private val budget: TorrentBufferBudget,
  private val peerId: ByteArray = torrentRandomBytes(20),
  private val connections: () -> Int = { 20 },
  /**
   * The part of [budget] pieces read back for peers may hold, every session's together, so
   * uploads never take the frames and pieces downloads need.
   */
  private val uploadBudget: TorrentBufferBudget = budget,
  /** Read live: peers are choked or unchoked within a tick, and a seed stops leaving SEED. */
  private val uploadPolicy: () -> TorrentUploadPolicy = { TorrentUploadPolicy.DISABLED },
  private val downloadPayload: suspend (Int) -> Unit = {},
  /** Engine-wide upload bucket, applied before [sessionUploadRate]. */
  private val uploadRate: TorrentRateLimiter = TorrentRateLimiter(),
  /** This torrent's own upload bucket. */
  private val sessionUploadRate: TorrentRateLimiter = TorrentRateLimiter(),
  /** Payload the limits admitted for a peer, counted just before it is written. */
  private val onUploaded: (Int) -> Unit = {},
  private val onProgress: suspend (Long) -> Unit = {},
  private val onCompleted: suspend () -> Unit = {},
  /** A selection change made a completed swarm incomplete; it downloads again, then completes. */
  private val onIncomplete: suspend () -> Unit = {},
  /** Test hook: lets peer exchange carry loopback and private addresses. */
  private val allowLocalPeers: Boolean = false,
  trackerOnly: Boolean = false,
  /** Our listen port as a peer at that address reaches it (BEP 10 `p`); 0 leaves it out. */
  private val listenPortFor: (PeerEndpoint) -> Int = { 0 },
  private val nowMs: () -> Long = monotonicClock(),
  private val logLabel: String = logHash(store.metadata.infoHash.hex),
) {
  /** What peer exchange may tell others about a connection: where it listens, and its flags. */
  private data class PexContact(val listen: PeerEndpoint?, val flags: Int)

  /** How a worker ended. An incoming one's [endpoint] is its source port, never dialed. */
  private class PeerResult(
    val endpoint: PeerEndpoint,
    val failure: Throwable?,
    val incoming: Boolean,
    val listen: PeerEndpoint?,
  )

  private val log = KetchLogger("TorrentSwarm")
  private val trackerRestricted = store.metadata.isPrivate || trackerOnly
  private val chokerMutex = Mutex()
  // Guarded by chokerMutex; keyed by worker ID.
  private val choker = TorrentChoker<Int>(nowMs)
  private val connectedMutex = Mutex()
  // Guarded by connectedMutex. Each worker publishes only its own contact.
  private val connected = mutableMapOf<Int, PexContact>()
  private val uploaded = AtomicLong(0)

  private fun uploadAllowed(policy: TorrentUploadPolicy, complete: Boolean): Boolean =
    policy == TorrentUploadPolicy.SEED_AFTER_COMPLETION ||
      (policy == TorrentUploadPolicy.WHILE_DOWNLOADING && !complete)

  /**
   * A closed peer stream fails once every candidate has exhausted its bounded retries. Each of
   * [selections] tells the swarm the store's selection changed; it completes the deferred once
   * the scheduler follows it, keeping every peer connected.
   */
  @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class,
    kotlinx.coroutines.DelicateCoroutinesApi::class)
  suspend fun run(
    peers: ReceiveChannel<PeerEndpoint>,
    incoming: ReceiveChannel<TorrentConnection>? = null,
    resets: ReceiveChannel<CompletableDeferred<Unit>>? = null,
    selections: ReceiveChannel<CompletableDeferred<Unit>>? = null,
  ) = supervisorScope {
    store.initialize()
    if (uploadPolicy() != TorrentUploadPolicy.SEED_AFTER_COMPLETION && store.finishIfComplete()) {
      onProgress(store.progress().sum())
      onCompleted()
      return@supervisorScope
    }
    val scheduler = TorrentPieceScheduler(BooleanArray(store.pieceCount) { store.needed(it) },
      store.verifiedPieces(), store::pieceSize, budget)
    // Any piece may become wanted, and the first is the longest.
    val largestPiece = if (store.pieceCount == 0) 0 else store.pieceSize(0)
    val peerOverhead = PEER_WIRE_BYTES + store.pieceCount * 4
    require(budget.capacity >= largestPiece + peerOverhead) {
      "Torrent buffer limit cannot hold a piece and peer protocol state"
    }
    // Leave room for one claim so connected peers can never starve piece buffers entirely.
    fun reservePeer(): TorrentBufferBudget.Lease? {
      if (budget.capacity - budget.allocated - peerOverhead < largestPiece) return null
      return budget.reserve(peerOverhead)
    }
    val active = mutableMapOf<PeerEndpoint, Job>()
    val attempts = linkedMapOf<PeerEndpoint, Int>()
    val attemptedAt = mutableMapOf<PeerEndpoint, Long>()
    val pending = ArrayDeque<PeerEndpoint>()
    val results = Channel<PeerResult>(512)
    val progressEvents = Channel<Unit>(Channel.CONFLATED)
    val pexEvents = Channel<Pair<PeerEndpoint, PexUpdate>>(64)
    val pexDirectory = TorrentPeerDirectory(trackerRestricted)
    // Flags peers advertised for endpoints; handed to a worker when it dials one.
    val peerFlags = PeerFlagBook()
    val retryAt = mutableMapOf<PeerEndpoint, Long>()
    val now = nowMs
    var discoveryClosed = false
    var complete = false
    // Hashes of stored pieces a selection made wanted; each wakes the loop when done.
    val rechecked = Channel<Unit>(Channel.CONFLATED)
    val verifiers = mutableListOf<Job>()
    suspend fun applySelection() {
      val view = scheduler.retarget { store.selectionView() }
      verifiers.removeAll { it.isCompleted }
      if (view.recheck.isEmpty()) return
      verifiers += launch {
        try {
          val found = store.verifyExisting(view.recheck)
          log.d { "Swarm $logLabel: $found of ${view.recheck.size} reselected piece(s) stored" }
          if (found > 0) rechecked.trySend(Unit)
        } catch (e: CancellationException) {
          throw e
        } catch (e: Exception) {
          // Unchecked pieces download as usual.
          log.d { "Swarm $logLabel: checking reselected pieces failed: ${e.describeWithoutUrls()}" }
        }
      }
    }
    var nextId = 0
    // Counters since the last periodic summary, which explains a stalled swarm.
    var discovered = 0
    var connectFailures = 0
    var corruptPieces = 0
    var lastSummary = now()
    fun acceptPex(source: PeerEndpoint, update: PexUpdate) {
      peerFlags.record(update.flags)
      pexDirectory.update(PeerOrigin.PEX, "${source.host}:${source.port}", update.added,
        update.dropped)
      for (endpoint in pexDirectory.candidates()) {
        if (endpoint !in attempts && endpoint !in pending &&
          attempts.size + pending.size < 4096) pending.addLast(endpoint)
      }
    }
    suspend fun logSummary() {
      // The inline lambda only runs, and only reads the store, when logging is enabled.
      log.d {
        val verified = store.verifiedPieces().count { it }
        val handshaken = connectedMutex.withLock { connected.size }
        val uploads = chokerMutex.withLock {
          "uploadSlots=${choker.unchokedCount}/$UPLOAD_SLOTS " +
            "interestedPeers=${choker.interestedCount}"
        }
        "Swarm $logLabel: pieces $verified/${store.pieceCount}, peers connected=$handshaken " +
          "active=${active.size}/${connections()}, queued=${pending.size}, " +
          "known=${attempts.size}, discovered=$discovered, failed=$connectFailures, " +
          "corrupt=$corruptPieces, $uploads uploaded=${uploaded.load()}, " +
          "discovery=${if (discoveryClosed) "closed" else "open"}"
      }
      discovered = 0
      connectFailures = 0
      corruptPieces = 0
    }
    fun launchPeer(
      endpoint: PeerEndpoint,
      overhead: TorrentBufferBudget.Lease,
      connection: TorrentConnection? = null,
    ) {
      val id = nextId++
      val incoming = connection != null
      // An incoming peer's source port is not a candidate, so it never counts as an attempt.
      if (!incoming) {
        attempts[endpoint] = (attempts[endpoint] ?: 0) + 1
        attemptedAt[endpoint] = now()
      }
      val flags = if (incoming) 0 else peerFlags[endpoint]
      active[endpoint] = launch(Dispatchers.Default, start = CoroutineStart.ATOMIC) {
        var failure: Throwable? = null
        var listen: PeerEndpoint? = if (incoming) null else endpoint
        try {
          peer(id, endpoint, flags, scheduler, progressEvents, pexEvents, connection) {
            listen = it
          }
        } catch (e: CancellationException) {
          throw e
        } catch (e: Exception) {
          failure = e
        } finally {
          withContext(NonCancellable) {
            scheduler.remove(id)
            connectedMutex.withLock { connected.remove(id) }
            chokerMutex.withLock { choker.remove(id) }
          }
          connection?.close()
          overhead.close()
          results.trySend(PeerResult(endpoint, failure, incoming, listen))
        }
      }
    }
    try {
      while (currentCoroutineContext().isActive) {
        // A terminating introducer may have queued its last PEX before its result was selected.
        repeat(64) {
          val event = pexEvents.tryReceive().getOrNull() ?: return@repeat
          acceptPex(event.first, event.second)
        }
        if (complete && !store.completed()) {
          // A selection, or a piece revoked from a file outside it, needs pieces again.
          complete = false
          onIncomplete()
        }
        // A selection change between the check and finishing keeps the swarm downloading.
        if (!complete && store.completed() && store.finishIfComplete()) {
          onProgress(store.progress().sum())
          onCompleted()
          complete = true
        }
        val policy = uploadPolicy()
        // Workers apply what changed on their next pass; this loop ticks at least every 100 ms.
        chokerMutex.withLock { choker.update(uploadAllowed(policy, complete), seeding = complete) }
        if (now() - lastSummary >= SWARM_SUMMARY_INTERVAL_MS) {
          lastSummary = now()
          logSummary()
        }
        if (complete && policy != TorrentUploadPolicy.SEED_AFTER_COMPLETION) break
        val expired = attemptedAt.filter { (endpoint, time) ->
          now() - time >= 300_000 && endpoint !in active && endpoint !in pending
        }.keys
        expired.forEach { attempts.remove(it); attemptedAt.remove(it); retryAt.remove(it) }
        val limit = connections().coerceIn(1, 512)
        active.entries.drop(limit).forEach { it.value.cancel() }
        var candidates = pending.size
        while (active.size < limit && pending.isNotEmpty() && candidates-- > 0) {
          val endpoint = pending.removeFirst()
          if (endpoint in active) continue
          if ((retryAt[endpoint] ?: 0) > now()) {
            pending.addLast(endpoint)
            continue
          }
          val overhead = reservePeer() ?: run {
            pending.addFirst(endpoint)
            break
          }
          launchPeer(endpoint, overhead)
        }
        if (discoveryClosed && pending.isEmpty() && active.isEmpty() && incoming == null) {
          if (!complete) logSummary()
          check(complete) { "No peer could complete the torrent" }
          break
        }
        select<Unit> {
          incoming?.onReceive { connection ->
            val overhead = if (active.size < limit && connection.remote !in active) {
              reservePeer()
            } else null
            if (overhead == null) connection.close()
            else launchPeer(connection.remote, overhead, connection)
          }
          resets?.onReceive { done ->
            log.d { "Swarm $logLabel: disconnecting ${active.size} peer(s) for a tracker change" }
            active.values.forEach { it.cancel() }
            active.values.forEach { it.join() }
            active.clear()
            while (results.tryReceive().isSuccess) { }
            while (peers.tryReceive().isSuccess) { }
            while (true) (incoming?.tryReceive()?.getOrNull() ?: break).close()
            pending.clear()
            attempts.clear()
            attemptedAt.clear()
            retryAt.clear()
            done.complete(Unit)
          }
          selections?.onReceive { done ->
            applySelection()
            if (complete && !store.completed()) {
              complete = false
              onIncomplete()
            }
            progressEvents.trySend(Unit)
            done.complete(Unit)
          }
          rechecked.onReceive {
            applySelection()
            progressEvents.trySend(Unit)
          }
          if (!discoveryClosed) peers.onReceiveCatching { result ->
            val endpoint = result.getOrNull()
            if (endpoint == null) discoveryClosed = true
            else if (endpoint !in attempts && attempts.size + pending.size < 4096 &&
              endpoint !in pending) {
              pending.addLast(endpoint)
              discovered++
            }
          }
          results.onReceive { result ->
            val endpoint = result.endpoint
            val failure = result.failure
            active.remove(endpoint)
            if (failure is TorrentStorageException) throw failure
            if (failure != null) {
              connectFailures++
              if (failure is CorruptPieceException) corruptPieces++
              log.v { "Peer $endpoint for $logLabel closed: ${failure.describeWithoutUrls()}" }
            }
            if (result.incoming) {
              // Never redial a source port: a public torrent dials where the peer listens.
              val listen = result.listen
              if (listen != null && !trackerRestricted && listen !in active &&
                listen !in attempts && listen !in pending &&
                attempts.size + pending.size < 4096) {
                pending.addLast(listen)
              }
            } else if (failure !is IllegalArgumentException &&
              (attempts[endpoint] ?: 0) < 3 && !complete) {
              retryAt[endpoint] = now() + 1000L * (attempts[endpoint] ?: 1)
              pending.addLast(endpoint)
            }
          }
          pexEvents.onReceive { (source, update) -> acceptPex(source, update) }
          progressEvents.onReceive { onProgress(store.progress().sum()) }
          onTimeout(100) {}
        }
      }
    } finally {
      verifiers.forEach { it.cancel() }
      active.values.forEach { it.cancel() }
      active.values.forEach { it.join() }
      verifiers.forEach { it.join() }
      rechecked.close()
      results.close()
      progressEvents.close()
      pexEvents.close()
    }
  }

  /** Runs one peer; [onListen] learns where it listens once known, also when it fails. */
  private suspend fun peer(
    id: Int,
    endpoint: PeerEndpoint,
    receivedFlags: Int,
    scheduler: TorrentPieceScheduler,
    progressEvents: SendChannel<Unit>,
    pexEvents: SendChannel<Pair<PeerEndpoint, PexUpdate>>,
    accepted: TorrentConnection?,
    onListen: (PeerEndpoint?) -> Unit,
  ) {
    val connection = accepted ?: network.connect(endpoint)
    try {
      val worker = PeerWorker(id, endpoint, connection, accepted != null, receivedFlags, scheduler,
        progressEvents, pexEvents)
      try { worker.run() } finally { onListen(worker.listen) }
    } finally {
      connection.close()
    }
  }

  /**
   * One peer connection. Its state lives in fields and its loop is split into small functions:
   * as one coroutine body, every suspension point saved every local, and the body grew past the
   * size ART compiles, leaving the hot peer loop interpreted on Android.
   */
  private inner class PeerWorker(
    private val id: Int,
    private val endpoint: PeerEndpoint,
    private val connection: TorrentConnection,
    /** The peer dialed us; [endpoint] is then its source port. */
    private val incoming: Boolean,
    /** Flags other peers advertised for [endpoint] (BEP 11), for later transports. */
    val receivedFlags: Int,
    private val scheduler: TorrentPieceScheduler,
    private val progressEvents: SendChannel<Unit>,
    private val pexEvents: SendChannel<Pair<PeerEndpoint, PexUpdate>>,
  ) {
    /** Where the peer accepts connections: the dialed endpoint, or `p` of an incoming peer. */
    var listen: PeerEndpoint? = if (incoming) null else endpoint
      private set
    private val uploadCache = TorrentUploadCache(store, uploadBudget)
    /** Requests of the peer we unchoked, oldest first; at most what we advertise (`reqq`). */
    private val uploads = ArrayDeque<PeerMessage.Request>()
    /** When the upload buckets admit the next block, if they held the last one back. */
    private var uploadRetryAt = 0L
    private val wire = PeerWire(connection, store.metadata)
    private val exchange = PeerExchange()
    /** Hosts the peer introduced by peer exchange, bounded over this connection. */
    private val introductions = PexIntroductions()
    private val extensions = PeerExtensions()
    private val state = PeerProtocolState(store.pieceCount, maxPending = 16)
    private var extensionsNegotiated = false
    /** We unchoked the peer: the choker gave it a slot and we told it so. */
    private var uploadSlot = false
    /** The peer's interest as the choker last heard it. */
    private var reportedInterest = false
    private var metadataServed = 0
    private var metadataWindow = TimeSource.Monotonic.markNow()
    private var advertised = BooleanArray(0)
    private var version = -1L
    private var claim: TorrentPieceScheduler.Claim? = null
    private var received = BooleanArray(0)
    private var requested = BooleanArray(0)
    private var receivedBytes = 0
    /** When the peer last sent a wanted block, on [nowMs]; a selection change restarts it. */
    private var lastUsefulPayload = nowMs()
    private var seenWantVersion = scheduler.wantVersion
    /** The store was complete at the last pass: a seed judges no peer by its downloads. */
    private var sawComplete = false
    private var lastBlock = TimeSource.Monotonic.markNow()
    private var lastWrite = TimeSource.Monotonic.markNow()

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    suspend fun run() = supervisorScope {
      try {
        open()
        val messages = Channel<PeerMessage>(1)
        val consumed = Channel<Unit>(1)
        val reader = launch {
          try {
            while (isActive) {
              val message = wire.read()
              messages.send(message)
              // Do not start an idle read while the actor intentionally waits on a rate limiter.
              consumed.receive()
            }
          } catch (e: Throwable) {
            messages.close(e)
          }
        }
        lastUsefulPayload = nowMs()
        lastBlock = TimeSource.Monotonic.markNow()
        lastWrite = TimeSource.Monotonic.markNow()
        try {
          while (isActive) {
            val message = select<PeerMessage?> {
              messages.onReceive { it }
              onTimeout(idleMs()) { null }
            }
            if (message != null) {
              receive(message)
              consumed.trySend(Unit)
            }
            maintain()
          }
        } finally {
          withContext(NonCancellable) {
            reader.cancelAndJoin()
            runCatching {
              withTimeoutOrNull(200) {
                for (request in state.requests) {
                  wire.send(PeerMessage.Cancel(request.index, request.begin, request.length))
                }
              }
            }
            messages.cancel()
            consumed.cancel()
          }
        }
      } finally {
        uploadCache.close()
      }
    }

    private suspend fun open() {
      val handshake = wire.handshake(PeerHandshake(store.metadata.infoHash, peerId, true, false))
      require(!handshake.peerId.contentEquals(peerId)) { "Connected to ourselves" }
      chokerMutex.withLock { choker.add(id) }
      publishContact()
      extensionsNegotiated = handshake.extensions
      metadataWindow = TimeSource.Monotonic.markNow()
      if (extensionsNegotiated) {
        wire.send(PeerExtensions.handshake(
          utMetadata = servesMetadata(store.metadata.isPrivate, uploadPolicy()),
          metadataSize = store.metadata.infoBytes.size, pex = !trackerRestricted,
          listenPort = listenPortFor(connection.remote), requestQueue = REQUEST_QUEUE,
        ))
      }
      advertised = store.verifiedPieces()
      wire.send(PeerMessage.Bitfield(pieceBitfield(advertised)))
      wire.send(PeerMessage.Control(PeerMessage.Signal.INTERESTED))
    }

    /** Publishes this connection's contact; others read nothing else of this worker. */
    private suspend fun publishContact() {
      val contact = PexContact(listen, pexFlags(reachedOutgoing = !incoming, connection,
        extensions))
      connectedMutex.withLock { connected[id] = contact }
    }

    private suspend fun receive(message: PeerMessage) {
      if (message is PeerMessage.Piece) {
        val throttling = TimeSource.Monotonic.markNow()
        val throttledAt = nowMs()
        downloadPayload(message.bytes.size)
        lastBlock += throttling.elapsedNow()
        lastUsefulPayload += nowMs() - throttledAt
      }
      val accepted = state.received(message)
      when (message) {
        // Only the one-time bitfield rebuilds availability; HAVE stays incremental so a
        // peer cannot force a full snapshot and rarity scan per announcement.
        is PeerMessage.Bitfield -> scheduler.availability(id, state.availabilitySnapshot())
        is PeerMessage.Have -> scheduler.announce(id, message.index)
        is PeerMessage.Control -> when (message.signal) {
          PeerMessage.Signal.CHOKE -> {
            scheduler.release(id)
            claim = null
          }
          // A peer that wants nothing more has no requests left (BEP 3).
          PeerMessage.Signal.NOT_INTERESTED -> uploads.clear()
          else -> Unit
        }
        is PeerMessage.Piece -> if (accepted) {
          receiveBlock(message)
          chokerMutex.withLock { choker.received(id, message.bytes.size.toLong()) }
        }
        is PeerMessage.Request -> if (uploadSlot && state.interested &&
          scheduler.isVerified(message.index) && message !in uploads &&
          uploads.size < REQUEST_QUEUE) {
          uploads.addLast(message)
        }
        is PeerMessage.Cancel ->
          uploads.remove(PeerMessage.Request(message.index, message.begin, message.length))
        is PeerMessage.Extended -> receiveExtension(message)
        else -> Unit
      }
    }

    private suspend fun receiveBlock(message: PeerMessage.Piece) {
      val current = checkNotNull(claim)
      check(message.index == current.index && message.begin % PeerWire.BLOCK_SIZE == 0)
      val block = message.begin / PeerWire.BLOCK_SIZE
      check(!received[block])
      message.bytes.copyInto(current.bytes, message.begin)
      received[block] = true
      receivedBytes += message.bytes.size
      lastBlock = TimeSource.Monotonic.markNow()
      lastUsefulPayload = nowMs()
      if (receivedBytes == current.bytes.size) {
        when (storage { store.commit(current.index, current.bytes) }) {
          CommitOutcome.VERIFIED -> {
            scheduler.verified(current.index)
            progressEvents.trySend(Unit)
          }
          // The selection dropped the piece while it arrived: the peer did nothing wrong.
          CommitOutcome.NOT_WANTED -> Unit
          CommitOutcome.CORRUPT -> {
            log.w { "Piece ${current.index} of $logLabel from $endpoint failed its hash check" }
            throw CorruptPieceException()
          }
        }
        scheduler.release(id)
        claim = null
      }
    }

    /**
     * Sends queued blocks while the upload buckets admit them. A held block waits for a later
     * pass, so a low limit never keeps this worker from reading the peer or keeping its
     * deadlines.
     */
    private suspend fun serveUploads() {
      while (uploadSlot && uploads.isNotEmpty() && uploadRetryAt <= nowMs()) {
        val request = uploads.first()
        val bytes = try {
          storage { uploadCache.read(request.index) }
        } catch (revoked: PieceRevokedException) {
          // A piece outside the selection could not be read back: refuse it and keep the peer.
          log.d { "Swarm $logLabel: revoked piece ${revoked.index}" }
          scheduler.revoke(revoked.index)
          uploads.clear()
          uploadCache.close()
          chokerMutex.withLock { choker.refused(id) }
          return
        }
        if (bytes == null) {
          // No buffer for the piece: the choker hands the slot on, and the peer is choked next.
          uploads.clear()
          chokerMutex.withLock { choker.refused(id) }
          return
        }
        val delay = uploadRate.requestDelay(request.length, sessionUploadRate)
        if (delay > 0) {
          uploadRetryAt = nowMs() + delay
          return
        }
        uploads.removeFirst()
        onUploaded(request.length)
        wire.send(PeerMessage.Piece(request.index, request.begin,
          bytes.copyOfRange(request.begin, request.begin + request.length)))
        uploaded.fetchAndAdd(request.length.toLong())
        chokerMutex.withLock { choker.uploaded(id, request.length.toLong()) }
      }
    }

    /** How long the worker waits for a message: a held upload wakes it when it is due. */
    private fun idleMs(): Long {
      if (!uploadSlot || uploads.isEmpty()) return IDLE_MS
      return (uploadRetryAt - nowMs()).coerceIn(1, IDLE_MS)
    }

    private suspend fun receiveExtension(message: PeerMessage.Extended) {
      require(extensionsNegotiated) { "Unnegotiated peer extension" }
      if (message.id == 0) {
        extensions.receive(message.payload, 4 * 1024 * 1024)
        // An incoming peer becomes a contact once it says where it listens.
        val port = extensions.listenPort
        if (incoming && port != null) listen = PeerEndpoint(connection.remote.host, port)
        publishContact()
      } else if (message.id == PeerExtensions.METADATA) {
        val header = Bencode.parse(message.payload, PeerWire.MAX_FRAME_SIZE)
        if (header["msg_type"]?.integer == 0L) {
          val index = requireNotNull(header["piece"]?.integer)
          require(index in 0..Int.MAX_VALUE.toLong())
          val remoteId = extensions.id("ut_metadata")
          if (remoteId != 0) {
            if (metadataWindow.elapsedNow().inWholeSeconds >= 60) {
              metadataServed = 0
              metadataWindow = TimeSource.Monotonic.markNow()
            }
            val limit = (store.metadata.infoBytes.size / 16_384 + 1) * 3
            // Served only while the policy still allows it, whatever the handshake offered.
            val response = if (servesMetadata(store.metadata.isPrivate, uploadPolicy()) &&
              metadataServed < limit) {
              metadataServed++
              TorrentMetadataExchange.response(remoteId, index.toInt(), store.metadata)
            } else TorrentMetadataExchange.metadataMessage(remoteId, 2, index.toInt())
            wire.send(response)
          }
        }
      } else if (message.id == PeerExtensions.PEX) {
        require(!trackerRestricted) {
          "Tracker-restricted peer exchange is forbidden"
        }
        val update = exchange.receive(message.payload)
        // One endpoint per host, and a bounded number over the connection. The directory frees
        // what a peer drops, but those may already wait to be dialed: dropping makes no room.
        val added = update.added.filter { peer ->
          (allowLocalPeers || numericAddress(peer.host)?.let(::publicTorrentAddress) == true) &&
            introductions.admit(peer)
        }
        val kept = added.toSet()
        pexEvents.send(connection.remote to update.copy(added = added,
          flags = update.flags.filterKeys { it in kept }))
      }
    }

    /** Runs after every message and idle tick: upload slot, PEX, HAVEs, deadlines, requests. */
    private suspend fun maintain() {
      uploadCache.expire()
      val unchoked = chokerMutex.withLock {
        if (reportedInterest != state.interested) {
          reportedInterest = state.interested
          choker.interested(id, reportedInterest)
        }
        choker.isUnchoked(id)
      }
      if (unchoked != uploadSlot) {
        uploadSlot = unchoked
        if (!unchoked) {
          // Choking a peer discards what it asked for (BEP 3).
          uploads.clear()
          uploadCache.close()
        }
        wire.send(PeerMessage.Control(
          if (unchoked) PeerMessage.Signal.UNCHOKE else PeerMessage.Signal.CHOKE))
      }
      serveUploads()
      if (!trackerRestricted && extensions.id("ut_pex") != 0 && exchange.due()) {
        // Only listen endpoints, never another peer's source port.
        val contacts = connectedMutex.withLock {
          connected.filterKeys { it != id }.values.mapNotNull { contact ->
            contact.listen?.takeIf { it != listen }?.let { it to contact.flags }
          }
        }.filter { (peer, _) ->
          allowLocalPeers || numericAddress(peer.host)?.let(::publicTorrentAddress) == true
        }.toMap()
        exchange.message(extensions.id("ut_pex"), contacts)?.let {
          wire.send(it.message)
          exchange.commit(it)
        }
      }
      scheduler.snapshot(version)?.let { (nextVersion, pieces) ->
        version = nextVersion
        for (index in pieces.indices) {
          if (pieces[index] && !advertised[index]) {
            wire.send(PeerMessage.Have(index))
            advertised[index] = true
          }
        }
      }
      val wantVersion = scheduler.wantVersion
      val retargeted = wantVersion != seenWantVersion
      if (retargeted) {
        seenWantVersion = wantVersion
        // The selection changed: a seed that downloads again judges its peers from now on.
        lastUsefulPayload = nowMs()
      }
      val current = claim
      if (current != null && (scheduler.isVerified(current.index) ||
          retargeted && !scheduler.isWanted(current.index))) {
        for (request in state.requests) {
          state.cancel(request)
          wire.send(PeerMessage.Cancel(request.index, request.begin, request.length))
        }
        scheduler.release(id)
        claim = null
      }
      val completed = store.completed()
      // The selection grew while seeding: a peer has 90 s from now, not from its last block.
      if (sawComplete && !completed) lastUsefulPayload = nowMs()
      sawComplete = completed
      if (!completed && nowMs() - lastUsefulPayload >= 90_000) {
        throw IOException("Peer made no useful download progress")
      }
      if (state.requests.isNotEmpty() && lastBlock.elapsedNow().inWholeSeconds >= 20) {
        error("Peer block deadline exceeded")
      }
      if (!state.choking && claim == null) {
        claim = scheduler.claim(id)
        claim?.let {
          received = BooleanArray((it.bytes.size + PeerWire.BLOCK_SIZE - 1) /
            PeerWire.BLOCK_SIZE)
          requested = BooleanArray(received.size)
          receivedBytes = 0
          lastBlock = TimeSource.Monotonic.markNow()
        }
      }
      claim?.let { assigned ->
        if (!state.choking) {
          for (block in requested.indices) {
            if (state.requests.size >= 16) break
            if (requested[block]) continue
            val begin = block * PeerWire.BLOCK_SIZE
            val request = PeerMessage.Request(assigned.index, begin,
              minOf(PeerWire.BLOCK_SIZE, assigned.bytes.size - begin))
            state.requested(request)
            wire.send(request)
            requested[block] = true
            lastWrite = TimeSource.Monotonic.markNow()
          }
        }
      }
      if (lastWrite.elapsedNow().inWholeSeconds >= 30) {
        wire.send(PeerMessage.KeepAlive)
        lastWrite = TimeSource.Monotonic.markNow()
      }
    }
  }
}

/** One maximal frame plus its decode copy; piece buffers are charged separately per claim. */
private const val PEER_WIRE_BYTES = 2 * PeerWire.MAX_FRAME_SIZE

/** Requests a v1 peer may queue with us (BEP 10 `reqq`). */
private const val REQUEST_QUEUE = 16

/** The longest a peer worker waits for a message before its next pass. */
private const val IDLE_MS = 100L

internal class TorrentStorageException(cause: IOException) :
  Exception("Torrent storage failed", cause)

/** An [IllegalArgumentException], so the swarm never retries the peer that sent the piece. */
internal class CorruptPieceException : IllegalArgumentException("Peer sent a corrupt piece")

/** How often a swarm logs its peer and piece counts at debug level. */
internal const val SWARM_SUMMARY_INTERVAL_MS = 30_000L

private suspend fun <T> storage(block: suspend () -> T): T = try {
  block()
} catch (e: IOException) {
  throw TorrentStorageException(e)
}
