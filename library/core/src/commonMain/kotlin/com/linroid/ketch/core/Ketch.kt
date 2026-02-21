package com.linroid.ketch.core

import com.linroid.ketch.api.DownloadProgress
import com.linroid.ketch.api.DownloadRequest
import com.linroid.ketch.api.DownloadSchedule
import com.linroid.ketch.api.DownloadState
import com.linroid.ketch.api.DownloadTask
import com.linroid.ketch.api.KetchApi
import com.linroid.ketch.api.KetchError
import com.linroid.ketch.api.KetchStatus
import com.linroid.ketch.api.ResolvedSource
import com.linroid.ketch.api.Segment
import com.linroid.ketch.api.config.DownloadConfig
import com.linroid.ketch.core.engine.DelegatingSpeedLimiter
import com.linroid.ketch.core.engine.DownloadCoordinator
import com.linroid.ketch.core.engine.DownloadScheduler
import com.linroid.ketch.core.engine.DownloadSource
import com.linroid.ketch.core.engine.HttpDownloadSource
import com.linroid.ketch.core.engine.HttpEngine
import com.linroid.ketch.core.engine.ScheduleManager
import com.linroid.ketch.core.engine.SourceResolver
import com.linroid.ketch.core.engine.SpeedLimiter
import com.linroid.ketch.core.engine.TokenBucket
import com.linroid.ketch.core.file.DefaultFileNameResolver
import com.linroid.ketch.core.file.FileNameResolver
import com.linroid.ketch.core.log.KetchLogger
import com.linroid.ketch.core.log.Logger
import com.linroid.ketch.core.task.DownloadTaskImpl
import com.linroid.ketch.core.task.InMemoryTaskStore
import com.linroid.ketch.core.task.TaskRecord
import com.linroid.ketch.core.task.TaskState
import com.linroid.ketch.core.task.TaskStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
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
 */
class Ketch(
  private val httpEngine: HttpEngine,
  private val taskStore: TaskStore = InMemoryTaskStore(),
  private val config: DownloadConfig = DownloadConfig.Default,
  private val name: String = "Ketch",
  private val fileNameResolver: FileNameResolver = DefaultFileNameResolver(),
  additionalSources: List<DownloadSource> = emptyList(),
  logger: Logger = Logger.None,
) : KetchApi {
  private val startMark = TimeSource.Monotonic.markNow()

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
    maxConnections = config.maxConnections,
    progressUpdateIntervalMs = config.progressUpdateIntervalMs,
    segmentSaveIntervalMs = config.segmentSaveIntervalMs,
  )

  private val sourceResolver = SourceResolver(
    additionalSources + httpSource,
  )

  override val backendLabel: String = "Core"

  private val log = KetchLogger("Ketch")

  init {
    KetchLogger.setLogger(logger)
    log.i { "Ketch v${KetchApi.VERSION} initialized" }
    if (!config.speedLimit.isUnlimited) {
      log.i {
        "Global speed limit: " +
          "${config.speedLimit.bytesPerSecond} bytes/sec"
      }
    }
    if (additionalSources.isNotEmpty()) {
      log.i {
        "Additional sources: " +
          additionalSources.joinToString { it.type }
      }
    }
  }

  private val scope =
    CoroutineScope(SupervisorJob() + Dispatchers.Default)

  private val coordinator = DownloadCoordinator(
    sourceResolver = sourceResolver,
    taskStore = taskStore,
    config = config,
    fileNameResolver = fileNameResolver,
    globalLimiter = globalLimiter,
  )

  private val scheduler = DownloadScheduler(
    queueConfig = config.queueConfig,
    coordinator = coordinator,
    scope = scope,
  )

  private val scheduleManager = ScheduleManager(
    scheduler = scheduler,
    scope = scope,
  )

  private val tasksMutex = Mutex()
  private val _tasks =
    MutableStateFlow<List<DownloadTask>>(emptyList())

  /** Observable list of all download tasks. */
  override val tasks: StateFlow<List<DownloadTask>> = _tasks.asStateFlow()

  /**
   * Starts a new download and adds it to the [tasks] flow.
   * The task may be queued if the maximum number of concurrent
   * downloads has been reached.
   */
  override suspend fun download(request: DownloadRequest): DownloadTask {
    val taskId = Uuid.random().toString()
    val now = Clock.System.now()
    val isScheduled =
      request.schedule !is DownloadSchedule.Immediate ||
        request.conditions.isNotEmpty()
    log.i {
      "Downloading: taskId=$taskId, url=${request.url}, " +
        "connections=${request.connections}, " +
        "priority=${request.priority}" +
        if (isScheduled) ", schedule=${request.schedule}" else ""
    }
    val stateFlow =
      MutableStateFlow<DownloadState>(DownloadState.Pending)
    val segmentsFlow =
      MutableStateFlow<List<Segment>>(emptyList())

    if (isScheduled) {
      scheduleManager.schedule(
        taskId, request, now, stateFlow, segmentsFlow,
      )
    } else {
      scheduler.enqueue(
        taskId, request, now, stateFlow, segmentsFlow,
      )
    }

    val task = DownloadTaskImpl(
      taskId = taskId,
      request = request,
      createdAt = now,
      state = stateFlow.asStateFlow(),
      segments = segmentsFlow.asStateFlow(),
      pauseAction = {
        if (stateFlow.value.isActive) {
          coordinator.pause(taskId)
        } else {
          log.d {
            "Ignoring pause for taskId=$taskId " +
              "in state ${stateFlow.value}"
          }
        }
      },
      resumeAction = { dest ->
        val state = stateFlow.value
        if (state is DownloadState.Paused ||
          state is DownloadState.Failed
        ) {
          val resumed = coordinator.resume(
            taskId, scope, stateFlow, segmentsFlow, dest,
          )
          if (!resumed) {
            coordinator.start(
              taskId, request, scope, stateFlow, segmentsFlow,
            )
          }
        } else {
          log.d { "Ignoring resume for taskId=$taskId in state $state" }
        }
      },
      cancelAction = {
        val s = stateFlow.value
        if (!s.isTerminal) {
          scheduleManager.cancel(taskId)
          scheduler.dequeue(taskId)
          coordinator.cancel(taskId)
          if (s is DownloadState.Scheduled) {
            stateFlow.value = DownloadState.Canceled
          }
        } else {
          log.d { "Ignoring cancel for taskId=$taskId in state $s" }
        }
      },
      removeAction = { removeTaskInternal(taskId) },
      setSpeedLimitAction = { limit ->
        coordinator.setTaskSpeedLimit(taskId, limit)
      },
      setPriorityAction = { priority ->
        scheduler.setPriority(taskId, priority)
      },
      setConnectionsAction = { connections ->
        coordinator.setTaskConnections(taskId, connections)
      },
      rescheduleAction = { schedule, conditions ->
        val s = stateFlow.value
        if (s.isTerminal) {
          log.d {
            "Ignoring reschedule for taskId=$taskId in " +
              "terminal state $s"
          }
          return@DownloadTaskImpl
        }
        log.i {
          "Rescheduling taskId=$taskId, schedule=$schedule, " +
            "conditions=${conditions.size}"
        }
        scheduleManager.cancel(taskId)
        if (s.isActive) {
          coordinator.pause(taskId)
        }
        scheduler.dequeue(taskId)
        scheduleManager.reschedule(
          taskId, request, schedule, conditions,
          now, stateFlow, segmentsFlow,
        )
      },
    )

    monitorTaskState(taskId, stateFlow)
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
    loadTasks()
  }

  override suspend fun status(): KetchStatus {
    return KetchStatus(
      name = name,
      version = KetchApi.VERSION,
      revision = KetchApi.REVISION,
      uptime = startMark.elapsedNow().inWholeSeconds,
      config = currentConfig,
      system = currentSystemInfo(config.defaultDirectory),
    )
  }

  /**
   * Loads all task records from the [TaskStore] and populates the
   * [tasks] flow. Does **not** auto-resume — call
   * [DownloadTask.resume] on individual tasks to continue
   * interrupted downloads.
   *
   * State mapping:
   * - `SCHEDULED` / `PENDING` / `QUEUED` / `DOWNLOADING` / `PAUSED`
   *   -> [DownloadState.Paused] (treat as paused, user decides when
   *   to resume). `SCHEDULED` is mapped to Paused because conditions
   *   are transient and cannot be restored after deserialization.
   * - `COMPLETED` -> [DownloadState.Completed]
   * - `FAILED` -> [DownloadState.Failed]
   * - `CANCELED` -> [DownloadState.Canceled]
   */
  suspend fun loadTasks() {
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
          createTaskFromRecord(record)
        }
      }

      _tasks.value = loaded
    }
  }

  private fun createTaskFromRecord(
    record: TaskRecord,
  ): DownloadTask {
    val stateFlow = MutableStateFlow(mapRecordState(record))
    val segmentsFlow =
      MutableStateFlow(record.segments ?: emptyList())

    monitorTaskState(record.taskId, stateFlow)

    return DownloadTaskImpl(
      taskId = record.taskId,
      request = record.request,
      createdAt = record.createdAt,
      state = stateFlow.asStateFlow(),
      segments = segmentsFlow.asStateFlow(),
      pauseAction = {
        if (stateFlow.value.isActive) {
          coordinator.pause(record.taskId)
        } else {
          log.d {
            "Ignoring pause for taskId=${record.taskId} " +
              "in state ${stateFlow.value}"
          }
        }
      },
      resumeAction = { dest ->
        val state = stateFlow.value
        if (state is DownloadState.Paused ||
          state is DownloadState.Failed
        ) {
          val resumed = coordinator.resume(
            record.taskId, scope, stateFlow, segmentsFlow, dest,
          )
          if (!resumed) {
            coordinator.startFromRecord(
              record, scope, stateFlow, segmentsFlow,
            )
          }
        } else {
          log.d {
            "Ignoring resume for taskId=${record.taskId} " +
              "in state $state"
          }
        }
      },
      cancelAction = {
        val s = stateFlow.value
        if (!s.isTerminal) {
          scheduleManager.cancel(record.taskId)
          scheduler.dequeue(record.taskId)
          coordinator.cancel(record.taskId)
          if (s is DownloadState.Scheduled) {
            stateFlow.value = DownloadState.Canceled
          }
        } else {
          log.d {
            "Ignoring cancel for taskId=${record.taskId} " +
              "in state $s"
          }
        }
      },
      removeAction = { removeTaskInternal(record.taskId) },
      setSpeedLimitAction = { limit ->
        coordinator.setTaskSpeedLimit(record.taskId, limit)
      },
      setPriorityAction = { priority ->
        scheduler.setPriority(record.taskId, priority)
      },
      setConnectionsAction = { connections ->
        coordinator.setTaskConnections(record.taskId, connections)
      },
      rescheduleAction = { schedule, conditions ->
        val s = stateFlow.value
        if (s.isTerminal) {
          log.d {
            "Ignoring reschedule for taskId=${record.taskId} in " +
              "terminal state $s"
          }
          return@DownloadTaskImpl
        }
        log.i {
          "Rescheduling taskId=${record.taskId}, " +
            "schedule=$schedule, " +
            "conditions=${conditions.size}"
        }
        scheduleManager.cancel(record.taskId)
        if (s.isActive) {
          coordinator.pause(record.taskId)
        }
        scheduler.dequeue(record.taskId)
        scheduleManager.reschedule(
          record.taskId, record.request, schedule, conditions,
          record.createdAt, stateFlow, segmentsFlow,
        )
      },
    )
  }

  private fun mapRecordState(record: TaskRecord): DownloadState {
    return when (record.state) {
      // SCHEDULED maps to Paused: conditions are @Transient and
      // cannot be restored, so treat as interrupted download.
      TaskState.SCHEDULED,
      TaskState.PENDING,
      TaskState.QUEUED,
      TaskState.DOWNLOADING,
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
    stateFlow: MutableStateFlow<DownloadState>,
  ) {
    scope.launch {
      val terminalState =
        stateFlow.filterIsInstance<DownloadState>()
          .first { it.isTerminal }
      when (terminalState) {
        is DownloadState.Completed ->
          scheduler.onTaskCompleted(taskId)

        is DownloadState.Failed ->
          scheduler.onTaskFailed(taskId)

        is DownloadState.Canceled ->
          scheduler.onTaskCanceled(taskId)

        else -> {}
      }
    }
  }

  private suspend fun removeTaskInternal(taskId: String) {
    scheduleManager.cancel(taskId)
    scheduler.dequeue(taskId)
    coordinator.cancel(taskId)
    taskStore.remove(taskId)
    tasksMutex.withLock {
      _tasks.value = _tasks.value.filter { it.taskId != taskId }
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
    scheduler.queueConfig = config.queueConfig

    log.i { "Config updated: $config" }
  }

  override fun close() {
    log.i { "Closing Ketch" }
    httpEngine.close()
    scope.cancel()
  }

  companion object
}
