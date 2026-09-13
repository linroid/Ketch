package com.linroid.ketch.torrent

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.SendChannel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
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
  private val discover: suspend (SendChannel<PeerEndpoint>) -> Unit,
) {
  private val lifecycle = Mutex()
  private var job: Job? = null
  private var closed = false
  private val mutableState = MutableStateFlow(TorrentSessionState.PAUSED)
  private val mutableProgress = MutableStateFlow(0L)
  private val mutableFailure = MutableStateFlow<Throwable?>(null)
  val state: StateFlow<TorrentSessionState> get() = mutableState
  val verifiedBytes: StateFlow<Long> get() = mutableProgress
  val failure: StateFlow<Throwable?> get() = mutableFailure

  suspend fun resume() = lifecycle.withLock {
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

  suspend fun pause() = lifecycle.withLock {
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
    val discovery = launch {
      try { discover(endpoints) } catch (error: Throwable) {
        endpoints.close(error)
      } finally { endpoints.close() }
    }
    try {
      PeerV2Pool.run(memory, maxPeers = maxPeers) { pool ->
        PeerV2Dialer.run(endpoints, memory, parallelism = minOf(4, maxPeers),
          connect = { remote ->
            PeerV2Connector.connect(network, remote, document, layout, peerId, buffers, memory)
          },
        ) { dialer ->
          TorrentV2CommitWorker.run(store) { worker ->
            TorrentV2SessionLoop.download(layout, selected, store, pool, worker, buffers, memory,
              maxPeers = maxPeers, connections = dialer.connections, onProgress = ::updateProgress)
          }
        }
      }
    } finally {
      withContext(NonCancellable) {
        try { discovery.cancelAndJoin() } finally { endpoints.cancel() }
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
     * The caller admits document/layout/storage indexes before entry and retains that admission
     * until return. This download lifecycle does not yet seed or expose the public engine API.
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
      discover: suspend (SendChannel<PeerEndpoint>) -> Unit,
      body: suspend (TorrentV2DownloadSession) -> T,
    ): T = coroutineScope {
      require(layout.infoHash == document.info.hash && peerId.size == 20)
      require(maxPeers in 1..500)
      store.requireBinding(document.identity, selected, layout)
      val lease = checkNotNull(state.reserve(maxPeers * 512 + 4096)) {
        "Session lifecycle state budget exhausted"
      }
      val owner = SupervisorJob(coroutineContext[Job])
      val session = TorrentV2DownloadSession(CoroutineScope(coroutineContext + owner), document,
        layout, selected.toSet(), store, network, peerId, buffers, state, maxPeers, discover)
      try { body(session) } finally {
        withContext(NonCancellable) {
          try { session.shutdown() } finally { lease.close() }
        }
      }
    }
  }
}
