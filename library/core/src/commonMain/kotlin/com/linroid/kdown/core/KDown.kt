package com.linroid.kdown.core

import com.linroid.kdown.api.DownloadProgress
import com.linroid.kdown.api.DownloadRequest
import com.linroid.kdown.api.DownloadSchedule
import com.linroid.kdown.api.DownloadState
import com.linroid.kdown.api.DownloadTask
import com.linroid.kdown.api.KDownApi
import com.linroid.kdown.api.KDownError
import com.linroid.kdown.api.KDownVersion
import com.linroid.kdown.api.ResolvedSource
import com.linroid.kdown.api.Segment
import com.linroid.kdown.api.SpeedLimit
import com.linroid.kdown.core.engine.DelegatingSpeedLimiter
import com.linroid.kdown.core.engine.DownloadCoordinator
import com.linroid.kdown.core.engine.DownloadScheduler
import com.linroid.kdown.core.engine.DownloadSource
import com.linroid.kdown.core.engine.HttpDownloadSource
import com.linroid.kdown.core.engine.HttpEngine
import com.linroid.kdown.core.engine.ScheduleManager
import com.linroid.kdown.core.engine.SourceResolver
import com.linroid.kdown.core.engine.SpeedLimiter
import com.linroid.kdown.core.engine.TokenBucket
import com.linroid.kdown.core.file.DefaultFileNameResolver
import com.linroid.kdown.core.file.FileAccessor
import com.linroid.kdown.core.file.FileNameResolver
import com.linroid.kdown.core.log.KDownLogger
import com.linroid.kdown.core.log.Logger
import com.linroid.kdown.core.task.DownloadTaskImpl
import com.linroid.kdown.core.task.InMemoryTaskStore
import com.linroid.kdown.core.task.TaskRecord
import com.linroid.kdown.core.task.TaskState
import com.linroid.kdown.core.task.TaskStore
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
import kotlinx.io.files.Path
import kotlin.time.Clock
import kotlin.uuid.Uuid

/**
 * Core in-process implementation of [KDownApi]. No HTTP involved.
 *
 * @param httpEngine the HTTP engine for HTTP/HTTPS downloads
 * @param taskStore persistent storage for task records
 * @param config global download configuration
 * @param fileAccessorFactory factory for creating platform file writers
 * @param fileNameResolver strategy for resolving download file names
 * @param additionalSources extra [DownloadSource] implementations
 *   (e.g., torrent, media). HTTP is always included as a fallback.
 * @param logger logging backend
 */
class KDown(
  private val httpEngine: HttpEngine,
  private val taskStore: TaskStore = InMemoryTaskStore(),
  private val config: DownloadConfig = DownloadConfig.Default,
  private val fileAccessorFactory: (Path) -> FileAccessor = { path ->
    FileAccessor(path)
  },
  private val fileNameResolver: FileNameResolver =
    DefaultFileNameResolver(),
  additionalSources: List<DownloadSource> = emptyList(),
  logger: Logger = Logger.None,
) : KDownApi {
  private val globalLimiter = DelegatingSpeedLimiter(
    if (config.speedLimit.isUnlimited) {
      SpeedLimiter.Unlimited
    } else {
      TokenBucket(config.speedLimit.bytesPerSecond)
    },
  )

  private val httpSource = HttpDownloadSource(
    httpEngine = httpEngine,
    fileNameResolver = fileNameResolver,
    maxConnections = config.maxConnections,
    progressUpdateIntervalMs = config.progressUpdateIntervalMs,
    segmentSaveIntervalMs = config.segmentSaveIntervalMs,
  )

  private val sourceResolver = SourceResolver(
    additionalSources + httpSource,
  )

  override val backendLabel: String = "Core"

  init {
    KDownLogger.setLogger(logger)
    KDownLogger.i("KDown") { "KDown v${KDownVersion.DEFAULT} initialized" }
    if (!config.speedLimit.isUnlimited) {
      KDownLogger.i("KDown") {
        "Global speed limit: " +
          "${config.speedLimit.bytesPerSecond} bytes/sec"
      }
    }
    if (additionalSources.isNotEmpty()) {
      KDownLogger.i("KDown") {
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
    fileAccessorFactory = fileAccessorFactory,
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

  override val version: StateFlow<KDownVersion> =
    MutableStateFlow(KDownVersion(KDownVersion.DEFAULT, KDownVersion.DEFAULT))

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
    KDownLogger.i("KDown") {
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
          KDownLogger.d("KDown") {
            "Ignoring pause for taskId=$taskId " +
                "in state ${stateFlow.value}"
          }
        }
      },
      resumeAction = {
        val state = stateFlow.value
        if (state is DownloadState.Paused ||
          state is DownloadState.Failed
        ) {
          val resumed = coordinator.resume(
            taskId, scope, stateFlow, segmentsFlow,
          )
          if (!resumed) {
            coordinator.start(
              taskId, request, scope, stateFlow, segmentsFlow,
            )
          }
        } else {
          KDownLogger.d("KDown") {
            "Ignoring resume for taskId=$taskId in state $state"
          }
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
          KDownLogger.d("KDown") {
            "Ignoring cancel for taskId=$taskId in state $s"
          }
        }
      },
      removeAction = { removeTaskInternal(taskId) },
      setSpeedLimitAction = { limit ->
        coordinator.setTaskSpeedLimit(taskId, limit)
      },
      setPriorityAction = { priority ->
        scheduler.setPriority(taskId, priority)
      },
      rescheduleAction = { schedule, conditions ->
        val s = stateFlow.value
        if (s.isTerminal) {
          KDownLogger.d("KDown") {
            "Ignoring reschedule for taskId=$taskId in " +
                "terminal state $s"
          }
          return@DownloadTaskImpl
        }
        KDownLogger.i("KDown") {
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
    KDownLogger.i("KDown") { "Resolving URL: $url" }
    val source = sourceResolver.resolve(url)
    return source.resolve(url, headers)
  }

  override suspend fun start() {
    loadTasks()
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
    KDownLogger.i("KDown") {
      "Loading tasks from persistent storage"
    }
    val records = taskStore.loadAll()
    KDownLogger.i("KDown") { "Found ${records.size} task(s)" }

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
          KDownLogger.d("KDown") {
            "Ignoring pause for taskId=${record.taskId} " +
                "in state ${stateFlow.value}"
          }
        }
      },
      resumeAction = {
        val state = stateFlow.value
        if (state is DownloadState.Paused ||
          state is DownloadState.Failed
        ) {
          val resumed = coordinator.resume(
            record.taskId, scope, stateFlow, segmentsFlow,
          )
          if (!resumed) {
            coordinator.startFromRecord(
              record, scope, stateFlow, segmentsFlow,
            )
          }
        } else {
          KDownLogger.d("KDown") {
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
          KDownLogger.d("KDown") {
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
      rescheduleAction = { schedule, conditions ->
        val s = stateFlow.value
        if (s.isTerminal) {
          KDownLogger.d("KDown") {
            "Ignoring reschedule for taskId=${record.taskId} in " +
                "terminal state $s"
          }
          return@DownloadTaskImpl
        }
        KDownLogger.i("KDown") {
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
        record.destPath.toString(),
      )

      TaskState.FAILED -> DownloadState.Failed(
        KDownError.Unknown(
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

  /**
   * Updates the global speed limit applied across all downloads.
   * Takes effect immediately on all active downloads.
   *
   * @param limit the new global speed limit, or
   *   [com.linroid.kdown.api.SpeedLimit.Unlimited]
   */
  override suspend fun setGlobalSpeedLimit(limit: SpeedLimit) {
    val current = globalLimiter.delegate
    if (limit.isUnlimited) {
      globalLimiter.delegate = SpeedLimiter.Unlimited
    } else if (current is TokenBucket) {
      current.updateRate(limit.bytesPerSecond)
    } else {
      globalLimiter.delegate = TokenBucket(limit.bytesPerSecond)
    }
    KDownLogger.i("KDown") {
      "Global speed limit updated: " +
        "${limit.bytesPerSecond} bytes/sec"
    }
  }

  override fun close() {
    KDownLogger.i("KDown") { "Closing KDown" }
    httpEngine.close()
    scope.cancel()
  }

  companion object
}
