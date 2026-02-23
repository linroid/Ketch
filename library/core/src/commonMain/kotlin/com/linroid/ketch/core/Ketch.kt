package com.linroid.ketch.core

import com.linroid.ketch.api.Destination
import com.linroid.ketch.api.DownloadCondition
import com.linroid.ketch.api.DownloadPriority
import com.linroid.ketch.api.DownloadProgress
import com.linroid.ketch.api.DownloadRequest
import com.linroid.ketch.api.DownloadSchedule
import com.linroid.ketch.api.DownloadState
import com.linroid.ketch.api.DownloadTask
import com.linroid.ketch.api.KetchApi
import com.linroid.ketch.api.KetchError
import com.linroid.ketch.api.KetchStatus
import com.linroid.ketch.api.ResolvedSource
import com.linroid.ketch.api.SpeedLimit
import com.linroid.ketch.api.DownloadConfig
import com.linroid.ketch.api.log.KetchLogger
import com.linroid.ketch.api.log.Logger
import com.linroid.ketch.core.engine.DelegatingSpeedLimiter
import com.linroid.ketch.core.engine.DownloadCoordinator
import com.linroid.ketch.core.engine.DownloadQueue
import com.linroid.ketch.core.engine.DownloadScheduler
import com.linroid.ketch.core.engine.DownloadSource
import com.linroid.ketch.core.engine.HttpDownloadSource
import com.linroid.ketch.core.engine.HttpEngine
import com.linroid.ketch.core.engine.SourceResolver
import com.linroid.ketch.core.engine.SpeedLimiter
import com.linroid.ketch.core.engine.TokenBucket
import com.linroid.ketch.core.file.DefaultFileNameResolver
import com.linroid.ketch.core.file.FileNameResolver
import com.linroid.ketch.core.task.InMemoryTaskStore
import com.linroid.ketch.core.task.RealDownloadTask
import com.linroid.ketch.core.task.TaskController
import com.linroid.ketch.core.task.TaskHandle
import com.linroid.ketch.core.task.TaskRecord
import com.linroid.ketch.core.task.TaskState
import com.linroid.ketch.core.task.TaskStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.concurrent.Volatile
import kotlin.time.Clock
import kotlin.time.TimeSource
import kotlin.uuid.Uuid

/**
 * Core in-process implementation of [KetchApi]. No HTTP involved.
 *
 * @param httpEngine the HTTP engine for HTTP/HTTPS downloads
 * @param taskStore persistent storage for task records
 * @param config global download configuration
 * @param name user-visible instance name included in [status]
 * @param fileNameResolver strategy for resolving download file names
 * @param additionalSources extra [DownloadSource] implementations
 *   (e.g., torrent, media). HTTP is always included as a fallback.
 * @param logger logging backend
 * @param dispatchers dedicated dispatchers for task management,
 *   network operations, and file I/O
 */
class Ketch(
  private val httpEngine: HttpEngine,
  private val taskStore: TaskStore = InMemoryTaskStore(),
  private val config: DownloadConfig = DownloadConfig.Default,
  private val name: String = "Ketch",
  private val fileNameResolver: FileNameResolver = DefaultFileNameResolver(),
  additionalSources: List<DownloadSource> = emptyList(),
  logger: Logger = Logger.None,
  private val dispatchers: KetchDispatchers = KetchDispatchers(
    networkThreads = config.maxConnectionsPerDownload,
    ioThreads = maxOf(config.maxConnectionsPerDownload / 2, 2),
  ),
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

  private val httpSource = HttpDownloadSource(
    httpEngine = httpEngine,
    maxConnections = config.maxConnectionsPerDownload,
    progressIntervalMs = config.progressIntervalMs,
  )

  private val sourceResolver = SourceResolver(
    additionalSources + httpSource,
  )

  override val backendLabel: String = "Core"

  private val log = KetchLogger("Ketch")

  /** Scope for task coordination (scheduling, queue, state). */
  private val scope = CoroutineScope(SupervisorJob() + dispatchers.main.limitedParallelism(1))

  private val coordinator = DownloadCoordinator(
    sourceResolver = sourceResolver,
    config = config,
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
  private val _tasks = MutableStateFlow<List<DownloadTask>>(emptyList())

  /** Observable list of all download tasks. */
  override val tasks: StateFlow<List<DownloadTask>> = _tasks.asStateFlow()

  init {
    KetchLogger.setLogger(logger)
    log.i { "Ketch v${KetchApi.VERSION} initialized" }
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
   */
  override suspend fun download(request: DownloadRequest): DownloadTask {
    val taskId = Uuid.random().toString()
    val now = Clock.System.now()
    val isScheduled = request.schedule !is DownloadSchedule.Immediate ||
      request.conditions.isNotEmpty()
    log.i {
      "Downloading: taskId=$taskId, url=${request.url}, " +
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
    headers: Map<String, String>,
  ): ResolvedSource {
    log.i { "Resolving URL: $url" }
    val source = sourceResolver.resolve(url)
    return source.resolve(url, headers)
  }

  override suspend fun start() {
    log.i { "Start" }
    loadTasks()
  }

  override suspend fun status(): KetchStatus {
    return KetchStatus(
      name = name,
      version = KetchApi.VERSION,
      revision = KetchApi.REVISION,
      uptime = startMark.elapsedNow().inWholeSeconds,
      config = currentConfig,
      system = currentSystemInfo(config.defaultDirectory ?: "downloads"),
    )
  }

  private val taskController = object : TaskController {
    override suspend fun pause(taskId: String) {
      coordinator.pause(taskId)
    }

    override suspend fun resume(
      handle: TaskHandle,
      destination: Destination?,
    ) {
      val resumed = coordinator.resume(handle, destination)
      if (!resumed) {
        coordinator.start(handle)
      }
    }

    override suspend fun cancel(handle: TaskHandle) {
      val taskId = handle.taskId
      scheduler.cancel(taskId)
      queue.dequeue(taskId)
      coordinator.cancel(handle)
    }

    override suspend fun remove(handle: TaskHandle) {
      val taskId = handle.taskId
      log.i { "Removing task: taskId=$taskId" }
      scheduler.cancel(taskId)
      queue.dequeue(taskId)
      coordinator.cancel(handle)
      taskStore.remove(taskId)
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
   * - `QUEUED` / `DOWNLOADING` -> enqueued for immediate download
   * - `PAUSED` -> stays [DownloadState.Paused]
   * - `COMPLETED` -> [DownloadState.Completed]
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

      TaskState.PAUSED -> DownloadState.Paused(
        DownloadProgress(
          record.downloadedBytes, record.totalBytes,
        ),
      )

      TaskState.COMPLETED -> DownloadState.Completed(
        record.outputPath ?: "",
      )

      TaskState.FAILED -> DownloadState.Failed(
        KetchError.Unknown(
          cause = Exception(
            record.errorMessage ?: "Unknown error",
          ),
        ),
      )

      TaskState.CANCELED -> DownloadState.Canceled
    }
  }

  private fun monitorTaskState(
    taskId: String,
    stateFlow: StateFlow<DownloadState>,
  ) {
    scope.launch {
      val terminalState =
        stateFlow.filterIsInstance<DownloadState>()
          .first { it.isTerminal }
      when (terminalState) {
        is DownloadState.Completed ->
          queue.onTaskCompleted(taskId)

        is DownloadState.Failed ->
          queue.onTaskFailed(taskId)

        is DownloadState.Canceled ->
          queue.onTaskCanceled(taskId)

        else -> {}
      }
    }
  }

  override suspend fun updateConfig(config: DownloadConfig) {
    currentConfig = config

    // Apply speed limit
    val limit = config.speedLimit
    val current = globalLimiter.delegate
    if (limit.isUnlimited) {
      globalLimiter.delegate = SpeedLimiter.Unlimited
    } else if (current is TokenBucket) {
      current.updateRate(limit.bytesPerSecond)
    } else {
      globalLimiter.delegate = TokenBucket(limit.bytesPerSecond)
    }

    // Apply queue config
    queue.maxConcurrent = config.maxConcurrentDownloads
    queue.maxPerHost = config.maxConnectionsPerHost

    log.i { "Config updated: $config" }
  }

  override fun close() {
    log.i { "Closing Ketch" }
    httpEngine.close()
    coordinator.close()
    scope.cancel()
    dispatchers.close()
  }
}
