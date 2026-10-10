package com.linroid.ketch.torrent

import com.linroid.ketch.api.log.KetchLogger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ClosedSendChannelException
import kotlinx.coroutines.channels.SendChannel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okio.ByteString
import kotlin.concurrent.atomics.AtomicBoolean
import kotlin.concurrent.atomics.AtomicLong
import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.ExperimentalAtomicApi

/** Engine-wide services a v2 owner borrows for its lifetime; the owner never closes them. */
internal class TorrentV2Runtime(
  val network: TorrentNetwork,
  val peerId: ByteString,
  /** Engine transfer budget: piece buffers and wire frames. */
  val buffers: TorrentBufferBudget,
  /** Engine session-state partition: lifecycle, pool and per-peer state. */
  val state: TorrentBufferBudget,
  /** Engine-wide download bucket, applied before the session's own. */
  val downloadRate: TorrentRateLimiter = TorrentRateLimiter(),
  /** Engine-wide upload bucket, applied before the session's own. */
  val uploadRate: TorrentRateLimiter = TorrentRateLimiter(),
  /** The engine's live upload policy, read whenever the session decides to upload or seed. */
  val uploadPolicy: () -> TorrentUploadPolicy = { TorrentUploadPolicy.DISABLED },
  /**
   * Where the peer at the given address can reach us, read for each extension handshake (BEP 10
   * `p`); zero leaves it out.
   */
  val listenPortFor: (PeerEndpoint) -> Int = { 0 },
  /** Test hook: peer exchange also shares and accepts loopback and private addresses. */
  val allowLocalPeers: Boolean = false,
  /**
   * The part of [buffers] what peers ask of us may hold, every owner's together: cached upload
   * pieces, the pieces block proofs hash and the proofs waiting to be sent.
   */
  val uploadBuffers: TorrentBufferBudget = buffers,
)

/** Per-owner limits and the discovery it runs while transferring. */
internal class TorrentV2SessionOptions(
  val maxPeers: Int = 100,
  val initialConnections: Int = maxPeers,
  /** Tracker-only privacy, like private metainfo, admits only peers its trackers returned. */
  val privacy: TorrentDiscoveryPrivacy = TorrentDiscoveryPrivacy.PUBLIC,
  /**
   * Keep transferring while no peer or candidate is left, as engine owners do; peers may still
   * arrive. Without it, a transfer whose discovery ended with nobody left fails.
   */
  val waitForPeers: Boolean = false,
  val throttle: suspend (Int) -> Unit = {},
  /** Runs for the length of each transfer, feeding it peers through the sink. */
  val discovery: suspend (TorrentV2DiscoverySink) -> Unit = { awaitCancellation() },
)

/** Discovery's only view of a session, valid for one transfer. */
internal interface TorrentV2DiscoverySink {
  /** Queues [endpoint] for the session's candidate book, waiting while the queue is full. */
  suspend fun offer(endpoint: PeerEndpoint, topic: PeerTopic, origin: PeerOrigin)

  /** Like [offer] without waiting: false when the queue is full or the transfer ended. */
  fun tryOffer(endpoint: PeerEndpoint, topic: PeerTopic, origin: PeerOrigin): Boolean

  /** The latest peers [topic]'s trackers returned; restricted sessions admit only their hosts. */
  suspend fun trackerPeers(topic: TrackerTopic, peers: List<PeerEndpoint>)

  /** A private torrent changed tracker: revoke its hosts, then drop every peer (BEP 27). */
  suspend fun reset()

  fun uploadedBytes(): Long
  fun receivedBytes(): Long
}

/**
 * Offers the endpoints [discover] sends as [topic] peers from [origin], until it returns or
 * fails; for discovery written against a plain channel.
 */
internal suspend fun TorrentV2DiscoverySink.relay(
  topic: PeerTopic = PeerTopic.V2,
  origin: PeerOrigin = PeerOrigin.TRACKER,
  discover: suspend (SendChannel<PeerEndpoint>) -> Unit,
): Unit = coroutineScope {
  val endpoints = Channel<PeerEndpoint>()
  val forward = launch { for (endpoint in endpoints) offer(endpoint, topic, origin) }
  try { discover(endpoints) } finally { endpoints.close() }
  forward.join()
}

/**
 * Full-metainfo v2/hybrid owner: downloads, uploads and seeds. Engine owners are created by
 * [open] and live until [close]; network policy and metadata admission belong to the engine.
 * While it transfers, it dials the peers discovery finds and answers the peers the engine routes
 * to [accept]; one pool, dialer and loop serve the whole transfer, so limit changes and resets
 * never drop other peers. Once complete it keeps uploading as a seed while the runtime's upload
 * policy is [TorrentUploadPolicy.SEED_AFTER_COMPLETION], also when it starts complete. Peers that
 * negotiate extensions (BEP 10) exchange peers with it and, as the policy allows, fetch its info
 * dictionary; v2 peers also get hash proofs (BEP 52), so magnet peers can resolve it.
 */
@OptIn(ExperimentalAtomicApi::class)
internal class TorrentV2DownloadSession private constructor(
  private val scope: CoroutineScope,
  private val document: TorrentV2Document,
  private val layout: TorrentContentLayout,
  private val selected: Set<String>,
  private val store: TorrentV2PieceStore,
  private val runtime: TorrentV2Runtime,
  options: TorrentV2SessionOptions,
  private val lifecycleLease: TorrentBufferBudget.Lease,
) : TorrentSession {
  private val network = runtime.network
  private val peerId = runtime.peerId
  private val buffers = runtime.buffers
  private val memory = runtime.state
  private val maxPeers = options.maxPeers
  private val globalRate = runtime.downloadRate
  private val throttle = options.throttle
  private val discover = options.discovery
  private val privacy = options.privacy
  private val waitForPeers = options.waitForPeers
  private val restricted = document.info.privateTorrent ||
    privacy == TorrentDiscoveryPrivacy.TRACKER_ONLY
  override val infoHash: String get() = document.info.hash.hex
  override val downloadedBytes: StateFlow<Long> get() = mutableProgress
  override val totalBytes: Long = TorrentOutputMapping.from(document).files
    .filter { selected.isEmpty() || it.id in selected }
    .sumOf { document.info.files[it.v2Index].length }
  override val downloadSpeed: Long get() = 0
  private val log = KetchLogger("TorrentSession")
  private val label = "taskId=${store.taskId} (v2 ${logHash(document.info.hash.hex)})"

  override fun setFilePriorities(priorities: Map<Int, Int>) {
    error("File selection is fixed for this session")
  }

  override suspend fun saveResumeData(): ByteArray? = store.resumeData()

  suspend fun fileProgress(): Map<String, Long> = store.progress()
  fun receivedBytes(): Long = store.receivedBytes()
  fun uploadedBytes(): Long = store.uploadedBytes()

  private val v1Uploaded = AtomicLong(0)

  /** The part of [uploadedBytes] that went to peers on v1 routes, a hybrid's v1 swarm. */
  fun v1UploadedBytes(): Long = v1Uploaded.load()

  private val connectionLimit = MutableStateFlow(options.initialConnections)

  fun setConnections(value: Int) {
    require(value in 1..500)
    connectionLimit.value = minOf(value, maxPeers)
  }

  private val lifecycle = Mutex()
  private val downloadRate = TorrentRateLimiter()
  private val uploadRate = TorrentRateLimiter()
  private var job: Job? = null
  private var closed = false
  private var filesDeleted = false
  private val mutableState = MutableStateFlow(TorrentSessionState.PAUSED)
  private val mutableProgress = MutableStateFlow(0L)
  private val mutableFailure = MutableStateFlow<Throwable?>(null)
  override val state: StateFlow<TorrentSessionState> get() = mutableState
  val verifiedBytes: StateFlow<Long> get() = mutableProgress
  val failure: StateFlow<Throwable?> get() = mutableFailure

  // Incoming admission. [admission] guards [accepting] and [allowedHosts], and orders every
  // enqueue before a revocation; [accept] only tries it, so the engine never waits.
  private val admission = Mutex()
  private val incoming = Channel<PeerV2Dialer.Incoming>(INCOMING_CAPACITY,
    onUndeliveredElement = { it.close() })
  private var accepting = false
  private val allowedHosts = mutableMapOf<TrackerTopic, Set<String>>()
  /** Raised by every reset; connections and dial targets of older ones are closed. */
  private val generation = AtomicLong(0)
  // Whether the running transfer's pool has room for a peer that dials us; see [accept].
  private val room = AtomicBoolean(true)
  /** The running transfer's control queue, for [resetPeers]. */
  private val controls = AtomicReference<SendChannel<TorrentV2SessionLoop.Control>?>(null)

  override fun setDownloadRateLimit(bytesPerSecond: Long) = downloadRate.set(bytesPerSecond)

  /** This owner's upload bucket, applied after the engine's; zero means unlimited. */
  override fun setUploadRateLimit(bytesPerSecond: Long) = uploadRate.set(bytesPerSecond)

  /**
   * The runtime's upload policy changed: the running transfer chokes or unchokes peers within one
   * pass, and a seed finishes unless the policy still seeds. Never waits; when the transfer's
   * queue is full it reads the policy anyway on the pass that drains it.
   */
  fun uploadPolicyChanged() {
    controls.load()?.trySend(TorrentV2SessionLoop.Control.PolicyChanged)
  }

  override suspend fun resume() = lifecycle.withLock {
    check(!closed) { "Torrent session is closed" }
    currentCoroutineContext().ensureActive()
    if (job?.isActive == true && (mutableState.value == TorrentSessionState.CHECKING_FILES ||
        mutableState.value == TorrentSessionState.DOWNLOADING ||
        mutableState.value == TorrentSessionState.SEEDING)) return@withLock
    job?.join()
    checkNotNull(scope.coroutineContext[Job]).ensureActive()
    mutableProgress.value = 0
    mutableFailure.value = null
    mutableState.value = TorrentSessionState.CHECKING_FILES
    job = scope.launch {
      try {
        log.i { "Checking files for $label" }
        store.initialize()
        // Persisted bits and earlier live progress cannot authorize bytes changed while paused.
        store.recheck()
        updateProgress()
        log.i { "Checked files for $label: ${mutableProgress.value}/$totalBytes bytes verified" }
        if (!store.completed()) {
          admission.withLock { accepting = true }
          mutableState.value = TorrentSessionState.DOWNLOADING
          log.i {
            "Downloading $label with up to ${connectionLimit.value} peer(s), " +
              "upload=${runtime.uploadPolicy()}, privacy=$privacy"
          }
          transfer()
        } else if (runtime.uploadPolicy() == TorrentUploadPolicy.SEED_AFTER_COMPLETION) {
          // A complete owner joins its swarms only to seed.
          admission.withLock { accepting = true }
          startSeeding()
          transfer()
        }
        currentCoroutineContext().ensureActive()
        val seeded = mutableState.value == TorrentSessionState.SEEDING
        mutableState.value = TorrentSessionState.FINISHED
        log.i {
          if (seeded) "Torrent $label stopped seeding: upload policy changed"
          else "Torrent $label completed"
        }
      } catch (error: CancellationException) {
        if (!checkNotNull(currentCoroutineContext()[Job]).isActive) throw error
        log.w(error) { "Torrent $label stopped: ${error.describeWithoutUrls()}" }
        mutableFailure.value = error
        mutableState.value = TorrentSessionState.STOPPED
      } catch (error: Exception) {
        log.w(error) { "Torrent $label stopped: ${error.describeWithoutUrls()}" }
        mutableFailure.value = error
        mutableState.value = TorrentSessionState.STOPPED
      } finally {
        withContext(NonCancellable) { closeAdmission() }
      }
    }
  }

  /** Stops transferring and keeps the files. A closed session has nothing left to pause. */
  override suspend fun pause() = lifecycle.withLock {
    if (closed) return@withLock
    withContext(NonCancellable) {
      stop()
      mutableState.value = TorrentSessionState.PAUSED
    }
  }

  /** Restores [checkpoint] before the owner is exposed; resume still rechecks the payload. */
  suspend fun restore(checkpoint: TorrentV2Checkpoint?) = lifecycle.withLock {
    check(!closed && job == null) { "Restore precedes the first resume" }
    if (checkpoint != null) store.restore(checkpoint)
  }

  /**
   * The engine's route for this owner's wire tags: queues [connection], whose handshake is still
   * unread, for the transfer's responders. Runs under the engine mutex, so it never suspends.
   * False, and the engine closes the connection, while the owner is not transferring, while a
   * revocation holds the admission lock, when the queue is full, or when a restricted owner's
   * trackers never returned the peer's host.
   */
  fun accept(connection: TorrentConnection): Boolean {
    if (!admission.tryLock()) return false
    try {
      // A full pool refuses at once, before any handshake, as a v1 swarm does.
      if (!accepting || !room.load()) return false
      if (restricted && allowedHosts.values.none { connection.remote.host in it }) return false
      return incoming.trySend(PeerV2Dialer.Incoming(connection, generation.load())).isSuccess
    } finally { admission.unlock() }
  }

  /**
   * Records the hosts [topic]'s trackers returned; a restricted owner admits only those. A public
   * owner keeps no list, so a tracker answer never holds [admission] while a peer dials in.
   */
  suspend fun trackerPeers(topic: TrackerTopic, peers: List<PeerEndpoint>) {
    if (!restricted) return
    val hosts = peers.map { it.host }.toSet()
    admission.withLock { allowedHosts[topic] = hosts }
  }

  /**
   * Revokes every admitted host and starts a new generation, then drops the running transfer's
   * peers and candidates without restarting it, returning once those peers have closed (BEP 27).
   */
  suspend fun resetPeers() = resetPeers(controls.load())

  private suspend fun resetPeers(channel: SendChannel<TorrentV2SessionLoop.Control>?) {
    // Admission and enqueue share the lock: every old-host enqueue precedes this revocation.
    val next = admission.withLock {
      allowedHosts.clear()
      drainIncoming()
      (generation.load() + 1).also { generation.store(it) }
    }
    if (channel == null) return
    val done = CompletableDeferred<Unit>()
    try {
      channel.send(TorrentV2SessionLoop.Control.Reset(done, next))
    } catch (_: ClosedSendChannelException) {
      return
    }
    done.await()
  }

  /** Under [admission]. */
  private fun drainIncoming() {
    while (true) (incoming.tryReceive().getOrNull() ?: break).close()
  }

  private suspend fun closeAdmission() = admission.withLock {
    accepting = false
    allowedHosts.clear()
    drainIncoming()
  }

  private suspend fun stop() = withContext(NonCancellable) {
    closeAdmission()
    job?.cancelAndJoin()
    job = null
    closeAdmission()
    updateProgress()
  }

  private suspend fun updateProgress() { mutableProgress.value = store.progress().values.sum() }

  private fun startSeeding() {
    mutableState.value = TorrentSessionState.SEEDING
    log.i { "Seeding $label: upload=${TorrentUploadPolicy.SEED_AFTER_COMPLETION}" }
  }

  /** The transfer verified every selected piece: true keeps it running as a seed. */
  private suspend fun completed(): Boolean {
    updateProgress()
    if (mutableState.value == TorrentSessionState.SEEDING) return true
    if (runtime.uploadPolicy() != TorrentUploadPolicy.SEED_AFTER_COMPLETION) return false
    startSeeding()
    return true
  }

  /** Discovery's view of one transfer. */
  private inner class Sink(
    private val discovered: SendChannel<TorrentV2Discovered>,
    private val controls: SendChannel<TorrentV2SessionLoop.Control>,
  ) : TorrentV2DiscoverySink {
    override suspend fun offer(endpoint: PeerEndpoint, topic: PeerTopic, origin: PeerOrigin) {
      // Once its transfer has ended, the sink drops what it is offered. Stamped before it waits
      // for room, an endpoint queued across a reset is dropped rather than dialed (BEP 27).
      try {
        discovered.send(TorrentV2Discovered(endpoint, topic, origin,
          generation = generation.load()))
      } catch (_: ClosedSendChannelException) {
        // Closed with the transfer.
      } catch (_: CancellationException) {
        // A cancelled queue throws its cause; only the caller's own cancellation propagates.
        currentCoroutineContext().ensureActive()
      }
    }

    override fun tryOffer(endpoint: PeerEndpoint, topic: PeerTopic, origin: PeerOrigin): Boolean =
      discovered.trySend(TorrentV2Discovered(endpoint, topic, origin,
        generation = generation.load())).isSuccess

    override suspend fun trackerPeers(topic: TrackerTopic, peers: List<PeerEndpoint>) =
      this@TorrentV2DownloadSession.trackerPeers(topic, peers)

    override suspend fun reset() = resetPeers(controls)

    override fun uploadedBytes(): Long = store.uploadedBytes()

    override fun receivedBytes(): Long = store.receivedBytes()
  }

  private suspend fun transfer() = coroutineScope {
    val credits = MutableStateFlow(0)
    var charging: Deferred<Unit>? = null
    suspend fun admitted(bytes: Int): Boolean {
      charging?.takeIf { it.isCompleted }?.await()
      if (credits.value < bytes && charging?.isActive != true) {
        val missing = bytes - credits.value
        charging = async(start = CoroutineStart.UNDISPATCHED) {
          // Charge each requested block before it can arrive, including retries/corrupt payload.
          // A pending limiter must not block peer control traffic, progress, or checkpoints.
          throttle(missing)
          credits.update { it + missing }
        }
      }
      charging?.takeIf { it.isCompleted }?.await()
      return credits.value >= bytes
    }
    val discovered = Channel<TorrentV2Discovered>(DISCOVERED_CAPACITY)
    val loopControls = Channel<TorrentV2SessionLoop.Control>(CONTROL_CAPACITY)
    val dial = Channel<TorrentV2DialTarget>(DIAL_QUEUE)
    val failures = dialFailures(maxPeers)
    controls.store(loopControls)
    room.store(true)
    val discovery = launch {
      try { discover(Sink(discovered, loopControls)) } catch (error: Throwable) {
        discovered.close(error)
      } finally { discovered.close() }
    }
    // Limit changes reach the running loop; they never restart the pool or its workers.
    val limits = launch {
      connectionLimit.collect { loopControls.send(TorrentV2SessionLoop.Control.SetLimit(it)) }
    }
    try {
      PeerV2Pool.run(memory, maxPeers = maxPeers) { pool ->
        PeerV2Dialer.run(dial, memory, parallelism = DIAL_WORKERS,
          connect = { target ->
            // A reset revokes the old trackers' peers before they are dialed (BEP 27).
            check(target.generation == generation.load()) { "Peer target predates a reset" }
            PeerV2Connector.connect(network, target.endpoint, document, layout, peerId, buffers,
              memory, mode = target.mode, allowUpgrade = target.upgrade, extensions = true,
              generation = target.generation)
          },
          incoming = incoming,
          respondParallelism = RESPOND_WORKERS,
          respond = { peer ->
            // The pool filled while the peer waited: it is closed unanswered.
            if (!room.load()) null else {
              PeerV2Connector.respond(peer.connection, document, layout, peerId, buffers, memory,
                extensions = true, generation = peer.generation)
            }
          },
          failures = failures,
        ) { dialer ->
          TorrentV2CommitWorker.run(store) { worker ->
            // Proofs above the piece layer are cached per file, charged to the session state. A
            // piece read back to prove its blocks is upload work: both upload limits pay for it.
            val hashServer = TorrentV2HashServer(document, layout, memory,
              canRead = { runtime.uploadRate.canCharge(uploadRate) },
              chargeRead = { bytes -> runtime.uploadRate.charge(bytes, uploadRate) })
            // What peers ask of us, pieces and proofs alike, stays within the upload partition.
            TorrentV2ServeWorker.run(store, hashServer, runtime.uploadBuffers) { serve ->
              TorrentV2SessionLoop.download(layout, selected, store, pool, worker, buffers, memory,
                maxPeers = maxPeers, maxActive = activePieces(layout, buffers),
                connections = dialer.connections, onProgress = ::updateProgress,
                requestDelay = { bytes, admit ->
                  if (!admitted(bytes)) 50L else globalRate.requestDelay(bytes, downloadRate) {
                    admit().also { sent -> if (sent) credits.update { it - bytes } }
                  }
                },
                swarm = TorrentV2Swarm(document, runtime, restricted, waitForPeers, discovered,
                  dial, failures, loopControls, connectionLimit::value, generation::load,
                  serve = serve, sessionUploadRate = uploadRate, onCompleted = ::completed,
                  uploadedOverV1 = { v1Uploaded.addAndFetch(it.toLong()) },
                  onRoom = room::store))
            }
          }
        }
      }
    } finally {
      controls.compareAndSet(loopControls, null)
      withContext(NonCancellable) {
        try {
          charging?.cancelAndJoin()
          discovery.cancelAndJoin()
          limits.cancelAndJoin()
        } finally {
          // A reset sent after the loop returned has no peers left to wait for.
          loopControls.close()
          while (true) {
            val control = loopControls.tryReceive().getOrNull() ?: break
            if (control is TorrentV2SessionLoop.Control.Reset) control.done.complete(Unit)
          }
          discovered.cancel()
          dial.cancel()
          failures.cancel()
        }
      }
    }
  }

  /**
   * Stops for good: joins the transfer and every child, then closes the store, or deletes the
   * files this owner created when [deleteFiles]. The state ends [TorrentSessionState.STOPPED].
   * Idempotent; a failed deletion can be retried by closing again with [deleteFiles].
   */
  suspend fun close(deleteFiles: Boolean = false) = lifecycle.withLock {
    withContext(NonCancellable) {
      if (!closed) {
        closed = true
        try { stop() } finally {
          try { checkNotNull(scope.coroutineContext[Job]).cancelAndJoin() } finally {
            try {
              incoming.cancel()
              if (deleteFiles) {
                store.cleanup()
                filesDeleted = true
              } else store.close()
            } finally {
              lifecycleLease.close()
              mutableState.value = TorrentSessionState.STOPPED
            }
          }
        }
      } else if (deleteFiles && !filesDeleted) {
        store.cleanup()
        filesDeleted = true
      }
    }
  }

  companion object {
    private const val INCOMING_CAPACITY = 16
    private const val DISCOVERED_CAPACITY = 256
    private const val CONTROL_CAPACITY = 8
    /** Dial targets queued ahead of the dial workers. */
    const val DIAL_QUEUE = 4
    const val DIAL_WORKERS = 4
    private const val RESPOND_WORKERS = 4

    /**
     * The queue dial workers report failures to. The loop counts every target it queues until
     * its connection or failure comes back, and never has more than [maxPeers] out, so workers
     * report without ever waiting for room; a full queue makes them wait rather than drop one,
     * as a lost failure would leave its target counted as out for good.
     */
    fun dialFailures(maxPeers: Int): Channel<PeerV2Dialer.Failure<TorrentV2DialTarget>> =
      Channel(maxPeers + DIAL_QUEUE + DIAL_WORKERS)

    /**
     * Creates an owner without I/O: binds [store] to [document] and reserves its lifecycle state
     * from [TorrentV2Runtime.state]. Its jobs are children of [parent]. The caller admits the
     * document, layout, storage indexes and any checkpoint beforehand, restores, and finally
     * [close]s the owner, which releases the reservation.
     */
    fun open(
      parent: CoroutineScope,
      document: TorrentV2Document,
      layout: TorrentContentLayout,
      selected: Set<String>,
      store: TorrentV2PieceStore,
      runtime: TorrentV2Runtime,
      options: TorrentV2SessionOptions,
    ): TorrentV2DownloadSession {
      require(layout.infoHash == document.info.hash && runtime.peerId.size == 20)
      require(options.maxPeers in 1..500 &&
        options.initialConnections in 1..options.maxPeers)
      store.requireBinding(document.identity, selected, layout)
      val lease = checkNotNull(runtime.state.reserve(options.maxPeers * 512 + 4096)) {
        "Session lifecycle state budget exhausted"
      }
      val owner = SupervisorJob(parent.coroutineContext[Job])
      return TorrentV2DownloadSession(CoroutineScope(parent.coroutineContext + owner), document,
        layout, selected.toSet(), store, runtime, options, lease)
    }

    /**
     * Scoped owner for tests and callers that need no engine registration: [open], restore
     * [checkpoint], run [body], then [close]. The store is owned until every command, discovery
     * job, peer and provider write has joined. [discover] sends tracker peers to dial; once it
     * returns and no peer or candidate is left, the transfer fails.
     */
    suspend fun <T> run(
      document: TorrentV2Document,
      layout: TorrentContentLayout,
      selected: Set<String>,
      store: TorrentV2PieceStore,
      network: TorrentNetwork,
      peerId: ByteString,
      buffers: TorrentBufferBudget,
      state: TorrentBufferBudget,
      maxPeers: Int = 100,
      initialConnections: Int = maxPeers,
      globalRate: TorrentRateLimiter = TorrentRateLimiter(),
      throttle: suspend (Int) -> Unit = {},
      checkpoint: TorrentV2Checkpoint? = null,
      discover: suspend (SendChannel<PeerEndpoint>) -> Unit,
      body: suspend (TorrentV2DownloadSession) -> T,
    ): T = coroutineScope {
      val session = open(this, document, layout, selected, store,
        TorrentV2Runtime(network, peerId, buffers, state, globalRate),
        TorrentV2SessionOptions(maxPeers, initialConnections, throttle = throttle,
          discovery = { sink -> sink.relay(discover = discover) }))
      try {
        // Restore before exposing the owner. Resume always rechecks the adopted payloads.
        session.restore(checkpoint)
        body(session)
      } finally {
        withContext(NonCancellable) { session.close() }
      }
    }
  }
}

/**
 * Pieces assembled at once across the swarm. Piece buffers come from the transfer budget, which
 * still gates each start, so this only bounds per-piece scheduler state.
 */
internal fun activePieces(layout: TorrentContentLayout, buffers: TorrentBufferBudget): Int =
  (buffers.capacity / layout.pieceLength).coerceIn(2L, 64L).toInt()
