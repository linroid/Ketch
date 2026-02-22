package com.linroid.ketch.core.engine

import com.linroid.ketch.api.Destination
import com.linroid.ketch.api.DownloadProgress
import com.linroid.ketch.api.DownloadRequest
import com.linroid.ketch.api.DownloadState
import com.linroid.ketch.api.KetchError
import com.linroid.ketch.api.Segment
import com.linroid.ketch.api.SpeedLimit
import com.linroid.ketch.api.DownloadConfig
import com.linroid.ketch.api.log.KetchLogger
import com.linroid.ketch.core.KetchDispatchers
import com.linroid.ketch.core.file.FileNameResolver
import com.linroid.ketch.core.task.TaskRecord
import com.linroid.ketch.core.task.TaskState
import com.linroid.ketch.core.task.TaskStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.time.Clock

internal class DownloadCoordinator(
  private val sourceResolver: SourceResolver,
  private val taskStore: TaskStore,
  private val config: DownloadConfig,
  private val fileNameResolver: FileNameResolver,
  private val globalLimiter: SpeedLimiter = SpeedLimiter.Unlimited,
  private val dispatchers: KetchDispatchers,
) {
  private val scope: CoroutineScope = CoroutineScope(dispatchers.network)
  private val log = KetchLogger("Coordinator")
  private val mutex = Mutex()
  private val recordMutex = Mutex()
  private val activeDownloads = mutableMapOf<String, ActiveEntry>()

  private data class ActiveEntry(
    val execution: DownloadExecution,
    val job: Job,
  )

  suspend fun start(
    taskId: String,
    request: DownloadRequest,
    stateFlow: MutableStateFlow<DownloadState>,
    segmentsFlow: MutableStateFlow<List<Segment>>,
  ) {
    log.i { "Starting download: taskId=$taskId, url=${request.url}" }
    updateTaskRecord(taskId) {
      it.copy(state = TaskState.QUEUED, updatedAt = Clock.System.now())
    }

    mutex.withLock {
      if (activeDownloads.containsKey(taskId)) {
        log.d { "Download already active for taskId=$taskId, skipping start" }
        return
      }

      val execution = createExecution(
        taskId, request, stateFlow, segmentsFlow,
      )

      val job = scope.launch {
        try {
          execution.execute()
        } catch (e: CancellationException) {
          val s = stateFlow.value
          if (s !is DownloadState.Paused &&
            s !is DownloadState.Queued
          ) {
            stateFlow.value = DownloadState.Canceled
          }
          throw e
        } catch (e: Exception) {
          if (e is CancellationException) throw e
          val error = when (e) {
            is KetchError -> e
            else -> KetchError.Unknown(e)
          }
          updateTaskState(taskId, TaskState.FAILED, error.message)
          stateFlow.value = DownloadState.Failed(error)
        } finally {
          withContext(NonCancellable) {
            mutex.withLock { activeDownloads.remove(taskId) }
          }
        }
      }

      activeDownloads[taskId] = ActiveEntry(execution, job)
    }
  }

  suspend fun pause(taskId: String) {
    mutex.withLock {
      val entry = activeDownloads[taskId] ?: return
      val execution = entry.execution
      log.i { "Pausing download for taskId=$taskId" }

      val currentSegments = execution.segmentsFlow.value
        .ifEmpty { null }

      val pausedDownloaded =
        currentSegments?.sumOf { it.downloadedBytes } ?: 0L

      execution.stateFlow.value = DownloadState.Paused(
        DownloadProgress(pausedDownloaded, execution.totalBytes),
      )

      entry.job.cancel()

      currentSegments?.let { segments ->
        log.d { "Saving pause state for taskId=$taskId" }
        val downloadedBytes =
          segments.sumOf { it.downloadedBytes }
        updateTaskRecord(taskId) {
          it.copy(
            state = TaskState.PAUSED,
            downloadedBytes = downloadedBytes,
            segments = segments,
            updatedAt = Clock.System.now(),
          )
        }
      }

      execution.fileAccessor?.let { accessor ->
        try {
          accessor.flush()
        } catch (e: Exception) {
          log.w(e) {
            "Failed to flush file during pause for taskId=$taskId"
          }
        }
      }

      activeDownloads.remove(taskId)
    }
  }

  suspend fun resume(
    taskId: String,
    stateFlow: MutableStateFlow<DownloadState>,
    segmentsFlow: MutableStateFlow<List<Segment>>,
    destination: Destination? = null,
  ): Boolean {
    mutex.withLock {
      if (activeDownloads.containsKey(taskId)) {
        log.d {
          "Download already active for taskId=$taskId, " +
            "skipping resume"
        }
        return true
      }
    }

    val taskRecord = taskStore.load(taskId) ?: return false
    val segments = taskRecord.segments ?: return false
    log.d {
      "Resume loaded record: taskId=$taskId, " +
        "segments=${segments.size}, " +
        "downloaded=${taskRecord.downloadedBytes}/" +
        "${taskRecord.totalBytes}"
    }

    stateFlow.value = DownloadState.Queued
    segmentsFlow.value = segments

    updateTaskRecord(taskId) {
      it.copy(
        state = TaskState.DOWNLOADING,
        updatedAt = Clock.System.now(),
        outputPath = destination?.value ?: it.outputPath,
      )
    }

    mutex.withLock {
      val execution = createExecution(
        taskId, taskRecord.request, stateFlow, segmentsFlow,
      )
      val resumeInfo = DownloadExecution.ResumeInfo(
        record = taskRecord.copy(
          outputPath = destination?.value ?: taskRecord.outputPath,
        ),
        segments = segments,
      )

      val job = scope.launch {
        try {
          execution.execute(resumeInfo)
        } catch (e: CancellationException) {
          val s = stateFlow.value
          if (s !is DownloadState.Paused &&
            s !is DownloadState.Queued
          ) {
            stateFlow.value = DownloadState.Canceled
          }
          throw e
        } catch (e: Exception) {
          if (e is CancellationException) throw e
          val error = when (e) {
            is KetchError -> e
            else -> KetchError.Unknown(e)
          }
          updateTaskState(taskId, TaskState.FAILED, error.message)
          stateFlow.value = DownloadState.Failed(error)
        } finally {
          withContext(NonCancellable) {
            mutex.withLock { activeDownloads.remove(taskId) }
          }
        }
      }

      activeDownloads[taskId] = ActiveEntry(execution, job)
    }

    return true
  }

  suspend fun cancel(taskId: String) {
    log.i { "Canceling download for taskId=$taskId" }
    mutex.withLock {
      val entry = activeDownloads[taskId]
      entry?.job?.cancel()
      entry?.execution?.stateFlow?.value = DownloadState.Canceled
      activeDownloads.remove(taskId)
    }
    updateTaskRecord(taskId) {
      it.copy(
        state = TaskState.CANCELED,
        segments = null,
        updatedAt = Clock.System.now(),
      )
    }
    log.d { "Cancel record updated for taskId=$taskId" }
  }

  suspend fun setTaskSpeedLimit(taskId: String, limit: SpeedLimit) {
    mutex.withLock {
      val entry = activeDownloads[taskId] ?: return
      entry.execution.setSpeedLimit(limit)
    }
  }

  suspend fun setTaskConnections(taskId: String, connections: Int) {
    mutex.withLock {
      val entry = activeDownloads[taskId] ?: return
      entry.execution.setConnections(connections)
    }
  }

  private fun createExecution(
    taskId: String,
    request: DownloadRequest,
    stateFlow: MutableStateFlow<DownloadState>,
    segmentsFlow: MutableStateFlow<List<Segment>>,
  ): DownloadExecution {
    return DownloadExecution(
      taskId = taskId,
      request = request,
      stateFlow = stateFlow,
      segmentsFlow = segmentsFlow,
      sourceResolver = sourceResolver,
      fileNameResolver = fileNameResolver,
      config = config,
      globalLimiter = globalLimiter,
      taskStore = taskStore,
      recordMutex = recordMutex,
      dispatchers = dispatchers,
    )
  }

  private suspend fun updateTaskState(
    taskId: String,
    state: TaskState,
    errorMessage: String? = null,
  ) {
    updateTaskRecord(taskId) {
      it.copy(
        state = state,
        errorMessage = errorMessage,
        updatedAt = Clock.System.now(),
      )
    }
  }

  private suspend fun updateTaskRecord(
    taskId: String,
    update: (TaskRecord) -> TaskRecord,
  ) = recordMutex.withLock {
    val existing = taskStore.load(taskId) ?: run {
      log.w { "TaskRecord not found for taskId=$taskId" }
      return@withLock
    }
    taskStore.save(update(existing))
  }

}
