package com.linroid.ketch.core

import com.linroid.ketch.api.Destination
import com.linroid.ketch.api.DownloadCondition
import com.linroid.ketch.api.DownloadConfig
import com.linroid.ketch.api.DownloadPriority
import com.linroid.ketch.api.DownloadProgress
import com.linroid.ketch.api.DownloadRequest
import com.linroid.ketch.api.DownloadSchedule
import com.linroid.ketch.api.DownloadState
import com.linroid.ketch.api.DownloadTask
import com.linroid.ketch.api.KetchApi
import com.linroid.ketch.api.KetchError
import com.linroid.ketch.api.KetchFeatures
import com.linroid.ketch.api.KetchStatus
import com.linroid.ketch.api.NetworkInterfaceConfig
import com.linroid.ketch.api.NetworkInterfaces
import com.linroid.ketch.api.PauseReason
import com.linroid.ketch.api.ProxyConfig
import com.linroid.ketch.api.ProxyMode
import com.linroid.ketch.api.ResolvedSource
import com.linroid.ketch.api.Segment
import com.linroid.ketch.api.SpeedLimit
import com.linroid.ketch.api.log.KetchLogger
import com.linroid.ketch.api.log.Logger
import com.linroid.ketch.api.log.describeCauses
import com.linroid.ketch.api.log.redactUrl
import com.linroid.ketch.core.engine.ConfigurableNetworkHttpEngine
import com.linroid.ketch.core.engine.DelegatingSpeedLimiter
import com.linroid.ketch.core.engine.DownloadCoordinator
import com.linroid.ketch.core.engine.DownloadQueue
import com.linroid.ketch.core.engine.DownloadScheduler
import com.linroid.ketch.core.engine.DownloadSource
import com.linroid.ketch.core.engine.HttpDownloadSource
import com.linroid.ketch.core.engine.HttpEngine
import com.linroid.ketch.core.engine.RequestHeaders
import com.linroid.ketch.core.engine.SeedingOutcome
import com.linroid.ketch.core.engine.SeedingTask
import com.linroid.ketch.core.engine.SelectionDelivery
import com.linroid.ketch.core.engine.SelectionPlan
import com.linroid.ketch.core.engine.SelectionRequest
import com.linroid.ketch.core.engine.SourceResolver
import com.linroid.ketch.core.engine.SpeedLimiter
import com.linroid.ketch.core.engine.TokenBucket
import com.linroid.ketch.core.engine.TorrentControlSource
import com.linroid.ketch.core.file.DefaultFileNameResolver
import com.linroid.ketch.core.file.FileNameResolver
import com.linroid.ketch.core.file.requireDownloadDirectory
import com.linroid.ketch.core.task.ControlOutcome
import com.linroid.ketch.core.task.InMemoryTaskStore
import com.linroid.ketch.core.task.RealDownloadTask
import com.linroid.ketch.core.task.TaskControl
import com.linroid.ketch.core.task.TaskController
import com.linroid.ketch.core.task.TaskHandle
import com.linroid.ketch.core.task.TaskRecord
import com.linroid.ketch.core.task.TaskState
import com.linroid.ketch.core.task.TaskStore
import com.linroid.ketch.core.task.awaitsFileSelection
import com.linroid.ketch.core.task.savedProgress
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.concurrent.Volatile
import kotlin.concurrent.atomics.AtomicBoolean
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.TimeSource
import kotlin.uuid.Uuid

/**
 * Core in-process implementation of [KetchApi]. No HTTP involved.
 *
 * @param httpEngine the HTTP engine for HTTP/HTTPS downloads. Proxies other than the system's
 *   need one that [supports them][HttpEngine.supportsProxies]; with any other, downloads given
 *   one fail with [KetchError.Unsupported]
 * @param taskStore persistent storage for task records
 * @param config initial global download configuration; replace it at
 *   runtime with [updateConfig]
 * @param name user-visible instance name included in [status]
 * @param fileNameResolver strategy for resolving download file names
 * @param additionalSources extra [DownloadSource] implementations
 *   (e.g., torrent, media). HTTP is always included as a fallback.
 * @param logger logging backend
 * @param dispatchers dedicated dispatchers for task management,
 *   network operations, and file I/O. The default pools have a fixed
 *   size: network transfers suspend instead of blocking a thread, so the
 *   pool size does not limit how many connections a download can use.
 */
@OptIn(ExperimentalAtomicApi::class)
class Ketch(
  private val httpEngine: HttpEngine,
  private val taskStore: TaskStore = InMemoryTaskStore(),
  config: DownloadConfig = DownloadConfig.Default,
  private val name: String = "Ketch",
  private val fileNameResolver: FileNameResolver = DefaultFileNameResolver(),
  additionalSources: List<DownloadSource> = emptyList(),
  logger: Logger = Logger.None,
  private val dispatchers: KetchDispatchers = KetchDispatchers(),
) : KetchApi {
  private val startMark = TimeSource.Monotonic.markNow()

  @Volatile
  private var currentConfig: DownloadConfig = config

  private val globalLimiter = DelegatingSpeedLimiter(
    if (config.speedLimit.isUnlimited) {
      SpeedLimiter.Unlimited
    } else {
      TokenBucket(config.speedLimit.bytesPerSecond)
    },
  )

  private val httpSource = HttpDownloadSource(httpEngine)

  private val sourceResolver = SourceResolver(
    additionalSources + httpSource,
  )

  /** Sources with torrent controls, such as seeding completed tasks. */
  private val controlSources: List<Pair<DownloadSource, TorrentControlSource>> =
    additionalSources.mapNotNull { source ->
      (source as? TorrentControlSource)?.let { source to it }
    }

  /** Set once [start] began following the control sources' seeding. */
  private val seedingFollowed = AtomicBoolean(false)

  private val features: Set<String> = buildSet {
    add(KetchFeatures.AUTO_CONNECTIONS)
    add(KetchFeatures.QUEUE_POSITION)
    add(KetchFeatures.REQUEST_ID)
    if (httpEngine.supportsProxies) add(KetchFeatures.PROXY)
    add(KetchFeatures.CATEGORY_FOLDERS)
    additionalSources.forEach { addAll(it.features) }
    if (KetchFeatures.FINITE_HLS in this && KetchFeatures.FINITE_DASH in this) {
      add(KetchFeatures.FINITE_MEDIA)
    }
  }

  override val backendLabel: String = "Core"

  private val log = KetchLogger("Ketch")

  /** Scope for task coordination (scheduling, queue, state). */
  private val scope = CoroutineScope(SupervisorJob() + dispatchers.main.limitedParallelism(1))

  private val coordinator = DownloadCoordinator(
    sourceResolver = sourceResolver,
    config = { currentConfig },
    fileNameResolver = fileNameResolver,
    globalLimiter = globalLimiter,
    dispatchers = dispatchers,
  )

  private val queue = DownloadQueue(
    maxConcurrentDownloads = config.maxConcurrentDownloads,
    maxConnectionsPerHost = config.maxConnectionsPerHost,
    coordinator = coordinator,
  )

  private val scheduler = DownloadScheduler(
    queue = queue,
    scope = scope,
  )

  private val tasksMutex = Mutex()
  private val submissionsMutex = Mutex()
  private val monitorMutex = Mutex()
  private val taskMonitors = mutableMapOf<String, Job>()
  private val _tasks = MutableStateFlow<List<DownloadTask>>(emptyList())

  /** Observable list of all download tasks. */
  override val tasks: StateFlow<List<DownloadTask>> = _tasks.asStateFlow()

  init {
    KetchLogger.setLogger(logger)
    log.i { "Ketch v${KetchApi.VERSION} (${KetchApi.REVISION}) initialized: name=$name" }
    log.d { "Initial config: $config" }
    if (!config.speedLimit.isUnlimited) {
      log.i { "Global speed limit: ${config.speedLimit}" }
    }
    if (additionalSources.isNotEmpty()) {
      log.i { "Additional sources: ${additionalSources.joinToString { it.type }}" }
    }
  }

  /**
   * Starts a new download and adds it to the [tasks] flow.
   * The task may be queued if the maximum number of concurrent
   * downloads has been reached.
   *
   * @throws IllegalArgumentException if a header in [DownloadRequest.headers] cannot be sent
   *   (see [RequestHeaders.requireValid])
   */
  override suspend fun download(request: DownloadRequest): DownloadTask {
    RequestHeaders.requireValid(request.headers)
    requireProxySupport(request.proxy)
    if (request.requestId == null) return createDownload(request)
    return submissionsMutex.withLock {
      val existing = tasks.value.find { it.request.requestId == request.requestId }
      if (existing != null) {
        // Live task controls, the file selection included, are mutable; metadata and conditions
        // are not persisted.
        require(existing.request.copy(
          connections = request.connections,
          speedLimit = request.speedLimit,
          priority = request.priority,
          schedule = request.schedule,
          selectedFileIds = request.selectedFileIds,
          awaitFileSelection = request.awaitFileSelection,
          resolvedSource = null,
          conditions = emptyList(),
        ) == request.copy(resolvedSource = null, conditions = emptyList())) {
          "Request ID already belongs to a different download request"
        }
        existing
      } else {
        // A disconnected caller must not interrupt publication after the record was saved.
        withContext(NonCancellable) { createDownload(request) }
      }
    }
  }

  private suspend fun createDownload(request: DownloadRequest): DownloadTask {
    val taskId = Uuid.random().toString()
    val now = Clock.System.now()
    val isScheduled = request.schedule !is DownloadSchedule.Immediate ||
      request.conditions.isNotEmpty()
    log.i {
      "Downloading: taskId=$taskId, url=${redactUrl(request.url)}, " +
        "connections=${request.connections}, " +
        "priority=${request.priority}" +
        if (isScheduled) ", schedule=${request.schedule}" else ""
    }
    val initialState = if (isScheduled) {
      TaskState.SCHEDULED
    } else {
      TaskState.QUEUED
    }
    val record = TaskRecord(
      taskId = taskId,
      request = request,
      state = initialState,
      downloadTime = Duration.ZERO,
      createdAt = now,
      updatedAt = now,
    )
    taskStore.save(record)

    val task = createTaskFromRecord(record)
    tasksMutex.withLock { _tasks.value += task }
    return task
  }

  override suspend fun resolve(
    url: String,
    properties: Map<String, String>,
  ): ResolvedSource {
    log.i { "Resolving URL: ${redactUrl(url)}" }
    val source = sourceResolver.resolve(url)
    return source.resolve(url, properties, currentConfig)
  }

  override suspend fun resolveContent(
    content: ByteArray,
    fileName: String?,
  ): ResolvedSource {
    log.i { "Resolving content: fileName=$fileName, size=${content.size}" }
    val source = sourceResolver.resolveContent(content, fileName)
    return source.resolveContent(content, fileName)
  }

  override suspend fun start() {
    log.i { "Start" }
    loadTasks()
    if (controlSources.isNotEmpty() && seedingFollowed.compareAndSet(false, true)) {
      scope.launch { followSeeding() }
      scope.launch { restoreSeeding() }
    }
  }

  /** Every task the control sources report seeding now. */
  private fun seedingTaskIds(): Set<String> =
    controlSources.flatMapTo(HashSet()) { it.second.seedingTaskIds.value }

  /** Shows on every completed task whether its source seeds it, as the sources report it. */
  private suspend fun followSeeding() {
    combine(controlSources.map { it.second.seedingTaskIds }) { sets ->
      sets.flatMapTo(HashSet()) { it }
    }.collect { ids ->
      for (task in _tasks.value) {
        (task as? RealDownloadTask)?.let { syncSeeding(it, ids) }
      }
    }
  }

  /**
   * Publishes whether a completed task seeds, as a copy of its [DownloadState.Completed], and
   * saves the intent to seed it again after a restart once it does. Nothing here clears the
   * intent: a seeder that a download stopped, or that stopped with Ketch, seeds again later.
   */
  private suspend fun syncSeeding(handle: TaskHandle, ids: Set<String>) {
    val taskId = handle.taskId
    val seeding = taskId in ids
    val current = handle.mutableState.value as? DownloadState.Completed ?: return
    if (current.seeding != seeding) {
      val updated = current.copy(seeding = seeding)
      if (handle.mutableState.compareAndSet(current, updated)) {
        log.i { if (seeding) "Seeding taskId=$taskId" else "Stopped seeding taskId=$taskId" }
        // The queue skips a completion whose state was replaced meanwhile, and a later copy may
        // equal the state it skipped, so the copy releases the slot itself. Releasing is
        // idempotent, and a task that started again since is never touched.
        queue.onTaskCompleted(taskId, updated)
      }
    }
    if (seeding && handle.record.value.control?.seeding != true) {
      scope.launch { saveSeedingIntent(handle, true) }
    }
  }

  /** Saves whether [handle]'s completed task seeds again after a restart. */
  private suspend fun saveSeedingIntent(handle: TaskHandle, seeding: Boolean) {
    handle.controlLock.withLock {
      val record = handle.record.value
      if ((record.control?.seeding ?: false) == seeding) return@withLock
      if (seeding && record.state != TaskState.COMPLETED) return@withLock
      handle.record.update {
        it.copy(
          control = (it.control ?: TaskControl()).copy(seeding = seeding),
          updatedAt = Clock.System.now(),
        )
      }
      log.d { "Saved seeding intent of taskId=${handle.taskId}: $seeding" }
    }
  }

  /**
   * Seeds the completed tasks that seeded before Ketch last stopped, oldest completion first,
   * through sources that restore seeding: each checks its files first and only takes a free
   * slot. The walk stops at the first that finds no slot or seeding switched off.
   */
  private suspend fun restoreSeeding() {
    for ((source, control) in controlSources) {
      if (!control.restoresSeeding) continue
      val candidates = _tasks.value.mapNotNull { it as? RealDownloadTask }.filter {
        val record = it.record.value
        record.state == TaskState.COMPLETED && record.control?.seeding == true &&
          record.sourceType == source.type
      }.sortedBy { it.record.value.completedAt }
      if (candidates.isEmpty()) continue
      log.i { "Restoring seeding of ${candidates.size} task(s) from ${source.type}" }
      for (handle in candidates) {
        val taskId = handle.taskId
        val record = handle.record.value
        // The task may have started again, or stopped seeding for good, since the walk began.
        if (handle.mutableState.value !is DownloadState.Completed ||
          record.state != TaskState.COMPLETED || record.control?.seeding != true
        ) continue
        val resumeState = record.sourceResumeState
        val outputPath = record.outputPath
        if (resumeState == null || outputPath == null) {
          log.w { "Cannot restore seeding of taskId=$taskId: nothing saved to seed from" }
          continue
        }
        val outcome = try {
          control.startSeeding(
            SeedingTask(taskId, record.request.url, resumeState, outputPath,
              record.request.selectedFileIds)
          )
        } catch (e: CancellationException) {
          throw e
        } catch (e: Exception) {
          log.w { "Restoring seeding of taskId=$taskId failed: ${e.describeCauses()}" }
          SeedingOutcome.FAILED
        }
        when (outcome) {
          SeedingOutcome.SEEDING -> log.i { "Restored seeding of taskId=$taskId" }
          SeedingOutcome.NO_SLOT, SeedingOutcome.POLICY_OFF -> {
            log.i { "Stopped restoring seeding at taskId=$taskId: $outcome" }
            return
          }
          SeedingOutcome.CHANGED_ON_DISK -> {
            saveSeedingIntent(handle, false)
            log.w { "Files of taskId=$taskId changed on disk since it completed; not seeding it" }
          }
          SeedingOutcome.ALREADY_ACTIVE, SeedingOutcome.UNSUPPORTED, SeedingOutcome.FAILED ->
            log.i { "Did not restore seeding of taskId=$taskId: $outcome" }
        }
      }
    }
  }

  override suspend fun status(): KetchStatus {
    val config = currentConfig
    return KetchStatus(
      name = name,
      version = KetchApi.VERSION,
      revision = KetchApi.REVISION,
      uptime = startMark.elapsedNow().inWholeSeconds,
      config = config,
      system = currentSystemInfo(config.defaultDirectory),
      features = features,
    )
  }

  private val taskController = object : TaskController {
    override suspend fun pause(handle: TaskHandle) {
      val taskId = handle.taskId
      coordinator.pause(taskId)
      queue.dequeue(taskId)
      // Stop an execution promoted between the two calls above.
      coordinator.pause(taskId)
      val state = handle.mutableState.value
      if (state !is DownloadState.Queued && state !is DownloadState.Paused) return
      // A task still waiting for a slot, or stopped before it saved any segments,
      // must not restart on its own after a restart.
      val record = handle.record.value
      if (record.state != TaskState.PAUSED) {
        handle.record.update {
          it.copy(state = TaskState.PAUSED, updatedAt = Clock.System.now())
        }
      }
      if (state is DownloadState.Queued) {
        handle.mutableState.value = DownloadState.Paused(record.savedProgress())
      } else if (state is DownloadState.Paused && state.reason is PauseReason.Preempted) {
        // It waited in the queue; dequeue above took it out, so it now stays paused.
        handle.mutableState.value = state.copy(reason = PauseReason.User)
      }
    }

    override suspend fun resume(
      handle: TaskHandle,
      destination: Destination?,
    ) {
      val state = handle.mutableState.value
      if (state is DownloadState.Paused && state.reason == PauseReason.AwaitingFileSelection) {
        if (destination != null) {
          log.d { "Ignoring the destination of taskId=${handle.taskId}, which waits for files" }
        }
        log.i { "Resuming taskId=${handle.taskId} with every file" }
        selectFiles(handle, fileIds = null)
        return
      }
      if (state is DownloadState.Failed) {
        coordinator.awaitCompletion(handle.taskId)
      }
      queue.enqueue(handle, preferResume = true, destination = destination)
    }

    override suspend fun cancel(handle: TaskHandle) {
      val taskId = handle.taskId
      scheduler.cancel(taskId)
      coordinator.cancel(handle, deletePartialFile = true)
      queue.dequeue(taskId)
    }

    override suspend fun remove(handle: TaskHandle, deleteFiles: Boolean) {
      val taskId = handle.taskId
      log.i { "Removing task: taskId=$taskId, deleteFiles=$deleteFiles" }
      scheduler.cancel(taskId)
      coordinator.cancel(handle, deletePartialFile = deleteFiles)
      queue.dequeue(taskId)
      try {
        coordinator.release(handle)
      } catch (e: CancellationException) {
        throw e
      } catch (e: Throwable) {
        log.w(e) { "Release failed for taskId=$taskId" }
      }
      if (deleteFiles) {
        try {
          coordinator.cleanup(handle)
        } catch (e: CancellationException) {
          throw e
        } catch (e: Throwable) {
          log.w(e) { "Cleanup failed for taskId=$taskId" }
        }
      }
      taskStore.remove(taskId)
      monitorMutex.withLock { taskMonitors.remove(taskId)?.cancel() }
      tasksMutex.withLock {
        _tasks.value = _tasks.value.filter { it.taskId != taskId }
      }
    }

    override suspend fun setSpeedLimit(taskId: String, limit: SpeedLimit) {
      coordinator.setTaskSpeedLimit(taskId, limit)
    }

    override suspend fun setConnections(taskId: String, connections: Int) {
      coordinator.setTaskConnections(taskId, connections)
    }

    override suspend fun setPriority(
      taskId: String,
      priority: DownloadPriority,
    ) {
      queue.setPriority(taskId, priority)
    }

    override suspend fun selectFiles(handle: TaskHandle, fileIds: Set<String>?): ControlOutcome {
      val taskId = handle.taskId
      while (true) {
        val state = handle.mutableState.value
        if (state is DownloadState.Canceled) {
          throw IllegalStateException("Canceled downloads cannot change their files")
        }
        // Joins happen before the control lock is taken: a finishing or stopping execution
        // must be able to end, and an execution that already stopped must be gone before the
        // task starts again.
        when {
          state is DownloadState.Completed || state is DownloadState.Failed ||
            state.isAwaitingFiles() -> coordinator.awaitCompletion(taskId)
          state is DownloadState.Paused -> coordinator.awaitStopping(taskId)
          else -> {}
        }
        if (coordinator.isFinishing(taskId)) coordinator.awaitCompletion(taskId)
        val outcome = handle.controlLock.withLock { applySelection(handle, fileIds) }
        // Null when the execution was finishing: join it, then plan again.
        if (outcome != null) return outcome
      }
    }

    override suspend fun reschedule(
      handle: TaskHandle,
      schedule: DownloadSchedule,
      conditions: List<DownloadCondition>,
    ) {
      val state = handle.mutableState.value
      log.i {
        "Rescheduling taskId=${handle.taskId}, " +
          "schedule=$schedule, conditions=${conditions.size}"
      }
      scheduler.cancel(handle.taskId)
      if (state.isActive) {
        coordinator.pause(handle.taskId)
      }
      queue.dequeue(handle.taskId)
      scheduler.reschedule(handle, schedule, conditions)
    }
  }

  /**
   * Validates, saves and applies a file selection; the caller holds the task's control lock.
   * Saves the selection, its size, the new segment layout and the next generation in one record
   * write, then hands it to the running execution, starts a task that waited for it or that
   * gains files after it completed, or updates the state a stopped task shows. Returns `null`
   * when the execution is finishing and the caller must join it and try again.
   */
  private suspend fun applySelection(handle: TaskHandle, fileIds: Set<String>?): ControlOutcome? {
    val taskId = handle.taskId
    if (coordinator.isFinishing(taskId)) return null
    val record = handle.record.value
    val state = handle.mutableState.value
    if (state is DownloadState.Canceled) {
      throw IllegalStateException("Canceled downloads cannot change their files")
    }
    val control = record.control ?: TaskControl()
    val source = selectionSource(record)
    val plan = source.planSelection(
      SelectionRequest(
        taskId = taskId,
        fileIds = fileIds,
        current = record.request.selectedFileIds,
        resumeState = record.sourceResumeState,
        resolved = record.request.resolvedSource,
        segments = record.segments,
        outputPath = record.outputPath,
        completed = state is DownloadState.Completed,
      )
    )
    val awaiting = record.awaitsFileSelection()
    if (!plan.changed && !awaiting) {
      log.d { "Selection of taskId=$taskId is unchanged" }
      return ControlOutcome(control.selectionGeneration, control.seeding, null, replayed = false)
    }
    val running = coordinator.isActive(taskId)
    val reopen = !running && state is DownloadState.Completed && plan.expands
    val generation = control.selectionGeneration + if (plan.changed) 1 else 0
    val now = Clock.System.now()
    handle.record.update { r ->
      r.copy(
        request = r.request.copy(selectedFileIds = plan.fileIds),
        totalBytes = plan.totalBytes,
        segments = when {
          reopen -> plan.segments ?: emptyList()
          // A task that never started (or waits for files) starts fresh: segments would make it
          // resume without an output path.
          r.outputPath == null -> r.segments
          else -> plan.segments ?: r.segments
        },
        state = if (reopen || awaiting) TaskState.QUEUED else r.state,
        completedAt = if (reopen) null else r.completedAt,
        control = (r.control ?: TaskControl()).copy(selectionGeneration = generation),
        updatedAt = now,
      )
    }
    val delivery = if (running) {
      coordinator.deliverSelection(taskId, plan.fileIds, plan.totalBytes)
    } else {
      SelectionDelivery.NOT_RUNNING
    }
    when {
      delivery == SelectionDelivery.DELIVERED -> {}
      // Cannot happen while the lock is held, as an execution only starts finishing under it.
      delivery == SelectionDelivery.FINISHING -> return null
      reopen || awaiting -> {
        queue.dequeue(taskId)
        handle.mutableState.value = DownloadState.Queued
        queue.enqueue(handle, preferResume = true)
      }
      else -> showStopped(handle, plan, handle.record.value.segments)
    }
    log.i {
      "Selected files for taskId=$taskId: files=${plan.fileIds.size}, " +
        "totalBytes=${plan.totalBytes}, generation=$generation" +
        if (reopen) ", reopened" else ""
    }
    return ControlOutcome(generation, control.seeding, null, replayed = false)
  }

  /** The source that plans [record]'s selection: its own, else its request's, else its URL's. */
  private fun selectionSource(record: TaskRecord): DownloadSource {
    val url = record.request.url
    record.sourceType?.let { return sourceResolver.resolveByType(it, url) }
    record.request.resolvedSource?.let {
      return sourceResolver.resolveByType(it.sourceType, it.url)
    }
    return sourceResolver.resolve(url)
  }

  /**
   * Shows a saved selection on a task that is not running: a completed task's new size, or a
   * paused one's saved [segments] and progress. Other states read the record when they start.
   */
  private fun showStopped(handle: TaskHandle, plan: SelectionPlan, segments: List<Segment>?) {
    handle.mutableState.update { current ->
      when (current) {
        is DownloadState.Completed -> current.copy(totalBytes = plan.totalBytes)
        is DownloadState.Paused -> current.copy(
          progress = DownloadProgress(
            segments?.sumOf { it.downloadedBytes } ?: 0L,
            plan.totalBytes
          ),
        )
        else -> current
      }
    }
    if (segments != null && handle.mutableState.value is DownloadState.Paused) {
      handle.mutableSegments.value = segments
    }
  }

  private fun DownloadState.isAwaitingFiles(): Boolean =
    this is DownloadState.Paused && reason == PauseReason.AwaitingFileSelection

  // -- Internal task lifecycle --

  /**
   * Loads all task records from the [TaskStore] and populates the
   * [tasks] flow. Non-terminal, non-paused tasks are automatically
   * re-scheduled or enqueued based on their persisted state.
   *
   * State restoration:
   * - `SCHEDULED` -> re-scheduled (schedule is preserved, conditions
   *   default to met after deserialization)
   * - `QUEUED` / `DOWNLOADING` -> enqueued for immediate download; this includes tasks
   *   that were paused for [PauseReason.Preempted] or [PauseReason.Shutdown]
   * - `PAUSED` -> stays [DownloadState.Paused] for [PauseReason.User], or for
   *   [PauseReason.AwaitingFileSelection] when the record waits for a file selection
   * - `COMPLETED` -> [DownloadState.Completed], with the saved finish time
   * - `FAILED` -> [DownloadState.Failed]
   * - `CANCELED` -> [DownloadState.Canceled]
   */
  private suspend fun loadTasks() {
    log.i { "Loading tasks from persistent storage" }
    val records = taskStore.loadAll()
    log.i { "Found ${records.size} task(s)" }

    tasksMutex.withLock {
      val currentTasks = _tasks.value
      val currentTaskIds =
        currentTasks.map { it.taskId }.toSet()

      val loaded = records.mapNotNull { record ->
        if (currentTaskIds.contains(record.taskId)) {
          currentTasks.find { it.taskId == record.taskId }
        } else {
          log.d {
            "Loading record: taskId=${record.taskId}, " +
              "state=${record.state}"
          }
          createTaskFromRecord(record)
        }
      }

      // Downloads added while the records loaded, such as through a server that already
      // listens, are saved after the snapshot and stay in the list.
      val recordIds = records.map { it.taskId }.toSet()
      _tasks.value = loaded + currentTasks.filter { it.taskId !in recordIds }
    }
  }

  private suspend fun createTaskFromRecord(record: TaskRecord): DownloadTask {
    val task = RealDownloadTask(
      taskId = record.taskId,
      request = record.request,
      createdAt = record.createdAt,
      initialState = mapRecordState(record),
      initialSegments = record.segments ?: emptyList(),
      controller = taskController,
      taskStore = taskStore,
      record = record,
    )

    when (record.state) {
      TaskState.SCHEDULED -> scheduler.schedule(task)

      TaskState.QUEUED,
      TaskState.DOWNLOADING -> queue.enqueue(
        task, preferResume = true,
      )

      else -> {} // PAUSED, COMPLETED, FAILED, CANCELED — no action
    }

    monitorTaskState(task)
    return task
  }

  private fun mapRecordState(record: TaskRecord): DownloadState {
    return when (record.state) {
      TaskState.SCHEDULED -> DownloadState.Scheduled(
        record.request.schedule,
      )

      TaskState.QUEUED,
      TaskState.DOWNLOADING -> DownloadState.Queued

      TaskState.PAUSED -> DownloadState.Paused(
        record.savedProgress(),
        if (record.awaitsFileSelection()) PauseReason.AwaitingFileSelection else PauseReason.User
      )

      TaskState.COMPLETED -> DownloadState.Completed(
        outputPath = record.outputPath ?: "",
        totalBytes = record.totalBytes.takeIf { it >= 0 },
        downloadTime = record.downloadTime,
        completedAt = record.completedAt,
        seeding = false,
      )

      TaskState.FAILED -> DownloadState.Failed(
        record.error ?: KetchError.Unknown(),
      )

      TaskState.CANCELED -> DownloadState.Canceled
    }
  }

  private suspend fun monitorTaskState(handle: RealDownloadTask) {
    val taskId = handle.taskId
    val stateFlow = handle.state
    monitorMutex.withLock {
      taskMonitors.remove(taskId)?.cancel()
      taskMonitors[taskId] = scope.launch {
        var previous: DownloadState? = null
        stateFlow.collect { state ->
          // Progress updates keep the state type; only real transitions are worth a line.
          val last = previous
          previous = state
          if (last != null && last::class != state::class) {
            log.i { "Task state: taskId=$taskId, ${last.logLabel()} -> ${state.logLabel()}" }
          }
          when (state) {
            is DownloadState.Completed -> {
              queue.onTaskCompleted(taskId, state)
              if (controlSources.isNotEmpty()) syncSeeding(handle, seedingTaskIds())
            }
            is DownloadState.Failed -> queue.onTaskFailed(taskId, state)
            is DownloadState.Canceled -> queue.onTaskCanceled(taskId, state)
            is DownloadState.Paused -> if (state.reason == PauseReason.AwaitingFileSelection) {
              queue.onTaskParked(taskId, state)
            }
            else -> {}
          }
        }
      }
    }
  }

  private fun DownloadState.logLabel(): String = when (this) {
    is DownloadState.Failed -> "Failed(${error.describeCauses()})"
    is DownloadState.Completed ->
      "Completed($outputPath, totalBytes=$totalBytes, downloadTime=$downloadTime)"
    is DownloadState.Paused -> "Paused(${reason.logName()})"
    else -> this::class.simpleName ?: toString()
  }

  private fun PauseReason.logName(): String = when (this) {
    PauseReason.User -> "user"
    is PauseReason.Preempted -> "preempted by taskId=$byTaskId"
    PauseReason.WaitingForCondition -> "waiting for conditions"
    PauseReason.Shutdown -> "shutdown"
    PauseReason.AwaitingFileSelection -> "awaiting file selection"
  }

  /**
   * Replaces the global configuration. The speed limit and queue limits
   * apply immediately; the remaining fields are snapshotted by each
   * download when it starts or resumes. See [KetchApi.updateConfig].
   */
  override suspend fun updateConfig(config: DownloadConfig) {
    requireProxySupport(config.proxy)
    val directory = config.defaultDirectory
    if (directory != null && directory != currentConfig.defaultDirectory) {
      requireDownloadDirectory(directory)
    }
    currentConfig = config

    // Apply speed limit
    val limit = config.speedLimit
    val current = globalLimiter.delegate
    if (limit.isUnlimited) {
      (current as? TokenBucket)?.updateRate(0)
      globalLimiter.delegate = SpeedLimiter.Unlimited
    } else if (current is TokenBucket) {
      current.updateRate(limit.bytesPerSecond)
    } else {
      globalLimiter.delegate = TokenBucket(limit.bytesPerSecond)
    }

    // Apply queue limits, starting queued tasks if a limit was raised
    queue.updateLimits(config.maxConcurrentDownloads, config.maxConnectionsPerHost)

    log.i { "Config updated: $config" }
  }

  /** Refuses a [proxy] other than the system's when the HTTP engine cannot apply it. */
  private fun requireProxySupport(proxy: ProxyConfig?) {
    if (proxy == null || proxy.mode == ProxyMode.SYSTEM || KetchFeatures.PROXY in features) return
    throw UnsupportedOperationException("The HTTP engine cannot use a proxy")
  }

  override suspend fun networkInterfaces(): NetworkInterfaces =
    (httpEngine as? ConfigurableNetworkHttpEngine)?.networkInterfaces() ?: NetworkInterfaces()

  override suspend fun updateNetworkInterfaces(config: NetworkInterfaceConfig): NetworkInterfaces {
    val configurable = httpEngine as? ConfigurableNetworkHttpEngine
      ?: throw UnsupportedOperationException("HTTP network interface configuration is unavailable")
    return configurable.updateNetworkInterfaces(config)
  }

  override fun close() {
    log.i { "Closing Ketch" }
    coordinator.close()
    scope.cancel()
    sourceResolver.close()
    httpEngine.close()
    dispatchers.close()
  }
}
