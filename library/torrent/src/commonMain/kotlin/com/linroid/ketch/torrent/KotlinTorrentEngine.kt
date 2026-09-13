package com.linroid.ketch.torrent

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.SendChannel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import okio.FileSystem
import okio.Path.Companion.toPath
import kotlin.concurrent.atomics.AtomicBoolean
import kotlin.concurrent.atomics.ExperimentalAtomicApi

/** Source-owned Kotlin runtime. Task jobs borrow its bounded transports and discovery services. */
@OptIn(ExperimentalAtomicApi::class, kotlinx.coroutines.ExperimentalCoroutinesApi::class,
  kotlinx.coroutines.DelicateCoroutinesApi::class)
internal class KotlinTorrentEngine(
  private val config: TorrentConfig,
  rawNetwork: TorrentNetwork = createTorrentNetwork(),
  private val http: TorrentHttp = TorrentHttp.default(),
  private val allowLocalDiscovery: Boolean = false,
  private val discoveryIntervalMs: Long = 30_000,
  private val nowMs: () -> Long = monotonicClock(),
) : TorrentEngine {
  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
  private val network = TorrentConnectionBudget(rawNetwork, config.maxConnections)
  private val exchangeBudgets = TorrentExchangeBudgets(config)
  private val budget = exchangeBudgets.transfer
  private val storageSlots = Semaphore(config.maxOpenPayloadFiles)
  private val admissions = TorrentAdmissionLedger(exchangeBudgets.sessions)
  internal val admittedSessionBytes: Int get() = exchangeBudgets.sessions.allocated
  private val cache = TorrentMetadataCache(scope, config.maxCachedMetadataBytes,
    exchangeBudgets.cache)
  private val tracker = TorrentTracker(http, network)
  private val peerId = torrentRandomBytes(20)
  private val mutex = Mutex()
  private val dhtMutex = Mutex()
  private val sessions = mutableMapOf<String, KotlinTorrentSession>()
  private val outputs = mutableMapOf<String, String>()
  private val sessionLeases = mutableMapOf<String, TorrentBufferBudget.Lease>()
  private val running = AtomicBoolean(false)
  private var closed = false
  private var port = 0
  val listenPort: Int get() = port
  private var nodes: List<DhtNode>? = null
  private val downloadRate = TorrentRateLimiter()
  private val uploadRate = TorrentRateLimiter()
  override val isRunning: Boolean get() = running.load()

  init {
    checkNotNull(scope.coroutineContext[Job]).invokeOnCompletion { admissions.close() }
  }

  override suspend fun start() = mutex.withLock {
    check(!closed) { "Torrent runtime is closed" }
    if (running.load()) return@withLock
    val listener = network.listen(PeerEndpoint("0.0.0.0", config.listenPort))
    port = listener.local.port
    running.store(true)
    accept(listener)
    // Some systems provide dual-stack sockets and reject a second bind on the same port.
    try { accept(network.listen(PeerEndpoint("::", port))) } catch (_: Exception) {
      currentCoroutineContext().let { if (!it.isActive) throw CancellationException() }
    }
  }

  private fun accept(listener: TorrentListener) = scope.launch {
    try {
      while (isActive) {
        val connection = listener.accept()
        launch(start = CoroutineStart.ATOMIC) {
          var handedOff = false
          try {
            val bytes = withTimeout(10_000) { connection.readExactly(68) }
            val hash = InfoHash.fromBytes(bytes.copyOfRange(28, 48))
            PeerWire.decodeHandshake(bytes, hash)
            // Replay the already bounded handshake through the ordinary peer worker.
            val replay = object : TorrentConnection by connection {
              var header: ByteArray? = bytes
              override suspend fun readExactly(size: Int): ByteArray {
                val first = header
                if (first == null) return connection.readExactly(size)
                require(size == first.size)
                header = null
                return first
              }
            }
            handedOff = mutex.withLock { sessions[hash.hex]?.accept(replay) == true }
          } catch (e: CancellationException) {
            throw e
          } catch (_: Exception) {
            // Malformed, unknown, or expired handshakes are isolated to this connection.
          } finally {
            if (!handedOff) connection.close()
          }
        }
      }
    } finally { listener.close() }
  }

  override fun close() {
    running.store(false)
    scope.cancel()
    network.close()
    http.close()
  }

  override suspend fun stop() {
    val active = mutex.withLock {
      if (closed) return
      closed = true
      running.store(false)
      sessions.values.toList().also { sessions.clear(); outputs.clear(); sessionLeases.clear() }
    }
    withContext(NonCancellable) {
      try {
        active.forEach { it.pause(); it.close() }
        nodes?.forEach { it.close() }
      } finally {
        scope.cancel()
        try { network.close() } finally {
          try { http.close() } finally {
            cache.close()
            checkNotNull(scope.coroutineContext[Job]).join()
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
    val metadata = cache.resolve(magnet.infoHash) {
      withTimeout(config.metadataTimeout) {
        if (privacy == TorrentDiscoveryPrivacy.TRACKER_ONLY) {
          return@withTimeout fetchTrackerOnly(magnet, restrictedTiers)
        }
        coroutineScope {
          val peers = Channel<PeerEndpoint>(256)
          val discovery = launch { discoverMagnet(magnet, peers) }
          val attempted = mutableSetOf<PeerEndpoint>()
          try {
            while (true) {
              val endpoint = peers.receive()
              if (!attempted.add(endpoint)) continue
              if (attempted.size > 4096) error("Metadata peer limit exceeded")
              try {
                return@coroutineScope TorrentMetadataExchange(network, config.maxMetadataBytes,
                  budget = exchangeBudgets.metadata).fetch(magnet.infoHash, endpoint)
              } catch (e: PrivateTorrentMagnetException) {
                throw e
              } catch (e: CancellationException) {
                if (!currentCoroutineContext().isActive) throw e
              } catch (_: Exception) {
                // A bad peer must not prevent trying the remaining discovery candidates.
              }
            }
            @Suppress("UNREACHABLE_CODE")
            error("No metadata peers")
          } finally { discovery.cancel(); peers.cancel() }
        }
      }
    }
    require(magnet.identity.matchesInfo(metadata.infoBytes)) { "Exact topic hash mismatch" }
    // A cache hit must not bypass this caller's pre-discovery privacy choice.
    if (metadata.isPrivate && privacy != TorrentDiscoveryPrivacy.TRACKER_ONLY) {
      throw PrivateTorrentMagnetException()
    }
    // Cache the immutable info dictionary, retaining this caller's tracker list.
    return TorrentMetadata.fromBencode(metainfoFromInfo(metadata.infoBytes,
      magnet.trackers.map { listOf(it) }), config.maxMetadataBytes)
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
            tiers.announce(TrackerAnnounce(magnet.infoHash, peerId, port, 0, 1,
              event = TrackerEvent.STARTED))
          } ?: continue
          contacted = true
          retrySeconds = maxOf(retrySeconds, result.intervalSeconds)
          for (endpoint in result.peers.distinct()) {
            check(++attempts <= 4096) { "Metadata peer limit exceeded" }
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
                tiers.announce(TrackerAnnounce(magnet.infoHash, peerId, port, 0, 1,
                  event = TrackerEvent.STOPPED))
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

  private suspend fun discoverMagnet(magnet: MagnetUri, output: SendChannel<PeerEndpoint>) =
    supervisorScope {
      launch {
        for (text in magnet.explicitPeers) {
          resolveEndpoint(text).forEach { output.send(it) }
        }
      }
      if (magnet.trackers.isNotEmpty()) launch {
        val tiers = TrackerTiers(magnet.trackers.map { listOf(it) }, tracker::announce)
        while (isActive) {
          val result = attempt {
            tiers.announce(TrackerAnnounce(magnet.infoHash, peerId, port, 0, 1,
              event = TrackerEvent.STARTED))
          }
          result?.peers?.forEach { output.send(it) }
          delay((result?.intervalSeconds ?: 30) * 1000)
        }
      }
      if (config.dhtEnabled) launch {
        while (isActive) {
          dhtPeers(magnet.infoHash, announce = false).forEach { output.send(it) }
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
    check(isRunning && !closed)
    check(sessions.size < config.maxActiveTorrents) { "Too many active torrents" }
    val hash = spec.metadata.infoHash.hex
    check(hash !in sessions) { "Torrent already has an active owner" }
    val lease = admissions.admit(spec, config)
    try {
      val requested = FileSystem.SYSTEM.canonicalize(".".toPath())
        .resolve(spec.outputPath).normalized()
      val store = TorrentPieceStore(spec.metadata, requested, spec.selected, spec.taskId,
        storageSlots = storageSlots)
      val output = store.outputPath.toPath()
      check(outputs.values.none { previous ->
        val path = previous.toPath()
        val left = path.segments.map { canonicalTorrentName(it).lowercase() }
        val right = output.segments.map { canonicalTorrentName(it).lowercase() }
        path.root.toString().equals(output.root.toString(), ignoreCase = true) &&
          (left.take(right.size) == right || right.take(left.size) == left)
      }) { "Torrent output overlaps another task" }
      val checkpoint = spec.resumeData?.let(TorrentCheckpoint::decode)
      val trackerState = TorrentBufferBudget(
        maxOf(1, trackerControlStateWeight().toInt()))
      val session = KotlinTorrentSession(store, network, budget, scope,
        connections = config.connectionsPerTorrent, uploadPolicy = config.effectiveUploadPolicy,
        checkpoint = checkpoint, peerId = peerId,
        discover = { peers, owner -> discover(spec, peers, owner, trackerState) },
        downloadThrottle = { downloadRate.acquire(it); spec.throttle(it) },
        uploadThrottle = { uploadRate.acquire(it) },
        trackerConfigurationBudget = exchangeBudgets.sessions,
        privacy = spec.privacy,
      )
      sessionLeases[hash] = lease
      sessions[hash] = session
      outputs[hash] = output.toString()
      session
    } catch (failure: Throwable) {
      sessions.remove(hash)
      outputs.remove(hash)
      sessionLeases.remove(hash)
      admissions.release(lease)
      throw failure
    }
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
      val discovery = TrackerDiscovery(metadata, peerId, port,
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
            val lease = exchangeBudgets.metadata.reserve(64 * 1024 * 17)
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
      spec.magnetUri?.let { uri -> launch {
        MagnetUri.parse(uri).explicitPeers.forEach { text ->
          resolveEndpoint(text).forEach { output.send(it) }
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
      dht().map { node -> async { attempt { node.peers(hash, if (announce) port else null) }
        ?: emptyList() } }.awaitAll().flatten().distinct()
    }

  private suspend fun dht(): List<DhtNode> = dhtMutex.withLock {
    nodes?.let { return@withLock it }
    val result = mutableListOf<DhtNode>()
    for (host in listOf("0.0.0.0", "::")) {
      val node = attempt { DhtNode(network.bindUdp(PeerEndpoint(host, 0)), scope,
        allowLocalAddresses = allowLocalDiscovery) } ?: continue
      node.start()
      result.add(node)
      scope.launch {
        val snapshot = config.stateDirectory?.toPath()?.resolve(
          if (':' in host) "dht6.nodes" else "dht4.nodes")
        val restored = snapshot?.let { path -> attempt {
          require((FileSystem.SYSTEM.metadata(path).size ?: Long.MAX_VALUE) <= 256 * 1024)
          DhtRoutingTable.restore(FileSystem.SYSTEM.read(path) { readByteArray() })
            .second.map { it.endpoint }
        } } ?: emptyList()
        val endpoints = config.dhtBootstrap.flatMap { attempt { resolveEndpoint(it) }
          ?: emptyList() }.filter { (':' in it.host) == (':' in host) }
        attempt { node.bootstrap((restored + endpoints).distinct().take(64)) }
        while (isActive) {
          if (snapshot != null) attempt {
            FileSystem.SYSTEM.createDirectories(checkNotNull(snapshot.parent))
            val temporary = snapshot.parent!! / "${snapshot.name}.tmp"
            val bytes = node.snapshot()
            FileSystem.SYSTEM.write(temporary) { write(bytes) }
            FileSystem.SYSTEM.atomicMove(temporary, snapshot)
          }
          delay(15 * 60_000)
          attempt { node.refresh() }
        }
      }
    }
    nodes = result
    result
  }

  override suspend fun removeTorrent(infoHash: String, deleteFiles: Boolean) {
    mutex.withLock {
      sessions[infoHash]?.close(deleteFiles)
      sessions.remove(infoHash)
      outputs.remove(infoHash)
      sessionLeases.remove(infoHash)?.let(admissions::release)
    }
  }

  override fun setDownloadRateLimit(bytesPerSecond: Long) = downloadRate.set(bytesPerSecond)
  override fun setUploadRateLimit(bytesPerSecond: Long) = uploadRate.set(bytesPerSecond)
  fun setConnections(value: Int) = network.set(value)
}

private suspend fun <T> attempt(block: suspend () -> T): T? = try {
  block()
} catch (e: CancellationException) {
  if (!currentCoroutineContext().isActive) throw e
  null
} catch (_: Exception) { null }

internal suspend fun resolveEndpoint(value: String): List<PeerEndpoint> {
  val port = value.substringAfterLast(':').toIntOrNull()
  require(port != null && port in 1..65535) { "Invalid peer endpoint" }
  val host = value.substringBeforeLast(':').removeSurrounding("[", "]")
  return resolveTorrentHost(host).map { PeerEndpoint(numericHost(it), port) }
}
