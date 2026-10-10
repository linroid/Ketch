package com.linroid.ketch.torrent

import com.linroid.ketch.api.DownloadConfig
import com.linroid.ketch.api.FileSelectionMode
import com.linroid.ketch.api.KetchError
import com.linroid.ketch.api.KetchFeatures
import com.linroid.ketch.api.ResolvedSource
import com.linroid.ketch.api.SourceFile
import com.linroid.ketch.api.log.KetchLogger
import com.linroid.ketch.api.log.describeCauses
import com.linroid.ketch.api.log.redactUrl
import com.linroid.ketch.api.torrent.TorrentActivity
import com.linroid.ketch.api.torrent.TorrentCapability
import com.linroid.ketch.core.engine.DownloadContext
import com.linroid.ketch.core.engine.DownloadSource
import com.linroid.ketch.core.engine.HttpEngine
import com.linroid.ketch.core.engine.LiveTorrent
import com.linroid.ketch.core.engine.SeedingOutcome
import com.linroid.ketch.core.engine.SeedingTask
import com.linroid.ketch.core.engine.SelectionPlan
import com.linroid.ketch.core.engine.SelectionRequest
import com.linroid.ketch.core.engine.SourceResumeState
import com.linroid.ketch.core.engine.TorrentControlSource
import io.ktor.http.Url
import io.ktor.http.decodeURLPart
import kotlin.concurrent.atomics.AtomicBoolean
import kotlin.concurrent.atomics.AtomicLong
import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.io.encoding.Base64
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
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
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import okio.IOException
import okio.Path.Companion.toPath

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
 * when it last stopped, each only once a recheck finds its files complete.
 *
 * @param restoreSeeding whether [com.linroid.ketch.core.Ketch.start] shares again the completed
 *   tasks that were sharing; false for processes that share a task store with the apps, such as
 *   `ketch mcp`
 */
@OptIn(ExperimentalAtomicApi::class)
class TorrentDownloadSource(
  private val config: TorrentConfig = TorrentConfig(),
  httpEngine: HttpEngine? = null,
  private val restoreSeeding: Boolean = true,
) : DownloadSource, TorrentControlSource {
  private val httpDelegate = lazy { httpEngine?.let { TorrentHttp(it) } ?: TorrentHttp.default() }
  private val http by httpDelegate
  private val additionalTrackers = AtomicReference(config.additionalTrackers)
  private val listedTrackers = AtomicReference(emptyList<String>())
  private val uploadPolicy = AtomicReference(config.effectiveUploadPolicy)
  private val uploadRateLimit = AtomicLong(config.uploadRateLimit)

  /** The upload policy of running and later torrents: the configured one or the last set. */
  internal val currentUploadPolicy: TorrentUploadPolicy get() = uploadPolicy.load()

  /** Creates the engine from [engineConfig], under [engineMutex]. */
  internal var engineFactory: (TorrentConfig) -> TorrentEngine = {
    KotlinTorrentEngine(it, http = http)
  }
  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
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
  private val engineMutex = Mutex()
  private val tasks = TorrentSessionRegistry()
  private val slots = TorrentActiveSlots(config.maxActiveTorrents)
  // Magnet fetches beyond the runtime's pending-metadata bound queue instead of failing.
  private val metadataFetches = Semaphore(MAX_PENDING_METADATA)
  private val stateMutex = Mutex()
  private val states = linkedMapOf<String, TorrentResumeState>()
  // Guarded by stateMutex: what stops each seeding session's lent slot coming back.
  private val seedWatchers = mutableMapOf<String, Job>()
  private val fileTables = TorrentFileTables()
  private val seeding = MutableStateFlow<Set<String>>(emptySet())
  // Tasks waiting in withActiveSlot for one of the engine's slots.
  private val waitingForSlot = MutableStateFlow<Set<String>>(emptySet())
  private val log = KetchLogger("TorrentSource")
  override val type: String = TYPE
  override val managesOwnFileIo: Boolean = true
  override val features: Set<String> = setOf(KetchFeatures.TORRENT_FILE_SELECTION,
    KetchFeatures.TORRENT_AWAIT_FILE_SELECTION)

  override val torrentCapabilities: Set<String>
    get() = buildSet {
      add(TorrentCapability.FILE_SELECTION.wireName)
      add(TorrentCapability.V1.wireName)
      add(TorrentCapability.V2.wireName)
      add(TorrentCapability.HYBRID.wireName)
      // Sharing follows the live upload setting.
      if (uploadPolicy.load() == TorrentUploadPolicy.SEED_AFTER_COMPLETION) {
        add(TorrentCapability.SEEDING.wireName)
      }
    }

  override val seedingTaskIds: StateFlow<Set<String>> = seeding.asStateFlow()

  /** Only while the live upload setting seeds, so switching it on needs no restart. */
  override val restoresSeeding: Boolean
    get() = restoreSeeding &&
      uploadPolicy.load() == TorrentUploadPolicy.SEED_AFTER_COMPLETION

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
      if (closed.load()) { engine.exchange(null)?.close(); error("Torrent source is closed") }
      created
    } catch (e: Throwable) { created.close(); throw e }
  }

  override fun close() {
    if (closed.compareAndSet(false, true)) {
      slots.close()
      scope.cancel()
      seeding.value = emptySet()
      engine.exchange(null)?.close()
      if (httpDelegate.isInitialized()) http.close()
    }
  }

  /** The configuration of the next engine: the config with what the setters changed since. */
  private fun engineConfig(): TorrentConfig = config.copy(additionalTrackers = extraTrackers(),
    uploadPolicy = uploadPolicy.load(), uploadRateLimit = uploadRateLimit.load())

  /**
   * Replaces [TorrentConfig.uploadPolicy] at once: running torrents choke or unchoke their peers
   * within a rechoke, seeding ones finish unless [policy] is
   * [TorrentUploadPolicy.SEED_AFTER_COMPLETION], and torrents started later use it. Never starts
   * the engine.
   */
  suspend fun setUploadPolicy(policy: TorrentUploadPolicy) {
    engineMutex.withLock {
      uploadPolicy.store(policy)
      engine.load()?.setUploadPolicy(policy)
    }
    log.i { "Upload policy: $policy" }
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

  override fun canHandle(url: String): Boolean {
    val lower = url.lowercase()
    return lower.startsWith("magnet:") || lower.startsWith("torrent:") ||
      lower.substringBefore('?').substringBefore('#').endsWith(".torrent")
  }

  /** Accepts `.torrent` file names, or content that starts like a bencoded dictionary. */
  override fun canHandleContent(content: ByteArray, fileName: String?): Boolean {
    if (fileName?.endsWith(".torrent", ignoreCase = true) == true) return true
    // Metainfo is a dictionary whose first key is length-prefixed, e.g. "d8:announce".
    return content.size >= 2 && content[0] == 'd'.code.toByte() &&
      content[1] in '1'.code.toByte()..'9'.code.toByte()
  }

  /** Resolves dropped or picked `.torrent` bytes via [resolveMetainfo], off the caller thread. */
  override suspend fun resolveContent(content: ByteArray, fileName: String?): ResolvedSource =
    withContext(Dispatchers.Default) {
      try {
        resolveMetainfo(content)
      } catch (e: CancellationException) { throw e
      } catch (e: Exception) {
        if (e is KetchError) throw e
        throw KetchError.SourceError(TYPE, e)
      }
    }

  /** Resolve metainfo supplied by a file picker or SDK caller without making a network request. */
  fun resolveMetainfo(
    bytes: ByteArray,
    privacy: TorrentDiscoveryPrivacy = config.discoveryPrivacy,
  ): ResolvedSource = parseMetainfo(bytes, privacy).logged("metainfo bytes")

  private fun parseMetainfo(bytes: ByteArray, privacy: TorrentDiscoveryPrivacy): ResolvedSource {
    check(!closed.load()) { "Torrent source is closed" }
    resolveV2Metainfo(null, bytes, config, privacy)?.let { return it }
    val metadata = TorrentMetadata.fromBencode(bytes, config.maxMetadataBytes)
    return resolved("torrent:${metadata.infoHash.hex}", metadata, privacy)
  }

  /** Rebuilds the resolution a task saved, from the metainfo in its state; no network access. */
  override suspend fun resolveStored(resumeState: SourceResumeState): ResolvedSource? {
    val state = decodeResumeState(resumeState.data)
    if (state.metainfo.isEmpty()) return null
    val resolved = withContext(Dispatchers.Default) {
      parseMetainfo(decodeBase64(state.metainfo), state.privacy ?: config.discoveryPrivacy)
    }
    require(resolved.metadata[META_INFO_HASH] == state.infoHash) { "Saved torrent changed" }
    return resolved
  }

  /**
   * Validates and sizes a selection from the metainfo Ketch saved for the task (its state, else
   * its resolution, else a v1 checkpoint), never from file sizes a client sent. A v2 or hybrid
   * task that started refuses a newly chosen file whose path holds something it does not own.
   */
  override suspend fun planSelection(request: SelectionRequest): SelectionPlan {
    val state = request.resumeState?.let { decodeResumeState(it.data) }
    val table = fileTable(state, request.resolved)
      ?: throw IllegalStateException("The file list is not known yet")
    val requested = request.fileIds ?: table.ids
    require(requested.isNotEmpty()) { "Select at least one file" }
    require(requested.size <= maxOf(config.maxFilesPerTorrent, table.files.size)) {
      "Too many files selected"
    }
    require(requested.all { table[it] != null }) { "Unknown file id" }
    val ids = table.ordered(requested)
    val mirror = state?.selectedFileIds.orEmpty().filterTo(LinkedHashSet()) { table[it] != null }
    val effective = table.ordered(request.current.ifEmpty { mirror }.ifEmpty { table.ids })
    val added = ids - effective
    val output = request.outputPath ?: state?.savePath?.takeIf { it.isNotEmpty() }
    if (table.v2 && output != null && added.isNotEmpty()) {
      requireOwnedPaths(request.taskId, table, state, output, added)
    }
    val saved = request.segments.orEmpty().associateBy { it.index }
    val segments = table.segments(ids) { file ->
      if (request.completed && file.id in effective) file.size
      else saved[file.id.toInt()]?.downloadedBytes ?: 0
    }
    return SelectionPlan(ids, table.totalOf(ids), changed = ids != effective,
      expands = added.isNotEmpty(), segments = segments)
  }

  /**
   * Refuses newly chosen v2 files whose path exists without the task owning it: the v2 store
   * never takes over a file it did not create. A live session's ownership is the latest.
   */
  private suspend fun requireOwnedPaths(
    taskId: String,
    table: TorrentFileTable,
    state: TorrentResumeState?,
    output: String,
    added: Set<String>,
  ) {
    val live = (tasks.session(taskId) as? TorrentV2DownloadSession)?.saveResumeData()
    val checkpoint = live?.let(TorrentV2Checkpoint::decode)
      ?: state?.resumeData?.takeIf { it.isNotEmpty() && state.version == 3 }
        ?.let { TorrentV2Checkpoint.decode(decodeBase64(it)) }
    val owned = checkpoint?.owned.orEmpty().mapTo(HashSet()) { it.components }
    val root = output.toPath()
    withContext(Dispatchers.IO) {
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
   * The task's files from metadata the source holds: [state]'s metainfo, else [resolved]'s, else
   * a v1 checkpoint's; null when none holds any.
   */
  private suspend fun fileTable(
    state: TorrentResumeState?,
    resolved: ResolvedSource?,
  ): TorrentFileTable? {
    val bytes = state?.metainfo?.takeIf { it.isNotEmpty() }?.let(::decodeBase64)
      ?: resolved?.metadata?.get(ResolvedSource.METAINFO_KEY)?.takeIf { it.isNotEmpty() }
        ?.let(::decodeBase64)
      ?: state?.takeIf { it.version != 3 && it.resumeData.isNotEmpty() }
        ?.let { TorrentCheckpoint.decode(decodeBase64(it.resumeData))?.metadata?.metainfoBytes }
      ?: return null
    return withContext(Dispatchers.Default) {
      fileTables.get(bytes) { TorrentFileTable.parse(bytes, config) }
    }
  }

  override suspend fun torrentFiles(
    resumeState: SourceResumeState?,
    resolved: ResolvedSource?,
  ): List<SourceFile>? {
    val table = fileTable(resumeState?.let { decodeResumeState(it.data) }, resolved) ?: return null
    return table.files.map {
      SourceFile(it.id, it.path, it.size, metadata = mapOf("path" to it.path))
    }
  }

  override suspend fun liveTorrent(taskId: String): LiveTorrent? {
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
    return LiveTorrent(activity, counters.received, counters.uploaded, counters.uploadSpeed)
  }

  override suspend fun seedingAvailability(taskId: String): SeedingOutcome? = when {
    uploadPolicy.load() != TorrentUploadPolicy.SEED_AFTER_COMPLETION -> SeedingOutcome.POLICY_OFF
    tasks.isReserved(taskId) -> SeedingOutcome.ALREADY_ACTIVE
    !slots.hasFree() -> SeedingOutcome.NO_SLOT
    else -> null
  }

  /**
   * Seeds a completed task on a free slot, never one a download waits for: a seed-only session
   * checks the files first and contacts no tracker or peer unless they are complete. The slot is
   * lent from the start, so a download that needs it stops the seeder even while it checks.
   */
  override suspend fun startSeeding(task: SeedingTask): SeedingOutcome {
    val taskId = task.taskId
    seedingAvailability(taskId)?.let { return it }
    val plan = try {
      seedPlan(task)
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      log.w { "Torrent taskId=$taskId cannot seed: ${e.describeCauses()}" }
      return SeedingOutcome.FAILED
    }
    if (!slots.tryAcquire()) return SeedingOutcome.NO_SLOT
    var reserved = false
    var lent = false
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
      lent = slots.lend(taskId)
      if (!lent) {
        outcome = SeedingOutcome.NO_SLOT
        return outcome
      }
      log.i { "Torrent taskId=$taskId checks its files to seed (${logHash(plan.hash)})" }
      session.resume()
      val reached = session.state.first {
        it == TorrentSessionState.SEEDING || it == TorrentSessionState.STOPPED ||
          it == TorrentSessionState.FINISHED
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
        // The upload setting stopped seeding while it checked.
        else -> SeedingOutcome.FAILED
      }
      if (outcome == SeedingOutcome.SEEDING) {
        markSeeding(taskId)
        watchSeeder(taskId, session)
        log.i { "Torrent taskId=$taskId seeds again" }
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
      if (outcome != SeedingOutcome.SEEDING) withContext(NonCancellable) {
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

  /** A completed task's seed-only session: its identity and how to open it. */
  private class SeedPlan(val hash: String, val open: suspend () -> TorrentSession)

  private suspend fun seedPlan(task: SeedingTask): SeedPlan {
    val state = decodeResumeState(task.resumeState.data)
    val checkpoint = if (state.version == 3 || state.resumeData.isEmpty()) null else
      TorrentCheckpoint.decode(decodeBase64(state.resumeData))
    val bytes = state.metainfo.takeIf { it.isNotEmpty() }?.let(::decodeBase64)
      ?: checkpoint?.metadata?.metainfoBytes
      ?: error("The task saved no metainfo")
    val privacy = state.privacy ?: config.discoveryPrivacy
    val output = state.savePath.ifEmpty { task.outputPath }
    require(output.isNotEmpty() && "://" !in output) { "Torrent output requires a filesystem path" }
    val table = withContext(Dispatchers.Default) {
      fileTables.get(bytes) { TorrentFileTable.parse(bytes, config) }
    }
    val selected = selectionOf(table, task.selectedFileIds, state)
    val magnet = task.url.takeIf { it.startsWith("magnet:", true) }
    val resumeData = state.resumeData.takeIf { it.isNotEmpty() }
    if (table.v2) {
      require(state.version == 3) { "Invalid v2 resume state" }
      val document = TorrentV2Document.parse(bytes, maxDocumentBytes = config.maxMetadataBytes,
        maxFiles = config.maxFilesPerTorrent)
      val hash = document.info.hash.hex
      require(hash == state.infoHash) { "Saved torrent changed" }
      return SeedPlan(hash) {
        (getEngine() as KotlinTorrentEngine).addV2Task(TorrentV2TaskSpec(task.taskId, document,
          output, selected, checkpointEncoded = resumeData,
          trackerTiers = sourceTrackerTiers(bytes, config.maxMetadataBytes),
          magnetUri = magnet, privacy = privacy, recoverCreations = true,
          legacySelections = listOfNotNull(state.selectedFileIds.takeIf { it.isNotEmpty() }),
          seedOnly = true))
      }
    }
    require(state.version != 3) { "Cannot downgrade a v2 task" }
    val metadata = TorrentMetadata.fromBencode(bytes, config.maxMetadataBytes)
    val hash = metadata.infoHash.hex
    require(hash == state.infoHash) { "Saved torrent changed" }
    return SeedPlan(hash) {
      getEngine().addTask(TorrentTaskSpec(task.taskId, metadata, output,
        selected.mapTo(LinkedHashSet()) { it.toInt() }, magnet, resumeData?.let(::decodeBase64),
        privacy = privacy, seedOnly = true))
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
  override suspend fun stopSeeding(taskId: String) {
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

  /** Tasks holding a session reservation; for leak checks. */
  internal suspend fun reservedTasks(): Int = tasks.size()

  /** Engine slots owned or lent to seeders; for leak checks. */
  internal suspend fun slotsInUse(): Int = slots.inUse()

  /** The task's registered session; for tests. */
  internal suspend fun sessionOf(taskId: String): TorrentSession? = tasks.session(taskId)

  override suspend fun resolve(url: String, properties: Map<String, String>): ResolvedSource =
    resolve(url, config.discoveryPrivacy, properties)

  /** Resolve with an explicit privacy choice before any discovery or metadata request starts. */
  suspend fun resolve(
    url: String,
    privacy: TorrentDiscoveryPrivacy,
    properties: Map<String, String> = emptyMap(),
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
          http.fetch(url, config.maxMetadataBytes, headers = properties)
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
        withContext(Dispatchers.IO) {
          torrentSystemFileSystem.read(path.toPath()) {
            val result = readByteArray(minOf(config.maxMetadataBytes.toLong(),
              torrentSystemFileSystem.metadata(path.toPath()).size ?: 0L))
            require(exhausted()) { "Metainfo exceeds limit" }
            result
          }
        }
      }
      (resolveV2Metainfo(url, bytes, config, privacy)
        ?: resolved(url, TorrentMetadata.fromBencode(bytes, config.maxMetadataBytes), privacy))
        .logged(if (url.startsWith("http", true)) "HTTP metainfo" else "metainfo file")
    }
  }

  /**
   * Resolves [url] for a task. A magnet keeps looking for its metadata, in lookups of
   * [TorrentConfig.metadataTimeoutSeconds] each, until one finds it or the task stops: the peers
   * of a small swarm may come online at any time. Other inputs resolve as [resolve] does.
   */
  override suspend fun resolveForDownload(
    url: String,
    properties: Map<String, String>,
    config: DownloadConfig,
  ): ResolvedSource = resolveForTask(url, this.config.discoveryPrivacy, properties)

  private suspend fun resolveForTask(
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
          "No metadata for magnet ${logHash(MagnetUri.parse(url).infoHash.hex)} after " +
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
    url = url, sourceType = TYPE, totalBytes = metadata.totalBytes, supportsResume = true,
    suggestedFileName = metadata.name, maxSegments = metadata.files.size,
    metadata = buildMap {
      put(META_INFO_HASH, metadata.infoHash.hex)
      put(META_NAME, metadata.name)
      put(META_PIECE_LENGTH, metadata.pieceLength.toString())
      put(ResolvedSource.METAINFO_KEY, encodeBase64(metadata.metainfoBytes))
      put(META_PRIVACY, privacy.name)
      metadata.comment?.let { put(META_COMMENT, it) }
    },
    files = metadata.files.map { SourceFile(it.index.toString(), it.path, it.size,
      metadata = mapOf("path" to it.path)) },
    selectionMode = FileSelectionMode.MULTIPLE,
  )

  override fun buildResumeState(resolved: ResolvedSource, totalBytes: Long): SourceResumeState =
    encode(TorrentResumeState(resolved.metadata[META_INFO_HASH] ?: "", totalBytes, "",
      resolved.files.map { it.id }.toSet(), "",
      resolved.metadata[ResolvedSource.METAINFO_KEY] ?: "",
      version = if (resolved.metadata["format"] == "v2") 3 else 2,
      privacy = resolvedPrivacy(resolved)))

  private fun resolvedPrivacy(resolved: ResolvedSource): TorrentDiscoveryPrivacy =
    resolved.metadata[META_PRIVACY]?.let(TorrentDiscoveryPrivacy::valueOf)
      ?: config.discoveryPrivacy

  override suspend fun updateResumeState(context: DownloadContext): SourceResumeState? {
    val owner = tasks.session(context.taskId)
    val saved = owner?.saveResumeData()
    return stateMutex.withLock {
      val state = states[context.taskId] ?: return@withLock null
      val updated = if (saved != null) state.copy(resumeData = encodeBase64(saved)) else state
      if (owner == null) states.remove(context.taskId) else states[context.taskId] = updated
      encode(updated)
    }
  }

  override suspend fun download(context: DownloadContext) = reportingFailures {
    val resolved = context.preResolved
      ?: resolveForTask(context.url, config.discoveryPrivacy, context.headers)
    execute(context, resolved, null)
  }

  override suspend fun resume(
    context: DownloadContext,
    resumeState: SourceResumeState,
  ) = reportingFailures {
    val state = try { decodeResumeState(resumeState.data)
    } catch (e: Exception) { throw KetchError.CorruptResumeState(e.message, e) }
    val checkpoint = if (state.version == 3 || state.resumeData.isEmpty()) null else
      TorrentCheckpoint.decode(decodeBase64(state.resumeData))
    val privacy = state.privacy ?: config.discoveryPrivacy
    val resolved = when {
      state.metainfo.isNotEmpty() -> resolveMetainfo(decodeBase64(state.metainfo), privacy)
      checkpoint != null -> resolved(context.url, checkpoint.metadata, privacy)
      else -> resolveForTask(context.url, privacy, context.headers)
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
    previous: TorrentResumeState?,
  ) {
    val hash = requireNotNull(resolved.metadata[META_INFO_HASH])
    val bytes = decodeBase64(requireNotNull(resolved.metadata[ResolvedSource.METAINFO_KEY]))
    val v2 = resolveV2Metainfo(resolved.url, bytes, config, resolvedPrivacy(resolved))
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
    val state = TorrentResumeState(hash, total, previous?.resumeData ?: "", selected, output,
      encodeBase64(bytes), version = 2, privacy = privacy)
    log.i {
      "Starting torrent taskId=${context.taskId} (${logHash(hash)}): " +
        "files=${selected.size}/${metadata.files.size}, totalBytes=$total, output=$output, " +
        "resume=${previous != null}"
    }
    runSession(context, hash, table, state, initial.revision) {
      getEngine().addTask(TorrentTaskSpec(context.taskId, metadata, output,
        selected.mapTo(LinkedHashSet()) { it.toInt() },
        context.url.takeIf { it.startsWith("magnet:", true) },
        previous?.resumeData?.takeIf { it.isNotEmpty() }?.let(::decodeBase64), context.throttle,
        privacy = privacy))
    }
  }

  private suspend fun executeV2(
    context: DownloadContext,
    resolved: ResolvedSource,
    previous: TorrentResumeState?,
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
    val state = TorrentResumeState(hash, total, previous?.resumeData.orEmpty(), selected, output,
      encodeBase64(bytes), version = 3, privacy = privacy)
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
    previous: TorrentResumeState?,
  ): Set<String> {
    val ids = requested.ifEmpty { previous?.selectedFileIds.orEmpty() }.ifEmpty { table.ids }
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
    state: TorrentResumeState,
    initialRevision: Int,
    open: suspend () -> TorrentSession,
  ) {
    val taskId = context.taskId
    val adopted = adoptLive(taskId, hash)
    val total = { table.totalOf(context.selection.value.fileIds.ifEmpty { state.selectedFileIds }) }
    withActiveSlot(context, total, hash, owned = adopted != null) {
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
        // An adopted session applies the current selection first, whatever it ran with.
        keepSeeding = track(context, running, table, state.selectedFileIds,
          applied = if (adopted == null) initialRevision else -1, resume = adopted == null)
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
              updateResumeState(context)
              // Seeding is optional: a download waiting for this slot takes precedence.
              if (keepSeeding && owned != null) lent = lendSlot(taskId, owned)
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
    val owned = slots.reclaim(taskId)
    if (owned && existing.infoHash == hash && existing.state.value in ADOPTABLE) {
      log.i { "Torrent taskId=$taskId takes over its running session" }
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
      // Marked before the watcher starts, which unmarks it once it stops seeding.
      markSeeding(taskId)
      watchSeeder(taskId, session)
    }
    return lent
  }

  /**
   * The only seed watcher: reports that a lent seeder stopped seeding once it leaves
   * [TorrentSessionState.SEEDING], and takes the slot back once it stops by itself, such as when
   * the upload policy stops seeding or its storage fails, and stops it. [release], eviction,
   * adoption and [close] cancel it.
   */
  private suspend fun watchSeeder(taskId: String, session: TorrentSession) {
    val watcher = scope.launch(start = CoroutineStart.LAZY) {
      val self = coroutineContext.job
      session.state.first { it != TorrentSessionState.SEEDING }
      stateMutex.withLock { if (seedWatchers[taskId] === self) unmarkSeeding(taskId) }
      session.state.first {
        it == TorrentSessionState.STOPPED || it == TorrentSessionState.FINISHED
      }
      // Once it reclaimed the slot, it stops the seeder and returns the slot, whatever happens.
      withContext(NonCancellable) {
        stateMutex.withLock { if (seedWatchers[taskId] === self) seedWatchers.remove(taskId) }
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
   * arrive after it decided to. [applied] is the revision the session already runs; [fallback]
   * is the selection shown for sessions that report none.
   */
  private suspend fun track(
    context: DownloadContext,
    session: TorrentSession,
    table: TorrentFileTable,
    fallback: Set<String>,
    applied: Int,
    resume: Boolean,
  ): Boolean = coroutineScope {
    val taskId = context.taskId
    val gate = Mutex()
    // Both guarded by gate.
    var attached = true
    var appliedRevision = applied
    val collector = launch {
      context.selection.collect { update ->
        if (gate.withLock { update.revision <= appliedRevision }) return@collect
        val ids = table.ordered(update.fileIds.ifEmpty { table.ids })
        val live = session.changeSelection(ids)
        stateMutex.withLock {
          states[taskId]?.let {
            states[taskId] = it.copy(selectedFileIds = ids, totalBytes = table.totalOf(ids))
          }
        }
        gate.withLock {
          appliedRevision = update.revision
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
    var lastTime = clock()
    var lastReceived = (session as? TorrentV2DownloadSession)?.receivedBytes() ?: 0
    var seeding = false
    try {
      if (resume) session.resume()
      var lastState: TorrentSessionState? = null
      while (true) {
        val status = session.state.value
        if (status != lastState) {
          log.d { "Torrent taskId=$taskId session state: $status" }
          lastState = status
        }
        val chosen = session.selectedFileIds.ifEmpty { fallback }
        context.segments.value = when (session) {
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
        context.reportedSpeed.value = if (session is TorrentV2DownloadSession) {
          val now = clock()
          val received = session.receivedBytes()
          ((if (now > lastTime) (received - lastReceived) * 1000 / (now - lastTime) else 0))
            .also { lastReceived = received; lastTime = now }
        } else session.downloadSpeed
        context.onProgress(context.segments.value.sumOf { it.downloadedBytes },
          table.totalOf(chosen))
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
    total: () -> Long,
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
          context.onProgress(context.segments.value.sumOf { it.downloadedBytes }, total())
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
  private suspend fun rememberState(taskId: String, state: TorrentResumeState) =
    stateMutex.withLock {
      while (states.size >= 128 && taskId !in states) {
        val finished = checkNotNull(states.keys.firstOrNull { !tasks.isReserved(it) }) {
          "Too many pending torrent tasks"
        }
        states.remove(finished)
      }
      states[taskId] = state
    }

  /** Stops a session whose download already returned, such as one still seeding. */
  private suspend fun stopSession(taskId: String) {
    stateMutex.withLock {
      unmarkSeeding(taskId)
      seedWatchers.remove(taskId)
    }?.cancel()
    val session = tasks.session(taskId) ?: return
    try {
      engine.load()?.removeTorrent(session.infoHash)
    } finally {
      tasks.release(taskId)
    }
  }

  /** Stops a session that outlived its download, such as one still seeding. */
  override suspend fun release(taskId: String, resumeState: SourceResumeState?) {
    val owned = slots.reclaim(taskId)
    try {
      stopSession(taskId)
    } finally {
      if (owned) withContext(NonCancellable) { slots.release() }
    }
  }

  override suspend fun cleanup(context: DownloadContext, resumeState: SourceResumeState?) {
    if (resumeState == null) return
    try {
      val state = decodeResumeState(resumeState.data)
      if (state.version == 3) {
        cleanupV2(context, state)
        return
      }
      if (tasks.session(context.taskId) != null) {
        engine.load()?.removeTorrent(state.infoHash, deleteFiles = true)
      }
      tasks.release(context.taskId)
      val checkpoint = state.resumeData.takeIf { it.isNotEmpty() }
        ?.let { TorrentCheckpoint.decode(decodeBase64(it)) }
      val metadata = checkpoint?.metadata ?: state.metainfo.takeIf { it.isNotEmpty() }
        ?.let { TorrentMetadata.fromBencode(decodeBase64(it)) } ?: return
      val output = context.outputPath ?: checkpoint?.output ?: state.savePath
      if (output.isEmpty()) return
      val store = TorrentPieceStore(metadata, output.toPath(),
        state.selectedFileIds.map { it.toInt() }.toSet(), context.taskId)
      checkpoint?.let { store.restore(it) }
      store.recoverOwnership()
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

  private suspend fun cleanupV2(context: DownloadContext, state: TorrentResumeState) {
    check(!tasks.isReserved(context.taskId)) { "Join the torrent download before cleanup" }
    val document = TorrentV2Document.parse(decodeBase64(state.metainfo),
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
      store.cleanup()
      stateMutex.withLock { states.remove(context.taskId) }
    } finally { store.close() }
  }

  private fun encode(state: TorrentResumeState): SourceResumeState =
    SourceResumeState(TYPE, Json.encodeToString(state))

  companion object {
    const val TYPE = "torrent"
    internal const val META_INFO_HASH = "infoHash"
    internal const val META_NAME = "name"
    internal const val META_PIECE_LENGTH = "pieceLength"
    internal const val META_COMMENT = "comment"
    internal const val META_PRIVACY = "discoveryPrivacy"

    fun buildResumeState(
      infoHash: String,
      totalBytes: Long,
      resumeData: ByteArray,
      selectedFileIds: Set<String>,
      savePath: String,
    ): SourceResumeState {
      val state = TorrentResumeState(
        infoHash = infoHash,
        totalBytes = totalBytes,
        resumeData = encodeBase64(resumeData),
        selectedFileIds = selectedFileIds,
        savePath = savePath,
      )
      return SourceResumeState(
        sourceType = TYPE,
        data = Json.encodeToString(state),
      )
    }
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

/** Session states a run takes over instead of starting its own session. */
private val ADOPTABLE = setOf(TorrentSessionState.CHECKING_FILES,
  TorrentSessionState.DOWNLOADING, TorrentSessionState.SEEDING)

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
 * and admission failures are [KetchError.SourceError]. Neither is retried.
 */
internal fun torrentFailure(error: Throwable): KetchError = when (error) {
  is KetchError -> error
  is TimeoutCancellationException -> KetchError.Network(error)
  is TorrentStorageException -> KetchError.Disk(error.cause)
  is IOException -> KetchError.Disk(error)
  else -> KetchError.SourceError(TorrentDownloadSource.TYPE, error)
}

/** Platform-specific base64 encoding. */
internal fun encodeBase64(data: ByteArray): String = Base64.Default.encode(data)

/** Platform-specific base64 decoding. */
internal fun decodeBase64(data: String): ByteArray = Base64.Default.decode(data)
