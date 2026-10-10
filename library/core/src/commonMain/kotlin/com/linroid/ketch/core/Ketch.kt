package com.linroid.ketch.core

import com.linroid.ketch.api.Destination
import com.linroid.ketch.api.DownloadCondition
import com.linroid.ketch.api.DownloadPriority
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
import com.linroid.ketch.api.ResolvedSource
import com.linroid.ketch.api.SpeedLimit
import com.linroid.ketch.api.DownloadConfig
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
import com.linroid.ketch.core.engine.SourceResolver
import com.linroid.ketch.core.engine.SpeedLimiter
import com.linroid.ketch.core.engine.TokenBucket
import com.linroid.ketch.core.file.DefaultFileNameResolver
import com.linroid.ketch.core.file.FileNameResolver
import com.linroid.ketch.core.file.requireDownloadDirectory
import com.linroid.ketch.core.task.InMemoryTaskStore
import com.linroid.ketch.core.task.RealDownloadTask
import com.linroid.ketch.core.task.TaskController
import com.linroid.ketch.core.task.TaskHandle
import com.linroid.ketch.core.task.TaskRecord
import com.linroid.ketch.core.task.TaskState
import com.linroid.ketch.core.task.TaskStore
import com.linroid.ketch.core.task.savedProgress
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.concurrent.Volatile
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.TimeSource
import kotlin.uuid.Uuid

/**
 * Core in-process implementation of [KetchApi]. No HTTP involved.
 *
 * @param httpEngine the HTTP engine for HTTP/HTTPS downloads
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

  private val features: Set<String> = buildSet {
    add(KetchFeatures.AUTO_CONNECTIONS)
    add(KetchFeatures.QUEUE_POSITION)
    add(KetchFeatures.REQUEST_ID)
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
    if (request.requestId == null) return createDownload(request)
    return submissionsMutex.withLock {
      val existing = tasks.value.find { it.request.requestId == request.requestId }
      if (existing != null) {
        // Live task controls are mutable; metadata and conditions are not persisted.
        require(existing.request.copy(
          connections = request.connections,
          speedLimit = request.speedLimit,
          priority = request.priority,
          schedule = request.schedule,
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
      if (handle.mutableState.value is DownloadState.Failed) {
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
   * - `PAUSED` -> stays [DownloadState.Paused] for [PauseReason.User]
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

      _tasks.value = loaded
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

    monitorTaskState(record.taskId, task.state)
    return task
  }

  private fun mapRecordState(record: TaskRecord): DownloadState {
    return when (record.state) {
      TaskState.SCHEDULED -> DownloadState.Scheduled(
        record.request.schedule,
      )

      TaskState.QUEUED,
      TaskState.DOWNLOADING -> DownloadState.Queued

      TaskState.PAUSED -> DownloadState.Paused(record.savedProgress())

      TaskState.COMPLETED -> DownloadState.Completed(
        outputPath = record.outputPath ?: "",
        totalBytes = record.totalBytes.takeIf { it >= 0 },
        downloadTime = record.downloadTime,
        completedAt = record.completedAt,
      )

      TaskState.FAILED -> DownloadState.Failed(
        record.error ?: KetchError.Unknown(),
      )

      TaskState.CANCELED -> DownloadState.Canceled
    }
  }

  private suspend fun monitorTaskState(
    taskId: String,
    stateFlow: StateFlow<DownloadState>,
  ) {
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
            is DownloadState.Completed -> queue.onTaskCompleted(taskId, state)
            is DownloadState.Failed -> queue.onTaskFailed(taskId, state)
            is DownloadState.Canceled -> queue.onTaskCanceled(taskId, state)
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
  }

  /**
   * Replaces the global configuration. The speed limit and queue limits
   * apply immediately; the remaining fields are snapshotted by each
   * download when it starts or resumes. See [KetchApi.updateConfig].
   */
  override suspend fun updateConfig(config: DownloadConfig) {
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
