package com.linroid.ketch.torrent

import com.linroid.ketch.api.log.KetchLogger
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
import kotlinx.coroutines.selects.select
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
  /** The part of [budget] pieces read back for peers may hold, every session's together. */
  private val uploadBudget: TorrentBufferBudget = budget,
  /** Read live: peers follow a change within a tick, and a seed finishes once it leaves SEED. */
  private val uploadPolicy: () -> TorrentUploadPolicy = { TorrentUploadPolicy.DISABLED },
  private val checkpoint: TorrentCheckpoint? = null,
  private val peerId: ByteArray = torrentRandomBytes(20),
  private val discover: suspend (SendChannel<PeerEndpoint>, KotlinTorrentSession) -> Unit,
  private val downloadThrottle: suspend (Int) -> Unit = {},
  /** The engine's upload bucket, shared by every torrent and applied before this one's. */
  private val engineUploadRate: TorrentRateLimiter = TorrentRateLimiter(),
  private val trackerConfigurationBudget: TorrentBufferBudget? = null,
  private val privacy: TorrentDiscoveryPrivacy = TorrentDiscoveryPrivacy.PUBLIC,
  /** Test hook: lets peer exchange carry loopback and private addresses. */
  private val allowLocalPeers: Boolean = false,
  /** Our listen port as a peer at that address reaches it (BEP 10 `p`); 0 leaves it out. */
  private val listenPortFor: (PeerEndpoint) -> Int = { 0 },
  /**
   * Only seed: when the file check finds the selection incomplete, stop with
   * [IncompleteSeedException] before any discovery instead of downloading.
   */
  private val seedOnly: Boolean = false,
  /** The swarm's clock, for its peer deadlines; tests pass virtual time. */
  private val nowMs: () -> Long = monotonicClock(),
) : TorrentSession {
  init { require(connections in 1..512) }

  private val log = KetchLogger("TorrentSession")
  private val label = "taskId=${store.taskId} (${logHash(store.metadata.infoHash.hex)})"
  private val scope = CoroutineScope(parent.coroutineContext +
    SupervisorJob(parent.coroutineContext[Job]) + Dispatchers.Default)
  private val lifecycle = Mutex()
  private val incoming = Channel<TorrentConnection>(16, onUndeliveredElement = { it.close() })
  private val resets = Channel<CompletableDeferred<Unit>>(1)
  /** One selection change at a time; the swarm completes each once it follows the store. */
  private val selectionSignals = Channel<CompletableDeferred<Unit>>(1)

  suspend fun trackerTiers(): List<List<String>> =
    store.currentTrackerConfiguration()?.tiers ?: store.metadata.trackerTiers

  private val trackerEdits = Semaphore(1)
  private val trackerOwner = AtomicReference<TrackerConfiguration.Owned?>(null)
  private val trackerWorkspace = AtomicReference<TorrentBufferBudget.Lease?>(null)

  init {
    checkNotNull(scope.coroutineContext[Job]).invokeOnCompletion { releaseTrackerState() }
  }

  /** Every edit/save is a child of [scope]; none can still reference the committed override. */
  private fun releaseTrackerState() {
    store.discardTrackerConfiguration()
    trackerOwner.exchange(null)?.close()
    trackerWorkspace.exchange(null)?.close()
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

  suspend fun scrapeTracker(): Boolean = trackerControl.load()?.scrape() ?: false

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
  private val uploadSampleTime = AtomicLong(clock())
  private val uploadSampleBytes = AtomicLong(uploaded.load())
  private val currentUploadSpeed = AtomicLong(0)
  private val lastUpload = AtomicLong(0)
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
  val uploadSpeed: Long get() = if (clock() - lastUpload.load() > 2000) 0
    else currentUploadSpeed.load()
  override val selectedFileIds: Set<String>
    get() = store.selectedIndices.mapTo(LinkedHashSet()) { it.toString() }

  /** Whether every file is selected; trackers hear `completed` only for a whole torrent. */
  fun selectsAllFiles(): Boolean = store.selectsAllFiles()

  override suspend fun payloadCounters(): TorrentPayloadCounters =
    TorrentPayloadCounters(received.load(), uploaded.load(), uploadSpeed)

  private fun countUploaded(bytes: Int) {
    val total = uploaded.addAndFetch(bytes.toLong())
    val now = clock()
    lastUpload.store(now)
    val since = uploadSampleTime.load()
    if (now - since >= 1000 && uploadSampleTime.compareAndSet(since, now)) {
      val base = uploadSampleBytes.exchange(total)
      currentUploadSpeed.store(((total - base) * 1000.0 / (now - since)).toLong())
    }
  }

  override suspend fun changeSelection(fileIds: Set<String>): Boolean {
    val indices = fileIds.mapTo(LinkedHashSet()) { id ->
      requireNotNull(id.toIntOrNull()?.takeIf { it in store.metadata.files.indices }) {
        "Unknown file id"
      }
    }
    require(indices.isNotEmpty()) { "Select at least one file" }
    check(scope.coroutineContext[Job]?.isActive == true) { "Torrent session is closed" }
    // Applied in the session's scope, so a canceled caller never leaves a check stopped.
    val pending = scope.async {
      lifecycle.withLock {
        check(!closed) { "Torrent session is closed" }
        val running = job?.takeIf { it.isActive }
        when {
          // No peer is connected while the files are checked: check again with the change.
          running != null && _state.value == TorrentSessionState.CHECKING_FILES -> {
            stopLocked()
            try {
              store.changeSelection(indices)
            } finally {
              if (currentCoroutineContext()[Job]?.isActive == true) resumeLocked()
            }
            Applied.Restarted
          }
          running != null -> {
            store.changeSelection(indices)
            _downloadedBytes.value = store.progress().sum()
            Applied.Running(running)
          }
          else -> {
            store.changeSelection(indices)
            _downloadedBytes.value = store.progress().sum()
            Applied.Saved
          }
        }
      }
    }
    val applied = try { pending.await() } finally {
      withContext(NonCancellable) { pending.cancelAndJoin() }
    }
    log.i {
      "Torrent $label selection: files=${indices.size}/${store.metadata.files.size}, " +
        "wanted=${store.totalSelectedBytes}"
    }
    val swarm = when (applied) {
      Applied.Restarted -> return true
      Applied.Saved -> return false
      is Applied.Running -> applied.job
    }
    // No lock is held while the swarm takes the change; a swarm that ends first did not.
    val done = CompletableDeferred<Unit>()
    val sent = select {
      selectionSignals.onSend(done) { true }
      swarm.onJoin { false }
    }
    if (!sent) return false
    return select {
      done.onAwait { true }
      swarm.onJoin { false }
    }
  }

  private sealed interface Applied {
    data object Restarted : Applied
    data object Saved : Applied
    class Running(val job: Job) : Applied
  }

  suspend fun verifiedPieces(): BooleanArray = store.verifiedPieces()
  suspend fun fileProgress(): LongArray = store.progress()

  override suspend fun resume() = lifecycle.withLock { resumeLocked() }

  private fun resumeLocked() {
    check(!closed) { "Torrent session is closed" }
    scope.coroutineContext.ensureActive()
    if (job?.isActive == true) return
    _failure.value = null
    // A change sent to an earlier swarm was saved in the store, which this run reads.
    while (selectionSignals.tryReceive().isSuccess) { }
    job = scope.launch {
      try {
        _state.value = TorrentSessionState.CHECKING_FILES
        log.i { "Checking files for $label" }
        recover()
        val verified = store.recheck()
        _downloadedBytes.value = store.progress().sum()
        log.i {
          "Checked files for $label: ${verified.count { it }}/${verified.size} pieces verified, " +
            "${_downloadedBytes.value}/${store.totalSelectedBytes} bytes"
        }
        if (seedOnly && !store.completed()) {
          // No discovery or peer: seeding only serves a completed selection.
          log.i { "Completed torrent $label changed on disk; not seeding" }
          _failure.value = IncompleteSeedException()
          _state.value = TorrentSessionState.STOPPED
          return@launch
        }
        if (store.completed() && uploadPolicy() != TorrentUploadPolicy.SEED_AFTER_COMPLETION &&
          store.finishIfComplete()) {
          store.persistCheckpoint(received.load(), uploaded.load())
          _state.value = TorrentSessionState.FINISHED
          log.i { "Torrent $label is already complete" }
          return@launch
        }
        speedMutex.withLock {
          sampleTime = clock()
          sampleBytes = received.load()
          currentSpeed.store(0)
        }
        uploadSampleTime.store(clock())
        uploadSampleBytes.store(uploaded.load())
        currentUploadSpeed.store(0)
        _state.value = TorrentSessionState.DOWNLOADING
        log.i {
          "Downloading $label with up to ${connectionLimit.load()} peer(s), " +
            "upload=${uploadPolicy()}, privacy=$privacy"
        }
        coroutineScope {
          val peers = Channel<PeerEndpoint>(256)
          val discovery = launch {
            try { discover(peers, this@KotlinTorrentSession) } finally { peers.close() }
          }
          try {
            TorrentSwarm(store, network, budget, peerId = peerId,
              connections = { connectionLimit.load() }, uploadBudget = uploadBudget,
              uploadPolicy = uploadPolicy,
              allowLocalPeers = allowLocalPeers, trackerOnly = trackerRestricted,
              listenPortFor = listenPortFor,
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
              uploadRate = engineUploadRate, sessionUploadRate = uploadRate,
              onUploaded = ::countUploaded,
              onProgress = { _downloadedBytes.value = it },
              onCompleted = {
                store.persistCheckpoint(received.load(), uploaded.load())
                _state.value = if (uploadPolicy() == TorrentUploadPolicy.SEED_AFTER_COMPLETION) {
                  TorrentSessionState.SEEDING
                } else TorrentSessionState.FINISHED
                log.i {
                  "Torrent $label completed: received=${received.load()}, " +
                    "uploaded=${uploaded.load()}, state=${_state.value}"
                }
              },
              onIncomplete = {
                _state.value = TorrentSessionState.DOWNLOADING
                log.i { "Torrent $label is downloading again for its selection" }
              },
              nowMs = nowMs,
              logLabel = label,
            ).run(peers, incoming, resets, selectionSignals)
            // A swarm returns while seeding only once the policy stops seeding.
            if (_state.value == TorrentSessionState.SEEDING) {
              store.persistCheckpoint(received.load(), uploaded.load())
              _state.value = TorrentSessionState.FINISHED
              log.i { "Torrent $label stopped seeding: upload policy changed" }
            }
          } finally {
            withContext(NonCancellable) { discovery.cancelAndJoin(); peers.cancel() }
          }
        }
      } catch (e: CancellationException) {
        throw e
      } catch (e: Exception) {
        log.w(e) { "Torrent $label stopped: ${e.describeWithoutUrls()}" }
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

  override fun setUploadRateLimit(bytesPerSecond: Long) = uploadRate.set(bytesPerSecond)

  fun setConnections(value: Int) {
    require(value in 1..512)
    if (connectionLimit.exchange(value) != value) log.d { "Peer limit for $label: $value" }
  }

  suspend fun close(deleteFiles: Boolean = false) = lifecycle.withLock {
    if (!closed) {
      job?.cancelAndJoin()
      job = null
      checkNotNull(scope.coroutineContext[Job]).cancelAndJoin()
      // join() can return before the completion handler has run on the completing thread.
      releaseTrackerState()
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
