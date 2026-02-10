package com.linroid.kdown

import com.linroid.kdown.engine.DelegatingSpeedLimiter
import com.linroid.kdown.engine.DownloadCoordinator
import com.linroid.kdown.engine.SpeedLimiter
import com.linroid.kdown.engine.TokenBucket
import com.linroid.kdown.segment.Segment
import com.linroid.kdown.engine.HttpEngine
import com.linroid.kdown.error.KDownError
import com.linroid.kdown.file.DefaultFileNameResolver
import com.linroid.kdown.file.FileAccessor
import com.linroid.kdown.file.FileNameResolver
import com.linroid.kdown.log.KDownLogger
import com.linroid.kdown.log.Logger
import com.linroid.kdown.task.DownloadTask
import com.linroid.kdown.task.InMemoryTaskStore
import com.linroid.kdown.task.TaskRecord
import com.linroid.kdown.task.TaskState
import com.linroid.kdown.task.TaskStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.io.files.Path
import kotlin.time.Clock
import kotlin.uuid.Uuid

class KDown(
  private val httpEngine: HttpEngine,
  private val taskStore: TaskStore = InMemoryTaskStore(),
  private val config: DownloadConfig = DownloadConfig.Default,
  private val fileAccessorFactory: (Path) -> FileAccessor = { path ->
    FileAccessor(path)
  },
  private val fileNameResolver: FileNameResolver = DefaultFileNameResolver(),
  logger: Logger = Logger.None
) {
  private val globalLimiter = DelegatingSpeedLimiter(
    if (config.speedLimit.isUnlimited) {
      SpeedLimiter.Unlimited
    } else {
      TokenBucket(config.speedLimit.bytesPerSecond)
    }
  )

  init {
    KDownLogger.setLogger(logger)
    KDownLogger.i("KDown") { "KDown v$VERSION initialized" }
    if (!config.speedLimit.isUnlimited) {
      KDownLogger.i("KDown") {
        "Global speed limit: ${config.speedLimit.bytesPerSecond} bytes/sec"
      }
    }
  }

  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

  private val coordinator = DownloadCoordinator(
    httpEngine = httpEngine,
    taskStore = taskStore,
    config = config,
    fileAccessorFactory = fileAccessorFactory,
    fileNameResolver = fileNameResolver,
    globalLimiter = globalLimiter
  )

  private val tasksMutex = Mutex()
  private val _tasks = MutableStateFlow<List<DownloadTask>>(emptyList())

  /** Observable list of all download tasks. */
  val tasks: StateFlow<List<DownloadTask>> = _tasks.asStateFlow()

  /**
   * Starts a new download and adds it to the [tasks] flow.
   */
  suspend fun download(request: DownloadRequest): DownloadTask {
    val taskId = Uuid.random().toString()
    val now = Clock.System.now()
    KDownLogger.i("KDown") {
      "Starting download: taskId=$taskId, url=${request.url}, " +
        "connections=${request.connections}"
    }
    val stateFlow = MutableStateFlow<DownloadState>(DownloadState.Pending)
    val segmentsFlow = MutableStateFlow<List<Segment>>(emptyList())

    coordinator.start(taskId, request, scope, stateFlow, segmentsFlow)

    val task = DownloadTask(
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
            "Ignoring pause for taskId=$taskId in state ${stateFlow.value}"
          }
        }
      },
      resumeAction = {
        val state = stateFlow.value
        if (state is DownloadState.Paused || state is DownloadState.Failed) {
          val resumed = coordinator.resume(
            taskId, scope, stateFlow, segmentsFlow
          )
          if (!resumed) {
            coordinator.start(
              taskId, request, scope, stateFlow, segmentsFlow
            )
          }
        } else {
          KDownLogger.d("KDown") {
            "Ignoring resume for taskId=$taskId in state $state"
          }
        }
      },
      cancelAction = {
        if (!stateFlow.value.isTerminal) {
          coordinator.cancel(taskId)
        } else {
          KDownLogger.d("KDown") {
            "Ignoring cancel for taskId=$taskId in state " +
              "${stateFlow.value}"
          }
        }
      },
      removeAction = { removeTaskInternal(taskId) },
      setSpeedLimitAction = { limit ->
        coordinator.setTaskSpeedLimit(taskId, limit)
      }
    )

    tasksMutex.withLock { _tasks.value = _tasks.value + task }
    return task
  }

  /**
   * Loads all task records from the [TaskStore] and populates the
   * [tasks] flow. Does **not** auto-resume — call [DownloadTask.resume]
   * on individual tasks to continue interrupted downloads.
   *
   * State mapping:
   * - `PENDING` / `DOWNLOADING` / `PAUSED` → [DownloadState.Paused]
   *   (treat as paused, user decides when to resume)
   * - `COMPLETED` → [DownloadState.Completed]
   * - `FAILED` → [DownloadState.Failed]
   * - `CANCELED` → [DownloadState.Canceled]
   */
  suspend fun loadTasks() {
    KDownLogger.i("KDown") { "Loading tasks from persistent storage" }
    val records = taskStore.loadAll()
    KDownLogger.i("KDown") { "Found ${records.size} task(s)" }

    tasksMutex.withLock {
      val currentTasks = _tasks.value
      val currentTaskIds = currentTasks.map { it.taskId }.toSet()

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

  private fun createTaskFromRecord(record: TaskRecord): DownloadTask {
    val stateFlow = MutableStateFlow(mapRecordState(record))
    val segmentsFlow = MutableStateFlow(record.segments ?: emptyList())

    return DownloadTask(
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
        if (state is DownloadState.Paused || state is DownloadState.Failed) {
          val resumed = coordinator.resume(
            record.taskId, scope, stateFlow, segmentsFlow
          )
          if (!resumed) {
            coordinator.startFromRecord(
              record, scope, stateFlow, segmentsFlow
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
        if (!stateFlow.value.isTerminal) {
          coordinator.cancel(record.taskId)
        } else {
          KDownLogger.d("KDown") {
            "Ignoring cancel for taskId=${record.taskId} " +
              "in state ${stateFlow.value}"
          }
        }
      },
      removeAction = { removeTaskInternal(record.taskId) },
      setSpeedLimitAction = { limit ->
        coordinator.setTaskSpeedLimit(record.taskId, limit)
      }
    )
  }

  private fun mapRecordState(record: TaskRecord): DownloadState {
    return when (record.state) {
      TaskState.PENDING,
      TaskState.DOWNLOADING,
      TaskState.PAUSED -> DownloadState.Paused(
        DownloadProgress(record.downloadedBytes, record.totalBytes)
      )
      TaskState.COMPLETED -> DownloadState.Completed(record.destPath)
      TaskState.FAILED -> DownloadState.Failed(
        KDownError.Unknown(
          cause = Exception(record.errorMessage ?: "Unknown error")
        )
      )
      TaskState.CANCELED -> DownloadState.Canceled
    }
  }

  private suspend fun removeTaskInternal(taskId: String) {
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
   * @param limit the new global speed limit, or [SpeedLimit.Unlimited]
   */
  fun setSpeedLimit(limit: SpeedLimit) {
    val current = globalLimiter.delegate
    if (limit.isUnlimited) {
      globalLimiter.delegate = SpeedLimiter.Unlimited
    } else if (current is TokenBucket) {
      current.updateRate(limit.bytesPerSecond)
    } else {
      globalLimiter.delegate = TokenBucket(limit.bytesPerSecond)
    }
    KDownLogger.i("KDown") {
      "Global speed limit updated: ${limit.bytesPerSecond} bytes/sec"
    }
  }

  fun close() {
    KDownLogger.i("KDown") { "Closing KDown" }
    httpEngine.close()
    scope.cancel()
  }

  companion object {
    const val VERSION = "1.0.0"
  }
}
