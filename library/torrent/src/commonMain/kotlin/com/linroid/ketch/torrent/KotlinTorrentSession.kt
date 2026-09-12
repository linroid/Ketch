package com.linroid.ketch.torrent

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.SendChannel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.concurrent.atomics.AtomicInt
import kotlin.concurrent.atomics.AtomicLong
import kotlin.concurrent.atomics.AtomicReference
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
  private val peerId: ByteArray = torrentRandomBytes(20),
  private val discover: suspend (SendChannel<PeerEndpoint>, KotlinTorrentSession) -> Unit,
  private val downloadThrottle: suspend (Int) -> Unit = {},
  private val uploadThrottle: suspend (Int) -> Unit = {},
  private val trackerConfigurationBudget: TorrentBufferBudget? = null,
  private val privacy: TorrentDiscoveryPrivacy = TorrentDiscoveryPrivacy.PUBLIC,
) : TorrentSession {
  init { require(connections in 1..512) }

  private val scope = CoroutineScope(parent.coroutineContext +
    SupervisorJob(parent.coroutineContext[Job]) + Dispatchers.Default)
  private val lifecycle = Mutex()
  private val incoming = Channel<TorrentConnection>(16, onUndeliveredElement = { it.close() })
  private val resets = Channel<CompletableDeferred<Unit>>(1)

  suspend fun trackerTiers(): List<List<String>> =
    store.currentTrackerConfiguration()?.tiers ?: store.metadata.trackerTiers

  private val trackerEdits = Semaphore(1)
  private val trackerOwner = AtomicReference<TrackerConfiguration.Owned?>(null)
  private val trackerWorkspace = AtomicReference<TorrentBufferBudget.Lease?>(null)

  init {
    checkNotNull(scope.coroutineContext[Job]).invokeOnCompletion {
      // Every edit/save is a child of this scope; none can still reference the committed override.
      store.discardTrackerConfiguration()
      trackerOwner.exchange(null)?.close()
      trackerWorkspace.exchange(null)?.close()
    }
  }

  suspend fun trackerConfiguration(): TrackerConfigurationSnapshot = lifecycle.withLock {
    if (!recovered) TrackerConfigurationSnapshot(
      checkpoint?.trackerConfiguration?.tiers ?: store.metadata.trackerTiers,
      checkpoint?.trackerRevision ?: 0)
    else store.trackerConfigurationSnapshot()
  }

  /** False means another edit is pending or configuration credit is unavailable. */
  suspend fun replaceTrackers(
    tiers: List<List<String>>,
    expectedRevision: Long? = null,
  ): Boolean {
    require(expectedRevision == null || expectedRevision >= 0)
    if (!trackerEdits.tryAcquire()) return false
    var proposal: TrackerConfiguration.Owned? = null
    var workspace: TorrentBufferBudget.Lease? = null
    try {
      val state = trackerConfigurationBudget ?: return false
      proposal = TrackerConfiguration.admit(tiers, state) ?: return false
      val candidate = proposal.configuration
      workspace = state.reserve(candidate.checkpointWorkspaceBytes) ?: return false
      val pending = scope.async {
        lifecycle.withLock {
          check(!closed) { "Torrent session is closed" }
          if (trackerCheckpointRecovered) {
            val actualRevision = store.trackerConfigurationSnapshot().revision
            if (expectedRevision != null && expectedRevision != actualRevision) {
              throw TrackerRevisionConflict(expectedRevision, actualRevision)
            }
            check(actualRevision < Long.MAX_VALUE) { "Tracker configuration revision exhausted" }
          }
          val restart = job?.isActive == true
          val previousState = _state.value
          try {
            stopLocked()
            currentCoroutineContext().ensureActive()
            recover()
            try {
              store.replaceTrackerConfiguration(candidate, received.load(), uploaded.load(),
                expectedRevision)
            } finally {
              withContext(NonCancellable) {
                // Cancellation at the I/O return boundary does not imply that rename rolled back.
                if (store.currentTrackerConfiguration() === candidate) {
                  trackerOwner.exchange(checkNotNull(proposal).transfer())?.close()
                  trackerWorkspace.exchange(checkNotNull(workspace))?.close()
                  workspace = null
                }
              }
            }
          } finally {
            if (restart && currentCoroutineContext()[Job]?.isActive == true) resumeLocked()
            else if (!restart) _state.value = previousState
          }
          true
        }
      }
      try { return pending.await() } finally {
        withContext(NonCancellable) { pending.cancelAndJoin() }
      }
    } finally {
      proposal?.close()
      workspace?.close()
      trackerEdits.release()
    }
  }

  private val trackerControl = AtomicReference<TrackerControl?>(null)
  private val _trackerStatus = MutableStateFlow<List<TrackerStatus>>(emptyList())
  val trackerStatus: StateFlow<List<TrackerStatus>> = _trackerStatus

  suspend fun reannounceTrackers(): Boolean = trackerControl.load()?.reannounce() ?: false

  fun attachTrackerControl(control: TrackerControl) {
    check(trackerControl.compareAndSet(null, control))
  }

  fun detachTrackerControl(control: TrackerControl) {
    check(trackerControl.compareAndSet(control, null))
  }

  fun updateTrackerStatus(status: List<TrackerStatus>) { _trackerStatus.value = status }

  private val trackerRestricted = store.metadata.isPrivate ||
    privacy == TorrentDiscoveryPrivacy.TRACKER_ONLY
  private val privateAdmission = Mutex()
  private var allowedPrivateHosts: Set<String> = emptySet()

  suspend fun trackerPeers(peers: List<PeerEndpoint>) {
    val hosts = peers.map { it.host }.toSet()
    privateAdmission.withLock { allowedPrivateHosts = hosts }
  }

  fun accept(connection: TorrentConnection): Boolean {
    if (_state.value != TorrentSessionState.DOWNLOADING &&
      _state.value != TorrentSessionState.SEEDING) return false
    if (!trackerRestricted) return incoming.trySend(connection).isSuccess
    if (!privateAdmission.tryLock()) return false
    try {
      if (connection.remote.host !in allowedPrivateHosts) return false
      return incoming.trySend(connection).isSuccess
    } finally { privateAdmission.unlock() }
  }

  suspend fun resetPeers() {
    // Admission and enqueue share the lock: every old-host enqueue precedes this revocation.
    privateAdmission.withLock { allowedPrivateHosts = emptySet() }
    val done = CompletableDeferred<Unit>()
    resets.send(done)
    done.await()
  }

  private val rate = TorrentRateLimiter()
  private val uploadRate = TorrentRateLimiter()
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
  private var filesDeleted = false
  private var recovered = false
  private var trackerCheckpointRecovered = false
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

  override suspend fun resume() = lifecycle.withLock { resumeLocked() }

  private fun resumeLocked() {
    check(!closed) { "Torrent session is closed" }
    scope.coroutineContext.ensureActive()
    if (job?.isActive == true) return
    _failure.value = null
    job = scope.launch {
      try {
        _state.value = TorrentSessionState.CHECKING_FILES
        recover()
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
            TorrentSwarm(store, network, budget, peerId = peerId,
              connections = { connectionLimit.load() }, uploadPolicy = uploadPolicy,
              trackerOnly = trackerRestricted,
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
                uploadRate.acquire(bytes)
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
            ).run(peers, incoming, resets)
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

  private suspend fun recover() {
    if (!recovered) {
      checkpoint?.let { store.restore(it) }
      recovered = true
    }
    store.initialize()
    if (trackerCheckpointRecovered) return
    val state = trackerConfigurationBudget ?: return
    store.withPersistedCheckpoint(state) { saved ->
      val current = store.trackerConfigurationSnapshot()
      if (saved.trackerRevision > current.revision) {
        val proposal = checkNotNull(TrackerConfiguration.admit(
          checkNotNull(saved.trackerConfiguration).tiers, state)) {
          "Recovered tracker configuration budget exhausted"
        }
        var workspace: TorrentBufferBudget.Lease? = null
        val candidate = proposal.configuration
        try {
          workspace = checkNotNull(state.reserve(candidate.checkpointWorkspaceBytes)) {
            "Recovered tracker checkpoint workspace exhausted"
          }
          try { store.adoptTrackerCheckpoint(saved, candidate) } finally {
            withContext(NonCancellable) {
              if (store.currentTrackerConfiguration() === candidate) {
                trackerOwner.exchange(proposal.transfer())?.close()
                trackerWorkspace.exchange(checkNotNull(workspace))?.close()
                workspace = null
                received.store(maxOf(received.load(), saved.receivedBytes))
                uploaded.store(maxOf(uploaded.load(), saved.uploadedBytes))
              }
            }
          }
        } finally {
          proposal.close()
          workspace?.close()
        }
      } else if (saved.trackerRevision == current.revision && current.revision > 0) {
        require(saved.trackerConfiguration?.tiers == current.tiers) {
          "Conflicting tracker configurations at the same revision"
        }
      }
      received.store(maxOf(received.load(), saved.receivedBytes))
      uploaded.store(maxOf(uploaded.load(), saved.uploadedBytes))
    }
    trackerCheckpointRecovered = true
  }

  private suspend fun stopLocked() = withContext(NonCancellable) {
    job?.cancelAndJoin()
    job = null
    privateAdmission.withLock { allowedPrivateHosts = emptySet() }
    while (true) (incoming.tryReceive().getOrNull() ?: break).close()
    _state.value = TorrentSessionState.PAUSED
    currentSpeed.store(0)
  }

  private suspend fun canSaveCheckpoint(): Boolean = store.isInitialized() &&
    (trackerConfigurationBudget == null || trackerCheckpointRecovered)

  override suspend fun pause() {
    if (scope.coroutineContext[Job]?.isActive != true) {
      lifecycle.withLock { if (!closed) stopLocked() }
      return
    }
    val pending = scope.async {
      lifecycle.withLock {
        if (!closed) {
          stopLocked()
          if (canSaveCheckpoint()) store.persistCheckpoint(received.load(), uploaded.load())
        }
      }
    }
    try { pending.await() } finally {
      withContext(NonCancellable) { pending.cancelAndJoin() }
    }
  }

  override suspend fun saveResumeData(): ByteArray? {
    if (scope.coroutineContext[Job]?.isActive != true) return null
    val pending = scope.async {
      lifecycle.withLock {
        if (closed || !canSaveCheckpoint()) null
        else store.persistCheckpoint(received.load(), uploaded.load())
      }
    }
    try { return pending.await() } finally {
      withContext(NonCancellable) { pending.cancelAndJoin() }
    }
  }

  override fun setDownloadRateLimit(bytesPerSecond: Long) = rate.set(bytesPerSecond)

  fun setUploadRateLimit(value: Long) = uploadRate.set(value)

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
    if (!closed) {
      job?.cancelAndJoin()
      job = null
      checkNotNull(scope.coroutineContext[Job]).cancelAndJoin()
      incoming.cancel()
      resets.cancel()
      closed = true
      _state.value = TorrentSessionState.STOPPED
    }
    if (deleteFiles && !filesDeleted) {
      if (!recovered) checkpoint?.let { store.restore(it) }
      store.recoverOwnership()
      store.cleanup()
      filesDeleted = true
    }
  }
}
