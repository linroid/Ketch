package com.linroid.ketch.torrent.v1

import com.linroid.ketch.api.log.KetchLogger
import com.linroid.ketch.core.engine.ConnectionReporter
import com.linroid.ketch.torrent.TorrentClient
import com.linroid.ketch.torrent.TorrentDispatchers
import com.linroid.ketch.torrent.TorrentUploadPolicy
import com.linroid.ketch.torrent.api.TorrentStall
import com.linroid.ketch.torrent.describeWithoutUrls
import com.linroid.ketch.torrent.engine.StallRecovery
import com.linroid.ketch.torrent.engine.StallSignals
import com.linroid.ketch.torrent.engine.TorrentBufferBudget
import com.linroid.ketch.torrent.engine.TorrentRateLimiter
import com.linroid.ketch.torrent.engine.TorrentStallWatchdog
import com.linroid.ketch.torrent.engine.describe
import com.linroid.ketch.torrent.logHash
import com.linroid.ketch.torrent.magnet.TorrentMetadataExchange
import com.linroid.ketch.torrent.metainfo.Bencode
import com.linroid.ketch.torrent.monotonicClock
import com.linroid.ketch.torrent.net.PeerEndpoint
import com.linroid.ketch.torrent.net.TorrentConnection
import com.linroid.ketch.torrent.net.TorrentHttp
import com.linroid.ketch.torrent.net.TorrentNetwork
import com.linroid.ketch.torrent.net.connectPeer
import com.linroid.ketch.torrent.net.numericAddress
import com.linroid.ketch.torrent.net.publicTorrentAddress
import com.linroid.ketch.torrent.peer.FAST_REJECT_GRACE_MS
import com.linroid.ketch.torrent.peer.HOLEPUNCH_EXTENSION
import com.linroid.ketch.torrent.peer.HolepunchMessage
import com.linroid.ketch.torrent.peer.HolepunchType
import com.linroid.ketch.torrent.peer.PEX_FLAG_HOLEPUNCH
import com.linroid.ketch.torrent.peer.PEX_FLAG_UTP
import com.linroid.ketch.torrent.peer.PeerAttempt
import com.linroid.ketch.torrent.peer.PeerDiagnostics
import com.linroid.ketch.torrent.peer.PeerExchange
import com.linroid.ketch.torrent.peer.PeerExtensions
import com.linroid.ketch.torrent.peer.PeerFlagBook
import com.linroid.ketch.torrent.peer.PeerHandshake
import com.linroid.ketch.torrent.peer.PeerIdentity
import com.linroid.ketch.torrent.peer.PeerIdentityHandshake
import com.linroid.ketch.torrent.peer.PeerMessage
import com.linroid.ketch.torrent.peer.PeerOrigin
import com.linroid.ketch.torrent.peer.PeerOutcomeReport
import com.linroid.ketch.torrent.peer.PeerProtocolState
import com.linroid.ketch.torrent.peer.PeerRedial
import com.linroid.ketch.torrent.peer.PeerServeCredit
import com.linroid.ketch.torrent.peer.PeerStage
import com.linroid.ketch.torrent.peer.PeerTraffic
import com.linroid.ketch.torrent.peer.PeerWire
import com.linroid.ketch.torrent.peer.PexIntroductions
import com.linroid.ketch.torrent.peer.PexUpdate
import com.linroid.ketch.torrent.peer.SWARM_SUMMARY_INTERVAL_MS
import com.linroid.ketch.torrent.peer.TorrentChoker
import com.linroid.ketch.torrent.peer.TorrentPeerDirectory
import com.linroid.ketch.torrent.peer.UPLOAD_SLOTS
import com.linroid.ketch.torrent.peer.allowedFastOffer
import com.linroid.ketch.torrent.peer.isFastExtension
import com.linroid.ketch.torrent.peer.keepsOutgoing
import com.linroid.ketch.torrent.peer.pexFlags
import com.linroid.ketch.torrent.peer.pieceBitfield
import com.linroid.ketch.torrent.peer.servesMetadata
import com.linroid.ketch.torrent.storage.CommitOutcome
import com.linroid.ketch.torrent.storage.PieceRevokedException
import com.linroid.ketch.torrent.storage.TorrentPieceStore
import com.linroid.ketch.torrent.storage.TorrentStorageException
import com.linroid.ketch.torrent.webseed.SwarmWebSeedPieces
import com.linroid.ketch.torrent.webseed.WEB_SEED_RETRY_MS
import com.linroid.ketch.torrent.webseed.WebSeedStats
import com.linroid.ketch.torrent.webseed.WebSeedWorker
import kotlin.concurrent.atomics.AtomicBoolean
import kotlin.concurrent.atomics.AtomicLong
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.time.TimeSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.channels.SendChannel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.onTimeout
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okio.ByteString
import okio.ByteString.Companion.toByteString
import okio.IOException

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
  private val peerId: ByteArray = TorrentClient.peerId(),
  private val connections: () -> Int = { 20 },
  /**
   * The part of [budget] pieces read back for peers may hold, every session's together, so
   * uploads never take the frames and pieces downloads need.
   */
  private val uploadBudget: TorrentBufferBudget = budget,
  /**
   * What each connected peer's wire frames and availability may hold. The engine keeps it apart
   * from [budget], so a torrent with many peers still has piece buffers to fetch with.
   */
  private val peerBudget: TorrentBufferBudget = budget,
  /** Read live: peers are choked or unchoked within a tick, and a seed stops leaving SEED. */
  private val uploadPolicy: () -> TorrentUploadPolicy = { TorrentUploadPolicy.DISABLED },
  private val downloadPayload: suspend (Int) -> Unit = {},
  /** Engine-wide upload bucket, applied before [sessionUploadRate]. */
  private val uploadRate: TorrentRateLimiter = TorrentRateLimiter(),
  /** This torrent's own upload bucket. */
  private val sessionUploadRate: TorrentRateLimiter = TorrentRateLimiter(),
  /** Payload the limits admitted for a peer, counted just before it is written. */
  private val onUploaded: (Int) -> Unit = {},
  /**
   * Payload [downloadPayload] counted that is thrown away rather than verified: blocks nobody
   * asked for or another copy of which came first, pieces that fail their hash check, and partial
   * pieces given up, from peers and web seeds alike.
   */
  private val onDiscarded: (Long) -> Unit = {},
  private val onProgress: suspend (Long) -> Unit = {},
  private val onCompleted: suspend () -> Unit = {},
  /** A selection change made a completed swarm incomplete; it downloads again, then completes. */
  private val onIncomplete: suspend () -> Unit = {},
  /** Test hook: lets peer exchange carry loopback and private addresses. */
  private val allowLocalPeers: Boolean = false,
  trackerOnly: Boolean = false,
  /** Our listen port as a peer at that address reaches it (BEP 10 `p`); 0 leaves it out. */
  private val listenPortFor: (PeerEndpoint) -> Int = { 0 },
  /**
   * Whether a peer is at our own public address, read as each is dialed: peers trackers and the
   * DHT named before the engine knew its address are often its own earlier listen ports.
   */
  private val isOwnAddress: (PeerEndpoint) -> Boolean = { false },
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
  /**
   * Whether uTP is on, read live: hole punching (BEP 55) dials over uTP, so it is offered and
   * acted on only while it is.
   */
  private val utpEnabled: () -> Boolean = { false },
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
  /** Why the download stalls, published about once a second; null while it progresses. */
  private val stall: MutableStateFlow<TorrentStall?>? = null,
  /** Announces to the torrent's trackers now, as a stalled swarm asks to recover. */
  private val reannounce: suspend () -> Unit = {},
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
    /** When the worker started, on the swarm's clock. */
    val startedAt: Long,
    /** Closed because both sides were seeds. */
    val redundant: Boolean = false,
    /** Where we dialed the peer of an incoming connection kept over that dial. */
    val alias: PeerEndpoint? = null,
    /** A connection to the same peer that this one replaced brought payload. */
    val inherited: Boolean = false,
  )

  private val log = KetchLogger("TorrentSwarm")
  private val trackerRestricted = store.metadata.isPrivate || trackerOnly
  private val chokerMutex = Mutex()
  // Guarded by chokerMutex; keyed by worker ID.
  private val choker = TorrentChoker<Int>(nowMs)
  private val connectedMutex = Mutex()
  // Guarded by connectedMutex. Each worker publishes only its own contact.
  private val connected = mutableMapOf<Int, PexContact>()
  // Connected peers that unchoke us, guarded like [connected].
  private val unchoking = mutableSetOf<Int>()
  // The one connection kept to each peer, guarded like [connected].
  private val identities = mutableMapOf<PeerIdentity, PeerWorker>()
  // Payload received from peers and web seeds, and time spent waiting on speed limits for it.
  private val payloadReceived = AtomicLong(0)
  private val throttledMs = AtomicLong(0)
  private val uploaded = AtomicLong(0)

  /** Hole punching (BEP 55) runs for public torrents while uTP is on. */
  private fun holepunchOn(): Boolean = !trackerRestricted && utpEnabled()

  private fun uploadAllowed(policy: TorrentUploadPolicy, complete: Boolean): Boolean =
    policy == TorrentUploadPolicy.SEED_AFTER_COMPLETION ||
      (policy == TorrentUploadPolicy.WHILE_DOWNLOADING && !complete)

  /**
   * A closed peer stream fails once no candidate is left to dial: while it downloads, the swarm
   * dials endpoints again with backoff a bounded number of times ([PeerRedial]). Each of
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
      store.verifiedPieces(), store::pieceSize, budget, nowMs, onDiscarded)
    // Any piece may become wanted, and the first is the longest.
    val largestPiece = if (store.pieceCount == 0) 0 else store.pieceSize(0)
    val peerOverhead = PEER_WIRE_BYTES + store.pieceCount * 4
    val sharedOverhead = if (peerBudget === budget) peerOverhead else 0
    require(budget.capacity >= largestPiece + sharedOverhead &&
      peerBudget.capacity >= peerOverhead) {
      "Torrent buffer limit cannot hold a piece and peer protocol state"
    }
    // Peers sharing the piece budget, as in tests, are admitted only while all of it but this
    // swarm's own pieces, which its peers share and finish, leaves room for one more piece, so
    // peers can never starve piece buffers entirely.
    fun reservePeer(): TorrentBufferBudget.Lease? {
      if (peerBudget === budget) {
        val others = budget.allocated - scheduler.heldBytes
        if (budget.capacity - others - peerOverhead < largestPiece) return null
      }
      return peerBudget.reserve(peerOverhead)
    }
    val active = mutableMapOf<PeerEndpoint, Job>()
    // Every endpoint the swarm knows, queued or not, with its dials since it last brought payload
    // (PeerRedial); at most MAX_CANDIDATES. Those not queued are given up on until a source names
    // them again GIVEN_UP_MS after their last dial, or a stalled swarm revives them; they are
    // never forgotten for time alone, as a source may name a peer only once (a magnet's, one
    // that sent the metadata), but the longest known of them leave to make room for new ones.
    val attempts = linkedMapOf<PeerEndpoint, Int>()
    val attemptedAt = mutableMapOf<PeerEndpoint, Long>()
    // Endpoints that brought payload since they were last given up, dialed again for longer.
    val delivered = mutableSetOf<PeerEndpoint>()
    // When a source naming a peer again last brought its dial forward.
    val broughtForwardAt = mutableMapOf<PeerEndpoint, Long>()
    // Endpoints to dial, each one of [attempts].
    val pending = ArrayDeque<PeerEndpoint>()
    val results = Channel<PeerResult>(512)
    val progressEvents = Channel<Unit>(Channel.CONFLATED)
    val pexEvents = Channel<Pair<PeerEndpoint, PexUpdate>>(64)
    // Peers a relay told us to dial now over uTP (BEP 55), as they dial us.
    val punches = Channel<PeerEndpoint>(64)
    val holepunch = SwarmHolepunch(nowMs)
    val pexDirectory = TorrentPeerDirectory(trackerRestricted)
    // Flags peers advertised for endpoints; handed to a worker when it dials one.
    val peerFlags = PeerFlagBook()
    val retryAt = mutableMapOf<PeerEndpoint, Long>()
    // Known peers that broke the protocol or sent a corrupt piece, which are never retried.
    val faulty = mutableSetOf<PeerEndpoint>()
    val now = nowMs
    var discoveryClosed = false
    var complete = false
    // Forgets everything about [endpoint].
    fun forget(endpoint: PeerEndpoint) {
      attempts.remove(endpoint)
      attemptedAt.remove(endpoint)
      retryAt.remove(endpoint)
      faulty.remove(endpoint)
      delivered.remove(endpoint)
      broughtForwardAt.remove(endpoint)
    }
    // Queues [endpoint], which a source named: one found for the first time, or one given up on
    // GIVEN_UP_MS or longer after its last dial, which starts over. When the swarm knows enough,
    // the longest known endpoint given up on makes room.
    fun discover(endpoint: PeerEndpoint): Boolean {
      if (endpoint in attempts) {
        if (endpoint in active || endpoint in pending) return false
        val dialedAt = attemptedAt[endpoint] ?: return false
        if (now() - dialedAt < GIVEN_UP_MS) return false
        forget(endpoint)
      } else if (attempts.size >= MAX_CANDIDATES) {
        val oldest = attempts.keys.firstOrNull { it !in active && it !in pending } ?: return false
        forget(oldest)
      }
      attempts[endpoint] = 0
      pending.addLast(endpoint)
      return true
    }
    // Discovery named [endpoint] again: a peer we downloaded from that waits to be dialed again
    // may be back.
    fun foundAgain(endpoint: PeerEndpoint) {
      if (complete || endpoint !in delivered) return
      val wait = retryAt[endpoint] ?: return
      val dialedAt = attemptedAt[endpoint] ?: return
      val due = PeerRedial.foundAgainAt(now(), dialedAt, wait, broughtForwardAt[endpoint])
        ?: return
      retryAt[endpoint] = due
      broughtForwardAt[endpoint] = now()
    }
    // A connection to the peer that listens at [endpoint], dialed there or not, ended while we
    // download: it is dialed again after its delay, or given up on. One that brought payload, over
    // it or a connection it replaced, or that [lasted], counts as its first dial.
    fun redial(endpoint: PeerEndpoint, broughtPayload: Boolean, lasted: Boolean) {
      if (endpoint in faulty || endpoint !in attempts && attempts.size >= MAX_CANDIDATES) return
      if (broughtPayload) delivered += endpoint
      val dials = if (broughtPayload || lasted) 1 else attempts[endpoint] ?: 1
      attempts[endpoint] = dials
      // Never dialed, it still starts over once given up long enough and named again.
      attemptedAt.getOrPut(endpoint) { now() }
      val delay = PeerRedial.delayMs(dials, endpoint in delivered)
      if (delay == null) {
        // Its dials are spent: found again later, it starts over as any endpoint does.
        delivered -= endpoint
        return
      }
      retryAt[endpoint] = now() + delay
      if (endpoint !in pending) pending.addLast(endpoint)
    }
    // Hashes of stored pieces a selection made wanted; each wakes the loop when done.
    val rechecked = Channel<Unit>(Channel.CONFLATED)
    val verifiers = mutableListOf<Job>()
    // Notices a download that stopped making progress, and says why ([stall]).
    val watchdog = TorrentStallWatchdog(nowMs)
    suspend fun applySelection() {
      watchdog.progressed()
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
    // Negative IDs never collide with peer workers'.
    val webSeedPieces = webSeedUrls.mapIndexed { number, url ->
      SwarmWebSeedPieces(-1 - number, url, store, scheduler,
        onVerified = { progressEvents.trySend(Unit) })
    }
    // Payload from web seeds counts and is throttled as peers' does.
    val webSeedPayload: suspend (Int) -> Unit = { size ->
      val at = nowMs()
      downloadPayload(size)
      throttledMs.addAndFetch(nowMs() - at)
      payloadReceived.addAndFetch(size.toLong())
    }
    // One worker per seed for the whole transfer: started again, a seed keeps what it learned.
    val webSeedWorkers = webSeedUrls.mapIndexed { number, url ->
      WebSeedWorker(url, webSeedPieces[number], checkNotNull(webSeedHttp), webSeedStats,
        logLabel, reporter, webSeedRetryMs, downloadPayload = webSeedPayload,
        discardPayload = onDiscarded)
    }
    fun launchWebSeed(number: Int): Job = launch(TorrentDispatchers.engine) {
      try {
        // A seed started again joins with every piece, those it could not serve included.
        webSeedPieces[number].join()
        webSeedWorkers[number].run()
      } catch (e: TorrentStorageException) {
        webSeedFailures.trySend(e)
      }
    }
    val webSeeds = mutableListOf<Job>()
    // Web seeds start once pieces are wanted: a swarm that only seeds never needs them. Every
    // seed joins before any claims a run, so the first runs leave the other seeds their share.
    suspend fun startWebSeeds() {
      if (webSeeds.isNotEmpty() || webSeedUrls.isEmpty()) return
      webSeedPieces.forEach { it.join() }
      webSeedUrls.indices.mapTo(webSeeds) { launchWebSeed(it) }
      log.i { "Swarm $logLabel: downloading from ${webSeeds.size} web seed(s) too" }
    }
    fun acceptPex(source: PeerEndpoint, update: PexUpdate) {
      peerFlags.record(update.flags)
      // A peer that punches holes speaks uTP (BEP 55), and a connection to it over uTP shows it
      // our uTP mapping, the port it must hand out when it introduces us.
      val utpPeers = PEX_FLAG_UTP or PEX_FLAG_HOLEPUNCH
      onUtpPeers(update.flags.filterValues { it and utpPeers != 0 }.keys)
      for (endpoint in update.added) foundAgain(endpoint)
      pexDirectory.update(PeerOrigin.PEX, "${source.host}:${source.port}", update.added,
        update.dropped)
      for (endpoint in pexDirectory.candidates()) discover(endpoint)
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
          "discovery=${if (discoveryClosed) "closed" else "open"}; ${diagnostics.summary()}; " +
          holepunch.summary() +
          (if (webSeeds.isEmpty()) "" else
            "; ${webSeedStats.summary(webSeeds.count { it.isActive }, webSeeds.size)}") +
          (stall?.value?.let { "; stall: ${it.describe()}" } ?: "")
      }
      discovered = 0
      connectFailures = 0
      corruptPieces = 0
    }
    var lastStallCheck = now()
    // Peers given up on, or waiting to be retried, are dialed again at once. Each keeps its count
    // of dials, so one that fails again waits, or is given up, as it would have been.
    fun revivePeers() {
      var revived = 0
      val queued = pending.toHashSet()
      for (endpoint in attempts.keys) {
        if (endpoint in active || endpoint in faulty) continue
        if (endpoint !in queued) pending.addLast(endpoint)
        else if (retryAt.remove(endpoint) == null) continue
        revived++
      }
      if (revived > 0) log.d { "Swarm $logLabel: dialing $revived peer(s) it gave up on again" }
    }
    suspend fun checkStall() {
      val published = stall ?: return
      val (peers, unchoked) = connectedMutex.withLock { connected.size to unchoking.size }
      val running = webSeeds.count { it.isActive }
      // With nothing connected, only the pieces every web seed found it could not serve are
      // known to be missing: the rest wait for peers, or for a seed started again.
      val health = if (peers + running > 0) scheduler.health(MAX_REPORTED_MISSING_PIECES) else {
        webSeedPieces.map { it.unservable }.reduceOrNull { refused, seed -> refused intersect seed }
          ?.takeIf { it.isNotEmpty() }
          ?.let { scheduler.health(MAX_REPORTED_MISSING_PIECES, among = it) }
      }
      val files = if (health == null || health.missing.isEmpty()) emptyList() else {
        store.filesHolding(health.missing, TorrentStall.MAX_MISSING_FILES).map { it.toString() }
      }
      // In this order, so no web seed byte looks kept before it is (see StallSignals).
      val payload = payloadReceived.load()
      val unsettled = webSeedStats.unsettled.load()
      val wasted = webSeedStats.wasted.load()
      val due = watchdog.check(StallSignals(
        downloading = !complete, peers = peers, unchokingPeers = unchoked,
        missingPieces = health?.missingCount ?: 0, missingFileIds = files,
        lateBlocks = health?.lateBlocks ?: 0, webSeeds = running,
        droppedWebSeeds = webSeeds.size - running, payloadBytes = payload,
        unsettledBytes = unsettled, wastedBytes = wasted, throttledMs = throttledMs.load(),
        pieceLength = store.metadata.pieceLength,
      ))
      val current = watchdog.stall
      val previous = published.value
      if (current?.reason != previous?.reason) {
        if (current != null) log.i { "Swarm $logLabel stalled: ${current.describe()}" }
        // A swarm that completed is no longer stalled, without making progress again.
        else if (!complete) log.i { "Swarm $logLabel makes progress again" }
      }
      published.value = current
      for (recovery in due) {
        log.i { "Swarm $logLabel recovers from a stall: ${recovery.name.lowercase()}" }
        when (recovery) {
          StallRecovery.REANNOUNCE -> launch {
            try { reannounce() } catch (e: CancellationException) { throw e } catch (e: Exception) {
              log.d { "Swarm $logLabel: reannounce failed: ${e.describeWithoutUrls()}" }
            }
          }
          StallRecovery.REVIVE_PEERS -> revivePeers()
          StallRecovery.RELAUNCH_WEB_SEEDS -> for (number in webSeeds.indices) {
            if (!webSeeds[number].isActive) webSeeds[number] = launchWebSeed(number)
          }
        }
      }
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
        val dials = attempts[endpoint] ?: 0
        attempts[endpoint] = dials + 1
        retryAt.remove(endpoint)
        // A revive's dial of an endpoint given up on leaves when that was (PeerRedial.spent).
        if (!PeerRedial.spent(dials, endpoint in delivered)) attemptedAt[endpoint] = now()
      }
      val flags = if (incoming) 0 else peerFlags[endpoint]
      val attempt = PeerAttempt(incoming, PeerIdentityHandshake.Mode.V1)
      val startedAt = now()
      active[endpoint] = launch(TorrentDispatchers.engine, start = CoroutineStart.ATOMIC) {
        var failure: Throwable? = null
        var end = PeerEnd(listen = if (incoming) null else endpoint)
        try {
          peer(id, endpoint, flags, attempt, scheduler, progressEvents, pexEvents, holepunch,
            punches, connection) { end = it }
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
            connectedMutex.withLock {
              connected.remove(id)
              unchoking.remove(id)
            }
            chokerMutex.withLock { choker.remove(id) }
          }
          connection?.close()
          overhead.close()
          results.trySend(PeerResult(endpoint, failure, incoming, end.listen, attempt, flags,
            startedAt, end.redundant, end.alias, end.inherited))
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
        if (!complete) startWebSeeds()
        val policy = uploadPolicy()
        // Workers apply what changed on their next pass; this loop ticks at least every 100 ms.
        chokerMutex.withLock { choker.update(uploadAllowed(policy, complete), seeding = complete) }
        if (now() - lastSummary >= SWARM_SUMMARY_INTERVAL_MS) {
          lastSummary = now()
          logSummary()
        }
        if (now() - lastStallCheck >= STALL_CHECK_MS) {
          lastStallCheck = now()
          checkStall()
        }
        if (complete && policy != TorrentUploadPolicy.SEED_AFTER_COMPLETION) break
        val limit = connections().coerceIn(1, 512)
        active.entries.drop(limit).forEach { it.value.cancel() }
        var candidates = pending.size
        while (active.size < limit && pending.isNotEmpty() && candidates-- > 0) {
          val endpoint = pending.removeFirst()
          if (endpoint in active) {
            // Dialed once its connection, or an incoming one from that port, ends.
            pending.addLast(endpoint)
            continue
          }
          if (isOwnAddress(endpoint)) {
            // Known, as given up on, so its discovery does not queue it again.
            log.v { "Peer $endpoint for $logLabel left out: our own address" }
            attemptedAt[endpoint] = now()
            continue
          }
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
            delivered.clear()
            broughtForwardAt.clear()
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
            else if (discover(endpoint)) discovered++
            else foundAgain(endpoint)
          }
          results.onReceive { result ->
            val endpoint = result.endpoint
            val failure = result.failure
            active.remove(endpoint)
            diagnostics.record(endpoint, result.attempt, failure, result.incoming, result.flags)
            if (failure is TorrentStorageException) throw failure
            val broughtPayload = result.attempt.payload > 0 || result.inherited
            val lasted = now() - result.startedAt >= PeerRedial.LASTING_MS
            if (failure != null && failure !is DuplicatePeerException) {
              connectFailures++
              if (failure is CorruptPieceException) corruptPieces++
              log.v { "Peer $endpoint for $logLabel closed: ${failure.describeWithoutUrls()}" }
              // Not reached at all: the peer that introduced it may punch a way through.
              if (!result.incoming && result.attempt.stage == PeerStage.DIALING &&
                holepunchOn() && !complete && holepunch.rendezvous(endpoint)) {
                log.v { "Peer $endpoint for $logLabel: asked its introducer to punch through" }
              }
            }
            val alias = result.alias
            if (failure is DuplicatePeerException) {
              // The connection kept to the peer dials it again once it closes, unless it closed
              // first. Until then, or until forgotten 5 minutes from now, it is given up on.
              if (!result.incoming && endpoint !in pending) attemptedAt[endpoint] = now()
              log.v { "Peer $endpoint for $logLabel is connected over another connection" }
            } else if (result.incoming && alias != null) {
              // It listens where we dialed it too, and was kept over that dial.
              if (failure is IllegalArgumentException) {
                if (alias in attempts) faulty += alias
              } else if (!complete) {
                redial(alias, broughtPayload, lasted)
              }
            } else if (result.incoming) {
              // Never redial a source port: a public torrent dials where the peer listens, unless
              // both of us were seeds.
              val listen = result.listen
              if (listen != null && !trackerRestricted && !result.redundant) {
                if (broughtPayload && !complete && failure !is IllegalArgumentException) {
                  redial(listen, broughtPayload = true, lasted)
                } else if (listen !in active) {
                  discover(listen)
                }
              }
            } else if (failure is IllegalArgumentException) {
              faulty += endpoint
            } else if (!complete) {
              redial(endpoint, broughtPayload, lasted)
            }
          }
          pexEvents.onReceive { (source, update) -> acceptPex(source, update) }
          punches.onReceive { endpoint ->
            // Dialed at once and over uTP first, while the peer dials us; a seed answers too, as
            // that is how leechers behind NATs reach it. Bounded like every other candidate.
            if (endpoint !in active &&
              (endpoint in attempts || attempts.size < MAX_CANDIDATES)) {
              attempts.getOrPut(endpoint) { 0 }
              onUtpPeers(listOf(endpoint))
              retryAt.remove(endpoint)
              pending.remove(endpoint)
              pending.addFirst(endpoint)
            }
          }
          webSeedFailures.onReceive { throw it }
          progressEvents.onReceive {
            watchdog.progressed()
            onProgress(store.progress().sum())
          }
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
      withContext(NonCancellable) { scheduler.close() }
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

  /** Runs one peer; [onEnd] learns how it ended ([PeerEnd]), also when it fails. */
  private suspend fun peer(
    id: Int,
    endpoint: PeerEndpoint,
    receivedFlags: Int,
    attempt: PeerAttempt,
    scheduler: TorrentPieceScheduler,
    progressEvents: SendChannel<Unit>,
    pexEvents: SendChannel<Pair<PeerEndpoint, PexUpdate>>,
    holepunch: SwarmHolepunch,
    punches: SendChannel<PeerEndpoint>,
    accepted: TorrentConnection?,
    onEnd: (PeerEnd) -> Unit,
  ) {
    val connection = accepted ?: network.connectPeer(endpoint, store.metadata.infoHash.toBytes())
    attempt.stage = PeerStage.HANDSHAKING
    attempt.encrypted = connection.encrypted
    attempt.utp = connection.utp
    try {
      val worker = PeerWorker(id, endpoint, connection, accepted != null, receivedFlags, attempt,
        scheduler, progressEvents, pexEvents, holepunch, punches)
      try { worker.run() } finally {
        onEnd(PeerEnd(worker.listen, worker.redundant, worker.alias, worker.inherited))
      }
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
    private val holepunch: SwarmHolepunch,
    private val punches: SendChannel<PeerEndpoint>,
  ) {
    /** Where the peer accepts connections: the dialed endpoint, or `p` of an incoming peer. */
    var listen: PeerEndpoint? = if (incoming) null else endpoint
      private set
    private val uploadCache = TorrentUploadCache(store, uploadBudget)
    /** Whole pieces read back for this peer, beyond what it takes of them. */
    private val serveCredit =
      PeerServeCredit(PeerServeCredit.capacityFor(store.metadata.pieceLength), nowMs)
    /** Requests of the peer we unchoked, oldest first; at most what we advertise (`reqq`). */
    private val uploads = LinkedHashSet<PeerMessage.Request>()
    /** When the upload buckets admit the next block, if they held the last one back. */
    private var uploadRetryAt = 0L
    private val wire = PeerWire(connection, store.metadata)
    private val exchange = PeerExchange()
    /** Hosts the peer introduced by peer exchange, bounded over this connection. */
    private val introductions = PexIntroductions()
    private val extensions = PeerExtensions()
    /** Holepunch messages for this peer from the swarm (BEP 55), sent on the next pass. */
    private val holepunchOut = Channel<HolepunchMessage>(16)
    /** When this peer's current minute of CONNECTs began, and how many came in it. */
    private var connectWindow = nowMs()
    private var connectsInWindow = 0
    /** Created once the handshake tells whether the Fast extension is negotiated. */
    private lateinit var state: PeerProtocolState
    private var extensionsNegotiated = false
    /** Both sides set the Fast extension bit (BEP 6). */
    private var fast = false
    /** Pieces this peer may download while we choke it (BEP 6), offered only while we upload. */
    private var allowedFast: Set<Int> = emptySet()
    /** Pieces the peer rejected though it has them; fetched elsewhere until it unchokes us anew. */
    private val refused = mutableSetOf<Int>()
    /** Rejects of each piece's blocks while unchoked; as many as the piece has refuse it. */
    private val rejects = mutableMapOf<Int, Int>()
    /** Requests to keep outstanding: about [QUEUE_TIME_MS] of the peer's rate ([measureRate]). */
    private var depth = INITIAL_PIPELINE
    /** Until the first rate sample, each block received adds a request: the depth doubles a trip. */
    private var slowStart = true
    /** Payload since [rateSince], and the peer's smoothed rate in bytes a second. */
    private var rateBytes = 0L
    private var rateSince = nowMs()
    private var rate = 0.0
    /** When the peer last choked us, on [nowMs]. */
    private var chokedAt = 0L
    /** We unchoked the peer: the choker gave it a slot and we told it so. */
    private var uploadSlot = false
    /** The peer's interest as the choker last heard it. */
    private var reportedInterest = false
    /** Whether we told the peer we are interested (BEP 3); a connection starts without. */
    private var interesting = false
    /** Both sides have every piece: nothing will ever flow, so the worker ends. */
    var redundant = false
      private set
    private var metadataServed = 0
    private var metadataWindow = TimeSource.Monotonic.markNow()
    private var advertised = BooleanArray(0)
    /** How far this peer has heard of the pieces we verified (the scheduler's log). */
    private var haveCursor = 0
    /** When the peer last sent a wanted block, on [nowMs]; a selection change restarts it. */
    private var lastUsefulPayload = nowMs()
    private var seenWantVersion = scheduler.wantVersion
    /** The store was complete at the last pass: a seed judges no peer by its downloads. */
    private var sawComplete = false
    private var lastBlock = TimeSource.Monotonic.markNow()
    private var lastWrite = TimeSource.Monotonic.markNow()
    /** This connection among the task's live connections, once its handshake succeeded. */
    private var traffic: PeerTraffic? = null
    /** The peer this connection reaches, once it is the connection kept to it. */
    private var identity: PeerIdentity? = null
    /** Another connection to the same peer, one both ends keep, replaced this one. */
    private val displaced = AtomicBoolean(false)
    /** The peer sent payload over this connection; read by one that replaces it. */
    private val broughtPayload = AtomicBoolean(false)
    /**
     * Where we dialed the peer of this incoming connection, which it was kept over; written under
     * [connectedMutex] while this is the connection kept, read once it ended.
     */
    var alias: PeerEndpoint? = null
      private set
    /** A connection to the same peer that this one replaced brought payload. */
    var inherited = false
      private set

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
          while (isActive && !redundant) {
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
                wire.sendAll(state.requests.map {
                  PeerMessage.Cancel(it.index, it.begin, it.length)
                })
              }
            }
            messages.cancel()
            consumed.cancel()
          }
        }
      } finally {
        withContext(NonCancellable) {
          holepunch.leave(id)
          release()
        }
        holepunchOut.cancel()
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
      claim(handshake.peerId.toByteString())
      fast = handshake.fast
      state = PeerProtocolState(store.pieceCount, maxPending = MAX_PIPELINE, fast = fast)
      attempt.stage = PeerStage.CONNECTED
      traffic = PeerTraffic.open(reporter, endpoint, incoming, v1 = true)
      chokerMutex.withLock { choker.add(id) }
      publishContact()
      holepunch.join(id, connection.remote, connection.utp, holepunchOut)
      extensionsNegotiated = handshake.extensions
      metadataWindow = TimeSource.Monotonic.markNow()
      if (extensionsNegotiated) {
        wire.send(PeerExtensions.handshake(
          utMetadata = servesMetadata(store.metadata.isPrivate, uploadPolicy()),
          metadataSize = store.metadata.infoBytes.size, pex = !trackerRestricted,
          listenPort = listenPortFor(connection.remote), requestQueue = REQUEST_QUEUE,
          holepunch = holepunchOn(), remote = connection.remote,
        ))
      }
      // Taken before the bitfield, so no piece verified meanwhile goes unannounced.
      haveCursor = scheduler.haveCursor()
      advertised = store.verifiedPieces()
      wire.send(when {
        fast && advertised.none { it } -> PeerMessage.HaveNone
        fast && advertised.all { it } -> PeerMessage.HaveAll
        else -> PeerMessage.Bitfield(pieceBitfield(advertised))
      })
      if (dhtPort > 0 && handshake.dht) wire.send(PeerMessage.Port(dhtPort))
      if (fast && uploadingFast()) offerAllowedFast()
    }

    /**
     * Keeps this connection unless the peer is connected already ([PeerIdentity]): of two
     * connections of one direction the first stays; otherwise both ends keep the same one
     * ([keepsOutgoing]), which replaces the other. A duplicate fails with [DuplicatePeerException].
     */
    private suspend fun claim(remoteId: ByteString) {
      val key = PeerIdentity.of(endpoint.host, remoteId)
      val kept = connectedMutex.withLock {
        val existing = identities[key]
        val outgoing = if (incoming) existing?.takeUnless { it.incoming }?.endpoint else endpoint
        val replaces = existing != null && existing.incoming != incoming && outgoing != null &&
          !incoming == keepsOutgoing(listenPortFor(outgoing), outgoing, peerId.toByteString(),
            remoteId)
        if (existing == null || replaces) {
          if (existing != null) {
            existing.displaced.store(true)
            // Where the peer is dialed again once this one closes counts what that one brought.
            if (incoming) alias = existing.endpoint
            inherited = existing.broughtPayload.load() || existing.inherited
          }
          identities[key] = this
          identity = key
        } else if (!incoming && existing.incoming && existing.alias == null) {
          // The peer that dialed us listens where we just reached it.
          existing.alias = endpoint
        }
        identity != null
      }
      if (!kept) {
        attempt.stage = PeerStage.CONNECTED
        throw DuplicatePeerException()
      }
    }

    /** Lets another connection to the same peer be kept once this one ends. */
    private suspend fun release() {
      val key = identity ?: return
      connectedMutex.withLock { if (identities[key] === this) identities.remove(key) }
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
    private fun uploadingFast(): Boolean = uploadAllowed(uploadPolicy(), store.completedNow())

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
        if (attempt.payload == 0L) broughtPayload.store(true)
        attempt.payload += message.bytes.size
        downloadPayload(message.bytes.size)
        lastBlock += throttling.elapsedNow()
        val throttled = nowMs() - throttledAt
        lastUsefulPayload += throttled
        throttledMs.addAndFetch(throttled)
        payloadReceived.addAndFetch(message.bytes.size.toLong())
      }
      require(fast || !message.isFastExtension()) { "Fast extension message without BEP 6" }
      val wasChoking = state.choking
      val accepted = try {
        state.received(message)
      } catch (e: IllegalArgumentException) {
        // A block nobody asked for, which closes the peer.
        if (message is PeerMessage.Piece) onDiscarded(message.bytes.size.toLong())
        throw e
      }
      if (state.choking != wasChoking) {
        connectedMutex.withLock { if (state.choking) unchoking.remove(id) else unchoking.add(id) }
      }
      when (message) {
        // Only the one-time bitfield rebuilds availability; HAVE stays incremental so a
        // peer cannot force a full snapshot and rarity scan per announcement.
        is PeerMessage.Bitfield, PeerMessage.HaveAll, PeerMessage.HaveNone ->
          scheduler.availability(id, state.availabilitySnapshot())
        is PeerMessage.Have -> scheduler.announce(id, message.index)
        is PeerMessage.Control -> when (message.signal) {
          PeerMessage.Signal.CHOKE -> if (fast) {
            // Our requests stand until the peer rejects them; the block deadline restarts. A
            // repeated CHOKE keeps both clocks, or a peer repeating it would hold the blocks.
            if (!wasChoking) {
              chokedAt = nowMs()
              lastBlock = TimeSource.Monotonic.markNow()
            }
          } else {
            // Our requests are void (BEP 3); the blocks already received stay with their pieces.
            scheduler.releasePeer(id)
          }
          PeerMessage.Signal.UNCHOKE -> {
            refused.clear()
            rejects.clear()
          }
          // A peer that wants nothing more has no requests left (BEP 3).
          PeerMessage.Signal.NOT_INTERESTED -> rejectUploads()
          else -> Unit
        }
        is PeerMessage.Piece -> if (accepted) {
          receiveBlock(message)
          chokerMutex.withLock { choker.received(id, message.bytes.size.toLong()) }
        } else {
          // A block we canceled that came anyway.
          onDiscarded(message.bytes.size.toLong())
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
      if (serves) uploads.add(message)
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
     * The peer will not send a block we asked for, which is no fault of its own: the block is
     * free for any peer at once. Choked, that is all; unchoked, the peer may ask it again, until
     * it has rejected as many blocks of the piece as it has and the piece is left to others.
     */
    private suspend fun rejected(message: PeerMessage.Reject) {
      scheduler.release(id, listOf(PeerMessage.Request(message.index, message.begin,
        message.length)))
      if (state.choking && !state.allowsFast(message.index)) return
      val count = (rejects[message.index] ?: 0) + 1
      val blocks = (store.pieceSize(message.index) + PeerWire.BLOCK_SIZE - 1) / PeerWire.BLOCK_SIZE
      if (count < blocks) {
        rejects[message.index] = count
        return
      }
      log.v { "Peer $endpoint of $logLabel keeps rejecting piece ${message.index}" }
      rejects.remove(message.index)
      refused += message.index
    }

    /** Cancels [requests] on the wire, handing their blocks back when [release]. */
    private suspend fun cancel(requests: List<PeerMessage.Request>, release: Boolean) {
      for (request in requests) state.cancel(request)
      wire.sendAll(requests.map { PeerMessage.Cancel(it.index, it.begin, it.length) })
      if (release) scheduler.release(id, requests)
    }

    private suspend fun receiveBlock(message: PeerMessage.Piece) {
      lastBlock = TimeSource.Monotonic.markNow()
      rateBytes += message.bytes.size
      when (val arrival = scheduler.received(id, message.index, message.begin, message.bytes)) {
        // Another copy came first, or the piece is done: the peer did nothing wrong.
        TorrentPieceScheduler.Arrival.Unneeded -> Unit
        TorrentPieceScheduler.Arrival.Kept -> useful()
        is TorrentPieceScheduler.Arrival.Complete -> {
          useful()
          commit(arrival)
        }
      }
    }

    private fun useful() {
      lastUsefulPayload = nowMs()
      if (slowStart && depth < MAX_PIPELINE) depth++
    }

    /**
     * Stores a piece this peer delivered the last block of. Only a piece it sent alone that fails
     * its hash check is its fault; one several peers sent is fetched again from one of them.
     */
    private suspend fun commit(piece: TorrentPieceScheduler.Arrival.Complete) {
      val outcome = try {
        storage { store.commit(piece.index, piece.bytes) }
      } catch (e: Throwable) {
        // Not stored: the piece's blocks are fetched again.
        withContext(NonCancellable) { scheduler.finish(id, piece.index, null) }
        throw e
      }
      val alone = scheduler.finish(id, piece.index, outcome)
      when (outcome) {
        CommitOutcome.VERIFIED -> progressEvents.trySend(Unit)
        // The selection dropped the piece while it arrived: the peer did nothing wrong.
        CommitOutcome.NOT_WANTED -> Unit
        CommitOutcome.CORRUPT -> if (alone) {
          log.w { "Piece ${piece.index} of $logLabel from $endpoint failed its hash check" }
          throw CorruptPieceException()
        } else {
          log.w { "Piece ${piece.index} of $logLabel failed its hash check; one peer fetches it" }
        }
      }
    }

    /**
     * Sends queued blocks while the upload buckets admit them. A held block waits for a later
     * pass, so a low limit never keeps this worker from reading the peer or keeping its
     * deadlines. Blocks of the piece at hand go first, and reading another piece costs the
     * peer's [serveCredit]: a peer that spent it has its requests for other pieces rejected,
     * so asking a block of each piece in turn never makes us read and hash whole pieces for it.
     */
    private suspend fun serveUploads() {
      // Choked, only allowed-fast requests are left queued (BEP 6).
      while (uploads.isNotEmpty() && uploadRetryAt <= nowMs()) {
        val request = uploads.firstOrNull { uploadCache.holds(it.index) } ?: uploads.first()
        val readCost =
          if (uploadCache.holds(request.index)) 0L else store.pieceSize(request.index).toLong()
        if (!serveCredit.tryCharge(readCost)) {
          log.v { "Peer $endpoint of $logLabel spent its reads; refusing piece ${request.index}" }
          rejectUploads { it.index == request.index }
          continue
        }
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
          serveCredit.refund(readCost)
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
        uploads.remove(request)
        serveCredit.earn(request.length.toLong())
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
        holepunch.update(id, listen, extensions.id(HOLEPUNCH_EXTENSION) != 0)
      } else if (message.id == PeerExtensions.HOLEPUNCH) {
        // Never offered for tracker-restricted torrents; a malformed message is ignored.
        if (trackerRestricted) return
        receiveHolepunch(HolepunchMessage.decode(message.payload) ?: return)
      } else if (message.id == PeerExtensions.METADATA) {
        val header = Bencode.parse(message.payload, PeerWire.MAX_FRAME_SIZE, lenient = true)
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
        holepunch.introduced(id, added)
        pexEvents.send(connection.remote to update.copy(added = added,
          flags = update.flags.filterKeys { it in kept }))
      }
    }

    /**
     * A rendezvous is relayed to the peer it names; a CONNECT dials that peer at once, unless it
     * is not a public address (outside tests); an error is only logged.
     */
    private suspend fun receiveHolepunch(message: HolepunchMessage) {
      when (message.type) {
        HolepunchType.RENDEZVOUS -> holepunch.relay(id, message.endpoint)
        HolepunchType.CONNECT -> {
          val target = message.endpoint
          val dialable = allowLocalPeers ||
            numericAddress(target.host)?.let(::publicTorrentAddress) == true
          if (dialable && extensions.id(HOLEPUNCH_EXTENSION) != 0 && holepunchOn() &&
            admitConnect()) {
            holepunch.connected()
            log.v { "Peer $endpoint of $logLabel relays a connection to $target" }
            punches.trySend(target)
          }
        }
        HolepunchType.ERROR -> {
          holepunch.failed(message.error)
          log.v {
            "Peer $endpoint of $logLabel could not punch through to ${message.endpoint}: " +
              "error ${message.error}"
          }
        }
      }
    }

    /**
     * Whether this peer may send another CONNECT now: at most [MAX_CONNECTS_PER_MINUTE] a minute,
     * as each one is a host we dial.
     */
    private fun admitConnect(): Boolean {
      val now = nowMs()
      if (now - connectWindow >= 60_000) {
        connectWindow = now
        connectsInWindow = 0
      }
      return ++connectsInWindow <= MAX_CONNECTS_PER_MINUTE
    }

    /** Sends what the swarm queued for this peer: rendezvous, CONNECTs and errors (BEP 55). */
    private suspend fun sendHolepunch() {
      val remoteId = extensions.id(HOLEPUNCH_EXTENSION)
      while (true) {
        val message = holepunchOut.tryReceive().getOrNull() ?: break
        if (remoteId != 0) wire.send(PeerMessage.Extended(remoteId, message.encode()))
      }
    }

    /**
     * A Fast peer that choked us rejects what it will not serve; requests it leaves unanswered
     * for [fastRejectGraceMs] are forgotten and their piece handed to other peers, without
     * holding it against the peer. Allowed-fast requests keep the block deadline.
     */
    private suspend fun expireChokedRequests() {
      if (!fast || !state.choking || state.pendingCount == 0 ||
        nowMs() - chokedAt < fastRejectGraceMs) return
      val expired = state.requests.filter { !state.allowsFast(it.index) }
      if (expired.isEmpty()) return
      log.v { "Peer $endpoint of $logLabel choked without rejecting ${expired.size} request(s)" }
      cancel(expired, release = true)
    }

    /**
     * Updates the peer's rate every [RATE_WINDOW_MS], and [depth] to [QUEUE_TIME_MS] of it: deep
     * enough to cover a fast peer's round trip, shallow for a slow one, whose blocks others fetch.
     */
    private fun measureRate() {
      val now = nowMs()
      val elapsed = now - rateSince
      if (elapsed < RATE_WINDOW_MS) return
      if (rateBytes > 0 || state.pendingCount > 0) {
        val sample = rateBytes * 1000.0 / elapsed
        rate = if (rate == 0.0) sample else (rate + sample) / 2
        depth = (rate * QUEUE_TIME_MS / 1000 / PeerWire.BLOCK_SIZE).toInt()
          .coerceIn(MIN_PIPELINE, MAX_PIPELINE)
        slowStart = false
      }
      rateBytes = 0
      rateSince = now
    }

    /** Tops our requests up to [depth], at most what the peer queues (BEP 10 `reqq`). */
    private suspend fun requestBlocks() {
      measureRate()
      val limit = minOf(depth, extensions.requestQueue ?: DEFAULT_PEER_QUEUE)
      val pending = state.pendingCount
      val need = limit - pending
      // Asked for in batches, each one scheduler call and one write.
      if (need <= 0 || pending > 0 && need < minOf(REFILL_BLOCKS, (limit + 7) / 8)) return
      // Late once twice the time its queue takes at its rate has passed: other peers may then
      // ask for the same blocks, so a slow peer never holds the download up.
      val lateAfter = if (rate > 0) {
        (2_000.0 * limit * PeerWire.BLOCK_SIZE / rate).toLong().coerceIn(MIN_LATE_MS, MAX_LATE_MS)
      } else TorrentPieceScheduler.DEFAULT_LATE_MS
      // Asked of every piece a search looks at: no boxed lookup while nothing is refused.
      val grant = scheduler.request(id, need, lateAfter) { index ->
        (refused.isEmpty() || index !in refused) && (!state.choking || state.allowsFast(index))
      }
      // Waiting for a piece buffer is our doing, not the peer's.
      if (grant.starved) lastUsefulPayload = nowMs()
      if (grant.requests.isEmpty()) return
      if (pending == 0) lastBlock = TimeSource.Monotonic.markNow()
      for (request in grant.requests) state.requested(request)
      wire.sendAll(grant.requests)
      lastWrite = TimeSource.Monotonic.markNow()
    }

    /** Runs after every message and idle tick: upload slot, PEX, HAVEs, deadlines, requests. */
    private suspend fun maintain() {
      if (displaced.load()) throw DuplicatePeerException()
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
      sendHolepunch()
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
      val poll = scheduler.poll(id, haveCursor)
      haveCursor = poll.haveCursor
      poll.haves?.let { verified ->
        val haves = verified.filter { !advertised[it] }
        for (index in haves) advertised[index] = true
        wire.sendAll(haves.map { PeerMessage.Have(it) })
      }
      // Blocks another copy delivered, or of pieces done or no longer wanted.
      val obsolete = poll.cancels.filter { state.isPending(it) }
      if (obsolete.isNotEmpty()) cancel(obsolete, release = false)
      val wantVersion = scheduler.wantVersion
      if (wantVersion != seenWantVersion) {
        seenWantVersion = wantVersion
        // The selection changed: a seed that downloads again judges its peers from now on.
        lastUsefulPayload = nowMs()
      }
      val completed = store.completedNow()
      // The selection grew while seeding: a peer has 90 s from now, not from its last block.
      if (sawComplete && !completed) lastUsefulPayload = nowMs()
      sawComplete = completed
      if (!completed && nowMs() - lastUsefulPayload >= 90_000) {
        throw IOException("Peer made no useful download progress")
      }
      if (completed && poll.bothSeeds) {
        log.v { "Peer $endpoint of $logLabel is a seed, as we are: closing" }
        redundant = true
        return
      }
      // Interested only while the peer has a piece we still want (BEP 3): one we would never
      // request from keeps its unchoke slots for peers that download.
      val wanted = poll.wanted
      if (wanted != interesting) {
        interesting = wanted
        wire.send(PeerMessage.Control(
          if (wanted) PeerMessage.Signal.INTERESTED else PeerMessage.Signal.NOT_INTERESTED))
      }
      expireChokedRequests()
      if (state.pendingCount > 0 && lastBlock.elapsedNow().inWholeSeconds >= 20) {
        error("Peer block deadline exceeded")
      }
      // Choked, a Fast peer still serves its allowed-fast pieces (BEP 6).
      if (interesting && (!state.choking || state.allowsAnyFast)) requestBlocks()
      if (lastWrite.elapsedNow().inWholeSeconds >= 30) {
        wire.send(PeerMessage.KeepAlive)
        lastWrite = TimeSource.Monotonic.markNow()
      }
      traffic?.state(peerChoking = state.choking, uploadSlot = uploadSlot,
        peerInterested = state.interested)
    }
  }
}

/** One maximal frame plus its decode copy; piece buffers are charged when pieces open. */
private const val PEER_WIRE_BYTES = 2 * PeerWire.MAX_FRAME_SIZE

/** Endpoints a swarm keeps to dial, known and queued together. */
private const val MAX_CANDIDATES = 4096

/** How long after its last dial an endpoint given up on starts over when a source names it. */
private const val GIVEN_UP_MS = 300_000L

/** CONNECTs (BEP 55) one peer may send a minute; each names a host we dial. */
private const val MAX_CONNECTS_PER_MINUTE = 8

/** Requests a v1 peer may queue with us (BEP 10 `reqq`); each is a few bytes until served. */
private const val REQUEST_QUEUE = 500

/** How often a swarm checks whether it stalled. */
private const val STALL_CHECK_MS = 1_000L

/** The most missing pieces a stall check looks up the files of. */
private const val MAX_REPORTED_MISSING_PIECES = 4_096

/** The least and most time a peer may take with blocks before others may ask for them too. */
private const val MIN_LATE_MS = 4_000L
private const val MAX_LATE_MS = 30_000L

/** Block requests we keep outstanding with a v1 peer before its rate is known. */
private const val INITIAL_PIPELINE = 16

/** The fewest block requests we keep outstanding with a peer that serves any. */
private const val MIN_PIPELINE = 4

/** The most block requests we keep outstanding with one v1 peer. */
private const val MAX_PIPELINE = 500

/** What a peer that names no `reqq` queues: the default BEP 10 documents. */
private const val DEFAULT_PEER_QUEUE = 250

/** How long a peer's outstanding requests last at its rate: enough to ride out a slow trip. */
private const val QUEUE_TIME_MS = 3_000L

/** How often a peer's rate is sampled; the first sample sets the depth for its round trip. */
private const val RATE_WINDOW_MS = 500L

/** The most blocks a fast peer's queue may lack before it is topped up. */
private const val REFILL_BLOCKS = 16

/** The longest a peer worker waits for a message before its next pass. */
private const val IDLE_MS = 100L

/** An [IllegalArgumentException], so the swarm never retries the peer that sent the piece. */
internal class CorruptPieceException : IllegalArgumentException("Peer sent a corrupt piece")

/** Closes a connection to a peer the swarm keeps another connection to. */
internal class DuplicatePeerException : IOException("Peer is connected over another connection")

/**
 * How a peer worker ended: where the peer [listen]s, once known; whether it was closed as
 * [redundant], both sides being seeds; where we dialed the peer of an incoming connection kept
 * over that dial ([alias]); and whether a connection it replaced brought payload ([inherited]).
 */
private class PeerEnd(
  val listen: PeerEndpoint?,
  val redundant: Boolean = false,
  val alias: PeerEndpoint? = null,
  val inherited: Boolean = false,
)

private suspend fun <T> storage(block: suspend () -> T): T = try {
  block()
} catch (e: IOException) {
  throw TorrentStorageException(e)
}
