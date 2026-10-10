package com.linroid.ketch.torrent

import com.linroid.ketch.api.log.KetchLogger
import kotlin.concurrent.atomics.AtomicBoolean
import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.SendChannel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import okio.ByteString
import okio.ByteString.Companion.toByteString
import okio.Path
import okio.Path.Companion.toPath
import kotlin.time.TimeSource

/** A full-metainfo v2 or hybrid task for [KotlinTorrentEngine.addV2Task]. */
internal data class TorrentV2TaskSpec(
  val taskId: String,
  val document: TorrentV2Document,
  val outputPath: String,
  val selected: Set<String> = emptySet(),
  val checkpoint: TorrentV2Checkpoint? = null,
  /** A source-persisted checkpoint, decoded under the session-state budget. */
  val checkpointEncoded: String? = null,
  val trackerTiers: List<List<String>> = emptyList(),
  val magnetUri: String? = null,
  val privacy: TorrentDiscoveryPrivacy,
  val throttle: suspend (Int) -> Unit = {},
  val recoverCreations: Boolean = false,
  /** Test hook replacing engine discovery: the endpoints it sends join as tracker peers. */
  val discover: (suspend (SendChannel<PeerEndpoint>) -> Unit)? = null,
  /** The swarm [discover]'s endpoints are in; a hybrid dials v1 ones in v1 mode. */
  val discoverMode: PeerIdentityHandshake.Mode = PeerIdentityHandshake.Mode.V2,
)

/** Source-owned Kotlin runtime. Task jobs borrow its bounded transports and discovery services. */
@OptIn(ExperimentalAtomicApi::class, kotlinx.coroutines.ExperimentalCoroutinesApi::class,
  kotlinx.coroutines.DelicateCoroutinesApi::class)
internal class KotlinTorrentEngine(
  private val config: TorrentConfig,
  rawNetwork: TorrentNetwork = createTorrentNetwork(),
  private val http: TorrentHttp = TorrentHttp.default(),
  /** Test hook: lets discovery (DHT) and peer exchange use loopback and private addresses. */
  private val allowLocalPeers: Boolean = false,
  private val discoveryIntervalMs: Long = 30_000,
  private val nowMs: () -> Long = monotonicClock(),
  /**
   * Test hook: the address peers reach us on, every interface by default. Tests that dial us on
   * loopback listen there, as on macOS another socket bound to 127.0.0.1 on the same port takes
   * loopback connections from a wildcard listener and resets them when it closes.
   */
  private val listenHost: String = WILDCARD_IPV4,
) : TorrentEngine {
  private class RuntimeContext : AbstractCoroutineContextElement(Key) {
    companion object Key : CoroutineContext.Key<RuntimeContext>
  }

  private val runtimeContext = RuntimeContext()
  private val log = KetchLogger("TorrentEngine")
  // Background work must never take down the host process; failures are contained per task.
  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default + runtimeContext +
    CoroutineExceptionHandler { _, error -> log.e(error) { "Torrent background task failed" } })
  private val shutdown = AtomicReference<Deferred<Unit>?>(null)
  private val network = TorrentConnectionBudget(rawNetwork, config.maxConnections)
  private val exchangeBudgets = TorrentExchangeBudgets(config)
  private val budget = exchangeBudgets.transfer
  private val storageSlots = Semaphore(config.maxOpenPayloadFiles)
  private val admissions = TorrentAdmissionLedger(exchangeBudgets.sessions)
  internal val admittedSessionBytes: Int get() = exchangeBudgets.sessions.allocated
  /** Every exchange partition together; zero after [stop] unless something leaked. */
  internal val allocatedExchangeBytes: Int get() = exchangeBudgets.allocated
  /** What pieces read back for peers hold, every torrent's together; for tests. */
  internal val allocatedUploadBytes: Int get() = exchangeBudgets.uploads.allocated
  private val cache = TorrentMetadataCache(scope, config.maxCachedMetadataBytes,
    exchangeBudgets.cache)
  private val tracker = TorrentTracker(http, network)
  private val peerId = torrentRandomBytes(20)
  private val mutex = Mutex()
  private val dhtMutex = Mutex()

  /** A registered owner: a v1 session, or a v2 or hybrid one. */
  private sealed interface Owner {
    val session: TorrentSession
    suspend fun pause()
    suspend fun close(deleteFiles: Boolean)

    class V1(override val session: KotlinTorrentSession) : Owner {
      override suspend fun pause() { session.pause() }
      override suspend fun close(deleteFiles: Boolean) { session.close(deleteFiles) }
    }

    class V2(override val session: TorrentV2DownloadSession) : Owner {
      override suspend fun pause() { session.pause() }
      override suspend fun close(deleteFiles: Boolean) { session.close(deleteFiles) }
    }
  }

  /**
   * One owner with its route claim, admission lease and output. [key] is the v1 hash of a v1
   * owner and the v2 hash of a v2 or hybrid one; [hexes] are every hash it answers to.
   */
  private class Entry(
    val key: String,
    val owner: Owner,
    val claim: TorrentRouteTable.Claim,
    val lease: TorrentBufferBudget.Lease,
    val hexes: List<String>,
    val output: String,
  ) {
    /** Removal began: the slot is free, but the hashes, tags and output stay claimed. */
    var closing = false
    var users = 0
  }

  // Guarded by [mutex].
  private val entries = mutableMapOf<String, Entry>()
  private val aliases = mutableMapOf<String, String>()
  private val routes = TorrentRouteTable()
  private val active: Int get() = entries.values.count { !it.closing }
  private val running = AtomicBoolean(false)
  private var closed = false
  internal val advertisedPort = TorrentAdvertisedPort()
  val listenPort: Int get() = advertisedPort.listenPort
  private var nodes: List<DhtNode>? = null
  private val downloadRate = TorrentRateLimiter()
  private val uploadRate = TorrentRateLimiter(config.uploadRateLimit)
  private val uploadPolicy = AtomicReference(config.effectiveUploadPolicy)
  private val additionalTrackers = AtomicReference(usableTrackers(config.additionalTrackers))
  override val isRunning: Boolean get() = running.load()

  init {
    checkNotNull(scope.coroutineContext[Job]).invokeOnCompletion { admissions.close() }
  }

  override suspend fun start() = mutex.withLock {
    check(!closed && shutdown.load() == null) { "Torrent runtime is closed" }
    if (running.load()) return@withLock
    val listener = network.listen(PeerEndpoint(listenHost, config.listenPort))
    advertisedPort.setListen(listener.local.port)
    running.store(true)
    acceptIncoming(listener)
    // Some systems provide dual-stack sockets and reject a second bind on the same port.
    val ipv6 = listenHost == WILDCARD_IPV4 && try {
      acceptIncoming(network.listen(PeerEndpoint("::", listenPort)))
      true
    } catch (_: Exception) {
      currentCoroutineContext().ensureActive()
      false
    }
    log.i {
      "Torrent engine listening on port $listenPort" +
        (if (ipv6) " (IPv4 and IPv6)" else " (IPv4)") +
        ", dht=${config.dhtEnabled}, maxActiveTorrents=${config.maxActiveTorrents}, " +
        "maxConnections=${config.maxConnections}, extraTrackers=${additionalTrackers.load().size}"
    }
  }

  private fun acceptIncoming(listener: TorrentListener) = scope.launch {
    try {
      var backoffMs = 0L
      while (isActive) {
        // Aborted handshakes and descriptor exhaustion are transient; keep accepting.
        val connection = try {
          listener.accept().also { backoffMs = 0 }
        } catch (e: CancellationException) {
          throw e
        } catch (e: Exception) {
          currentCoroutineContext().ensureActive()
          backoffMs = (backoffMs * 2).coerceIn(100, 5_000)
          log.w(e) { "Incoming peer accept failed; retrying in ${backoffMs}ms" }
          delay(backoffMs)
          continue
        }
        launchIncoming(connection)
      }
    } catch (error: Exception) {
      // Closing a socket during shutdown can throw before its provider observes cancellation.
      currentCoroutineContext().ensureActive()
      throw error
    } finally { listener.close() }
  }

  /**
   * Starts [dispatchIncoming] in the engine scope, never the caller's: an accept loop that is
   * cancelled (a listener replaced or a transport stopped) must not cancel handshakes already in
   * flight. ATOMIC start still runs its `finally`, which closes the socket, while stopping.
   */
  internal fun launchIncoming(connection: TorrentConnection) {
    scope.launch(start = CoroutineStart.ATOMIC) { dispatchIncoming(connection) }
  }

  /**
   * Reads one BitTorrent handshake within 10 s and hands [connection], with the handshake still
   * to read, to the owner of its wire tag. Transport-agnostic; closes unless handed off.
   */
  internal suspend fun dispatchIncoming(connection: TorrentConnection) {
    var handedOff = false
    try {
      val header = withTimeout(10_000) { connection.readExactly(68) }
      val tag = header.copyOfRange(28, 48)
      PeerWire.decodeHandshake(header, InfoHash.fromBytes(tag))
      handedOff = route(tag.toByteString(), PrefixedConnection(connection, header))
      if (!handedOff) log.v { "Refused incoming peer ${connection.remote}: no accepting owner" }
    } catch (_: TimeoutCancellationException) {
      log.v { "Refused incoming peer ${connection.remote}: no handshake within 10s" }
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      // Malformed handshakes and failed reads are isolated to this connection.
      log.v { "Refused incoming peer ${connection.remote}: ${e.describeWithoutUrls()}" }
    } finally {
      if (!handedOff) connection.close()
    }
  }

  /** Hands [connection] to the owner of [tag] under the engine mutex; true when it took it. */
  internal suspend fun route(tag: ByteString, connection: TorrentConnection): Boolean =
    mutex.withLock { routes.lookup(tag)?.sink?.accept(connection) == true }

  /** Wire tags of the accepting owners, readable without the engine mutex. */
  internal fun incomingTags(): Set<ByteString> = routes.tags

  override fun close() { requestShutdown() }

  /** Internal callbacks request shutdown; external callers await the complete cleanup barrier. */
  override suspend fun stop() {
    val pending = requestShutdown()
    if (currentCoroutineContext()[RuntimeContext] === runtimeContext) return
    withContext(NonCancellable) { pending.await() }
  }

  private fun requestShutdown(): Deferred<Unit> {
    shutdown.load()?.let { return it }
    // This job cannot be a descendant of the engine it must cancel and join.
    val candidate = CoroutineScope(Dispatchers.Default).async(start = CoroutineStart.LAZY) {
      stopRuntime()
    }
    if (shutdown.compareAndSet(null, candidate)) {
      running.store(false)
      candidate.start()
      return candidate
    }
    candidate.cancel()
    return checkNotNull(shutdown.load())
  }

  private suspend fun stopRuntime() {
    val owners = mutex.withLock {
      if (closed) return
      closed = true
      running.store(false)
      // Owners still closing elsewhere are closed again here; close() is idempotent.
      entries.values.map { it.owner }.also {
        entries.clear()
        aliases.clear()
        routes.clear()
      }
    }
    log.i { "Stopping torrent engine with ${owners.size} session(s)" }
    withContext(NonCancellable) {
      try {
        // v1, v2 and hybrid owners alike; a v2 close joins its discovery before admission returns.
        for (owner in owners) {
          try {
            owner.pause()
            owner.close(deleteFiles = false)
          } catch (e: Exception) {
            val hash = logHash(owner.session.infoHash)
            log.w { "Torrent $hash did not close while stopping: ${e.describeWithoutUrls()}" }
          }
        }
        nodes?.forEach { it.close() }
      } finally {
        scope.cancel()
        try { network.close() } finally {
          try { http.close() } finally {
            cache.close()
            checkNotNull(scope.coroutineContext[Job]).join()
            // join() can return while another thread is still running the completion handler
            // that closes the ledger, so return the credit before stop() does.
            admissions.close()
          }
        }
      }
    }
  }

  override suspend fun fetchMetadata(magnetUri: String): TorrentMetadata? =
    fetchMetadata(magnetUri, TorrentDiscoveryPrivacy.PUBLIC)

  override suspend fun fetchMetadata(
    magnetUri: String,
    privacy: TorrentDiscoveryPrivacy,
  ): TorrentMetadata? {
    check(isRunning)
    val magnet = MagnetUri.parse(magnetUri)
    val restrictedTiers = if (privacy == TorrentDiscoveryPrivacy.TRACKER_ONLY) {
      TrackerConfiguration.prepare(magnet.trackers.map { listOf(it) }).tiers.also {
        require(it.isNotEmpty()) { "Tracker-only magnets require supplied trackers" }
      }
    } else emptyList()
    val hash = logHash(magnet.infoHash.hex)
    val metadata = cache.resolve(magnet.infoHash, privacy) {
      log.i {
        "Fetching metadata for magnet $hash: privacy=$privacy, trackers=${magnet.trackers.size}, " +
          "peers=${magnet.explicitPeers.size}, timeout=${config.metadataTimeout}"
      }
      val started = TimeSource.Monotonic.markNow()
      var tried = 0
      try {
        withTimeout(config.metadataTimeout) {
          if (privacy == TorrentDiscoveryPrivacy.TRACKER_ONLY) {
            return@withTimeout fetchTrackerOnly(magnet, restrictedTiers)
          }
          coroutineScope {
            val peers = Channel<PeerEndpoint>(256)
            val discovery = launch { discoverMagnet(magnet, peers) }
            try {
              fetchFromMetadataPeers("Metadata for $hash", peers, onPeer = { tried = it }) {
                TorrentMetadataExchange(network, config.maxMetadataBytes,
                  budget = exchangeBudgets.metadata).fetch(magnet.infoHash, it)
              }
            } finally { discovery.cancel(); peers.cancel() }
          }
        }.also {
          log.i { "Fetched metadata for magnet $hash in ${started.elapsedNow()}" }
        }
      } catch (e: TimeoutCancellationException) {
        log.w {
          "Metadata for magnet $hash not found within ${config.metadataTimeout} " +
            "after trying $tried peer(s)"
        }
        throw e
      }
    }
    require(magnet.identity.matchesInfo(metadata.infoBytes)) { "Exact topic hash mismatch" }
    // A cache hit must not bypass this caller's pre-discovery privacy choice.
    if (metadata.isPrivate && privacy != TorrentDiscoveryPrivacy.TRACKER_ONLY) {
      throw PrivateTorrentMagnetException()
    }
    // Cache the immutable info dictionary, retaining this caller's tracker list. A hybrid's v1
    // view only tells the caller the v2 identity to download it by.
    return TorrentMetadata.fromBencode(metainfoFromInfo(metadata.infoBytes,
      magnet.trackers.map { listOf(it) }), config.maxMetadataBytes, allowHybrid = true)
  }

  suspend fun fetchV2Metadata(
    magnetUri: String,
    privacy: TorrentDiscoveryPrivacy,
  ): ByteArray {
    val pending = scope.async { resolveV2Metadata(magnetUri, privacy) }
    try { return pending.await() } finally {
      withContext(NonCancellable) { pending.cancelAndJoin() }
    }
  }

  private suspend fun resolveV2Metadata(
    magnetUri: String,
    privacy: TorrentDiscoveryPrivacy,
  ): ByteArray {
    val started = TimeSource.Monotonic.markNow()
    var hash = "?"
    try {
      return fetchV2MetadataWithinTimeout(magnetUri, privacy) { hash = it }.also {
        log.i { "Fetched v2 metadata for magnet $hash in ${started.elapsedNow()}" }
      }
    } catch (e: TimeoutCancellationException) {
      log.w { "V2 metadata for magnet $hash not found within ${config.metadataTimeout}" }
      throw e
    }
  }

  private suspend fun fetchV2MetadataWithinTimeout(
    magnetUri: String,
    privacy: TorrentDiscoveryPrivacy,
    onTopic: (String) -> Unit,
  ): ByteArray = withTimeout(config.metadataTimeout) {
    check(isRunning)
    val magnet = MagnetUri.parse(magnetUri)
    val topic = TrackerTopic.V2(requireNotNull(magnet.identity.v2))
    onTopic(topic.logHash())
    log.i {
      "Fetching v2 metadata for magnet ${topic.logHash()}: privacy=$privacy, " +
        "trackers=${magnet.trackers.size}, timeout=${config.metadataTimeout}"
    }
    val tiers = TrackerConfiguration.prepare(magnet.trackers.map { listOf(it) }).tiers
    require(privacy != TorrentDiscoveryPrivacy.TRACKER_ONLY || tiers.isNotEmpty()) {
      "Tracker-only magnets require supplied trackers"
    }
    suspend fun fetch(endpoint: PeerEndpoint): ByteArray =
      TorrentV2MetadataExchange(network, config.maxMetadataBytes,
        budget = exchangeBudgets.metadata).fetch(magnet.identity, endpoint, tiers, privacy)
    if (privacy == TorrentDiscoveryPrivacy.TRACKER_ONLY) {
      var attempts = 0
      while (isActive) {
        var interval = 30L
        for (url in tiers.flatten().distinct()) {
          val trackerSet = TrackerTiers(listOf(listOf(url)), tracker::announce)
          var started = false
          try {
            val response = attempt { trackerSet.announce(TrackerAnnounce(topic, peerId,
              advertisedPort.current(), 0, 1, event = TrackerEvent.STARTED)) } ?: continue
            started = true
            interval = maxOf(interval, response.intervalSeconds)
            for (endpoint in response.peers.distinct()) {
              check(++attempts <= MAX_METADATA_PEERS) { "Metadata peer limit exceeded" }
              attempt { fetch(endpoint) }?.let { return@withTimeout it }
            }
          } finally {
            if (started) withContext(NonCancellable) {
              withTimeoutOrNull(2000) {
                attempt { trackerSet.announce(TrackerAnnounce(topic, peerId,
                  advertisedPort.current(), 0, 1, event = TrackerEvent.STOPPED)) }
              }
            }
          }
        }
        delay(interval.coerceAtMost(Long.MAX_VALUE / 1000) * 1000)
      }
      error("Metadata resolution stopped")
    }
    coroutineScope {
      val peers = Channel<PeerEndpoint>(256)
      val discovery = launch { discoverMagnet(magnet, peers, topic) }
      try {
        fetchFromMetadataPeers("V2 metadata for ${topic.logHash()}", peers) { fetch(it) }
      } finally {
        withContext(NonCancellable) { discovery.cancelAndJoin(); peers.cancel() }
      }
    }
  }

  private suspend fun fetchTrackerOnly(
    magnet: MagnetUri,
    trackerTiers: List<List<String>>,
  ): TorrentMetadata {
    val urls = trackerTiers.flatten().distinct()
    var attempts = 0
    while (currentCoroutineContext().isActive) {
      var retrySeconds = 30L
      for (url in urls) {
        currentCoroutineContext().ensureActive()
        // Each connection closes before switching trackers, including unsuccessful peer sets.
        val tiers = TrackerTiers(listOf(listOf(url)), tracker::announce)
        var contacted = false
        try {
          val result = attempt {
            tiers.announce(TrackerAnnounce(magnet.infoHash, peerId, advertisedPort.current(), 0, 1,
              event = TrackerEvent.STARTED))
          } ?: continue
          contacted = true
          retrySeconds = maxOf(retrySeconds, result.intervalSeconds)
          for (endpoint in result.peers.distinct()) {
            check(++attempts <= MAX_METADATA_PEERS) { "Metadata peer limit exceeded" }
            try {
              return TorrentMetadataExchange(network, config.maxMetadataBytes,
                budget = exchangeBudgets.metadata).fetch(magnet.infoHash, endpoint, trackerTiers,
                  TorrentDiscoveryPrivacy.TRACKER_ONLY)
            } catch (error: CancellationException) {
              if (!currentCoroutineContext().isActive) throw error
            } catch (_: Exception) {
              // Only other peers returned by these trackers are eligible retries.
            }
          }
        } finally {
          if (contacted) withContext(NonCancellable) {
            withTimeoutOrNull(2000) {
              attempt {
                tiers.announce(TrackerAnnounce(magnet.infoHash, peerId,
                  advertisedPort.current(), 0, 1, event = TrackerEvent.STOPPED))
              }
            }
          }
        }
      }
      delay(retrySeconds.coerceAtMost(Long.MAX_VALUE / 1000) * 1000)
    }
    currentCoroutineContext().ensureActive()
    error("Metadata resolution stopped")
  }

  private suspend fun discoverMagnet(
    magnet: MagnetUri,
    output: SendChannel<PeerEndpoint>,
    topic: TrackerTopic = TrackerTopic.V1(magnet.infoHash),
  ) =
    supervisorScope {
      launch {
        for (text in magnet.explicitPeers) {
          attempt { resolveEndpoint(text) }?.forEach { output.send(it) }
        }
      }
      suspend fun announce(tiers: TrackerTiers) {
        while (currentCoroutineContext().isActive) {
          val result = attempt {
            tiers.announce(TrackerAnnounce(topic, peerId, advertisedPort.current(), 0, 1,
              event = TrackerEvent.STARTED))
          }
          result?.peers?.forEach { output.send(it) }
          delay((result?.intervalSeconds ?: 30) * 1000)
        }
      }
      if (magnet.trackers.isNotEmpty()) launch {
        announce(TrackerTiers(magnet.trackers.map { listOf(it) }, tracker::announce))
      }
      for (url in extraTrackers(magnet.trackers)) launch {
        announce(TrackerTiers(listOf(listOf(url)), tracker::announce))
      }
      if (config.dhtEnabled) launch {
        while (isActive) {
          dhtPeers(InfoHash.fromBytes(topic.wireBytes()), announce = false)
            .forEach { output.send(it) }
          delay(discoveryIntervalMs)
        }
      }
    }

  override suspend fun addTorrent(
    infoHash: String,
    savePath: String,
    magnetUri: String?,
    torrentData: ByteArray?,
    selectedFileIndices: Set<Int>,
    resumeData: ByteArray?,
  ): TorrentSession {
    val metadata = torrentData?.let { TorrentMetadata.fromBencode(it) }
      ?: magnetUri?.let { fetchMetadata(it) } ?: error("Torrent metainfo is required")
    require(metadata.infoHash.hex == infoHash)
    return addTask(TorrentTaskSpec(infoHash, metadata,
      (savePath.toPath() / metadata.name).toString(), selectedFileIndices, magnetUri, resumeData))
  }

  override suspend fun addTask(spec: TorrentTaskSpec): KotlinTorrentSession = mutex.withLock {
    // A hybrid's v1 view lacks its v2 hashes and identity; hybrids run as v2 owners.
    require(!spec.metadata.isHybrid) { "Hybrid torrents run as v2 owners" }
    check(isRunning && !closed)
    check(active < config.maxActiveTorrents) { "Too many active torrents" }
    val hash = spec.metadata.infoHash.hex
    check(hash !in aliases) { "Torrent already has an active owner" }
    // Every lookup also holds the mutex, so the claim cannot route before the session exists.
    var target: KotlinTorrentSession? = null
    val claim = routes.register(hash, listOf(spec.metadata.infoHash.toBytes().toByteString())) {
      target?.accept(it) == true
    }
    var lease: TorrentBufferBudget.Lease? = null
    try {
      lease = admissions.admit(spec, config)
      val requested = torrentSystemFileSystem.canonicalize(".".toPath())
        .resolve(spec.outputPath).normalized()
      val store = TorrentPieceStore(spec.metadata, requested, spec.selected, spec.taskId,
        storageSlots = storageSlots)
      val output = store.outputPath.toPath()
      requireAvailableOutput(output)
      val checkpoint = spec.resumeData?.let(TorrentCheckpoint::decode)
      val trackerState = TorrentBufferBudget(
        maxOf(1, trackerControlStateWeight().toInt()))
      val session = KotlinTorrentSession(store, network, budget, scope,
        connections = config.connectionsPerTorrent, uploadBudget = exchangeBudgets.uploads,
        uploadPolicy = { uploadPolicy.load() },
        checkpoint = checkpoint, peerId = peerId,
        discover = { peers, owner -> discover(spec, peers, owner, trackerState) },
        downloadThrottle = { downloadRate.acquire(it); spec.throttle(it) },
        engineUploadRate = uploadRate,
        trackerConfigurationBudget = exchangeBudgets.sessions,
        privacy = spec.privacy,
        allowLocalPeers = allowLocalPeers,
        listenPortFor = { advertisedPort.current() },
      )
      target = session
      entries[hash] = Entry(hash, Owner.V1(session), claim, lease, listOf(hash), output.toString())
      aliases[hash] = hash
      log.i {
        "Added torrent ${logHash(hash)} for taskId=${spec.taskId}: " +
          "trackers=${spec.metadata.trackerTiers.sumOf { it.size }}, privacy=${spec.privacy}, " +
          "private=${spec.metadata.isPrivate}, resume=${checkpoint != null}, " +
          "active=$active/${config.maxActiveTorrents}"
      }
      session
    } catch (failure: Throwable) {
      routes.release(claim)
      lease?.let(admissions::release)
      throw failure
    }
  }

  private fun requireAvailableOutput(output: Path) {
    check(entries.values.none { entry ->
      val path = entry.output.toPath()
      val left = path.segments.map { canonicalTorrentName(it).lowercase() }
      val right = output.segments.map { canonicalTorrentName(it).lowercase() }
      path.root.toString().equals(output.root.toString(), ignoreCase = true) &&
        (left.take(right.size) == right || right.take(left.size) == left)
    }) { "Torrent output overlaps another task" }
  }

  /**
   * Registers a long-lived full-metainfo v2 or hybrid owner under every hash and wire tag of its
   * identity, then restores its checkpoint. The owner downloads once resumed and stays until
   * [removeTorrent] names either of its hashes, or the engine stops. Retained metadata and
   * storage indexes are admitted before storage construction or file I/O; encoded checkpoints
   * and retained recovery records share the session-state budget. Recovery validates ownership
   * before the owner is returned, and resume rechecks the payload.
   */
  suspend fun addV2Task(spec: TorrentV2TaskSpec): TorrentV2DownloadSession {
    var decoding: TorrentBufferBudget.Lease? = null
    try {
      require(spec.checkpoint == null || spec.checkpointEncoded == null)
      val encoded = spec.checkpointEncoded
      val recovery = if (encoded == null) spec.checkpoint else {
        // Admit base64/raw/parser copies before decoding source-owned persisted state.
        val bytes = encoded.length * 12L + 4096
        require(bytes <= exchangeBudgets.sessions.capacity) { "Recovery state exceeds budget" }
        decoding = checkNotNull(exchangeBudgets.sessions.reserve(bytes.toInt())) {
          "Recovery decode budget exhausted"
        }
        requireNotNull(TorrentV2Checkpoint.decode(decodeBase64(encoded))) {
          "Invalid v2 checkpoint"
        }
      }
      val entry = mutex.withLock { registerV2(spec, recovery) }
      decoding?.close()
      decoding = null
      val owner = entry.owner as Owner.V2
      try {
        owner.session.restore(recovery)
      } catch (failure: Throwable) {
        withContext(NonCancellable) {
          try { owner.close(deleteFiles = false) } finally {
            mutex.withLock {
              if (entries[entry.key] === entry) unregister(entry)
              else admissions.release(entry.lease)
            }
          }
        }
        throw failure
      }
      return owner.session
    } finally {
      decoding?.close()
    }
  }

  /** Under [mutex]: admits, claims and opens a v2 owner. No file I/O happens here. */
  private fun registerV2(spec: TorrentV2TaskSpec, recovery: TorrentV2Checkpoint?): Entry {
    check(isRunning && !closed) { "Torrent runtime is closed" }
    check(active < config.maxActiveTorrents) { "Too many active torrents" }
    val document = spec.document
    require(spec.discoverMode == PeerIdentityHandshake.Mode.V2 || document.hybrid != null) {
      "Only a hybrid has a v1 swarm"
    }
    val key = document.info.hash.hex
    val hexes = listOfNotNull(key, document.identity.v1?.hex)
    check(hexes.none { it in aliases }) { "Torrent already has an active owner" }
    // Every lookup also holds the mutex, so the claim cannot route before the session exists.
    var target: TorrentV2DownloadSession? = null
    val claim = routes.register(key, TorrentRouteTable.tagsOf(document.identity)) {
      target?.accept(it) == true
    }
    var lease: TorrentBufferBudget.Lease? = null
    try {
      lease = admissions.adopt(admitV2Session(document, spec.selected.size,
        spec.outputPath.length, config, exchangeBudgets.sessions, recovery))
      val selection = spec.selected.toSet()
      val requested = torrentSystemFileSystem.canonicalize(".".toPath())
        .resolve(spec.outputPath).normalized()
      val parent = checkNotNull(requested.parent) { "Output root must have a parent" }
      val output = torrentSystemFileSystem.canonicalize(parent) / requested.name
      requireAvailableOutput(output)
      val layout = TorrentContentLayout.from(document.info, document.hybrid)
      val store = TorrentV2PieceStore(document, output, selection, spec.taskId, budget,
        storageSlots, creationLogPath = if (spec.recoverCreations) {
          v2CreationLog(output, spec.taskId)
        } else null)
      val custom = spec.discover
      val discovery: suspend (TorrentV2DiscoverySink) -> Unit = if (custom != null) {
        val topic = if (spec.discoverMode == PeerIdentityHandshake.Mode.V1) PeerTopic.V1
          else PeerTopic.V2
        { sink -> sink.relay(topic = topic, discover = custom) }
      } else {
        { sink ->
          discoverV2(document, layout, store, spec.trackerTiers, spec.magnetUri, spec.privacy,
            sink)
        }
      }
      val session = TorrentV2DownloadSession.open(scope, document, layout, selection, store,
        TorrentV2Runtime(network, peerId.toByteString(), budget, exchangeBudgets.sessions,
          downloadRate = downloadRate, uploadRate = uploadRate,
          uploadPolicy = { uploadPolicy.load() },
          listenPortFor = { advertisedPort.current() }, allowLocalPeers = allowLocalPeers,
          uploadBuffers = exchangeBudgets.uploads),
        TorrentV2SessionOptions(maxPeers = MAX_V2_PEERS,
          initialConnections = minOf(config.connectionsPerTorrent, MAX_V2_PEERS),
          privacy = spec.privacy, waitForPeers = true, throttle = spec.throttle,
          discovery = discovery))
      target = session
      val entry = Entry(key, Owner.V2(session), claim, lease, hexes, output.toString())
      entries[key] = entry
      hexes.forEach { aliases[it] = key }
      log.i {
        "Added v2 torrent ${logHash(key)} for taskId=${spec.taskId}: " +
          "hybrid=${document.hybrid != null}, trackers=${spec.trackerTiers.sumOf { it.size }}, " +
          "privacy=${spec.privacy}, private=${document.info.privateTorrent}, " +
          "resume=${recovery != null}, active=$active/${config.maxActiveTorrents}"
      }
      return entry
    } catch (failure: Throwable) {
      routes.release(claim)
      lease?.let(admissions::release)
      throw failure
    }
  }

  /**
   * Scoped wrapper over [addV2Task], for tests and callers that own one download: registers the
   * owner, runs [body], then removes the owner, even when [body] fails or is cancelled.
   */
  suspend fun <T> withV2Download(
    taskId: String,
    document: TorrentV2Document,
    outputPath: String,
    selected: Set<String> = emptySet(),
    checkpoint: TorrentV2Checkpoint? = null,
    checkpointEncoded: String? = null,
    discover: (suspend (SendChannel<PeerEndpoint>) -> Unit)? = null,
    trackerTiers: List<List<String>> = emptyList(),
    magnetUri: String? = null,
    privacy: TorrentDiscoveryPrivacy = config.discoveryPrivacy,
    throttle: suspend (Int) -> Unit = {},
    recoverCreations: Boolean = false,
    discoverMode: PeerIdentityHandshake.Mode = PeerIdentityHandshake.Mode.V2,
    body: suspend (TorrentV2DownloadSession) -> T,
  ): T {
    val spec = TorrentV2TaskSpec(taskId, document, outputPath, selected, checkpoint,
      checkpointEncoded, trackerTiers, magnetUri, privacy, throttle, recoverCreations, discover,
      discoverMode)
    // The engine scope runs the body, so callbacks inside it can request engine shutdown.
    val pending = scope.async {
      val session = addV2Task(spec)
      try { body(session) } finally {
        withContext(NonCancellable) { removeOwner(session) }
      }
    }
    try { return pending.await() } finally {
      withContext(NonCancellable) { pending.cancelAndJoin() }
    }
  }

  /**
   * Feeds a v2 owner's transfer: its own trackers (whose hosts a restricted owner admits), and
   * for public torrents extra trackers, explicit magnet peers and DHT. A hybrid is in two swarms,
   * the v2 hash's and the v1 hash's: each has its own tracker tiers and DHT lookups, and its peers
   * are dialed in its mode. A private hybrid's two topics share one tracker instead
   * ([PrivateTrackerGroup]). Traffic counters come from the owner, so trackers see what it
   * received and uploaded.
   */
  private suspend fun discoverV2(
    document: TorrentV2Document,
    layout: TorrentContentLayout,
    store: TorrentV2PieceStore,
    tiers: List<List<String>>,
    magnetUri: String?,
    privacy: TorrentDiscoveryPrivacy,
    sink: TorrentV2DiscoverySink,
  ): Unit = coroutineScope {
    val v1 = document.identity.v1?.takeIf { document.hybrid != null }
    val topics = listOfNotNull(TrackerTopic.V2(document.info.hash), v1?.let(TrackerTopic::V1))
    fun peerTopic(topic: TrackerTopic): PeerTopic =
      if (topic is TrackerTopic.V1) PeerTopic.V1 else PeerTopic.V2
    // Tier state follows each topic's own answers, so every topic gets fresh tiers.
    fun discovery(topic: TrackerTopic, urls: List<List<String>>, onChanged: suspend () -> Unit) =
      TrackerDiscovery(document, layout, peerId, advertisedPort::current,
        TrackerTiers(urls, tracker::announce), onChanged, topic = topic)
    suspend fun poll(discovery: TrackerDiscovery, stopped: Boolean = false): TrackerResponse? =
      discovery.poll(store.verifiedPieces(), sink.receivedBytes(), sink.uploadedBytes(),
        stopped = stopped)
    suspend fun publish(topic: TrackerTopic, peers: List<PeerEndpoint>) {
      // Admit the hosts first: a restricted owner accepts only peers its trackers name.
      sink.trackerPeers(topic, peers)
      peers.forEach { sink.offer(it, peerTopic(topic), PeerOrigin.TRACKER) }
    }
    if (tiers.isNotEmpty() && document.info.privateTorrent && topics.size > 1) launch {
      // BEP 27: both hashes of a private hybrid use one tracker at a time, and move together.
      val group = PrivateTrackerGroup(topics, PrivateTrackerGroup.order(tiers),
        { topic, url -> discovery(topic, listOf(listOf(url))) {} }, sink::reset)
      try {
        while (isActive) {
          attempt {
            group.poll(store.verifiedPieces(), sink.receivedBytes(), sink.uploadedBytes())
              .forEach { (topic, response) -> publish(topic, response.peers.distinct()) }
          }
          delay(1000)
        }
      } finally {
        withContext(NonCancellable) {
          withTimeoutOrNull(2000) {
            attempt {
              group.stop(store.verifiedPieces(), sink.receivedBytes(), sink.uploadedBytes())
            }
          }
        }
      }
    } else if (tiers.isNotEmpty()) for (topic in topics) launch {
      val discovery = discovery(topic, tiers, sink::reset)
      try {
        while (isActive) {
          attempt { poll(discovery)?.let { publish(topic, it.peers.distinct()) } }
          delay(1000)
        }
      } finally {
        withContext(NonCancellable) {
          withTimeoutOrNull(2000) {
            attempt { poll(discovery) }
            attempt { poll(discovery, stopped = true) }
          }
        }
      }
    }
    if (!document.info.privateTorrent && privacy == TorrentDiscoveryPrivacy.PUBLIC) {
      for (url in extraTrackers(tiers.flatten())) launch {
        // One coroutine per tracker polls the topics in turn, so a hybrid adds no sockets.
        announceExtra(topics.map { discovery(it, listOf(listOf(url))) {} },
          { extra, stopped -> poll(extra, stopped) }) { extra, found ->
          found.forEach { sink.offer(it, peerTopic(extra.topic), PeerOrigin.TRACKER) }
        }
      }
      // A hybrid's magnet peers may only know the v1 swarm: dial them in v1 mode, offering v2.
      val explicitTopic = if (v1 != null) PeerTopic.V1 else PeerTopic.V2
      magnetUri?.let { uri -> launch {
        MagnetUri.parse(uri).explicitPeers.forEach { endpoint ->
          attempt {
            resolveEndpoint(endpoint).forEach {
              sink.offer(it, explicitTopic, PeerOrigin.EXPLICIT)
            }
          }
        }
      } }
      if (config.dhtEnabled) launch {
        while (isActive) {
          attempt {
            dhtPeers(InfoHash.fromBytes(document.info.hash.wireBytes()), announce = true)
              .forEach { sink.offer(it, PeerTopic.V2, PeerOrigin.DHT) }
          }
          if (v1 != null) attempt {
            dhtPeers(v1, announce = true).forEach { sink.offer(it, PeerTopic.V1, PeerOrigin.DHT) }
          }
          delay(60_000)
        }
      }
    }
    // Keep the transfer open between tracker/DHT rounds and for tracker-only empty swarms.
    kotlinx.coroutines.awaitCancellation()
  }

  private suspend fun discover(
    spec: TorrentTaskSpec,
    output: SendChannel<PeerEndpoint>,
    session: KotlinTorrentSession,
    trackerState: TorrentBufferBudget,
  ) = supervisorScope {
    val metadata = spec.metadata
    val configuration = session.trackerConfiguration()
    val trackerTiers = configuration.tiers
    if (trackerTiers.isNotEmpty()) launch {
      val discovery = TrackerDiscovery(metadata, peerId, advertisedPort::current,
        TrackerTiers(trackerTiers, tracker::announce, configuration.revision), session::resetPeers,
        nowMs = nowMs,
        announceCompletion = spec.selected.isEmpty() || spec.selected.size == metadata.files.size,
      )
      discovery.observeStatus(session::updateTrackerStatus)
      try {
        TrackerControl.run(trackerState, operation = { manual ->
          discovery.poll(session.verifiedPieces(), session.receivedBytes, session.uploadedBytes,
            manual = manual)
        }, publish = { response ->
          session.trackerPeers(response.peers)
          response.peers.forEach { output.send(it) }
        }, readStatus = discovery::status,
          publishStatus = session::updateTrackerStatus,
          scrape = scrape@{
            // Admit the bounded HTTP body, parse tree, URL copies, and UDP workspace before I/O.
            val lease = exchangeBudgets.metadata.reserve(TRACKER_SCRAPE_WORKSPACE_BYTES)
              ?: return@scrape false
            try { discovery.scrape(tracker::scrape) } finally { lease.close() }
          },
        ) { control ->
          session.attachTrackerControl(control)
          try {
            while (isActive) {
              attempt { control.poll() }
              delay(1000)
            }
          } finally { session.detachTrackerControl(control) }
        }
      } finally {
        discovery.observeStatus {}
        session.updateTrackerStatus(emptyList())
        withContext(NonCancellable) {
          withTimeoutOrNull(2000) {
            if (session.state.value == TorrentSessionState.FINISHED ||
              session.state.value == TorrentSessionState.SEEDING) {
              attempt { discovery.poll(session.verifiedPieces(), session.receivedBytes,
                session.uploadedBytes) }
            }
            attempt { discovery.poll(session.verifiedPieces(), session.receivedBytes,
              session.uploadedBytes, stopped = true) }
          }
        }
      }
    }
    if (!metadata.isPrivate && spec.privacy != TorrentDiscoveryPrivacy.TRACKER_ONLY) {
      for (url in extraTrackers(trackerTiers.flatten())) launch {
        val discovery = TrackerDiscovery(metadata, peerId, advertisedPort::current,
          TrackerTiers(listOf(listOf(url)), tracker::announce), nowMs = nowMs,
          announceCompletion = spec.selected.isEmpty() || spec.selected.size == metadata.files.size,
        )
        announceExtra(listOf(discovery), { topic, stopped ->
          topic.poll(session.verifiedPieces(), session.receivedBytes, session.uploadedBytes,
            stopped = stopped)
        }) { _, found -> found.forEach { output.send(it) } }
      }
      spec.magnetUri?.let { uri -> launch {
        MagnetUri.parse(uri).explicitPeers.forEach { text ->
          attempt { resolveEndpoint(text) }?.forEach { output.send(it) }
        }
      } }
      if (config.dhtEnabled) launch {
        while (isActive) {
          dhtPeers(metadata.infoHash, announce = true).forEach { output.send(it) }
          delay(60_000)
        }
      }
    }
  }

  private suspend fun dhtPeers(hash: InfoHash, announce: Boolean): List<PeerEndpoint> =
    supervisorScope {
      dht().map { node -> async {
        attempt("DHT lookup for ${logHash(hash.hex)}") {
          node.peers(hash, if (announce) advertisedPort.current() else null)
        } ?: emptyList()
      } }.awaitAll().flatten().distinct()
    }.also { peers -> log.d { "DHT lookup for ${logHash(hash.hex)} found ${peers.size} peer(s)" } }

  /**
   * Binds a DHT socket on [host] at the configured listen port, so a port forwarded for peers
   * reaches DHT too, or at any port when none is configured or it cannot be had: dual-stack
   * systems refuse an IPv6 socket on the port the IPv4 one holds.
   */
  private suspend fun bindDhtSocket(host: String): TorrentDatagramSocket {
    if (config.listenPort != 0) {
      try {
        return network.bindUdp(PeerEndpoint(host, config.listenPort))
      } catch (e: CancellationException) {
        throw e
      } catch (e: Exception) {
        log.d {
          "DHT cannot use UDP port ${config.listenPort} on $host, using any port: " +
            e.describeWithoutUrls()
        }
      }
    }
    return network.bindUdp(PeerEndpoint(host, 0))
  }

  private suspend fun dht(): List<DhtNode> = dhtMutex.withLock {
    nodes?.let { current ->
      if (current.isNotEmpty() && current.all { it.isRunning }) return@withLock current
      // A node whose socket failed never recovers; rebind rather than lose DHT for good.
      current.forEach { attempt { it.close() } }
    }
    val result = mutableListOf<DhtNode>()
    for (host in listOf("0.0.0.0", "::")) {
      val family = if (':' in host) "IPv6" else "IPv4"
      val node = attempt("Binding the $family DHT socket") {
        DhtNode(bindDhtSocket(host), scope, allowLocalAddresses = allowLocalPeers)
      } ?: continue
      node.start()
      result.add(node)
      scope.launch {
        val snapshot = config.stateDirectory?.toPath()?.resolve(
          if (':' in host) "dht6.nodes" else "dht4.nodes")
        val restored = snapshot?.let { path ->
          attempt("Restoring $family DHT nodes") {
            if (!torrentSystemFileSystem.exists(path)) return@attempt emptyList()
            require((torrentSystemFileSystem.metadata(path).size ?: Long.MAX_VALUE) <= 256 * 1024)
            DhtRoutingTable.restore(torrentSystemFileSystem.read(path) { readByteArray() })
              .second.map { it.endpoint }
          }
        } ?: emptyList()
        // Resolve on every attempt: bootstrap names do not resolve while the device is offline.
        suspend fun bootstrap() {
          val endpoints = config.dhtBootstrap.flatMap { router ->
            attempt("Resolving DHT router $router") { resolveEndpoint(router) } ?: emptyList()
          }.filter { (':' in it.host) == (':' in host) }
          // A large snapshot must not crowd out the routers when its nodes have gone stale.
          val saved = restored.filter { it !in endpoints }
            .take((64 - endpoints.size).coerceAtLeast(0))
          attempt("$family DHT bootstrap") {
            node.bootstrap((saved + endpoints).distinct().take(64))
          }
          log.d {
            "$family DHT bootstrap from ${endpoints.size} router(s) and ${saved.size} saved " +
              "node(s): ${node.contactCount()} contact(s)"
          }
        }
        bootstrap()
        var retryMs = DHT_BOOTSTRAP_RETRY_MS
        while (isActive && node.isRunning) {
          if (node.contactCount() == 0) {
            log.d { "$family DHT has no contacts; bootstrapping again in ${retryMs / 1000}s" }
            delay(retryMs)
            retryMs = (retryMs * 2).coerceAtMost(DHT_REFRESH_MS)
            bootstrap()
            continue
          }
          retryMs = DHT_BOOTSTRAP_RETRY_MS
          if (snapshot != null) {
            // Processes sharing the directory must not write through the same temporary file.
            val temporary = checkNotNull(snapshot.parent) /
              "${snapshot.name}.${torrentRandomBytes(4).toByteString().hex()}.tmp"
            attempt("Saving $family DHT nodes") {
              torrentSystemFileSystem.createDirectories(checkNotNull(snapshot.parent))
              val bytes = node.snapshot()
              torrentSystemFileSystem.write(temporary) { write(bytes) }
              torrentSystemFileSystem.atomicMove(temporary, snapshot)
            } ?: attempt { torrentSystemFileSystem.delete(temporary, mustExist = false) }
          }
          delay(DHT_REFRESH_MS)
          attempt("$family DHT refresh") { node.refresh() }
        }
        if (isActive) log.i { "$family DHT socket stopped; the next lookup binds a new one" }
      }
    }
    if (result.isEmpty()) log.w { "DHT is unavailable: no UDP socket could be bound" }
    nodes = result
    result
  }

  /** False when [addTask] or [addV2Task] would reject a new torrent for lack of a slot. */
  internal suspend fun hasFreeSlot(): Boolean = mutex.withLock {
    active < config.maxActiveTorrents
  }

  /**
   * Removes the owner of [infoHash]: a v1 hash, a v2 hash, or a hybrid's v1 hash. Closing joins
   * peers, a final tracker announce and optionally a recursive delete, so it runs without the
   * engine lock. The owner stops receiving peers and frees its slot at once, but keeps its
   * hashes, wire tags, output path and admission lease until every concurrent close has finished
   * and the last one succeeded.
   */
  override suspend fun removeTorrent(infoHash: String, deleteFiles: Boolean) {
    val entry = mutex.withLock {
      val entry = aliases[infoHash]?.let { entries[it] } ?: return
      beginRemoval(entry)
    }
    log.d { "Removing torrent ${logHash(infoHash)}, deleteFiles=$deleteFiles" }
    finishRemoval(entry, deleteFiles)
  }

  /** Removes [session]'s entry, unless another owner replaced it meanwhile. */
  private suspend fun removeOwner(session: TorrentSession) {
    val entry = mutex.withLock {
      val entry = entries[session.infoHash]?.takeIf { it.owner.session === session } ?: return
      beginRemoval(entry)
    }
    finishRemoval(entry, deleteFiles = false)
  }

  /** Under [mutex]. */
  private fun beginRemoval(entry: Entry): Entry {
    if (!entry.closing) {
      entry.closing = true
      routes.stopAccepting(entry.claim)
    }
    entry.users++
    return entry
  }

  private suspend fun finishRemoval(entry: Entry, deleteFiles: Boolean) {
    var closed = false
    try {
      withContext(NonCancellable) { entry.owner.close(deleteFiles) }
      closed = true
    } finally {
      withContext(NonCancellable) {
        mutex.withLock {
          // A failed close (such as a refused delete) stays charged and registered for a retry.
          if (--entry.users == 0 && closed && entries[entry.key] === entry) unregister(entry)
        }
      }
    }
  }

  /** Under [mutex]: frees every hash, tag, the output and the admission of a closed owner. */
  private fun unregister(entry: Entry) {
    routes.release(entry.claim)
    entries.remove(entry.key)
    entry.hexes.forEach { if (aliases[it] == entry.key) aliases.remove(it) }
    admissions.release(entry.lease)
  }

  override fun setDownloadRateLimit(bytesPerSecond: Long) = downloadRate.set(bytesPerSecond)
  override fun setUploadRateLimit(bytesPerSecond: Long) = uploadRate.set(bytesPerSecond)

  /**
   * Replaces the upload policy of running and later torrents. Every owner chokes or unchokes its
   * peers within a tick and stops seeding unless [policy] seeds: v1 sessions read the policy on
   * each pass, and v2 and hybrid owners are told.
   */
  override suspend fun setUploadPolicy(policy: TorrentUploadPolicy) {
    uploadPolicy.store(policy)
    val owners = mutex.withLock {
      entries.values.mapNotNull { (it.owner as? Owner.V2)?.session }
    }
    owners.forEach { it.uploadPolicyChanged() }
    log.d { "Engine upload policy: $policy" }
  }
  fun setConnections(value: Int) = network.set(value)

  /** Replaces the extra public trackers; sessions and lookups started afterwards use them. */
  fun setAdditionalTrackers(urls: List<String>) {
    val usable = usableTrackers(urls)
    additionalTrackers.store(usable)
    log.i { "Extra trackers updated: ${usable.size} tracker(s)" }
  }

  private fun usableTrackers(urls: List<String>): List<String> {
    val candidates = urls.map { it.trim() }.filter { it.isNotEmpty() }.distinct()
    val valid = candidates.filter { url ->
      try {
        TrackerConfiguration.prepare(listOf(listOf(url)))
        true
      } catch (_: IllegalArgumentException) {
        false
      }
    }
    // Counts only: tracker URLs can carry passkeys.
    if (valid.size < candidates.size) {
      log.w { "Ignoring ${candidates.size - valid.size} invalid additional tracker URL(s)" }
    }
    if (valid.size > MAX_ADDITIONAL_TRACKERS) {
      log.w { "Using the first $MAX_ADDITIONAL_TRACKERS of ${valid.size} additional trackers" }
    }
    return valid.take(MAX_ADDITIONAL_TRACKERS)
  }

  /** Configured extra trackers that public discovery adds beside a torrent's [own] trackers. */
  private fun extraTrackers(own: List<String>): List<String> {
    val existing = own.toSet()
    return additionalTrackers.load().filter { it !in existing }
  }

  /**
   * Announces one extra tracker for each of [discoveries] (one per topic) until cancelled, then
   * sends a best-effort stop to each within one shared bound. Each extra runs on its own because
   * tier traversal stops at the first tracker that answers, and would otherwise wait behind every
   * unreachable tracker of the torrent's own list. Topics are polled in sequence, so a torrent
   * with two topics does not double the concurrent requests to a tracker.
   */
  private suspend fun announceExtra(
    discoveries: List<TrackerDiscovery>,
    poll: suspend (TrackerDiscovery, stopped: Boolean) -> TrackerResponse?,
    publish: suspend (TrackerDiscovery, List<PeerEndpoint>) -> Unit,
  ) {
    try {
      while (currentCoroutineContext().isActive) {
        for (discovery in discoveries) {
          attempt { poll(discovery, false) }?.let { publish(discovery, it.peers.distinct()) }
        }
        delay(1000)
      }
    } finally {
      withContext(NonCancellable) {
        withTimeoutOrNull(2000) {
          for (discovery in discoveries) attempt { poll(discovery, true) }
        }
      }
    }
  }
}

private const val WILDCARD_IPV4 = "0.0.0.0"

private const val DHT_BOOTSTRAP_RETRY_MS = 30_000L
internal const val MAX_ADDITIONAL_TRACKERS = 128
private const val DHT_REFRESH_MS = 15 * 60_000L

private val attemptLog = KetchLogger("TorrentEngine")

/**
 * Runs best-effort discovery work, returning null on failure. A [label] names the work in a
 * debug line when it fails; unlabeled attempts are either expected to fail often or already
 * report their own outcome.
 */
private suspend fun <T> attempt(label: String? = null, block: suspend () -> T): T? = try {
  block()
} catch (e: CancellationException) {
  if (!currentCoroutineContext().isActive) throw e
  if (label != null) attemptLog.d { "$label timed out" }
  null
} catch (e: Exception) {
  if (label != null) attemptLog.d { "$label failed: ${e.describeWithoutUrls()}" }
  null
}

internal suspend fun resolveEndpoint(value: String): List<PeerEndpoint> {
  val port = value.substringAfterLast(':').toIntOrNull()
  require(port != null && port in 1..65535) { "Invalid peer endpoint" }
  val host = value.substringBeforeLast(':').removeSurrounding("[", "]")
  return resolveTorrentHost(host).map { PeerEndpoint(numericHost(it), port) }
}
