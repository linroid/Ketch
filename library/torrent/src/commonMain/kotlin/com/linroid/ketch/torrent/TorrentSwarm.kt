package com.linroid.ketch.torrent

import com.linroid.ketch.api.log.KetchLogger
import com.linroid.ketch.core.engine.ConnectionReporter
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.TimeoutCancellationException
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
  /** Reports each connected peer and its payload among the task's live connections. */
  private val reporter: ConnectionReporter = ConnectionReporter.None,
  /** Hears how each peer connection ended, for diagnostics. */
  private val peerOutcomes: (PeerOutcomeReport) -> Unit = {},
  /** Our DHT port for a peer at that address (BEP 5 `PORT`); 0 when DHT does not run. */
  private val dhtPortFor: (PeerEndpoint) -> Int = { 0 },
  /** A DHT node a peer named with `PORT`; must return at once. */
  private val onDhtNode: (PeerEndpoint) -> Unit = {},
  /** Peers that peer exchange says speak uTP; must return at once. */
  private val onUtpPeers: (Collection<PeerEndpoint>) -> Unit = {},
  /** Fetches the torrent's web seeds (BEP 19); null leaves them out. */
  private val webSeedHttp: TorrentHttp? = null,
  /** The first backoff of a failed web seed; tests pass a short one. */
  private val webSeedRetryMs: Long = WEB_SEED_RETRY_MS,
  /** Peers local service discovery (BEP 14) found so far, for the summary. */
  private val localPeers: () -> Int = { 0 },
  /**
   * How long a Fast peer (BEP 6) that choked us has to reject our requests before we forget
   * them ourselves and hand their piece to other peers.
   */
  private val fastRejectGraceMs: Long = FAST_REJECT_GRACE_MS,
) {
  /**
   * What peer exchange may tell others about a connection: where it listens, and its flags.
   * [fast] counts it among peers with the Fast extension in the summary.
   */
  private data class PexContact(val listen: PeerEndpoint?, val flags: Int, val fast: Boolean)

  /** How a worker ended. An incoming one's [endpoint] is its source port, never dialed. */
  private class PeerResult(
    val endpoint: PeerEndpoint,
    val failure: Throwable?,
    val incoming: Boolean,
    val listen: PeerEndpoint?,
    val attempt: PeerAttempt,
    val flags: Int,
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
    val diagnostics = PeerDiagnostics(listener = peerOutcomes)
    val webSeedStats = WebSeedStats()
    // A web seed's storage failure stops the swarm, as a peer's does.
    val webSeedFailures = Channel<TorrentStorageException>(1)
    val webSeedUrls = webSeedHttp?.let { store.metadata.webSeeds }.orEmpty()
    val webSeeds = webSeedUrls.mapIndexed { number, url ->
      // Negative IDs never collide with peer workers'.
      val pieces = SwarmWebSeedPieces(-1 - number, url, store, scheduler,
        onVerified = { progressEvents.trySend(Unit) })
      val worker = WebSeedWorker(url, pieces, checkNotNull(webSeedHttp), webSeedStats, logLabel,
        reporter, webSeedRetryMs, downloadPayload = downloadPayload)
      launch(Dispatchers.Default) {
        try {
          worker.run()
        } catch (e: TorrentStorageException) {
          webSeedFailures.trySend(e)
        }
      }
    }
    if (webSeeds.isNotEmpty()) {
      log.i { "Swarm $logLabel: downloading from ${webSeeds.size} web seed(s) too" }
    }
    fun acceptPex(source: PeerEndpoint, update: PexUpdate) {
      peerFlags.record(update.flags)
      onUtpPeers(update.flags.filterValues { it and PEX_FLAG_UTP != 0 }.keys)
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
        val (handshaken, fast) = connectedMutex.withLock {
          connected.size to connected.values.count { it.fast }
        }
        val uploads = chokerMutex.withLock {
          "uploadSlots=${choker.unchokedCount}/$UPLOAD_SLOTS " +
            "interestedPeers=${choker.interestedCount}"
        }
        "Swarm $logLabel: pieces $verified/${store.pieceCount}, peers connected=$handshaken " +
          "fast=$fast " +
          "active=${active.size}/${connections()}, queued=${pending.size}, " +
          "known=${attempts.size}, discovered=$discovered, lsd=${localPeers()}, " +
          "failed=$connectFailures, " +
          "corrupt=$corruptPieces, $uploads uploaded=${uploaded.load()}, " +
          "discovery=${if (discoveryClosed) "closed" else "open"}; ${diagnostics.summary()}" +
          if (webSeeds.isEmpty()) "" else
            "; ${webSeedStats.summary(webSeeds.count { it.isActive }, webSeeds.size)}"
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
      val attempt = PeerAttempt(incoming, PeerIdentityHandshake.Mode.V1)
      active[endpoint] = launch(Dispatchers.Default, start = CoroutineStart.ATOMIC) {
        var failure: Throwable? = null
        var listen: PeerEndpoint? = if (incoming) null else endpoint
        try {
          peer(id, endpoint, flags, attempt, scheduler, progressEvents, pexEvents, connection) {
            listen = it
          }
        } catch (e: TimeoutCancellationException) {
          // A connect, handshake or idle deadline of this peer, not the swarm stopping it.
          failure = e
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
          results.trySend(PeerResult(endpoint, failure, incoming, listen, attempt, flags))
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
        if (discoveryClosed && pending.isEmpty() && active.isEmpty() && incoming == null &&
          webSeeds.none { it.isActive }) {
          // A web seed that stopped on a disk error queued it before going inactive.
          webSeedFailures.tryReceive().getOrNull()?.let { throw it }
          if (!complete) logSummary()
          if (!complete) log.i { "Swarm $logLabel ran out of peers: ${diagnostics.summary()}" }
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
            diagnostics.record(endpoint, result.attempt, failure, result.incoming, result.flags)
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
          webSeedFailures.onReceive { throw it }
          progressEvents.onReceive { onProgress(store.progress().sum()) }
          onTimeout(100) {}
        }
      }
    } finally {
      verifiers.forEach { it.cancel() }
      webSeeds.forEach { it.cancel() }
      active.values.forEach { it.cancel() }
      active.values.forEach { it.join() }
      webSeeds.forEach { it.join() }
      verifiers.forEach { it.join() }
      // Peers still connected when the swarm stops count as connected too.
      while (true) {
        val result = results.tryReceive().getOrNull() ?: break
        diagnostics.record(result.endpoint, result.attempt, result.failure, result.incoming,
          result.flags)
      }
      rechecked.close()
      webSeedFailures.close()
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
    attempt: PeerAttempt,
    scheduler: TorrentPieceScheduler,
    progressEvents: SendChannel<Unit>,
    pexEvents: SendChannel<Pair<PeerEndpoint, PexUpdate>>,
    accepted: TorrentConnection?,
    onListen: (PeerEndpoint?) -> Unit,
  ) {
    val connection = accepted ?: network.connectPeer(endpoint, store.metadata.infoHash.toBytes())
    attempt.stage = PeerStage.HANDSHAKING
    attempt.encrypted = connection.encrypted
    attempt.utp = connection.utp
    try {
      val worker = PeerWorker(id, endpoint, connection, accepted != null, receivedFlags, attempt,
        scheduler, progressEvents, pexEvents)
      try { worker.run() } finally { onListen(worker.listen) }
    } finally {
      connection.close()
    }
  }

  /**
   * One peer connection. Its state lives in fields and its loop is split into small functions:
   * as one coroutine body, every suspension point saved every local, and the body grew past the
   * size ART compiles, leaving the hot peer loop interpreted on Android.
   *
   * With the Fast extension (BEP 6) negotiated, availability may come as Have All or Have None,
   * requests we will not serve are rejected rather than dropped, a CHOKE keeps our requests
   * until the peer rejects them ([fastRejectGraceMs] at most), a rejected piece goes back to the
   * scheduler, and allowed-fast pieces flow both ways while choked. Without it, those messages
   * are protocol errors.
   */
  private inner class PeerWorker(
    private val id: Int,
    private val endpoint: PeerEndpoint,
    private val connection: TorrentConnection,
    /** The peer dialed us; [endpoint] is then its source port. */
    private val incoming: Boolean,
    /** Flags other peers advertised for [endpoint] (BEP 11), for later transports. */
    val receivedFlags: Int,
    private val attempt: PeerAttempt,
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
    /** Created once the handshake tells whether the Fast extension is negotiated. */
    private lateinit var state: PeerProtocolState
    private var extensionsNegotiated = false
    /** Both sides set the Fast extension bit (BEP 6). */
    private var fast = false
    /** Pieces this peer may download while we choke it (BEP 6), offered only while we upload. */
    private var allowedFast: Set<Int> = emptySet()
    /** Pieces the peer rejected though it has them; claimed elsewhere until it unchokes us anew. */
    private val refused = mutableSetOf<Int>()
    /** Rejects of the current claim's blocks; enough of them hand the piece to other peers. */
    private var claimRejects = 0
    /** When the peer last choked us, on [nowMs]. */
    private var chokedAt = 0L
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
    /** This connection among the task's live connections, once its handshake succeeded. */
    private var traffic: PeerTraffic? = null

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
        traffic?.close()
        uploadCache.close()
      }
    }

    private suspend fun open() {
      // Private and tracker-only torrents never touch the DHT (BEP 27).
      val dhtPort = if (trackerRestricted) 0 else dhtPortFor(connection.remote)
      val handshake = wire.handshake(PeerHandshake(store.metadata.infoHash, peerId, true,
        dht = dhtPort > 0, fast = true))
      require(!handshake.peerId.contentEquals(peerId)) { "Connected to ourselves" }
      fast = handshake.fast
      state = PeerProtocolState(store.pieceCount, maxPending = PIPELINE, fast = fast)
      attempt.stage = PeerStage.CONNECTED
      traffic = PeerTraffic.open(reporter, endpoint, incoming, v1 = true)
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
      wire.send(when {
        fast && advertised.none { it } -> PeerMessage.HaveNone
        fast && advertised.all { it } -> PeerMessage.HaveAll
        else -> PeerMessage.Bitfield(pieceBitfield(advertised))
      })
      if (dhtPort > 0 && handshake.dht) wire.send(PeerMessage.Port(dhtPort))
      if (fast && uploadingFast()) offerAllowedFast()
      wire.send(PeerMessage.Control(PeerMessage.Signal.INTERESTED))
    }

    /**
     * Tells an IPv4 peer the canonical allowed-fast pieces (BEP 6), held or not: the set stays the
     * same for the connection, and a piece we verify later is served once its HAVE goes out.
     */
    private suspend fun offerAllowedFast() {
      val pieces = allowedFastOffer(connection.remote.host, store.metadata.infoHash.toBytes(),
        store.pieceCount)
      allowedFast = pieces.toSet()
      for (index in pieces) wire.send(PeerMessage.AllowedFast(index))
    }

    /** Whether we serve allowed-fast pieces now: only while the policy uploads at all. */
    private suspend fun uploadingFast(): Boolean =
      uploadAllowed(uploadPolicy(), store.completed())

    /** Publishes this connection's contact; others read nothing else of this worker. */
    private suspend fun publishContact() {
      val contact = PexContact(listen, pexFlags(reachedOutgoing = !incoming, connection,
        extensions), fast)
      connectedMutex.withLock { connected[id] = contact }
    }

    private suspend fun receive(message: PeerMessage) {
      if (message is PeerMessage.Piece) {
        val throttling = TimeSource.Monotonic.markNow()
        val throttledAt = nowMs()
        traffic?.received(message.bytes.size)
        attempt.payload += message.bytes.size
        downloadPayload(message.bytes.size)
        lastBlock += throttling.elapsedNow()
        lastUsefulPayload += nowMs() - throttledAt
      }
      require(fast || !message.isFastExtension()) { "Fast extension message without BEP 6" }
      val wasChoking = state.choking
      val accepted = state.received(message)
      when (message) {
        // Only the one-time bitfield rebuilds availability; HAVE stays incremental so a
        // peer cannot force a full snapshot and rarity scan per announcement.
        is PeerMessage.Bitfield, PeerMessage.HaveAll, PeerMessage.HaveNone ->
          scheduler.availability(id, state.availabilitySnapshot())
        is PeerMessage.Have -> scheduler.announce(id, message.index)
        is PeerMessage.Control -> when (message.signal) {
          PeerMessage.Signal.CHOKE -> if (fast) {
            // Our requests stand until the peer rejects them; the block deadline restarts. A
            // repeated CHOKE keeps both clocks, or a peer repeating it would hold the piece.
            if (!wasChoking) {
              chokedAt = nowMs()
              lastBlock = TimeSource.Monotonic.markNow()
            }
          } else {
            scheduler.release(id)
            claim = null
          }
          PeerMessage.Signal.UNCHOKE -> refused.clear()
          // A peer that wants nothing more has no requests left (BEP 3).
          PeerMessage.Signal.NOT_INTERESTED -> rejectUploads()
          else -> Unit
        }
        is PeerMessage.Piece -> if (accepted) {
          receiveBlock(message)
          chokerMutex.withLock { choker.received(id, message.bytes.size.toLong()) }
        }
        is PeerMessage.Reject -> if (accepted) rejected(message)
        is PeerMessage.Request -> request(message)
        is PeerMessage.Cancel -> {
          val request = PeerMessage.Request(message.index, message.begin, message.length)
          // A Fast peer hears back about each queued request it cancels (BEP 6).
          if (uploads.remove(request) && fast) wire.send(reject(request))
        }
        is PeerMessage.Extended -> receiveExtension(message)
        // The peer's own address with its DHT port: a node that can start a DHT whose routers
        // do not answer.
        is PeerMessage.Port -> if (!trackerRestricted) {
          onDhtNode(PeerEndpoint(connection.remote.host, message.port))
        }
        else -> Unit
      }
    }

    /** Queues a request we serve; a Fast peer hears about one we will not (BEP 6). */
    private suspend fun request(message: PeerMessage.Request) {
      if (message in uploads) return
      val serves = uploads.size < REQUEST_QUEUE && scheduler.isVerified(message.index) &&
        (uploadSlot && state.interested ||
          fast && message.index in allowedFast && uploadingFast())
      if (serves) uploads.addLast(message)
      else if (fast) wire.send(reject(message))
    }

    /** Drops the queued requests [which] picks, rejecting them to a Fast peer. */
    private suspend fun rejectUploads(which: (PeerMessage.Request) -> Boolean = { true }) {
      val dropped = uploads.filter(which)
      if (dropped.isEmpty()) return
      uploads.removeAll(dropped)
      if (fast) for (request in dropped) wire.send(reject(request))
    }

    private fun reject(request: PeerMessage.Request) =
      PeerMessage.Reject(request.index, request.begin, request.length)

    /**
     * The peer will not send a block we asked for, which is no fault of its own. Choked, its
     * piece goes back to the scheduler at once for other peers; unchoked, the block is asked for
     * again, until the piece has had as many rejects as blocks and is left to other peers.
     */
    private suspend fun rejected(message: PeerMessage.Reject) {
      val current = checkNotNull(claim)
      check(message.index == current.index && message.begin % PeerWire.BLOCK_SIZE == 0)
      requested[message.begin / PeerWire.BLOCK_SIZE] = false
      if (state.choking && !state.allowsFast(current.index)) {
        dropClaim(cancel = false)
      } else if (++claimRejects >= requested.size) {
        log.v { "Peer $endpoint of $logLabel keeps rejecting piece ${current.index}" }
        refused += current.index
        dropClaim(cancel = true)
      }
    }

    /** Forgets our requests, canceling them on the wire when [cancel], and frees the claim. */
    private suspend fun dropClaim(cancel: Boolean) {
      for (request in state.requests) {
        state.cancel(request)
        if (cancel) wire.send(PeerMessage.Cancel(request.index, request.begin, request.length))
      }
      scheduler.release(id)
      claim = null
      claimRejects = 0
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
      // Choked, only allowed-fast requests are left queued (BEP 6).
      while (uploads.isNotEmpty() && uploadRetryAt <= nowMs()) {
        val request = uploads.first()
        val bytes = try {
          storage { uploadCache.read(request.index) }
        } catch (revoked: PieceRevokedException) {
          // A piece outside the selection could not be read back: refuse it and keep the peer.
          log.d { "Swarm $logLabel: revoked piece ${revoked.index}" }
          scheduler.revoke(revoked.index)
          rejectUploads()
          uploadCache.close()
          chokerMutex.withLock { choker.refused(id) }
          return
        }
        if (bytes == null) {
          // No buffer for the piece: the choker hands the slot on, and the peer is choked next.
          rejectUploads()
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
        // Counted once sent, as v2 does: a blocked or failed write moved nothing yet.
        traffic?.sent(request.length)
        uploaded.fetchAndAdd(request.length.toLong())
        chokerMutex.withLock { choker.uploaded(id, request.length.toLong()) }
      }
    }

    /** How long the worker waits for a message: a held upload wakes it when it is due. */
    private fun idleMs(): Long {
      if (uploads.isEmpty()) return IDLE_MS
      return (uploadRetryAt - nowMs()).coerceIn(1, IDLE_MS)
    }

    private suspend fun receiveExtension(message: PeerMessage.Extended) {
      require(extensionsNegotiated) { "Unnegotiated peer extension" }
      if (message.id == 0) {
        extensions.receive(message.payload, 4 * 1024 * 1024)
        // An incoming peer becomes a contact once it says where it listens.
        val port = extensions.listenPort
        if (incoming && port != null) {
          listen = PeerEndpoint(connection.remote.host, port)
          traffic?.listenPort(port)
        }
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

    /**
     * A Fast peer that choked us rejects what it will not serve; requests it leaves unanswered
     * for [fastRejectGraceMs] are forgotten and their piece handed to other peers, without
     * holding it against the peer. Allowed-fast requests keep the block deadline.
     */
    private suspend fun expireChokedRequests() {
      val current = claim ?: return
      if (!fast || !state.choking || state.allowsFast(current.index) ||
        state.requests.isEmpty() || nowMs() - chokedAt < fastRejectGraceMs) return
      log.v { "Peer $endpoint of $logLabel choked without rejecting piece ${current.index}" }
      dropClaim(cancel = true)
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
        if (!unchoked) uploadCache.close()
        wire.send(PeerMessage.Control(
          if (unchoked) PeerMessage.Signal.UNCHOKE else PeerMessage.Signal.CHOKE))
      }
      if (!uploadSlot && uploads.isNotEmpty()) {
        // Choking a peer discards what it asked for (BEP 3), rejected after the CHOKE to a Fast
        // peer, except allowed-fast pieces while we still upload (BEP 6).
        val serving = fast && uploadingFast()
        rejectUploads { !serving || it.index !in allowedFast }
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
        dropClaim(cancel = true)
      }
      val completed = store.completed()
      // The selection grew while seeding: a peer has 90 s from now, not from its last block.
      if (sawComplete && !completed) lastUsefulPayload = nowMs()
      sawComplete = completed
      if (!completed && nowMs() - lastUsefulPayload >= 90_000) {
        throw IOException("Peer made no useful download progress")
      }
      expireChokedRequests()
      if (state.requests.isNotEmpty() && lastBlock.elapsedNow().inWholeSeconds >= 20) {
        error("Peer block deadline exceeded")
      }
      // Choked, a Fast peer still serves its allowed-fast pieces (BEP 6).
      if (claim == null && (!state.choking || state.allowsAnyFast)) {
        claim = scheduler.claim(id, eligible = { index ->
          index !in refused && (!state.choking || state.allowsFast(index))
        })
        claim?.let {
          received = BooleanArray((it.bytes.size + PeerWire.BLOCK_SIZE - 1) /
            PeerWire.BLOCK_SIZE)
          requested = BooleanArray(received.size)
          receivedBytes = 0
          claimRejects = 0
          lastBlock = TimeSource.Monotonic.markNow()
        }
      }
      claim?.let { assigned ->
        if (state.canRequest(assigned.index)) {
          // No more than the peer queues (BEP 10 `reqq`), which it would otherwise reject.
          val pipeline = minOf(PIPELINE, extensions.requestQueue ?: PIPELINE)
          for (block in requested.indices) {
            if (state.requests.size >= pipeline) break
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
      traffic?.state(peerChoking = state.choking, uploadSlot = uploadSlot,
        peerInterested = state.interested)
    }
  }
}

/** One maximal frame plus its decode copy; piece buffers are charged separately per claim. */
private const val PEER_WIRE_BYTES = 2 * PeerWire.MAX_FRAME_SIZE

/** Requests a v1 peer may queue with us (BEP 10 `reqq`). */
private const val REQUEST_QUEUE = 16

/** Block requests we keep outstanding with one v1 peer. */
private const val PIPELINE = 16

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
