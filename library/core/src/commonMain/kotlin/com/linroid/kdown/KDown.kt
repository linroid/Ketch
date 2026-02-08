package com.linroid.kdown

import com.linroid.kdown.internal.DefaultFileNameResolver
import com.linroid.kdown.internal.DownloadCoordinator
import com.linroid.kdown.internal.InMemoryMetadataStore
import com.linroid.kdown.internal.InMemoryTaskStore
import com.linroid.kdown.model.TaskRecord
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.io.files.Path
import kotlin.uuid.Uuid

class KDown(
  private val httpEngine: HttpEngine,
  private val metadataStore: MetadataStore = InMemoryMetadataStore(),
  private val taskStore: TaskStore = InMemoryTaskStore(),
  private val config: DownloadConfig = DownloadConfig.Default,
  private val fileAccessorFactory: (Path) -> FileAccessor = { path -> FileAccessor(path) },
  private val fileNameResolver: FileNameResolver = DefaultFileNameResolver(),
  logger: Logger = Logger.None
) {
  init {
    KDownLogger.setLogger(logger)
    KDownLogger.i("KDown") { "KDown v$VERSION initialized" }
  }

  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

  private val coordinator = DownloadCoordinator(
    httpEngine = httpEngine,
    metadataStore = metadataStore,
    taskStore = taskStore,
    config = config,
    fileAccessorFactory = fileAccessorFactory,
    fileNameResolver = fileNameResolver
  )

  suspend fun download(request: DownloadRequest): DownloadTask {
    val taskId = Uuid.random().toString()
    KDownLogger.i("KDown") {
      "Starting download: taskId=$taskId, url=${request.url}, " +
        "connections=${request.connections}"
    }
    val stateFlow = coordinator.start(taskId, request, scope)

    return DownloadTask(
      taskId = taskId,
      state = stateFlow,
      pauseAction = { coordinator.pause(taskId) },
      resumeAction = { resume(taskId) },
      cancelAction = { coordinator.cancel(taskId) }
    )
  }

  suspend fun pause(taskId: String) {
    KDownLogger.i("KDown") { "Pausing download: taskId=$taskId" }
    coordinator.pause(taskId)
  }

  suspend fun resume(taskId: String): DownloadTask? {
    KDownLogger.i("KDown") { "Resuming download: taskId=$taskId" }
    val stateFlow = coordinator.resume(taskId, scope) ?: return null

    return DownloadTask(
      taskId = taskId,
      state = stateFlow,
      pauseAction = { coordinator.pause(taskId) },
      resumeAction = { resume(taskId) },
      cancelAction = { coordinator.cancel(taskId) }
    )
  }

  suspend fun cancel(taskId: String) {
    KDownLogger.i("KDown") { "Canceling download: taskId=$taskId" }
    coordinator.cancel(taskId)
  }

  /**
   * Returns all stored [TaskRecord]s, including completed, failed, and
   * canceled tasks. Useful for displaying download history or building a
   * download manager UI.
   */
  suspend fun getAllTasks(): List<TaskRecord> {
    return taskStore.loadAll()
  }

  /**
   * Restores all restorable tasks (those in PENDING, DOWNLOADING, or PAUSED
   * state) from the [TaskStore]. Tasks that have segment-level metadata in
   * the [MetadataStore] will resume from where they left off; others will
   * restart from scratch.
   *
   * Call this method once after creating a new [KDown] instance following a
   * process restart to continue interrupted downloads.
   *
   * @return a list of [DownloadTask] handles for the restored downloads
   */
  suspend fun restoreTasks(): List<DownloadTask> {
    KDownLogger.i("KDown") { "Restoring tasks from persistent storage" }
    val records = taskStore.loadAll().filter { it.state.isRestorable }
    KDownLogger.i("KDown") { "Found ${records.size} restorable task(s)" }

    return records.mapNotNull { record ->
      val metadata = metadataStore.load(record.taskId)
      if (metadata != null) {
        KDownLogger.d("KDown") {
          "Resuming task ${record.taskId} from stored metadata " +
            "(${metadata.downloadedBytes}/${metadata.totalBytes} bytes)"
        }
        resume(record.taskId)
      } else {
        KDownLogger.d("KDown") {
          "Restarting task ${record.taskId} from scratch " +
            "(no segment metadata found)"
        }
        val destPath = record.destPath
        val request = DownloadRequest(
          url = record.url,
          directory = destPath.parent ?: Path("."),
          fileName = destPath.name,
          connections = record.connections,
          headers = record.headers
        )
        download(request)
      }
    }
  }

  /**
   * Removes a task record from the [TaskStore]. Does not cancel an active
   * download — call [cancel] first if the task is still running.
   */
  suspend fun removeTask(taskId: String): Unit {
    taskStore.remove(taskId)
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
