package com.linroid.ketch.torrent

import com.linroid.ketch.api.DownloadConfig
import com.linroid.ketch.api.FileSelectionMode
import com.linroid.ketch.api.KetchError
import com.linroid.ketch.api.KetchFeatures
import com.linroid.ketch.api.ResolveInput
import com.linroid.ketch.api.ResolvedSource
import com.linroid.ketch.api.SourceFile
import com.linroid.ketch.api.log.KetchLogger
import com.linroid.ketch.api.log.describeCauses
import com.linroid.ketch.api.log.redactUrl
import com.linroid.ketch.core.engine.DownloadContext
import com.linroid.ketch.core.engine.DownloadSource
import com.linroid.ketch.core.engine.HttpEngine
import com.linroid.ketch.core.engine.SelectionPlan
import com.linroid.ketch.core.engine.SelectionRequest
import com.linroid.ketch.core.engine.SourceExtension
import com.linroid.ketch.core.engine.SourceHost
import com.linroid.ketch.core.engine.SourceState
import com.linroid.ketch.core.engine.canHandle
import com.linroid.ketch.core.engine.decodeState
import com.linroid.ketch.core.engine.encodeState
import com.linroid.ketch.core.engine.unknownFilesMessage
import com.linroid.ketch.torrent.api.TorrentActivity
import com.linroid.ketch.torrent.api.TorrentCapability
import com.linroid.ketch.torrent.api.TorrentReachability
import com.linroid.ketch.torrent.api.TorrentFeatures
import com.linroid.ketch.torrent.control.LiveTorrent
import com.linroid.ketch.torrent.control.SeedingOutcome
import com.linroid.ketch.torrent.control.SeedingTask
import com.linroid.ketch.torrent.control.TorrentControlSource
import com.linroid.ketch.torrent.control.TorrentControls
import com.linroid.ketch.torrent.crypto.sha256Digest
import com.linroid.ketch.torrent.engine.IncompleteSeedException
import com.linroid.ketch.torrent.engine.KotlinTorrentEngine
import com.linroid.ketch.torrent.engine.MAGNET_LOOKUP_TIME
import com.linroid.ketch.torrent.engine.KotlinTorrentSession
import com.linroid.ketch.torrent.engine.TorrentActiveSlots
import com.linroid.ketch.torrent.engine.TorrentBufferBudget
import com.linroid.ketch.torrent.engine.TorrentPayloadCounters
import com.linroid.ketch.torrent.engine.TorrentEngine
import com.linroid.ketch.torrent.engine.TorrentSession
import com.linroid.ketch.torrent.engine.TorrentSessionRegistry
import com.linroid.ketch.torrent.engine.TorrentSessionState
import com.linroid.ketch.torrent.engine.TorrentTaskSpec
import com.linroid.ketch.torrent.engine.TorrentV2TaskSpec
import com.linroid.ketch.torrent.lsd.openLsdMulticast
import com.linroid.ketch.torrent.magnet.MAX_PENDING_METADATA
import com.linroid.ketch.torrent.magnet.MagnetUri
import com.linroid.ketch.torrent.metainfo.TorrentMetadata
import com.linroid.ketch.torrent.metainfo.TorrentV2Document
import com.linroid.ketch.torrent.metainfo.V2InfoHash
import com.linroid.ketch.torrent.net.TorrentHttp
import com.linroid.ketch.torrent.storage.TorrentCheckpoint
import com.linroid.ketch.torrent.storage.TorrentPieceStore
import com.linroid.ketch.torrent.storage.TorrentStorageException
import com.linroid.ketch.torrent.storage.TorrentV2Checkpoint
import com.linroid.ketch.torrent.storage.TorrentV2PieceStore
import com.linroid.ketch.torrent.storage.checkpointPayload
import com.linroid.ketch.torrent.storage.torrentSystemFileSystem
import com.linroid.ketch.torrent.tracker.TrackerLists
import com.linroid.ketch.torrent.tracker.bundledTrackers
import com.linroid.ketch.torrent.v2.TorrentV2DownloadSession
import com.linroid.ketch.torrent.v2.TorrentV2Speed
import io.ktor.http.Url
import io.ktor.http.decodeURLPart
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.getAndUpdate
import kotlinx.coroutines.flow.takeWhile
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.KSerializer
import okio.IOException
import okio.Path.Companion.toPath
import kotlin.concurrent.atomics.AtomicBoolean
import kotlin.concurrent.atomics.AtomicLong
import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.io.encoding.Base64
import kotlin.time.Duration

/**
 * Pure Kotlin BitTorrent v1, v2, and hybrid download source for JVM, Android, and iOS.
 * Supports HTTP(S) metainfo, local paths/file URLs, metainfo bytes, and btih/btmh magnets.
 * The optional HTTP engine remains owned by its caller. Output must be a filesystem path.
 *
 * A task's files can change at any time ([com.linroid.ketch.api.DownloadTask.selectFiles]): a
 * running torrent follows the change without reconnecting to its peers.
 *
 * Under [TorrentUploadPolicy.SEED_AFTER_COMPLETION] a completed task keeps seeding on a slot no
 * download needs, and [com.linroid.ketch.core.Ketch.start] seeds again the tasks that seeded
 * when it last stopped, each only once a recheck finds its files complete. They seed again the
 * same way, without a restart, whenever uploads resume ([setUploadsPaused]) or the policy
 * switches to seeding ([setUploadPolicy]).
 *
 * Uploads can be paused apart from the policy ([setUploadsPaused]), such as while the device is
 * on a metered network: the engine then runs as under [TorrentUploadPolicy.DISABLED], seeders
 * stop and free their slots, and the tasks keep their intent to seed.
 *
 * @param restoreSeeding whether [com.linroid.ketch.core.Ketch.start], and every resume of
 *   uploads or switch to seeding after it, shares again the completed tasks that were sharing,
 *   and a task that completes while uploads are paused is meant to share once they resume;
 *   false for processes that share a task store with the apps, such as `ketch mcp`
 * @param multicastLock held while the engine listens for peers on the LAN
 *   ([TorrentConfig.localDiscovery]), on hosts that filter multicast without one, such as Android
 *   on Wi-Fi
 * @param uploadsPaused whether uploads start paused ([setUploadsPaused]), so a host that knows
 *   uploads are not allowed yet starts that way before [com.linroid.ketch.core.Ketch.start]
 *   restores seeding
 */
@OptIn(ExperimentalAtomicApi::class)
class TorrentDownloadSource(
  private val config: TorrentConfig = TorrentConfig(),
  httpEngine: HttpEngine? = null,
  private val restoreSeeding: Boolean = true,
  private val multicastLock: TorrentMulticastLock? = null,
  uploadsPaused: Boolean = false,
) : DownloadSource<TorrentSourceState> {
  private val httpDelegate = lazy { httpEngine?.let { TorrentHttp(it) } ?: TorrentHttp.default() }
  private val http by httpDelegate
  private val additionalTrackers = AtomicReference(config.additionalTrackers)
  private val listedTrackers = AtomicReference(emptyList<String>())
  // The configured policy; the engine runs [engineUploadPolicy], which pausing overrides.
  private val uploadPolicy = AtomicReference(config.effectiveUploadPolicy)
  private val pausedUploads = AtomicBoolean(uploadsPaused)
  // Bumped under engineMutex each time the engine's policy turns to seeding; see sharingResumes.
  private val resumes = MutableStateFlow(0L)
  // Tasks that completed while uploads were paused under a seeding policy: takeDeferredSharing.
  private val deferredSharing = MutableStateFlow<Set<String>>(emptySet())
  private val uploadRateLimit = AtomicLong(config.uploadRateLimit)
  private val encryption = AtomicReference(config.encryption)
  private val utpEnabled = AtomicBoolean(config.utpEnabled)
  private val portMapping = AtomicBoolean(config.portMapping)
  private val localDiscovery = AtomicBoolean(config.localDiscovery)

  /** The upload policy of running and later torrents: the configured one or the last set. */
  internal val currentUploadPolicy: TorrentUploadPolicy get() = uploadPolicy.load()

  /** The policy the engine runs: [TorrentUploadPolicy.DISABLED] while uploads are paused. */
  private fun engineUploadPolicy(): TorrentUploadPolicy =
    if (pausedUploads.load()) TorrentUploadPolicy.DISABLED else uploadPolicy.load()

  /** Test hook: how long one magnet metadata lookup runs before the next starts. */
  internal var magnetLookupTime: Duration = MAGNET_LOOKUP_TIME

  /** Test hook: how often a seeding session saves its state into its task's record. */
  internal var seedingSaveIntervalMs: Long = SEEDING_SAVE_INTERVAL_MS

  /** Creates the engine from [engineConfig], under [engineMutex]. */
  internal var engineFactory: (TorrentConfig) -> TorrentEngine = {
    // May map ports and join the LAN's discovery group; TorrentConfig.portMapping and
    // localDiscovery, which their setters change, decide if it does.
    KotlinTorrentEngine(it, http = http, portMapping = true,
      localDiscovery = ::openLsdMulticast, multicastLock = multicastLock,
      magnetLookupTime = magnetLookupTime)
  }
  private val scope = CoroutineScope(SupervisorJob() + TorrentDispatchers.disk)
  private val trackerListSubscription = TrackerLists(
    http = { http },
    stateDirectory = config.stateDirectory?.toPath(),
    scope = scope,
    onTrackers = { trackers ->
      engineMutex.withLock {
        listedTrackers.store(trackers)
        (engine.load() as? KotlinTorrentEngine)?.setAdditionalTrackers(extraTrackers())
      }
    },
  )
  private val engine = AtomicReference<TorrentEngine?>(null)
  private val closed = AtomicBoolean(false)
  // Set once Ketch begins to close (stopping): the job stopping the seeders, which stop() awaits.
  private val seedersStopping = AtomicReference<Job?>(null)
  // Set by the first close(), which closes http.
  private val released = AtomicBoolean(false)
  private val engineMutex = Mutex()
  private val tasks = TorrentSessionRegistry()
  private val slots = TorrentActiveSlots(config.maxActiveTorrents)
  // Magnet fetches beyond the runtime's pending-metadata bound queue instead of failing.
  private val metadataFetches = Semaphore(MAX_PENDING_METADATA)
  private val stateMutex = Mutex()
  private val states = linkedMapOf<String, TorrentSourceState>()
  // Guarded by stateMutex: the watcher of each lent seeder ([watchSeeder]), from the moment its
  // slot is lent until it stops; what stops the slot coming back.
  private val seedWatchers = mutableMapOf<String, Job>()
  // The state each seeding session saves its checkpoint into, by task ([saveSeedingState]): set
  // once it seeds, removed once it stops or a run of the task takes it over.
  private val seedStates = MutableStateFlow<Map<String, TorrentSourceState>>(emptyMap())
  // The seed-only sessions [startSeeding] opened and still waits on, by task, listed before their
  // slot is lent: a smaller selection saved once the slot is lent reaches them
  // ([changeSeedingSelection]), and a run of the task that takes one over ends the wait
  // ([CheckingSeeder.takenOver]).
  private val checkingSeeders = MutableStateFlow<Map<String, CheckingSeeder>>(emptyMap())
  // Where seeding sessions save their state: the task records, once attached.
  private val sessionStates =
    AtomicReference<(suspend (taskId: String, state: SourceState) -> Unit)?>(null)
  // The payload counters of recent saved states, newest first, matched by identity.
  private val savedPayloads =
    MutableStateFlow<List<Pair<SourceState, TorrentPayloadCounters?>>>(emptyList())
  private val fileTables = TorrentFileTables()
  private val seeding = MutableStateFlow<Set<String>>(emptySet())
  // Tasks waiting in withActiveSlot for one of the engine's slots.
  private val waitingForSlot = MutableStateFlow<Set<String>>(emptySet())
  private val log = KetchLogger("TorrentSource")
  override val type: String = TYPE

  override val stateSerializer: KSerializer<TorrentSourceState> =
    TorrentSourceState.serializer()
  override val managesOwnFileIo: Boolean = true
  override val features: Set<String> = setOf(KetchFeatures.FILE_SELECTION,
    KetchFeatures.AWAIT_FILE_SELECTION, TorrentFeatures.CONTROL)

  /**
   * The torrent controls of the Ketch that runs this source: the [TorrentController] that
   * `ketch.controller(TorrentController)` returns, and seeding of completed tasks.
   *
   * [TorrentController]: com.linroid.ketch.torrent.api.TorrentController
   */
  override fun attach(host: SourceHost): SourceExtension =
    TorrentControls(controls, type, ::canHandle, host)

  /** This source's controls, for [TorrentControls]. */
  private val controls: TorrentControlSource = SourceControls(this)

  private class SourceControls(private val source: TorrentDownloadSource) : TorrentControlSource {
    override val torrentCapabilities: Set<String> get() = source.torrentCapabilities
    override val seedingTaskIds: StateFlow<Set<String>> get() = source.seedingTaskIds
    override val restoresSeeding: Boolean get() = source.restoresSeeding
    override val sharingResumes: StateFlow<Long> get() = source.sharingResumes

    override suspend fun takeDeferredSharing(taskId: String) = source.takeDeferredSharing(taskId)

    override suspend fun torrentFiles(sourceState: SourceState?, resolved: ResolvedSource?) =
      source.torrentFiles(sourceState, resolved)

    override suspend fun liveTorrent(taskId: String) = source.liveTorrent(taskId)

    override suspend fun savedPayload(sourceState: SourceState?) =
      source.savedPayload(sourceState)

    override fun saveSessionStates(save: suspend (taskId: String, state: SourceState) -> Unit) =
      source.saveSessionStates(save)

    override suspend fun seedingAvailability(taskId: String) = source.seedingAvailability(taskId)

    override suspend fun startSeeding(task: SeedingTask) = source.startSeeding(task)

    override suspend fun stopSeeding(taskId: String) = source.stopSeeding(taskId)

    override suspend fun changeSeedingSelection(taskId: String, fileIds: Set<String>) =
      source.changeSeedingSelection(taskId, fileIds)

    override suspend fun torrentReachability() = source.reachability()
  }

  /**
   * What [TorrentControlSource.torrentCapabilities] reports.
   *
   * `utp` and `mse-pe` name what the engine can speak, whatever [TorrentConfig.utpEnabled] and
   * [TorrentConfig.encryption] currently say: capabilities describe the backend, and those
   * settings change live without the backend gaining or losing the protocol. `seeding` follows
   * the live upload policy, also while uploads are paused: a task can still be meant to seed,
   * and seeds once they resume.
   */
  internal val torrentCapabilities: Set<String>
    get() = buildSet {
      add(TorrentCapability.FILE_SELECTION.wireName)
      add(TorrentCapability.V1.wireName)
      add(TorrentCapability.V2.wireName)
      add(TorrentCapability.HYBRID.wireName)
      add(TorrentCapability.UTP.wireName)
      add(TorrentCapability.ENCRYPTION.wireName)
      add(TorrentCapability.REACHABILITY.wireName)
      // Sharing follows the live upload setting, paused or not.
      if (uploadPolicy.load() == TorrentUploadPolicy.SEED_AFTER_COMPLETION) {
        add(TorrentCapability.SEEDING.wireName)
      }
    }

  /** What [TorrentControlSource.seedingTaskIds] reports. */
  internal val seedingTaskIds: StateFlow<Set<String>> = seeding.asStateFlow()

  /**
   * Only while the live upload setting seeds and uploads are not paused; [sharingResumes] tells
   * when that becomes true, so neither needs a restart.
   */
  internal val restoresSeeding: Boolean
    get() = restoreSeeding && engineUploadPolicy() == TorrentUploadPolicy.SEED_AFTER_COMPLETION

  /**
   * What [TorrentControlSource.sharingResumes] reports: bumped each time the engine's policy
   * turns to [TorrentUploadPolicy.SEED_AFTER_COMPLETION] from another, as uploads resume under it
   * or the policy switches to it; never without [restoreSeeding].
   */
  internal val sharingResumes: StateFlow<Long> = resumes.asStateFlow()

  /**
   * What [TorrentControlSource.takeDeferredSharing] reports: whether [taskId] completed meant to
   * seed under [TorrentUploadPolicy.SEED_AFTER_COMPLETION], forgetting it. That covers a session
   * paused uploads finished instead of seeding, and one lent its slot to seed, which a pause may
   * stop before Ketch publishes the completion and the seeding watcher could save the intent.
   */
  internal fun takeDeferredSharing(taskId: String): Boolean {
    var taken = false
    deferredSharing.update { ids ->
      taken = taskId in ids
      ids - taskId
    }
    return taken
  }

  /**
   * Remembers that [taskId] completed meant to seed, so the intent is saved whatever its session
   * does by the time Ketch publishes the completion: [seeded] when the session was lent its slot
   * to seed, else when only paused uploads finished it instead of seeding.
   */
  private fun deferSharing(taskId: String, seeded: Boolean) {
    if (!restoreSeeding || uploadPolicy.load() != TorrentUploadPolicy.SEED_AFTER_COMPLETION) return
    if (!seeded && !pausedUploads.load()) return
    deferredSharing.update { it + taskId }
    if (!seeded) {
      log.i { "Torrent taskId=$taskId completed while uploads are paused; seeds once resumed" }
    }
  }

  // The only writers of [seeding].
  private fun markSeeding(taskId: String) = seeding.update { it + taskId }

  private fun unmarkSeeding(taskId: String) = seeding.update { it - taskId }

  /** Test hook: runs once a transfer stopped following selections, before its run returns. */
  internal var onTransferEnd: suspend (taskId: String) -> Unit = {}

  /** The subscribed tracker lists ([TorrentConfig.trackerListUrls]) and their trackers. */
  val trackerLists: StateFlow<List<TrackerListState>> get() = trackerListSubscription.state

  // The configured lists' trackers without downloading: the shipped copies at once, then the
  // saved ones, which the first engine waits for so the first magnet already announces to them.
  private val initialTrackerList: Job? = config.trackerListUrls.takeIf { it.isNotEmpty() }
    ?.let { urls ->
      listedTrackers.store(urls.flatMap(::bundledTrackers).distinct())
      scope.launch { trackerListSubscription.subscribe(urls) }
    }

  private suspend fun getEngine(): TorrentEngine {
    // Outside engineMutex: restoring the list hands its trackers over under it.
    if (engine.load() == null) {
      initialTrackerList?.let { withTimeoutOrNull(TRACKER_LIST_RESTORE_MS) { it.join() } }
    }
    return startEngine()
  }

  private suspend fun startEngine(): TorrentEngine = engineMutex.withLock {
    check(!closed.load()) { "Torrent source is closed" }
    engine.load()?.let { return@withLock it }
    val created = engineFactory(engineConfig())
    try {
      try {
        created.start()
      } catch (e: IOException) {
        // Binding the peer listener is the only socket work here, such as a port still in use.
        log.e(e) { "Torrent engine could not listen on port ${config.listenPort}" }
        throw KetchError.Network(e)
      }
      engine.store(created)
      // Started once Ketch began to close: its window opens now, as stopping() would have.
      if (seedersStopping.load() != null) created.openStoppedWindow()
      if (closed.load()) {
        // Stopped under the mutex, which stop() waits for, so it never returns before this engine
        // released its listener and port mappings.
        withContext(NonCancellable) { engine.exchange(null)?.stop() }
        error("Torrent source is closed")
      }
      created
    } catch (e: Throwable) { created.close(); throw e }
  }

  /**
   * Opens the engine's one window for `stopped` announces as Ketch begins to close, and stops
   * the seeding sessions now, while Ketch pauses the downloads, so every torrent says it stopped
   * within that window rather than the seeders in one of their own after the downloads; [stop]
   * waits for them.
   */
  override fun stopping() {
    if (seedersStopping.load() != null) return
    val stopping = CoroutineScope(TorrentDispatchers.engine).launch(start = CoroutineStart.LAZY) {
      stopSeeders()
    }
    if (!seedersStopping.compareAndSet(null, stopping)) return
    engine.load()?.openStoppedWindow()
    stopping.start()
  }

  /**
   * Stops the engine and returns once it has: its sessions, such as seeding ones, close and
   * announce that they stopped while the HTTP engine still runs. No engine starts after it.
   */
  override suspend fun stop() {
    shutDown()
    engine.load()?.openStoppedWindow()
    // What stopping() began, then seeders that started since, or every seeder without it.
    seedersStopping.load()?.join()
    stopSeeders()
    // Taken under the mutex, so an engine that was starting is stopped too.
    engineMutex.withLock { engine.exchange(null) }?.stop()
  }

  /**
   * Stops the seeding sessions side by side, each pausing, which sends its `stopped` announces,
   * while it saves its state, with the counters, as no download run saves it: first as the
   * seeder is now, read without writing anything, so a slow disk saves while the trackers hear
   * it, within the 5 s hosts wait before they exit, rather than after the window they share;
   * then, once it paused, as it stopped, when that differs. Within [SHUTDOWN_SAVE_MS], so a
   * session that cannot save never holds the rest up.
   */
  private suspend fun stopSeeders() {
    val watched = stateMutex.withLock {
      seedWatchers.toMap().also {
        seedWatchers.clear()
        it.keys.forEach(::unmarkSeeding)
      }
    }
    watched.values.forEach { it.cancel() }
    val bases = seedStates.getAndUpdate { emptyMap() }
    coroutineScope {
      for (taskId in watched.keys + bases.keys) launch {
        val session = tasks.session(taskId) ?: return@launch
        val pausing = launch {
          try {
            session.pause()
          } catch (e: CancellationException) {
            throw e
          } catch (e: Exception) {
            log.w { "Torrent taskId=$taskId did not stop seeding: ${e.describeCauses()}" }
          }
        }
        val base = bases[taskId] ?: return@launch
        withTimeoutOrNull(SHUTDOWN_SAVE_MS) {
          val saved = saveSeedingState(taskId, session, base) { session.currentResumeData() }
          pausing.join()
          saveSeedingState(taskId, session, base) {
            session.currentResumeData()?.takeUnless { it.contentEquals(saved) }
          }
        }
      }
    }
  }

  override fun close() {
    shutDown()
    // Not awaited: an engine stop() already took is gone.
    engine.exchange(null)?.close()
    if (released.compareAndSet(false, true) && httpDelegate.isInitialized()) http.close()
  }

  /** Refuses new engines and work, and cancels the seed watchers; once. */
  private fun shutDown() {
    if (closed.compareAndSet(false, true)) {
      slots.close()
      scope.cancel()
      seeding.value = emptySet()
    }
  }

  /** The configuration of the next engine: the config with what the setters changed since. */
  private fun engineConfig(): TorrentConfig = config.copy(additionalTrackers = extraTrackers(),
    uploadPolicy = engineUploadPolicy(), uploadRateLimit = uploadRateLimit.load(),
    encryption = encryption.load(), utpEnabled = utpEnabled.load(),
    portMapping = portMapping.load(), localDiscovery = localDiscovery.load())

  /**
   * Replaces [TorrentConfig.encryption] for connections opened from now on; open ones keep
   * theirs. Never starts the engine.
   */
  suspend fun setEncryption(value: TorrentEncryption) {
    engineMutex.withLock {
      encryption.store(value)
      engine.load()?.setEncryption(value)
    }
  }

  /**
   * Replaces [TorrentConfig.utpEnabled] for connections opened from now on; open uTP connections
   * keep running. Never starts the engine.
   */
  suspend fun setUtpEnabled(enabled: Boolean) {
    engineMutex.withLock {
      utpEnabled.store(enabled)
      engine.load()?.setUtpEnabled(enabled)
    }
  }

  /**
   * Replaces [TorrentConfig.portMapping]: a running engine starts or stops asking the router to
   * forward its listen port, removing the mapping it has. Never starts the engine.
   */
  suspend fun setPortMapping(enabled: Boolean) {
    engineMutex.withLock {
      portMapping.store(enabled)
      engine.load()?.setPortMapping(enabled)
    }
  }

  /**
   * Replaces [TorrentConfig.localDiscovery]: a running engine starts or stops announcing public
   * torrents on the LAN and dialing the peers announced there. Never starts the engine.
   */
  suspend fun setLocalDiscovery(enabled: Boolean) {
    engineMutex.withLock {
      localDiscovery.store(enabled)
      engine.load()?.setLocalDiscovery(enabled)
    }
  }

  /**
   * Replaces [TorrentConfig.uploadPolicy] at once: running torrents choke or unchoke their peers
   * within a rechoke, seeding ones finish unless [policy] is
   * [TorrentUploadPolicy.SEED_AFTER_COMPLETION], and torrents started later use it. Switching to
   * seeding seeds again, after a recheck, the completed tasks meant to seed. While uploads are
   * paused ([setUploadsPaused]) the policy is kept for when they resume, and the engine stays as
   * under [TorrentUploadPolicy.DISABLED]. Never starts the engine.
   */
  suspend fun setUploadPolicy(policy: TorrentUploadPolicy) {
    val paused = engineMutex.withLock {
      val before = engineUploadPolicy()
      uploadPolicy.store(policy)
      applyUploadPolicy(before)
      pausedUploads.load()
    }
    log.i { "Upload policy: $policy" + if (paused) " (uploads paused)" else "" }
  }

  /**
   * Pauses or resumes every upload, whatever the upload policy, such as while the device is on a
   * metered network. Paused, the engine runs as under [TorrentUploadPolicy.DISABLED]: peers are
   * choked, seeding torrents finish and free their slots, and the tasks keep their intent to
   * seed. Resumed, the policy applies again, and under
   * [TorrentUploadPolicy.SEED_AFTER_COMPLETION] the completed tasks meant to seed seed again
   * after a recheck, without a restart. Never starts the engine.
   */
  suspend fun setUploadsPaused(paused: Boolean) {
    val changed = engineMutex.withLock {
      val before = engineUploadPolicy()
      (pausedUploads.exchange(paused) != paused).also { applyUploadPolicy(before) }
    }
    if (changed) log.i { if (paused) "Uploads paused" else "Uploads resumed" }
  }

  /**
   * Under [engineMutex]: hands a running engine the policy it runs now, and bumps [resumes] when
   * that turned to seeding from [before], so the tasks meant to seed seed again.
   */
  private suspend fun applyUploadPolicy(before: TorrentUploadPolicy) {
    val now = engineUploadPolicy()
    engine.load()?.setUploadPolicy(now)
    if (restoreSeeding && now == TorrentUploadPolicy.SEED_AFTER_COMPLETION && before != now) {
      resumes.update { it + 1 }
    }
  }

  /**
   * Replaces the upload cap shared by every torrent ([TorrentConfig.uploadRateLimit]) at once;
   * zero means unlimited. Torrents started later keep it. Never starts the engine.
   */
  suspend fun setUploadRateLimit(bytesPerSecond: Long) {
    require(bytesPerSecond >= 0)
    engineMutex.withLock {
      uploadRateLimit.store(bytesPerSecond)
      engine.load()?.setUploadRateLimit(bytesPerSecond)
    }
    log.i { "Upload limit: $bytesPerSecond B/s" }
  }

  /**
   * Change upload rate for an active task, v1, v2 or hybrid, including a completed task that is
   * seeding; zero means unlimited. It applies in addition to [setUploadRateLimit].
   */
  suspend fun setTaskUploadRateLimit(taskId: String, bytesPerSecond: Long) {
    require(bytesPerSecond >= 0)
    checkNotNull(tasks.session(taskId)) { "Torrent task is not active" }
      .setUploadRateLimit(bytesPerSecond)
  }

  /** Change the shared TCP connection bound. Existing excess connections are closed. */
  suspend fun setConnectionLimit(connections: Int) {
    require(connections in 1..4096)
    (getEngine() as KotlinTorrentEngine).setConnections(connections)
  }

  /**
   * Replace [TorrentConfig.additionalTrackers]. Torrents started or resumed afterwards use the new
   * list; running torrents keep the trackers they started with.
   */
  suspend fun setAdditionalTrackers(urls: List<String>) = engineMutex.withLock {
    additionalTrackers.store(urls)
    val running = engine.load() as? KotlinTorrentEngine
    if (running == null) log.d { "Extra trackers saved for the next engine start: ${urls.size}" }
    running?.setAdditionalTrackers(extraTrackers())
  }

  /**
   * Replace [TorrentConfig.trackerListUrls]; empty unsubscribes. New lists are downloaded at once;
   * like [setAdditionalTrackers], their trackers reach torrents started or resumed after.
   */
  suspend fun setTrackerLists(urls: List<String>) = trackerListSubscription.subscribe(urls)

  /** Download the subscribed tracker lists now rather than at their next daily refresh. */
  fun refreshTrackerLists() = trackerListSubscription.refresh()

  /** The trackers set by hand, then the list's; the engine uses the first 128 usable ones. */
  internal fun extraTrackers(): List<String> =
    (additionalTrackers.load() + listedTrackers.load()).distinct()

  /**
   * Accepts magnets, `torrent:` IDs and `.torrent` URLs or paths, and content named `.torrent` or
   * starting like a bencoded dictionary.
   */
  override fun canHandle(input: ResolveInput): Boolean = when (input) {
    is ResolveInput.Url -> {
      val lower = input.url.lowercase()
      lower.startsWith("magnet:") || lower.startsWith("torrent:") ||
        lower.substringBefore('?').substringBefore('#').endsWith(".torrent")
    }
    is ResolveInput.Content -> {
      val bytes = input.bytes
      // Metainfo is a dictionary whose first key is length-prefixed, e.g. "d8:announce".
      input.fileName?.endsWith(".torrent", ignoreCase = true) == true ||
        bytes.size >= 2 && bytes[0] == 'd'.code.toByte() &&
        bytes[1] in '1'.code.toByte()..'9'.code.toByte()
    }
  }

  /** Resolve metainfo supplied by a file picker or SDK caller without making a network request. */
  fun resolveMetainfo(
    bytes: ByteArray,
    privacy: TorrentDiscoveryPrivacy = config.discoveryPrivacy,
  ): ResolvedSource = parseMetainfo(bytes, privacy).logged("metainfo bytes")

  /**
   * Parses v2 or hybrid metainfo, else v1. A [newInput] is refused when Ketch could never download
   * it (see [resolveV2Metainfo]); the metainfo a task saved is read as it was accepted.
   */
  private fun parseMetainfo(
    bytes: ByteArray,
    privacy: TorrentDiscoveryPrivacy,
    newInput: Boolean = true,
  ): ResolvedSource {
    check(!closed.load()) { "Torrent source is closed" }
    resolveV2Metainfo(null, bytes, config, privacy, newInput = newInput)?.let { return it }
    val metadata = TorrentMetadata.fromBencode(bytes, config.maxMetadataBytes)
    val url = "torrent:${metadata.infoHash.hex}"
    return if (newInput) {
      resolvedMetainfo(url, metadata, privacy)
    } else {
      resolved(url, metadata, privacy)
    }
  }

  /** [resolved] for v1 metainfo a caller supplied, refusing one that could never find a peer. */
  private fun resolvedMetainfo(
    url: String,
    metadata: TorrentMetadata,
    privacy: TorrentDiscoveryPrivacy,
  ): ResolvedSource {
    requireDiscoverable(metadata.isPrivate, metadata.trackerTiers, metadata.webSeeds, privacy)
    return resolved(url, metadata, privacy)
  }

  /**
   * Validates and sizes a selection from the metainfo Ketch kept for the task (see [metainfoOf],
   * else a v1 checkpoint's), never from file sizes a client sent. A v2 or hybrid
   * task that started refuses a newly chosen file whose path holds something it does not own.
   */
  override suspend fun planSelection(request: SelectionRequest<TorrentSourceState>): SelectionPlan {
    val state = request.sourceState
    val table = fileTable(state, request.resolved)
      ?: throw IllegalStateException("The file list is not known yet")
    val requested = request.fileIds ?: table.ids
    require(requested.isNotEmpty()) { "Select at least one file" }
    require(requested.size <= maxOf(config.maxFilesPerTorrent, table.files.size)) {
      "Too many files selected"
    }
    val unknown = requested.filter { table[it] == null }
    require(unknown.isEmpty()) { unknownFilesMessage(unknown) }
    val ids = table.ordered(requested)
    val mirror = state?.selectedFileIds.orEmpty().filterTo(LinkedHashSet()) { table[it] != null }
    val effective = table.ordered(request.current.ifEmpty { mirror }.ifEmpty { table.ids })
    val added = ids - effective
    val output = request.outputPath ?: state?.savePath?.takeIf { it.isNotEmpty() }
    if (table.v2 && output != null && added.isNotEmpty()) {
      requireOwnedPaths(request.taskId, table, state, output, added)
    }
    val saved = request.segments.orEmpty().associateBy { it.index }
    fun whole(id: String) = request.completed && id in effective
    // A file without a saved segment, such as one deselected and chosen again, counts what the
    // saved checkpoint verified of it.
    val kept = if (ids.any { !whole(it) && saved[it.toInt()] == null }) {
      keptProgress(request.taskId, state, request.resolved)
    } else {
      emptyMap()
    }
    val segments = table.segments(ids) { file ->
      if (whole(file.id)) file.size
      else saved[file.id.toInt()]?.downloadedBytes ?: kept[file.id] ?: 0
    }
    return SelectionPlan(ids, table.totalOf(ids), changed = ids != effective,
      expands = added.isNotEmpty(), segments = segments)
  }

  /** [savedFileProgress] of [state], or none when its checkpoint cannot be read. */
  private suspend fun keptProgress(
    taskId: String,
    state: TorrentSourceState?,
    resolved: ResolvedSource?,
  ): Map<String, Long> {
    if (state == null || state.resumeData.isEmpty()) return emptyMap()
    return withContext(TorrentDispatchers.engine) {
      try {
        savedFileProgress(state, metainfoOf(resolved, state), config)
      } catch (e: Exception) {
        log.d { "Torrent taskId=$taskId saved no readable progress: ${e.describeCauses()}" }
        emptyMap()
      }
    }
  }

  /**
   * Refuses newly chosen v2 files whose path exists without the task owning it: the v2 store
   * never takes over a file it did not create. A live session's ownership is the latest.
   */
  private suspend fun requireOwnedPaths(
    taskId: String,
    table: TorrentFileTable,
    state: TorrentSourceState?,
    output: String,
    added: Set<String>,
  ) {
    val live = (tasks.session(taskId) as? TorrentV2DownloadSession)?.saveResumeData()
    val checkpoint = live?.let(TorrentV2Checkpoint::decode)
      ?: state?.resumeData?.takeIf { it.isNotEmpty() && state.version == 3 }
        ?.let { TorrentV2Checkpoint.decode(decodeBase64(it)) }
    val owned = checkpoint?.owned.orEmpty().mapTo(HashSet()) { it.components }
    val root = output.toPath()
    withContext(TorrentDispatchers.disk) {
      for (id in added) {
        val file = checkNotNull(table[id])
        if (file.components in owned) continue
        val path = file.components.fold(root) { parent, component -> parent / component }
        check(torrentSystemFileSystem.metadataOrNull(path) == null) {
          "A file Ketch does not own is in the way of file $id"
        }
      }
    }
  }

  /**
   * A task's metainfo: from [resolved], the resolution Ketch kept, else from [state] as builds
   * before that saved it; null when neither holds it.
   */
  private fun metainfoOf(resolved: ResolvedSource?, state: TorrentSourceState?): ByteArray? =
    (resolved?.metadata?.get(ResolvedSource.METAINFO_KEY)?.takeIf { it.isNotEmpty() }
      ?: state?.metainfo?.takeIf { it.isNotEmpty() })?.let(::decodeBase64)

  /**
   * The task's files from metadata the source holds: its [metainfoOf], else a v1 checkpoint's;
   * null when none holds any.
   */
  private suspend fun fileTable(
    state: TorrentSourceState?,
    resolved: ResolvedSource?,
  ): TorrentFileTable? {
    val bytes = metainfoOf(resolved, state)
      ?: state?.takeIf { it.version != 3 && it.resumeData.isNotEmpty() }
        ?.let { TorrentCheckpoint.decode(decodeBase64(it.resumeData))?.metadata?.metainfoBytes }
      ?: return null
    return withContext(TorrentDispatchers.engine) {
      fileTables.get(bytes) { TorrentFileTable.parse(bytes, config) }
    }
  }

  internal suspend fun torrentFiles(
    sourceState: SourceState?,
    resolved: ResolvedSource?,
  ): List<SourceFile>? {
    val table = fileTable(sourceState?.let { decodeState(it) }, resolved) ?: return null
    return table.files.map {
      SourceFile(it.id, it.path, it.size, metadata = mapOf("path" to it.path))
    }
  }

  /**
   * What [TorrentControlSource.torrentReachability] reports: the engine's, or unknown while no
   * torrent has started it.
   */
  internal fun reachability(): TorrentReachability =
    (engine.load() as? KotlinTorrentEngine)?.reachability() ?: TorrentReachability()

  internal suspend fun liveTorrent(taskId: String): LiveTorrent? {
    val session = tasks.session(taskId)
    if (session == null) {
      return if (taskId in waitingForSlot.value) {
        LiveTorrent(TorrentActivity.QUEUED, null, null, null)
      } else null
    }
    val activity = when (session.state.value) {
      TorrentSessionState.CHECKING_FILES -> TorrentActivity.CHECKING
      TorrentSessionState.DOWNLOADING -> TorrentActivity.DOWNLOADING
      TorrentSessionState.SEEDING -> TorrentActivity.SEEDING
      else -> TorrentActivity.STOPPED
    }
    val counters = session.payloadCounters()
    return LiveTorrent(activity, counters.received, counters.uploaded, counters.uploadSpeed,
      stall = session.stall.value.takeIf { activity == TorrentActivity.DOWNLOADING },
      discardedPayloadBytes = counters.discarded)
  }

  /**
   * What [TorrentControlSource.savedPayload] reports: the counters of the checkpoint in
   * [sourceState], read once per saved state.
   */
  internal suspend fun savedPayload(sourceState: SourceState?): TorrentPayloadCounters? {
    if (sourceState == null) return null
    savedPayloads.value.firstOrNull { it.first === sourceState }?.let { return it.second }
    val counters = withContext(TorrentDispatchers.engine) {
      try {
        decodeState(sourceState).resumeData.takeIf { it.isNotEmpty() }
          ?.let { checkpointPayload(decodeBase64(it)) }
      } catch (e: CancellationException) {
        throw e
      } catch (e: Exception) {
        log.d { "Saved torrent state has no readable checkpoint: ${e.describeCauses()}" }
        null
      }
    }
    savedPayloads.update { (listOf(sourceState to counters) + it).take(MAX_SAVED_PAYLOADS) }
    return counters
  }

  /** What [TorrentControlSource.saveSessionStates] sets. */
  internal fun saveSessionStates(save: suspend (taskId: String, state: SourceState) -> Unit) {
    sessionStates.store(save)
  }

  /**
   * Saves the latest checkpoint of [taskId]'s seeding [session], as [base] with its resume data
   * ([resumeData]: what [TorrentSession.saveResumeData] writes, unless the caller reads it
   * otherwise; null saves nothing), into the task's record through [sessionStates]: no download
   * run saves it, so the payload counters, what it uploaded above all, would otherwise go back to
   * what the task completed with after a restart. Returns the resume data it saved. A failure is
   * logged, and the record keeps its last state.
   */
  private suspend fun saveSeedingState(
    taskId: String,
    session: TorrentSession,
    base: TorrentSourceState,
    resumeData: suspend () -> ByteArray? = { session.saveResumeData() },
  ): ByteArray? {
    val save = sessionStates.load() ?: return null
    return try {
      val saved = resumeData() ?: return null
      save(taskId, encodeState(base.copy(resumeData = encodeBase64(saved))))
      log.d { "Torrent taskId=$taskId saved its seeding state" }
      saved
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      log.w { "Torrent taskId=$taskId could not save its seeding state: ${e.describeCauses()}" }
      null
    }
  }

  internal suspend fun seedingAvailability(taskId: String): SeedingOutcome? = when {
    uploadPolicy.load() != TorrentUploadPolicy.SEED_AFTER_COMPLETION -> SeedingOutcome.POLICY_OFF
    pausedUploads.load() -> SeedingOutcome.UPLOADS_PAUSED
    tasks.isReserved(taskId) -> SeedingOutcome.ALREADY_ACTIVE
    !slots.hasFree() -> SeedingOutcome.NO_SLOT
    else -> null
  }

  /**
   * Seeds a completed task on a free slot, never one a download waits for: a seed-only session
   * checks the files first and contacts no tracker or peer unless they are complete. The slot is
   * lent from the start, so a download that needs it stops the seeder even while it checks.
   */
  internal suspend fun startSeeding(task: SeedingTask): SeedingOutcome {
    val taskId = task.taskId
    seedingAvailability(taskId)?.let { return it }
    // A smaller selection saved since the task was read: a file dropped, deleted maybe, is never
    // opened or checked.
    val opened = smallerSelection(task, task.selectedFileIds) ?: task.selectedFileIds
    val plan = try {
      seedPlan(task, opened)
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      log.w { "Torrent taskId=$taskId cannot seed: ${e.describeCauses()}" }
      return SeedingOutcome.FAILED
    }
    if (!slots.tryAcquire()) return SeedingOutcome.NO_SLOT
    var reserved = false
    var lent = false
    // The task's own run took the session over as it checked ([adoptLive]): the run owns the
    // session, its reservation and its slot from then on.
    var takenOver = false
    var outcome = SeedingOutcome.FAILED
    try {
      try {
        tasks.reserve(taskId, plan.hash)
        reserved = true
      } catch (e: IllegalStateException) {
        // Its own run started meanwhile, or another task owns the same torrent.
        outcome = if (tasks.isReserved(taskId)) SeedingOutcome.ALREADY_ACTIVE else {
          log.i { "Torrent taskId=$taskId cannot seed: another task shares its torrent" }
          SeedingOutcome.FAILED
        }
        return outcome
      }
      val session = plan.open()
      try {
        tasks.attach(taskId, session)
      } catch (e: Throwable) {
        withContext(NonCancellable) { engine.load()?.removeTorrent(plan.hash) }
        throw e
      }
      // Listed before the slot is lent, so a run that takes the slot over finds it. Once the
      // slot is lent, a smaller selection saved meanwhile reaches the session, which checks its
      // files again with it; one saved as it opened is read before the check.
      val seeder = CheckingSeeder(session, opened)
      checkingSeeders.update { it + (taskId to seeder) }
      lent = slots.lend(taskId)
      if (!lent) {
        outcome = SeedingOutcome.NO_SLOT
        return outcome
      }
      seedLatestSelection(task, opened)
      log.i { "Torrent taskId=$taskId checks its files to seed (${logHash(plan.hash)})" }
      session.resume()
      // Null once the task's own run takes the session over, which may download for long.
      val reached = combine(session.state, seeder.takenOver) { state, taken ->
        state.takeUnless { taken }
      }.first {
        it == null || it == TorrentSessionState.SEEDING || it == TorrentSessionState.STOPPED ||
          it == TorrentSessionState.FINISHED
      }
      if (reached == null || seeder.takenOver.value) {
        takenOver = true
        outcome = SeedingOutcome.ALREADY_ACTIVE
        log.i { "Torrent taskId=$taskId: its download took the seeder over as it checked" }
        return outcome
      }
      if (!slots.isLent(taskId)) {
        // A download took the slot and stops the session, or the task's own run took it over.
        outcome = if (reached != TorrentSessionState.STOPPED && tasks.session(taskId) === session) {
          SeedingOutcome.ALREADY_ACTIVE
        } else SeedingOutcome.NO_SLOT
        log.i { "Torrent taskId=$taskId gave its slot up before seeding: $outcome" }
        return outcome
      }
      outcome = when (reached) {
        TorrentSessionState.SEEDING -> SeedingOutcome.SEEDING
        TorrentSessionState.STOPPED -> if (failureOf(session) is IncompleteSeedException) {
          SeedingOutcome.CHANGED_ON_DISK
        } else SeedingOutcome.FAILED
        // The upload setting stopped seeding while it checked, or uploads paused meanwhile.
        else -> if (pausedUploads.load()) SeedingOutcome.UPLOADS_PAUSED else SeedingOutcome.FAILED
      }
      if (outcome == SeedingOutcome.SEEDING) {
        // The state its checkpoint saves into takes the selection it seeds.
        val seeded = checkingSeeders.value[taskId]?.selection ?: opened
        val state = if (seeded == task.selectedFileIds) plan.state else {
          plan.state.copy(selectedFileIds = seeded, totalBytes = session.totalBytes)
        }
        seedStates.update { it + (taskId to state) }
        markSeeding(taskId)
        checkingSeeders.update { it - taskId }
        watchSeeder(taskId, session)
        log.i { "Torrent taskId=$taskId seeds again" }
        seedLatestSelection(task, seeded)
      } else {
        log.i { "Torrent taskId=$taskId does not seed: $outcome" }
      }
      return outcome
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      log.w { "Torrent taskId=$taskId could not seed: ${e.describeCauses()}" }
      outcome = SeedingOutcome.FAILED
      return outcome
    } finally {
      // Only the reservation's holder adds one.
      if (reserved) checkingSeeders.update { it - taskId }
      if (outcome != SeedingOutcome.SEEDING && !takenOver) withContext(NonCancellable) {
        if (lent) {
          stopSeeder(taskId)
        } else {
          try {
            if (reserved) stopSession(taskId)
          } finally {
            if (reserved) tasks.release(taskId)
            slots.release()
          }
        }
      }
    }
  }

  /**
   * Applies to [task]'s seeder, which seeds [from], the smaller selection its task saved since:
   * [changeSeedingSelection] changes a seeder only while it checks, or while its watcher follows
   * it from the moment it seeds, also as it downloads a piece it lost again, so one saved from
   * then on reaches it, and one saved before is read here, before the check and once it seeds.
   * Reads it again after each change, which a newer selection applied meanwhile may have
   * preceded, until it holds.
   */
  private suspend fun seedLatestSelection(task: SeedingTask, from: Set<String>) {
    var seeded = from
    while (true) {
      val latest = smallerSelection(task, seeded) ?: return
      try {
        changeSeedingSelection(task.taskId, latest)
      } catch (e: CancellationException) {
        throw e
      } catch (e: Exception) {
        log.w {
          "Torrent taskId=${task.taskId} seeds its previous selection: ${e.describeCauses()}"
        }
        return
      }
      seeded = latest
    }
  }

  /**
   * [task]'s current selection when it only drops files from [seeded], which a seeder can take;
   * else `null`. A selection that adds files reopens the task instead, whose run takes the seeder
   * over.
   */
  private fun smallerSelection(task: SeedingTask, seeded: Set<String>): Set<String>? {
    val latest = task.selection()
    if (latest.isEmpty() || latest == seeded) return null
    return latest.takeIf { seeded.isEmpty() || seeded.containsAll(it) }
  }

  /**
   * A seed-only session that checks its files, and the selection it checks them for. [takenOver]
   * turns true once the task's own run takes the session over ([adoptLive]), which ends the
   * [startSeeding] that opened it.
   */
  private class CheckingSeeder(
    val session: TorrentSession,
    val selection: Set<String>,
    val takenOver: MutableStateFlow<Boolean> = MutableStateFlow(false),
  )

  /**
   * A completed task's seed-only session: its identity, the state it saves its checkpoint into,
   * and how to open it.
   */
  private class SeedPlan(
    val hash: String,
    val state: TorrentSourceState,
    val open: suspend () -> TorrentSession,
  )

  private suspend fun seedPlan(task: SeedingTask, selection: Set<String>): SeedPlan {
    val state = decodeState(task.sourceState)
    val checkpoint = if (state.version == 3 || state.resumeData.isEmpty()) null else
      TorrentCheckpoint.decode(decodeBase64(state.resumeData))
    val bytes = metainfoOf(task.resolved, state)
      ?: checkpoint?.metadata?.metainfoBytes
      ?: error("The task saved no metainfo")
    val privacy = state.privacy ?: config.discoveryPrivacy
    val output = state.savePath.ifEmpty { task.outputPath }
    require(output.isNotEmpty() && "://" !in output) { "Torrent output requires a filesystem path" }
    val table = withContext(TorrentDispatchers.engine) {
      fileTables.get(bytes) { TorrentFileTable.parse(bytes, config) }
    }
    val selected = selectionOf(table, selection, state)
    val magnet = task.url.takeIf { it.startsWith("magnet:", true) }
    val resumeData = state.resumeData.takeIf { it.isNotEmpty() }
    if (table.v2) {
      require(state.version == 3) { "Invalid v2 resume state" }
      val document = TorrentV2Document.parse(bytes, maxDocumentBytes = config.maxMetadataBytes,
        maxFiles = config.maxFilesPerTorrent)
      val hash = document.info.hash.hex
      require(hash == state.infoHash) { "Saved torrent changed" }
      return SeedPlan(hash, state) {
        (getEngine() as KotlinTorrentEngine).addV2Task(TorrentV2TaskSpec(task.taskId, document,
          output, selected, checkpointEncoded = resumeData,
          trackerTiers = sourceTrackerTiers(bytes, config.maxMetadataBytes),
          magnetUri = magnet, privacy = privacy, recoverCreations = true,
          legacySelections = listOfNotNull(state.selectedFileIds.takeIf { it.isNotEmpty() }),
          seedOnly = true, connections = task.connections))
      }
    }
    require(state.version != 3) { "Cannot downgrade a v2 task" }
    val metadata = TorrentMetadata.fromBencode(bytes, config.maxMetadataBytes)
    val hash = metadata.infoHash.hex
    require(hash == state.infoHash) { "Saved torrent changed" }
    return SeedPlan(hash, state) {
      getEngine().addTask(TorrentTaskSpec(task.taskId, metadata, output,
        selected.mapTo(LinkedHashSet()) { it.toInt() }, magnet, resumeData?.let(::decodeBase64),
        privacy = privacy, seedOnly = true, connections = task.connections))
    }
  }

  private fun failureOf(session: TorrentSession): Throwable? = when (session) {
    is KotlinTorrentSession -> session.failure.value
    is TorrentV2DownloadSession -> session.failure.value
    else -> null
  }

  /** Stops a seeder whose slot is still lent to it and frees the slot; nothing otherwise. */
  private suspend fun stopSeeder(taskId: String) {
    if (!slots.reclaim(taskId)) return
    try { stopSession(taskId) } finally { withContext(NonCancellable) { slots.release() } }
  }

  /** Stops the task's seeding session after it saved its state, and frees its slot. */
  internal suspend fun stopSeeding(taskId: String) {
    if (!slots.reclaim(taskId)) {
      log.d { "Torrent taskId=$taskId is not seeding" }
      return
    }
    try {
      stateMutex.withLock {
        unmarkSeeding(taskId)
        seedWatchers.remove(taskId)
      }?.cancel()
      try {
        tasks.session(taskId)?.pause()
      } catch (e: CancellationException) {
        throw e
      } catch (e: Exception) {
        log.w { "Torrent taskId=$taskId could not save its state: ${e.describeCauses()}" }
      }
      stopSession(taskId)
      log.i { "Torrent taskId=$taskId stopped seeding" }
    } finally {
      withContext(NonCancellable) { slots.release() }
    }
  }

  /**
   * Applies a completed task's smaller selection to its seeding session, if it seeds, also while
   * it downloads a piece it lost again, or checks its files to seed: the check starts again with
   * it, so a file dropped meanwhile, deleted maybe, no longer keeps it from seeding.
   */
  internal suspend fun changeSeedingSelection(taskId: String, fileIds: Set<String>): Boolean {
    // A seeder its watcher follows is the task's session until the watcher is gone.
    val watched = taskId in seedingTaskIds.value || stateMutex.withLock { taskId in seedWatchers }
    // Not once a download took a checking seeder's slot, or the task's own run took it over.
    val checking = if (watched) null else {
      checkingSeeders.value[taskId]?.session
        ?.takeIf { slots.isLent(taskId) && tasks.session(taskId) === it } ?: return false
    }
    val session = checking ?: tasks.session(taskId) ?: return false
    val live = session.changeSelection(fileIds)
    checkingSeeders.update { seeders ->
      val current = seeders[taskId]
      if (checking == null || current?.session !== checking) return@update seeders
      seeders + (taskId to CheckingSeeder(checking, fileIds, current.takenOver))
    }
    stateMutex.withLock {
      states[taskId]?.let {
        states[taskId] = it.copy(selectedFileIds = fileIds, totalBytes = session.totalBytes)
      }
    }
    seedStates.update { seeds ->
      val base = seeds[taskId] ?: return@update seeds
      seeds + (taskId to base.copy(selectedFileIds = fileIds, totalBytes = session.totalBytes))
    }
    log.i { "Torrent taskId=$taskId seeding selection applied: files=${fileIds.size}, live=$live" }
    return live
  }

  /** Tasks holding a session reservation; for leak checks. */
  internal suspend fun reservedTasks(): Int = tasks.size()

  /** Engine slots owned or lent to seeders; for leak checks. */
  internal suspend fun slotsInUse(): Int = slots.inUse()

  /** The task's registered session; for tests. */
  internal suspend fun sessionOf(taskId: String): TorrentSession? = tasks.session(taskId)

  /** A magnet keeps looking for its metadata until it is found or the caller stops. */
  override suspend fun resolve(input: ResolveInput, config: DownloadConfig): ResolvedSource =
    when (input) {
      is ResolveInput.Url ->
        resolveUntilFound(input.url, this.config.discoveryPrivacy, input.headers)
      is ResolveInput.Content -> resolveContent(input.bytes)
    }

  /**
   * Resolves dropped or picked `.torrent` bytes via [resolveMetainfo], off the caller thread. A
   * refusal is a source error that says why, as one for a `.torrent` URL is.
   */
  private suspend fun resolveContent(content: ByteArray): ResolvedSource =
    withContext(TorrentDispatchers.engine) {
      try {
        resolveMetainfo(content)
      } catch (e: CancellationException) { throw e
      } catch (e: Exception) {
        if (e is KetchError) throw e
        // Content in memory never fails on a disk, whatever the parser throws.
        throw torrentSourceError(e)
      }
    }

  /** Resolve with an explicit privacy choice before any discovery or metadata request starts. */
  suspend fun resolve(
    url: String,
    privacy: TorrentDiscoveryPrivacy,
    headers: Map<String, String> = emptyMap(),
  ): ResolvedSource {
    check(!closed.load()) { "Torrent source is closed" }
    log.d { "Resolving ${redactUrl(url)} (privacy=$privacy)" }
    return reportingFailures {
      if (url.startsWith("magnet:", true)) {
        if (metadataFetches.availablePermits == 0) {
          log.i { "Waiting for one of $MAX_PENDING_METADATA magnet metadata lookups to finish" }
        }
        return@reportingFailures metadataFetches.withPermit { resolveMagnet(url, privacy) }
          .logged("magnet")
      }
      val bytes = if (url.startsWith("https://", true) || url.startsWith("http://", true)) {
        try {
          http.fetch(url, config.maxMetadataBytes, headers = headers)
        } catch (e: IOException) {
          // Caller-supplied engines may surface raw socket failures; the metainfo host is remote.
          throw KetchError.Network(e)
        }
      } else {
        val path = if (url.startsWith("file:", true)) {
          val parsed = Url(url)
          require(parsed.host.isEmpty() || parsed.host == "localhost")
          parsed.encodedPath.decodeURLPart()
        } else {
          require("://" !in url && !url.startsWith("torrent:")) {
            "Metainfo bytes are required for this input"
          }
          url
        }
        withContext(TorrentDispatchers.disk) {
          torrentSystemFileSystem.read(path.toPath()) {
            val result = readByteArray(minOf(config.maxMetadataBytes.toLong(),
              torrentSystemFileSystem.metadata(path.toPath()).size ?: 0L))
            require(exhausted()) { "Metainfo exceeds limit" }
            result
          }
        }
      }
      (resolveV2Metainfo(url, bytes, config, privacy)
        ?: resolvedMetainfo(url, TorrentMetadata.fromBencode(bytes, config.maxMetadataBytes),
          privacy))
        .logged(if (url.startsWith("http", true)) "HTTP metainfo" else "metainfo file")
    }
  }

  /**
   * Resolves [url] as [resolve] does, except that a magnet keeps looking for its metadata, one
   * lookup after another, until one finds it or the caller stops: the peers of a small swarm may
   * come online at any time.
   */
  private suspend fun resolveUntilFound(
    url: String,
    privacy: TorrentDiscoveryPrivacy,
    properties: Map<String, String>,
  ): ResolvedSource {
    if (!url.startsWith("magnet:", true)) return resolve(url, privacy, properties)
    var lookups = 0
    while (true) {
      try {
        return resolve(url, privacy, properties)
      } catch (e: KetchError.Network) {
        if (e.cause !is TimeoutCancellationException && e.cause !is MetadataNotFoundException) {
          throw e
        }
        lookups++
        log.i {
          "No metadata for magnet ${logHash(MagnetUri.parse(url).logTopic)} after " +
            "$lookups lookup(s); looking again"
        }
        delay(MAGNET_LOOKUP_PAUSE_MS)
      }
    }
  }

  private fun ResolvedSource.logged(origin: String): ResolvedSource = also {
    log.i {
      "Resolved torrent from $origin: ${logHash(metadata[META_INFO_HASH].orEmpty())} " +
        "\"$suggestedFileName\", ${metadata["format"] ?: "v1"}, files=${files.size}, " +
        "totalBytes=$totalBytes"
    }
  }

  private suspend fun resolveMagnet(url: String, privacy: TorrentDiscoveryPrivacy): ResolvedSource {
    if (MagnetUri.parse(url).identity.v2 != null) {
      val bytes = (getEngine() as KotlinTorrentEngine).fetchV2Metadata(url, privacy)
      return requireNotNull(resolveV2Metainfo(url, bytes, config, privacy))
    }
    val fetched = getEngine().fetchMetadata(url, privacy)
      ?: throw KetchError.Network(MetadataNotFoundException())
    if (!fetched.isHybrid) return resolved(url, fetched, privacy)
    // A btih-only magnet found a hybrid torrent. Its v2 identity keys the task, so fetch the
    // piece layers under that topic instead of mixing v1 and v2 hashes.
    val v2Hash = V2InfoHash.fromBytes(sha256Digest(fetched.infoBytes))
    val bytes = (getEngine() as KotlinTorrentEngine)
      .fetchV2Metadata("$url&xt=urn:btmh:${v2Hash.multihash()}", privacy)
    return requireNotNull(resolveV2Metainfo(url, bytes, config, privacy))
  }

  private fun resolved(
    url: String,
    metadata: TorrentMetadata,
    privacy: TorrentDiscoveryPrivacy,
  ): ResolvedSource = ResolvedSource(
    // Padding shapes the pieces but is never downloaded to disk.
    url = url, sourceType = TYPE, totalBytes = metadata.contentFiles.sumOf { it.size },
    supportsResume = true,
    suggestedFileName = metadata.name, maxSegments = metadata.contentFiles.size,
    metadata = buildMap {
      put(META_INFO_HASH, metadata.infoHash.hex)
      put(META_NAME, metadata.name)
      put(META_PIECE_LENGTH, metadata.pieceLength.toString())
      put(ResolvedSource.METAINFO_KEY, encodeBase64(metadata.metainfoBytes))
      put(META_PRIVACY, privacy.name)
      metadata.comment?.let { put(META_COMMENT, it) }
    },
    files = metadata.contentFiles.map { SourceFile(it.index.toString(), it.path, it.size,
      metadata = mapOf("path" to it.path)) },
    selectionMode = FileSelectionMode.MULTIPLE,
  )

  private fun resolvedPrivacy(resolved: ResolvedSource): TorrentDiscoveryPrivacy =
    resolved.metadata[META_PRIVACY]?.let(TorrentDiscoveryPrivacy::valueOf)
      ?: config.discoveryPrivacy

  /** The running session's state with its latest checkpoint; none until a session started. */
  override suspend fun store(context: DownloadContext): TorrentSourceState? {
    val owner = tasks.session(context.taskId)
    val saved = owner?.saveResumeData()
    return stateMutex.withLock {
      val state = states[context.taskId] ?: return@withLock null
      val updated = if (saved != null) state.copy(resumeData = encodeBase64(saved)) else state
      if (owner == null) states.remove(context.taskId) else states[context.taskId] = updated
      updated
    }
  }

  /** Continues from [state], or downloads anew without one, as before its session started. */
  override suspend fun download(
    context: DownloadContext,
    state: TorrentSourceState?,
  ) = reportingFailures {
    if (state == null) {
      val resolved = context.preResolved
        ?: resolveUntilFound(context.url, config.discoveryPrivacy, context.headers)
      execute(context, resolved, null)
      return@reportingFailures
    }
    val checkpoint = if (state.version == 3 || state.resumeData.isEmpty()) null else
      TorrentCheckpoint.decode(decodeBase64(state.resumeData))
    val privacy = state.privacy ?: config.discoveryPrivacy
    val metainfo = metainfoOf(context.preResolved, state)
    val resolved = when {
      // Saved with the task, which passed the checks of new inputs when it was added or predates
      // them; admission still refuses pieces too large to assemble.
      metainfo != null -> parseMetainfo(metainfo, privacy, newInput = false)
      checkpoint != null -> resolved(context.url, checkpoint.metadata, privacy)
      else -> resolveUntilFound(context.url, privacy, context.headers)
    }
    require(resolved.metadata[META_INFO_HASH] == state.infoHash) { "Resume torrent changed" }
    execute(context, resolved, state)
  }

  /**
   * Reports failures in Ketch's terms (see [torrentFailure]). Cancellation is never wrapped;
   * only an internal timeout, while the caller is still active, becomes a failure.
   */
  private suspend inline fun <T> reportingFailures(block: () -> T): T = try {
    block()
  } catch (e: CancellationException) {
    if (e !is TimeoutCancellationException) throw e
    currentCoroutineContext().ensureActive()
    throw torrentFailure(e)
  } catch (e: Exception) {
    throw torrentFailure(e)
  }

  private suspend fun execute(
    context: DownloadContext,
    resolved: ResolvedSource,
    previous: TorrentSourceState?,
  ) {
    val hash = requireNotNull(resolved.metadata[META_INFO_HASH])
    val bytes = decodeBase64(requireNotNull(resolved.metadata[ResolvedSource.METAINFO_KEY]))
    val v2 = resolveV2Metainfo(resolved.url, bytes, config, resolvedPrivacy(resolved),
      newInput = false)
    if (v2 != null) {
      require(v2.metadata[META_INFO_HASH] == hash) { "Resolved torrent hash mismatch" }
      executeV2(context, v2, previous)
      return
    }
    require(previous?.version != 3) { "Cannot downgrade a v2 task" }
    val metadata = TorrentMetadata.fromBencode(bytes, config.maxMetadataBytes)
    require(metadata.infoHash.hex == hash) { "Resolved torrent hash mismatch" }
    val table = fileTables.get(bytes) { TorrentFileTable.parse(bytes, config) }
    // Read once: a later revision reaches the running session through the collector.
    val initial = context.selection.value
    val selected = selectionOf(table, initial.fileIds, previous)
    val output = context.outputPath ?: previous?.savePath?.takeIf { it.isNotEmpty() }
      ?: error("Torrent output path has not been resolved")
    require(!output.contains("://")) { "Torrent output requires a filesystem path" }
    val total = table.totalOf(selected)
    val privacy = previous?.privacy ?: resolvedPrivacy(resolved)
    // The metainfo is Ketch's to keep; only states older builds saved carry it.
    val state = TorrentSourceState(hash, total, previous?.resumeData ?: "", selected, output,
      previous?.metainfo.orEmpty(), version = 2, privacy = privacy)
    log.i {
      "Starting torrent taskId=${context.taskId} (${logHash(hash)}): " +
        "files=${selected.size}/${table.files.size}, totalBytes=$total, output=$output, " +
        "resume=${previous != null}"
    }
    runSession(context, hash, table, state, initial.revision) {
      getEngine().addTask(TorrentTaskSpec(context.taskId, metadata, output,
        selected.mapTo(LinkedHashSet()) { it.toInt() },
        context.url.takeIf { it.startsWith("magnet:", true) },
        previous?.resumeData?.takeIf { it.isNotEmpty() }?.let(::decodeBase64), context.throttle,
        privacy = privacy, connections = context.connections))
    }
  }

  private suspend fun executeV2(
    context: DownloadContext,
    resolved: ResolvedSource,
    previous: TorrentSourceState?,
  ) {
    require(previous == null || previous.version == 3) { "Invalid v2 resume state" }
    val bytes = decodeBase64(resolved.metadata.getValue(ResolvedSource.METAINFO_KEY))
    val document = TorrentV2Document.parse(bytes, maxDocumentBytes = config.maxMetadataBytes,
      maxFiles = config.maxFilesPerTorrent)
    val table = fileTables.get(bytes) { TorrentFileTable.parse(bytes, config) }
    // Read once: a later revision reaches the running owner through the collector.
    val initial = context.selection.value
    val selected = selectionOf(table, initial.fileIds, previous)
    val output = context.outputPath ?: previous?.savePath?.takeIf { it.isNotEmpty() }
      ?: error("Torrent output path has not been resolved")
    require("://" !in output) { "Torrent output requires a filesystem path" }
    val privacy = previous?.privacy ?: resolvedPrivacy(resolved)
    val hash = document.info.hash.hex
    val total = table.totalOf(selected)
    val state = TorrentSourceState(hash, total, previous?.resumeData.orEmpty(), selected, output,
      previous?.metainfo.orEmpty(), version = 3, privacy = privacy)
    log.i {
      "Starting v2 torrent taskId=${context.taskId} (${logHash(hash)}): " +
        "files=${selected.size}/${table.files.size}, totalBytes=$total, output=$output, " +
        "resume=${previous != null}"
    }
    runSession(context, hash, table, state, initial.revision) {
      (getEngine() as KotlinTorrentEngine).addV2Task(TorrentV2TaskSpec(context.taskId, document,
        output, selected,
        checkpointEncoded = previous?.resumeData?.takeIf { it.isNotEmpty() },
        trackerTiers = sourceTrackerTiers(bytes, config.maxMetadataBytes),
        magnetUri = context.url.takeIf { it.startsWith("magnet:", true) },
        privacy = privacy, throttle = context.throttle, recoverCreations = true,
        // The creation log may still be bound to the selection the task last ran with.
        legacySelections = listOfNotNull(previous?.selectedFileIds?.takeIf { it.isNotEmpty() }),
        // Task-scoped: a session that keeps seeding after this run returns still reports.
        connections = context.connections,
      ))
    }
  }

  /**
   * The files a run starts with, in metainfo order: [requested], the selection Ketch saved, else
   * the one [previous] mirrors (records older than selection changes), else every file.
   */
  private fun selectionOf(
    table: TorrentFileTable,
    requested: Set<String>,
    previous: TorrentSourceState?,
  ): Set<String> {
    // Selections of older builds may name padding files, which tables no longer list.
    val ids = requested.ifEmpty { previous?.selectedFileIds.orEmpty() }
      .filterNotTo(LinkedHashSet()) { it in table.padding }.ifEmpty { table.ids }
    require(ids.all { table[it] != null }) { "Invalid file selection" }
    return table.ordered(ids)
  }

  /**
   * Runs the task's session on one of the engine's slots until it finishes, adopting the session
   * a previous run left running, such as one still seeding, instead of opening a new one. [open]
   * creates the session for [state]'s selection, which [initialRevision] acknowledges.
   */
  private suspend fun runSession(
    context: DownloadContext,
    hash: String,
    table: TorrentFileTable,
    state: TorrentSourceState,
    initialRevision: Int,
    open: suspend () -> TorrentSession,
  ) {
    val taskId = context.taskId
    // Only this run's completion counts: an earlier run's no longer stands for the task.
    deferredSharing.update { it - taskId }
    val adopted = adoptLive(taskId, hash)
    val following = {
      table.ordered(context.selection.value.fileIds.ifEmpty { state.selectedFileIds })
    }
    // Before its session reports, a run shows the progress Ketch restored or planned over its own
    // files, as track() does: Ketch may run it again with a context whose segments describe the
    // files the last run had.
    val fit = {
      val held = context.segments.value.associate { it.index to it.downloadedBytes }
      val ids = following()
      context.segments.value = table.segments(ids) { held[it.id.toInt()] ?: 0 }
      table.totalOf(ids)
    }
    withActiveSlot(context, fit, hash, owned = adopted != null) {
      if (adopted == null) tasks.reserve(taskId, hash)
      var session: TorrentSession? = adopted
      var keepSeeding = false
      var lent = false
      try {
        rememberState(taskId, state)
        val running = adopted ?: open().also { created ->
          session = created
          tasks.attach(taskId, created)
          // The new session runs the selection read at the start of this run.
          context.acknowledgeSelection(initialRevision)
        }
        // Payload pays this run's task limits, also on a session an earlier run opened.
        running.setTaskThrottle(context.throttle)
        // An adopted session applies the current selection first, whatever it ran with.
        keepSeeding = track(context, running, table, state.selectedFileIds, initialRevision,
          adopted = adopted != null)
        // Paused uploads finish a completed session that would have seeded.
        if (!keepSeeding) deferSharing(taskId, seeded = false)
        onTransferEnd(taskId)
      } catch (e: Throwable) {
        keepSeeding = false
        throw e
      } finally {
        val owned = session
        withContext(NonCancellable) {
          try {
            try {
              if (!keepSeeding) owned?.pause()
              store(context)
              // Seeding is optional: a download waiting for this slot takes precedence.
              if (keepSeeding && owned != null) {
                // The task's limit ends with this run, Ketch's global one still applies should
                // the seeder fetch a piece again; a run that takes the seeder over sets its own.
                owned.setTaskThrottle(context.globalThrottle)
                lent = lendSlot(taskId, owned)
              }
              // A pause may stop this seeder before the completion can save its intent.
              if (lent) deferSharing(taskId, seeded = true)
            } finally {
              // Removal closes the store before the task is released: cleanup cannot race it.
              if (!lent) engine.load()?.removeTorrent(hash)
            }
          } finally {
            if (!lent) tasks.release(taskId)
          }
        }
      }
      lent
    }
  }

  /**
   * The task's session left running by its previous run, such as one still seeding, when this
   * run takes it over together with its slot. A session that cannot be taken over is stopped, or
   * awaited while whoever took its slot stops it, and this run then starts its own.
   */
  private suspend fun adoptLive(taskId: String, hash: String): TorrentSession? {
    val existing = tasks.session(taskId)
    if (existing == null) {
      if (tasks.isReserved(taskId)) awaitRelease(taskId)
      return null
    }
    stateMutex.withLock {
      unmarkSeeding(taskId)
      seedWatchers.remove(taskId)
    }?.cancel()
    // The run saves the session's state from now on.
    seedStates.update { it - taskId }
    val owned = slots.reclaim(taskId)
    if (owned && existing.infoHash == hash && existing.state.value in ADOPTABLE) {
      log.i { "Torrent taskId=$taskId takes over its running session" }
      // A restored seeder downloads for this run, and once it seeds again as one would.
      existing.endSeedOnly()
      // One still checking its files: the startSeeding that opened it returns now, leaving the
      // session, its reservation and its slot to this run.
      checkingSeeders.getAndUpdate { it - taskId }[taskId]
        ?.takeIf { it.session === existing }?.takenOver?.value = true
      return existing
    }
    if (owned) {
      try { stopSession(taskId) } finally { withContext(NonCancellable) { slots.release() } }
    } else {
      // A download that took its slot, or its seed watcher, is stopping it.
      awaitRelease(taskId)
    }
    return null
  }

  private suspend fun awaitRelease(taskId: String) {
    withTimeoutOrNull(RELEASE_TIMEOUT_MS) { tasks.awaitRelease(taskId) }
      ?: throw IllegalStateException("The previous torrent session is still stopping")
  }

  /**
   * Lends the task's slot to its seeding [session], watched until it stops; false when a
   * download waits for the slot, and the caller stops the session.
   */
  private suspend fun lendSlot(taskId: String, session: TorrentSession): Boolean {
    val lent = slots.lend(taskId)
    log.i {
      if (lent) "Torrent taskId=$taskId keeps seeding"
      else "Torrent taskId=$taskId stops seeding for a waiting download"
    }
    if (lent) {
      // The state the run saved last, which the seeder saves its checkpoint into from now on.
      val base = stateMutex.withLock { states[taskId] }
      if (base != null) seedStates.update { it + (taskId to base) }
      // Marked before the watcher starts, which follows whether it seeds from then on.
      markSeeding(taskId)
      watchSeeder(taskId, session)
    }
    return lent
  }

  /**
   * The only seed watcher, following a lent seeder until it stops: it reports the seeder seeding
   * while it is [TorrentSessionState.SEEDING], and not while it downloads a piece it lost again,
   * such as one of a file the owner edited, after which it seeds again; it saves what the seeder
   * uploads meanwhile. Once the seeder stops by itself, such as when the upload policy stops
   * seeding or its storage fails, it takes the slot back and stops it. [release], eviction,
   * adoption and [close] cancel it.
   */
  private suspend fun watchSeeder(taskId: String, session: TorrentSession) {
    val watcher = scope.launch(start = CoroutineStart.LAZY) {
      val self = coroutineContext.job
      // What it uploads is saved about every minute, so a crash loses little of it.
      val saver = launch {
        while (true) {
          delay(seedingSaveIntervalMs)
          seedStates.value[taskId]?.let { saveSeedingState(taskId, session, it) }
        }
      }
      try {
        session.state.takeWhile {
          it != TorrentSessionState.STOPPED && it != TorrentSessionState.FINISHED
        }.collect { state ->
          stateMutex.withLock {
            if (seedWatchers[taskId] !== self) return@withLock
            if (state == TorrentSessionState.SEEDING) markSeeding(taskId) else unmarkSeeding(taskId)
          }
        }
      } finally {
        saver.cancel()
      }
      // Once it reclaimed the slot, it stops the seeder and returns the slot, whatever happens.
      withContext(NonCancellable) {
        stateMutex.withLock {
          if (seedWatchers[taskId] === self) {
            unmarkSeeding(taskId)
            seedWatchers.remove(taskId)
          }
        }
        if (slots.reclaim(taskId)) {
          log.i { "Torrent taskId=$taskId stopped seeding; its slot is free" }
          try { stopSession(taskId) } finally { slots.release() }
        }
      }
    }
    stateMutex.withLock { seedWatchers.put(taskId, watcher)?.cancel() }
    watcher.start()
  }

  /**
   * Reports [session]'s progress until it finishes, applying each selection revision the task
   * receives meanwhile without reconnecting, and returns whether it keeps seeding; a stopped
   * session fails the task. A revision is acknowledged only when the session takes it live and
   * this run still follows it: the gate closes before the run returns, so no acknowledgment can
   * arrive after it decided to. [files] are the files this run starts with, selection revision
   * [revision], also shown for sessions that report none. An [adopted] session, which an earlier
   * run left running, applies the current selection first.
   *
   * Until the session has checked its files and runs this run's selection, the task keeps the
   * progress of each file it started with, from the [DownloadContext.segments] Ketch restored or
   * planned: a session checking its files reports none yet, and one taken over still describes
   * the files an earlier run gave it. Should it check them again, the task keeps what the session
   * reported last. Either way the task shows that progress over the files it follows now, which
   * a selection taken meanwhile changes, as [planSelection] fits saved progress to a selection.
   * The task follows a new selection from the moment Ketch delivers it, as Ketch shows it then,
   * also while the session takes it or an earlier one, which can last: until it has, the session
   * reports the files it had, and one checking its files checks them again.
   */
  private suspend fun track(
    context: DownloadContext,
    session: TorrentSession,
    table: TorrentFileTable,
    files: Set<String>,
    revision: Int,
    adopted: Boolean,
  ): Boolean = coroutineScope {
    val taskId = context.taskId
    val gate = Mutex()
    // All guarded by gate.
    var attached = true
    var appliedRevision = if (adopted) -1 else revision
    // Whether the session is taking a selection.
    var applying = false
    // The files the run follows: the ones it started with until it receives a selection, then
    // the latest it received, also while the session still takes an earlier one. Ketch may run it
    // again for a selection that came as the last run finished, with segments that still describe
    // that run's files.
    var following = files
    // The selection revision of [following].
    var followed = revision
    // Each file's progress by file index, as Ketch restored or planned it, or as the session
    // last reported it, kept for files deselected meanwhile.
    val held = context.segments.value.associateTo(HashMap()) { it.index to it.downloadedBytes }
    // The size of the files the segments describe.
    var total = 0L

    /** Under gate: follows the latest selection the run received, which Ketch shows already. */
    fun follow() {
      val latest = context.selection.value
      if (latest.revision > followed) {
        following = table.ordered(latest.fileIds.ifEmpty { table.ids })
        followed = latest.revision
      }
    }

    /** Under gate: shows [held] over the files the run follows. */
    fun hold() {
      follow()
      context.segments.value = table.segments(following) { held[it.id.toInt()] ?: 0 }
      total = table.totalOf(following)
    }
    hold()
    val collector = launch {
      context.selection.collect { update ->
        if (gate.withLock { update.revision <= appliedRevision }) return@collect
        val ids = table.ordered(update.fileIds.ifEmpty { table.ids })
        // Shown until the session reports the new selection, as taking it can last: one taken
        // while the session checks its files starts the check again.
        gate.withLock {
          if (update.revision >= followed) {
            following = ids
            followed = update.revision
          }
          applying = true
          hold()
        }
        val live = session.changeSelection(ids)
        stateMutex.withLock {
          states[taskId]?.let {
            states[taskId] = it.copy(selectedFileIds = ids, totalBytes = table.totalOf(ids))
          }
        }
        gate.withLock {
          appliedRevision = update.revision
          applying = false
          if (attached && live) context.acknowledgeSelection(update.revision)
        }
        log.i {
          "Torrent taskId=$taskId selection applied: files=${ids.size}/${table.files.size}, " +
            "totalBytes=${table.totalOf(ids)}, live=$live"
        }
      }
    }
    val connections = launch {
      context.maxConnections.collect { value ->
        when (session) {
          is KotlinTorrentSession -> session.setConnections(
            torrentPeerLimit(value, config.connectionsPerTorrent, MAX_V1_PEERS))
          is TorrentV2DownloadSession -> session.setConnections(
            torrentPeerLimit(value, config.connectionsPerTorrent, MAX_V2_PEERS))
        }
      }
    }
    val clock = monotonicClock()
    val v2Speed = TorrentV2Speed()
    var seeding = false
    try {
      if (!adopted) session.resume()
      var lastState: TorrentSessionState? = null
      while (true) {
        val status = session.state.value
        if (status != lastState) {
          log.d { "Torrent taskId=$taskId session state: $status" }
          lastState = status
        }
        // The revision the session runs, once its report describes the files the run follows: it
        // took the last selection the run received.
        val reporting = if (status in CHECKED) {
          gate.withLock {
            follow()
            appliedRevision.takeIf { it == followed && !applying }
          }
        } else {
          null
        }
        if (reporting != null) {
          val chosen = session.selectedFileIds.ifEmpty { files }
          val reported = when (session) {
            is KotlinTorrentSession -> {
              val progress = session.fileProgress()
              table.segments(chosen) { progress.getOrElse(it.id.toInt()) { 0 } }
            }
            is TorrentV2DownloadSession -> {
              val progress = session.fileProgress()
              table.segments(chosen) { progress[it.id] ?: 0 }
            }
            else -> table.segments(chosen) { 0 }
          }
          gate.withLock {
            follow()
            if (applying || appliedRevision != reporting || followed != reporting) {
              // A selection arrived as the session reported, which may still describe the last.
              hold()
            } else {
              context.segments.value = reported
              total = table.totalOf(chosen)
              reported.forEach { held[it.index] = it.downloadedBytes }
            }
          }
        } else {
          // The selection may have changed since the session last reported.
          gate.withLock { hold() }
        }
        context.reportedSpeed.value = if (session is TorrentV2DownloadSession) {
          // Blocks arrive whole, under low limits seconds apart: measured across several.
          v2Speed.sample(clock(), session.receivedBytes())
        } else session.downloadSpeed
        val (downloaded, size) = gate.withLock {
          context.segments.value.sumOf { it.downloadedBytes } to total
        }
        context.onProgress(downloaded, size)
        when (status) {
          // The task completes either way; a seeding session outlives it.
          TorrentSessionState.FINISHED -> {
            gate.withLock {
              attached = false
              collector.cancel()
            }
            break
          }
          // Seeding covers the latest selection only once the session took it.
          TorrentSessionState.SEEDING -> {
            val done = gate.withLock {
              (appliedRevision == context.selection.value.revision &&
                session.state.value == TorrentSessionState.SEEDING).also {
                if (it) {
                  attached = false
                  collector.cancel()
                }
              }
            }
            if (done) {
              seeding = true
              break
            }
            delay(200)
          }
          TorrentSessionState.STOPPED -> throw (when (session) {
            is KotlinTorrentSession -> session.failure.value
            is TorrentV2DownloadSession -> session.failure.value
            else -> null
          } ?: IllegalStateException("Torrent session stopped"))
          else -> delay(200)
        }
      }
    } finally {
      connections.cancel()
      collector.cancel()
    }
    seeding
  }

  /**
   * Runs [block] holding one of the engine's [TorrentConfig.maxActiveTorrents] slots. When every
   * slot is taken the task waits, cancellably, by priority and then arrival instead of failing;
   * the oldest seeder yields its slot first. A seeder of the same torrent [hash], such as a
   * finished task's, gives way to the new task and hands it its slot, since one swarm has one
   * owner. A caller that already [owned] a slot, taken back from its own seeder, keeps it.
   * [block] returns true when it lent the slot to a seeding session, which gives it back on
   * eviction or [release]. While waiting, the task reports [total] as its size.
   */
  private suspend fun withActiveSlot(
    context: DownloadContext,
    /** Fits the task's segments to the files it follows; returns their size. */
    fit: () -> Long,
    hash: String,
    owned: Boolean,
    block: suspend () -> Boolean,
  ) {
    val taskId = context.taskId
    val evicted = if (owned) null else {
      // Only a lent seeder can be reclaimed; an active download of the same torrent still
      // refuses this task when it reserves the hash.
      val sameTorrent = tasks.ownerOf(hash)?.takeIf { it != taskId && slots.reclaim(it) }
      sameTorrent ?: try {
        slots.acquire(context.request.priority) {
          waitingForSlot.update { it + taskId }
          log.i {
            "Torrent taskId=$taskId waits for one of ${config.maxActiveTorrents} " +
              "active torrent slots"
          }
          // The task holds a Ketch download slot, so it reports as downloading (which also lets
          // it be paused) at 0 bytes/s with its restored progress; waiting transfers nothing.
          context.reportedSpeed.value = 0
          val total = fit()
          context.onProgress(context.segments.value.sumOf { it.downloadedBytes }, total)
        }
      } finally {
        waitingForSlot.update { it - taskId }
      }
    }
    var lent = false
    try {
      evicted?.let {
        log.i { "Stopping seeding taskId=$it to start taskId=$taskId" }
        stopSession(it)
      }
      lent = block()
    } finally {
      if (!lent) withContext(NonCancellable) { slots.release() }
    }
  }

  /**
   * Finished snapshots are bounded; persisted task state and the ownership journal recover tasks
   * after eviction. Active entries must remain until the final core snapshot.
   */
  private suspend fun rememberState(taskId: String, state: TorrentSourceState) =
    stateMutex.withLock {
      while (states.size >= 128 && taskId !in states) {
        val finished = checkNotNull(states.keys.firstOrNull { !tasks.isReserved(it) }) {
          "Too many pending torrent tasks"
        }
        states.remove(finished)
      }
      states[taskId] = state
    }

  /**
   * Stops a session whose download already returned, such as one still seeding, saving a
   * seeder's state first.
   */
  private suspend fun stopSession(taskId: String) {
    stateMutex.withLock {
      unmarkSeeding(taskId)
      seedWatchers.remove(taskId)
    }?.cancel()
    val base = seedStates.getAndUpdate { it - taskId }[taskId]
    val session = tasks.session(taskId) ?: return
    try {
      base?.let { saveSeedingState(taskId, session, it) }
      engine.load()?.removeTorrent(session.infoHash)
    } finally {
      tasks.release(taskId)
    }
  }

  /** Stops a session that outlived its download, such as one still seeding. */
  internal suspend fun release(taskId: String) {
    deferredSharing.update { it - taskId }
    // The task is going away: its record takes no more state.
    seedStates.update { it - taskId }
    val owned = slots.reclaim(taskId)
    try {
      stopSession(taskId)
    } finally {
      if (owned) withContext(NonCancellable) { slots.release() }
    }
  }

  override suspend fun cleanup(
    context: DownloadContext,
    state: TorrentSourceState?,
    deleteFiles: Boolean,
  ) {
    release(context.taskId)
    if (state == null) return
    if (!deleteFiles) {
      forget(context, state)
      return
    }
    try {
      if (state.version == 3) {
        cleanupV2(context, state)
        return
      }
      if (tasks.session(context.taskId) != null) {
        engine.load()?.removeTorrent(state.infoHash, deleteFiles = true)
      }
      tasks.release(context.taskId)
      val store = v1Store(context, state) ?: return
      store.cleanup()
      stateMutex.withLock { states.remove(context.taskId) }
    } catch (e: CancellationException) { throw e
    } catch (e: Exception) {
      // Unknown or legacy ownership is conservatively preserved.
      log.w(e) {
        "Kept torrent files of taskId=${context.taskId}: cleanup could not prove ownership"
      }
    }
  }

  /**
   * Deletes what a task removed with its files kept had beside them: a v1 task's sidecar folder
   * (checkpoint, ownership journal, pieces of skipped files) or a v2 task's creation log. Nothing
   * reads them once the task is gone, and they would stay in the download folder for good.
   */
  private suspend fun forget(context: DownloadContext, state: TorrentSourceState) {
    try {
      if (state.version == 3) {
        val store = v2Store(context, state)
        try { store.forget() } finally { store.close() }
      } else {
        v1Store(context, state)?.forget()
      }
      stateMutex.withLock { states.remove(context.taskId) }
    } catch (e: CancellationException) { throw e
    } catch (e: Exception) {
      log.w { "Kept the bookkeeping of taskId=${context.taskId}: ${e.describeWithoutUrls()}" }
    }
  }

  /** [state]'s v1 store with the files it owns recovered, or null when nothing was saved. */
  private suspend fun v1Store(
    context: DownloadContext,
    state: TorrentSourceState,
  ): TorrentPieceStore? {
    val checkpoint = state.resumeData.takeIf { it.isNotEmpty() }
      ?.let { TorrentCheckpoint.decode(decodeBase64(it)) }
    val metadata = checkpoint?.metadata
      ?: metainfoOf(context.preResolved, state)?.let { TorrentMetadata.fromBencode(it) }
      ?: return null
    val output = context.outputPath ?: checkpoint?.output ?: state.savePath
    if (output.isEmpty()) return null
    val store = TorrentPieceStore(metadata, output.toPath(),
      state.selectedFileIds.map { it.toInt() }.toSet(), context.taskId)
    checkpoint?.let { store.restore(it) }
    store.recoverOwnership()
    return store
  }

  private suspend fun cleanupV2(context: DownloadContext, state: TorrentSourceState) {
    val store = v2Store(context, state)
    try {
      store.cleanup()
      stateMutex.withLock { states.remove(context.taskId) }
    } finally { store.close() }
  }

  /** [state]'s v2 store with the paths its creation log names recovered; the caller closes it. */
  private suspend fun v2Store(
    context: DownloadContext,
    state: TorrentSourceState,
  ): TorrentV2PieceStore {
    check(!tasks.isReserved(context.taskId)) { "Join the torrent download before cleanup" }
    val metainfo = requireNotNull(metainfoOf(context.preResolved, state)) { "No saved metainfo" }
    val document = TorrentV2Document.parse(metainfo,
      maxDocumentBytes = config.maxMetadataBytes, maxFiles = config.maxFilesPerTorrent)
    require(document.info.hash.hex == state.infoHash) { "Resume torrent changed" }
    val output = (context.outputPath ?: state.savePath).toPath()
    val absolute = torrentSystemFileSystem.canonicalize(".".toPath()).resolve(output).normalized()
    val selected = if (state.resumeData.isEmpty() && state.savePath.isEmpty()) {
      context.request.selectedFileIds.ifEmpty { state.selectedFileIds }
    } else state.selectedFileIds
    val checkpoint = state.resumeData.takeIf { it.isNotEmpty() }?.let {
      requireNotNull(TorrentV2Checkpoint.decode(decodeBase64(it)))
    }
    // The creation log may be bound to any selection the task ran with: the checkpoint's, the
    // one the state mirrors, or the record's.
    val store = TorrentV2PieceStore(document, absolute, selected, context.taskId,
      TorrentBufferBudget(config.maxBufferedBytes),
      Semaphore(config.maxOpenPayloadFiles),
      creationLogPath = v2CreationLog(absolute, context.taskId),
      legacySelections = listOfNotNull(checkpoint?.selected, state.selectedFileIds,
        context.request.selectedFileIds).filter { it.isNotEmpty() })
    try {
      checkpoint?.let { store.restore(it) }
      store.recoverOwnership()
    } catch (e: Throwable) {
      store.close()
      throw e
    }
    return store
  }

  companion object {
    const val TYPE = "torrent"
    internal const val META_INFO_HASH = "infoHash"
    internal const val META_NAME = "name"
    internal const val META_PIECE_LENGTH = "pieceLength"
    internal const val META_COMMENT = "comment"
    internal const val META_PRIVACY = "discoveryPrivacy"
  }
}

/**
 * Platform-specific factory for [TorrentEngine].
 * Implemented in jvmAndAndroid source set.
 */
internal expect fun createTorrentEngine(
  config: TorrentConfig,
): TorrentEngine

/** Peer caps accepted by v1 and v2 sessions. */
internal const val MAX_V1_PEERS = 512
internal const val MAX_V2_PEERS = 500

/** A magnet's metadata lookup ended without finding it. */
private class MetadataNotFoundException : Exception("Torrent metadata resolution timed out")

/** Pause between a task's magnet lookups, so one that ends at once cannot spin. */
private const val MAGNET_LOOKUP_PAUSE_MS = 1_000L

/** How long a run waits for its task's previous session to stop before it fails. */
private const val RELEASE_TIMEOUT_MS = 10_000L

/** How often a seeding session saves its state, with what it uploaded, into its task's record. */
private const val SEEDING_SAVE_INTERVAL_MS = 60_000L

/** How long the seeding sessions may take to save their state as the source stops. */
private const val SHUTDOWN_SAVE_MS = 5_000L

/** Saved states whose payload counters [TorrentDownloadSource.savedPayload] remembers. */
private const val MAX_SAVED_PAYLOADS = 64

/** Session states a run takes over instead of starting its own session. */
private val ADOPTABLE = setOf(TorrentSessionState.CHECKING_FILES,
  TorrentSessionState.DOWNLOADING, TorrentSessionState.SEEDING)

/**
 * Session states whose progress counts the verified bytes of their files; a session checking
 * them, or about to, reports none yet.
 */
private val CHECKED = setOf(TorrentSessionState.DOWNLOADING, TorrentSessionState.SEEDING,
  TorrentSessionState.FINISHED)

/** How long the first engine start waits for the saved tracker list copy to be read. */
private const val TRACKER_LIST_RESTORE_MS = 5_000L

/**
 * Maps a task's connection setting onto its torrent peer cap. For torrents, Ketch's per-task
 * connections count peers, not HTTP segments: a positive value is clamped to 1..[max], and 0
 * (unset) uses [default], normally [TorrentConfig.connectionsPerTorrent].
 */
internal fun torrentPeerLimit(requested: Int, default: Int, max: Int): Int =
  (if (requested > 0) requested else default).coerceIn(1, max)

/**
 * Maps a torrent failure onto Ketch's retry policy. Peer, tracker and DHT failures are retried
 * inside the swarm, so only timeouts that reach a task (such as magnet metadata resolution) are
 * transient [KetchError.Network]. Storage failures are [KetchError.Disk]; verification, protocol
 * and admission failures are [KetchError.SourceError]. Neither is retried. A source error carries
 * the failure's message, without URLs, as its `detail`, which remote clients receive too: the
 * apps show it rather than guess at a cause.
 */
internal fun torrentFailure(error: Throwable): KetchError = when (error) {
  is KetchError -> error
  is TimeoutCancellationException -> KetchError.Network(error)
  is TorrentStorageException -> KetchError.Disk(error.cause)
  is IOException -> KetchError.Disk(error)
  else -> torrentSourceError(error)
}

/** A [KetchError.SourceError] for [error], with its message, without URLs, as the `detail`. */
internal fun torrentSourceError(error: Throwable): KetchError.SourceError =
  KetchError.SourceError(TorrentDownloadSource.TYPE, error,
    detail = error.message?.let(::withoutUrls)?.trim()?.take(MAX_FAILURE_DETAIL)?.ifEmpty { null })

/** The longest failure message a torrent's source error carries. */
private const val MAX_FAILURE_DETAIL = 200

/** Platform-specific base64 encoding. */
internal fun encodeBase64(data: ByteArray): String = Base64.Default.encode(data)

/** Platform-specific base64 decoding. */
internal fun decodeBase64(data: String): ByteArray = Base64.Default.decode(data)
