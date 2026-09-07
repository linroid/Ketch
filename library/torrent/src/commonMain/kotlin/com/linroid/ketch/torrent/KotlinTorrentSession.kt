package com.linroid.ketch.torrent

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.SendChannel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.concurrent.atomics.AtomicInt
import kotlin.concurrent.atomics.AtomicLong
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.coroutines.cancellation.CancellationException

/** One task owns this session, its lifecycle job, output, and checkpoint. Network/runtime are borrowed. */
@OptIn(ExperimentalAtomicApi::class)
internal class KotlinTorrentSession(
  private val store: TorrentPieceStore,
  private val network: TorrentNetwork,
  private val budget: TorrentBufferBudget,
  parent: CoroutineScope,
  connections: Int = 20,
  private val uploadPolicy: TorrentUploadPolicy = TorrentUploadPolicy.DISABLED,
  private val checkpoint: TorrentCheckpoint? = null,
  private val discover: suspend (SendChannel<PeerEndpoint>, KotlinTorrentSession) -> Unit,
  private val downloadThrottle: suspend (Int) -> Unit = {},
  private val uploadThrottle: suspend (Int) -> Unit = {},
) : TorrentSession {
  init { require(connections in 1..512) }

  private val scope = CoroutineScope(parent.coroutineContext +
    SupervisorJob(parent.coroutineContext[Job]) + Dispatchers.Default)
  private val lifecycle = Mutex()
  private val rate = TorrentRateLimiter()
  private val connectionLimit = AtomicInt(connections)
  private val received = AtomicLong(checkpoint?.receivedBytes ?: 0)
  private val uploaded = AtomicLong(checkpoint?.uploadedBytes ?: 0)
  private val clock = monotonicClock()
  private val speedMutex = Mutex()
  private var sampleTime = clock()
  private var sampleBytes = 0L
  private val currentSpeed = AtomicLong(0)
  private val lastPayload = AtomicLong(0)
  private var job: Job? = null
  private var closed = false
  private var recovered = false
  private val _state = MutableStateFlow(TorrentSessionState.PAUSED)
  private val _downloadedBytes = MutableStateFlow(0L)
  private val _failure = MutableStateFlow<Throwable?>(null)

  override val infoHash: String get() = store.metadata.infoHash.hex
  override val totalBytes: Long get() = store.totalSelectedBytes
  override val downloadedBytes: StateFlow<Long> get() = _downloadedBytes
  override val state: StateFlow<TorrentSessionState> get() = _state
  val failure: StateFlow<Throwable?> get() = _failure
  val receivedBytes: Long get() = received.load()
  val uploadedBytes: Long get() = uploaded.load()
  override val downloadSpeed: Long get() = if (clock() - lastPayload.load() > 2000) 0
    else currentSpeed.load()

  suspend fun verifiedPieces(): BooleanArray = store.verifiedPieces()
  suspend fun fileProgress(): LongArray = store.progress()

  override suspend fun resume() = lifecycle.withLock {
    check(!closed) { "Torrent session is closed" }
    scope.coroutineContext.ensureActive()
    if (job?.isActive == true) return@withLock
    _failure.value = null
    job = scope.launch {
      try {
        _state.value = TorrentSessionState.CHECKING_FILES
        if (!recovered) {
          checkpoint?.let { store.restore(it) }
          recovered = true
        }
        store.initialize()
        store.recheck()
        _downloadedBytes.value = store.progress().sum()
        if (store.completed() && uploadPolicy != TorrentUploadPolicy.SEED_AFTER_COMPLETION) {
          store.finish()
          store.persistCheckpoint(received.load(), uploaded.load())
          _state.value = TorrentSessionState.FINISHED
          return@launch
        }
        speedMutex.withLock {
          sampleTime = clock()
          sampleBytes = received.load()
          currentSpeed.store(0)
        }
        _state.value = TorrentSessionState.DOWNLOADING
        coroutineScope {
          val peers = Channel<PeerEndpoint>(256)
          val discovery = launch {
            try { discover(peers, this@KotlinTorrentSession) } finally { peers.close() }
          }
          try {
            TorrentSwarm(store, network, budget,
              connections = { connectionLimit.load() }, uploadPolicy = uploadPolicy,
              downloadPayload = { bytes ->
                val total = received.fetchAndAdd(bytes.toLong()) + bytes
                val now = clock()
                lastPayload.store(now)
                speedMutex.withLock {
                  if (now - sampleTime >= 1000) {
                    currentSpeed.store(((total - sampleBytes) * 1000.0 / (now - sampleTime)).toLong())
                    sampleTime = now
                    sampleBytes = total
                  }
                }
                rate.acquire(bytes)
                downloadThrottle(bytes)
              },
              uploadPayload = { bytes ->
                uploadThrottle(bytes)
                uploaded.fetchAndAdd(bytes.toLong())
              },
              onProgress = { _downloadedBytes.value = it },
              onCompleted = {
                store.persistCheckpoint(received.load(), uploaded.load())
                _state.value = if (uploadPolicy == TorrentUploadPolicy.SEED_AFTER_COMPLETION) {
                  TorrentSessionState.SEEDING
                } else TorrentSessionState.FINISHED
              },
            ).run(peers)
          } finally {
            withContext(NonCancellable) { discovery.cancelAndJoin(); peers.cancel() }
          }
        }
      } catch (e: CancellationException) {
        throw e
      } catch (e: Exception) {
        _failure.value = e
        _state.value = TorrentSessionState.STOPPED
      }
    }
  }

  override suspend fun pause() = lifecycle.withLock {
    if (closed) return@withLock
    job?.cancelAndJoin()
    job = null
    _state.value = TorrentSessionState.PAUSED
    currentSpeed.store(0)
    if (store.isInitialized()) store.persistCheckpoint(received.load(), uploaded.load())
  }

  override suspend fun saveResumeData(): ByteArray? = if (store.isInitialized()) {
    store.persistCheckpoint(received.load(), uploaded.load())
  } else null

  override fun setDownloadRateLimit(bytesPerSecond: Long) = rate.set(bytesPerSecond)

  fun setConnections(value: Int) {
    require(value in 1..512)
    connectionLimit.store(value)
  }

  override fun setFilePriorities(priorities: Map<Int, Int>) {
    require(priorities.values.all { it in 0..7 })
    require(priorities.filterValues { it > 0 }.keys == store.selectedIndices) {
      "Change file selection by creating a new task"
    }
  }

  suspend fun close(deleteFiles: Boolean = false) = lifecycle.withLock {
    if (closed) return@withLock
    closed = true
    job?.cancelAndJoin()
    job = null
    scope.cancel()
    _state.value = TorrentSessionState.STOPPED
    if (deleteFiles) {
      if (!recovered) checkpoint?.let { store.restore(it) }
      store.recoverOwnership()
      store.cleanup()
    }
  }
}
