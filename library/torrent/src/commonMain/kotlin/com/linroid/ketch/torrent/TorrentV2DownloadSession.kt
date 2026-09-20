package com.linroid.ketch.torrent

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.SendChannel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okio.ByteString

/** Full-metainfo v2 download owner. Network policy and metadata admission belong to the engine. */
internal class TorrentV2DownloadSession private constructor(
  private val scope: CoroutineScope,
  private val document: TorrentV2Document,
  private val layout: TorrentContentLayout,
  private val selected: Set<String>,
  private val store: TorrentV2PieceStore,
  private val network: TorrentNetwork,
  private val peerId: ByteString,
  private val buffers: TorrentBufferBudget,
  private val memory: TorrentBufferBudget,
  private val maxPeers: Int,
  initialConnections: Int,
  private val globalRate: TorrentRateLimiter,
  private val throttle: suspend (Int) -> Unit,
  private val discover: suspend (SendChannel<PeerEndpoint>, suspend () -> Unit) -> Unit,
) : TorrentSession {
  override val infoHash: String get() = document.info.hash.hex
  override val downloadedBytes: StateFlow<Long> get() = mutableProgress
  override val totalBytes: Long = TorrentOutputMapping.from(document).files
    .filter { selected.isEmpty() || it.id in selected }
    .sumOf { document.info.files[it.v2Index].length }
  override val downloadSpeed: Long get() = 0

  override fun setFilePriorities(priorities: Map<Int, Int>) {
    error("File selection is fixed for this session")
  }

  override suspend fun saveResumeData(): ByteArray? = store.resumeData()

  suspend fun fileProgress(): Map<String, Long> = store.progress()
  suspend fun receivedBytes(): Long = store.receivedBytes()

  private val connectionLimit = MutableStateFlow(initialConnections)

  fun setConnections(value: Int) {
    require(value in 1..500)
    connectionLimit.value = minOf(value, maxPeers)
  }

  private val lifecycle = Mutex()
  private val downloadRate = TorrentRateLimiter()
  private var job: Job? = null
  private var closed = false
  private val mutableState = MutableStateFlow(TorrentSessionState.PAUSED)
  private val mutableProgress = MutableStateFlow(0L)
  private val mutableFailure = MutableStateFlow<Throwable?>(null)
  override val state: StateFlow<TorrentSessionState> get() = mutableState
  val verifiedBytes: StateFlow<Long> get() = mutableProgress
  val failure: StateFlow<Throwable?> get() = mutableFailure

  override fun setDownloadRateLimit(bytesPerSecond: Long) = downloadRate.set(bytesPerSecond)

  override suspend fun resume() = lifecycle.withLock {
    check(!closed) { "Torrent session is closed" }
    currentCoroutineContext().ensureActive()
    if (job?.isActive == true && (mutableState.value == TorrentSessionState.CHECKING_FILES ||
        mutableState.value == TorrentSessionState.DOWNLOADING)) return@withLock
    job?.join()
    checkNotNull(scope.coroutineContext[Job]).ensureActive()
    mutableProgress.value = 0
    mutableFailure.value = null
    mutableState.value = TorrentSessionState.CHECKING_FILES
    job = scope.launch {
      try {
        store.initialize()
        // Persisted bits and earlier live progress cannot authorize bytes changed while paused.
        store.recheck()
        updateProgress()
        if (!store.completed()) {
          mutableState.value = TorrentSessionState.DOWNLOADING
          transfer()
        }
        currentCoroutineContext().ensureActive()
        mutableState.value = TorrentSessionState.FINISHED
      } catch (error: CancellationException) {
        if (!checkNotNull(currentCoroutineContext()[Job]).isActive) throw error
        mutableFailure.value = error
        mutableState.value = TorrentSessionState.STOPPED
      } catch (error: Exception) {
        mutableFailure.value = error
        mutableState.value = TorrentSessionState.STOPPED
      }
    }
  }

  override suspend fun pause() = lifecycle.withLock {
    check(!closed) { "Torrent session is closed" }
    withContext(NonCancellable) {
      stop()
      mutableState.value = TorrentSessionState.PAUSED
    }
  }

  private suspend fun stop() = withContext(NonCancellable) {
    job?.cancelAndJoin()
    job = null
    updateProgress()
  }

  private suspend fun updateProgress() { mutableProgress.value = store.progress().values.sum() }

  private suspend fun transfer() = coroutineScope {
    val endpoints = Channel<PeerEndpoint>(maxPeers)
    val credits = MutableStateFlow(0)
    var admission: Deferred<Unit>? = null
    suspend fun admitted(bytes: Int): Boolean {
      admission?.takeIf { it.isCompleted }?.await()
      if (credits.value < bytes && admission?.isActive != true) {
        val missing = bytes - credits.value
        admission = async(start = CoroutineStart.UNDISPATCHED) {
          // Charge each requested block before it can arrive, including retries/corrupt payload.
          // A pending limiter must not block peer control traffic, progress, or checkpoints.
          throttle(missing)
          credits.update { it + missing }
        }
      }
      admission?.takeIf { it.isCompleted }?.await()
      return credits.value >= bytes
    }
    val resets = Channel<CompletableDeferred<Unit>>(1)
    val acceptingResets = MutableStateFlow(true)
    val discovery = launch {
      try { discover(endpoints) {
        if (acceptingResets.value) {
          val joined = CompletableDeferred<Unit>()
          resets.send(joined)
          joined.await()
        }
      } } catch (error: Throwable) {
        endpoints.close(error)
      } finally { endpoints.close() }
    }
    val recentMutex = Mutex()
    val recentEndpoints = linkedSetOf<PeerEndpoint>()
    val limits = Channel<Int>(Channel.CONFLATED)
    val watchLimits = launch { connectionLimit.collect { limits.send(it) } }
    var activeLimit = connectionLimit.value
    suspend fun download(limit: Int) {
      PeerV2Pool.run(memory, maxPeers = limit) { pool ->
        PeerV2Dialer.run(endpoints, memory, parallelism = minOf(4, limit),
          connect = { remote ->
            recentMutex.withLock {
              recentEndpoints += remote
              if (recentEndpoints.size > maxPeers) recentEndpoints.remove(recentEndpoints.first())
            }
            PeerV2Connector.connect(network, remote, document, layout, peerId, buffers, memory)
          },
        ) { dialer ->
          TorrentV2CommitWorker.run(store) { worker ->
            TorrentV2SessionLoop.download(layout, selected, store, pool, worker, buffers, memory,
              maxPeers = limit, connections = dialer.connections, onProgress = ::updateProgress,
              requestDelay = { bytes, admit ->
                if (!admitted(bytes)) 50L else globalRate.requestDelay(bytes, downloadRate) {
                  admit().also { sent -> if (sent) credits.update { it - bytes } }
                }
              })
          }
        }
      }
    }
    var download = async { download(activeLimit) }
    try {
      var finished = false
      while (!finished) {
        select<Unit> {
          download.onAwait { finished = true }
          limits.onReceive { value ->
            if (value != activeLimit) {
              download.cancelAndJoin()
              activeLimit = value
              recentMutex.withLock { recentEndpoints.forEach { endpoints.trySend(it) } }
              download = async { download(activeLimit) }
            }
          }
          resets.onReceive { joined ->
            download.cancelAndJoin()
            recentMutex.withLock { recentEndpoints.clear() }
            while (endpoints.tryReceive().isSuccess) { /* Discard old tracker endpoints. */ }
            download = async { download(activeLimit) }
            joined.complete(Unit)
          }
        }
      }
    } finally {
      acceptingResets.value = false
      withContext(NonCancellable) {
        try {
          download.cancelAndJoin()
          admission?.cancelAndJoin()
          discovery.cancelAndJoin()
          watchLimits.cancelAndJoin()
        } finally {
          endpoints.cancel()
          resets.cancel()
          limits.cancel()
        }
      }
    }
  }

  private suspend fun shutdown() = lifecycle.withLock {
    closed = true
    try { stop() } finally {
      try { checkNotNull(scope.coroutineContext[Job]).cancelAndJoin() } finally {
        try { store.close() } finally { mutableState.value = TorrentSessionState.STOPPED }
      }
    }
  }

  companion object {
    /**
     * Owns the store until all commands, discovery, peers and provider writes have joined.
     * The caller admits document/layout/storage indexes and any checkpoint before entry, retaining
     * that admission until return. Downloads use outgoing peers; upload ownership remains separate.
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
      discoverWithReset: (suspend (SendChannel<PeerEndpoint>, suspend () -> Unit) -> Unit)? = null,
      body: suspend (TorrentV2DownloadSession) -> T,
    ): T = coroutineScope {
      require(layout.infoHash == document.info.hash && peerId.size == 20)
      require(maxPeers in 1..500 && initialConnections in 1..maxPeers)
      store.requireBinding(document.identity, selected, layout)
      val lease = checkNotNull(state.reserve(maxPeers * 512 + 4096)) {
        "Session lifecycle state budget exhausted"
      }
      val owner = SupervisorJob(coroutineContext[Job])
      val session = TorrentV2DownloadSession(CoroutineScope(coroutineContext + owner), document,
        layout, selected.toSet(), store, network, peerId, buffers, state, maxPeers,
        initialConnections, globalRate, throttle, discoverWithReset ?: { endpoints, _ -> discover(endpoints) })
      try {
        // Restore before exposing the owner. Resume always rechecks the adopted payloads.
        if (checkpoint != null) store.restore(checkpoint)
        body(session)
      } finally {
        withContext(NonCancellable) {
          try { session.shutdown() } finally { lease.close() }
        }
      }
    }
  }
}
