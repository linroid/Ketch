package com.linroid.ketch.core.engine

import com.linroid.ketch.api.Destination
import com.linroid.ketch.api.DownloadProgress
import com.linroid.ketch.api.DownloadState
import com.linroid.ketch.api.KetchError
import com.linroid.ketch.api.SpeedLimit
import com.linroid.ketch.api.DownloadConfig
import com.linroid.ketch.api.log.KetchLogger
import com.linroid.ketch.core.KetchDispatchers
import com.linroid.ketch.core.file.FileNameResolver
import com.linroid.ketch.core.task.TaskHandle
import com.linroid.ketch.core.task.TaskState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.time.Clock

internal class DownloadCoordinator(
  private val sourceResolver: SourceResolver,
  private val config: DownloadConfig,
  private val fileNameResolver: FileNameResolver,
  private val globalLimiter: SpeedLimiter = SpeedLimiter.Unlimited,
  private val dispatchers: KetchDispatchers,
) {
  private val scope: CoroutineScope = CoroutineScope(dispatchers.network)
  private val log = KetchLogger("Coordinator")
  private val mutex = Mutex()
  private val activeDownloads = mutableMapOf<String, ActiveEntry>()

  private data class ActiveEntry(
    val handle: TaskHandle,
    val execution: DownloadExecution,
    val job: Job,
  )

  suspend fun start(handle: TaskHandle) {
    val taskId = handle.taskId
    log.i { "Starting download: taskId=$taskId, url=${handle.request.url}" }
    handle.record.update {
      it.copy(state = TaskState.QUEUED, updatedAt = Clock.System.now())
    }

    launchExecution(handle)
  }

  suspend fun pause(taskId: String) {
    mutex.withLock {
      val entry = activeDownloads[taskId] ?: return
      val handle = entry.handle
      val execution = entry.execution
      log.i { "Pausing download for taskId=$taskId" }

      val currentSegments = handle.mutableSegments.value
        .ifEmpty { null }

      val pausedDownloaded =
        currentSegments?.sumOf { it.downloadedBytes } ?: 0L

      handle.mutableState.value = DownloadState.Paused(
        DownloadProgress(pausedDownloaded, execution.totalBytes),
      )

      entry.job.cancel()

      currentSegments?.let { segments ->
        log.d { "Saving pause state for taskId=$taskId" }
        handle.record.update {
          it.copy(
            state = TaskState.PAUSED,
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
    handle: TaskHandle,
    destination: Destination? = null,
  ): Boolean {
    val taskId = handle.taskId
    mutex.withLock {
      if (activeDownloads.containsKey(taskId)) {
        log.d {
          "Download already active for taskId=$taskId, " +
            "skipping resume"
        }
        return true
      }
    }

    val taskRecord = handle.record.value
    val segments = taskRecord.segments ?: return false
    log.d {
      "Resume loaded record: taskId=$taskId, " +
        "segments=${segments.size}, " +
        "totalBytes=${taskRecord.totalBytes}"
    }

    handle.mutableState.value = DownloadState.Queued
    handle.mutableSegments.value = segments

    handle.record.update {
      it.copy(
        state = TaskState.DOWNLOADING,
        updatedAt = Clock.System.now(),
        outputPath = destination?.value ?: it.outputPath,
      )
    }

    val resumeInfo = DownloadExecution.ResumeInfo(
      record = taskRecord.copy(
        outputPath = destination?.value ?: taskRecord.outputPath,
      ),
      segments = segments,
    )

    launchExecution(handle, resumeInfo)
    return true
  }

  suspend fun cancel(handle: TaskHandle) {
    val taskId = handle.taskId
    log.i { "Canceling download for taskId=$taskId" }
    mutex.withLock {
      val entry = activeDownloads[taskId]
      entry?.job?.cancel()
      activeDownloads.remove(taskId)
    }
    handle.mutableState.value = DownloadState.Canceled
    handle.record.update {
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

  private suspend fun launchExecution(
    handle: TaskHandle,
    resumeInfo: DownloadExecution.ResumeInfo? = null,
  ) {
    val taskId = handle.taskId
    mutex.withLock {
      if (activeDownloads.containsKey(taskId)) {
        log.d { "Download already active for taskId=$taskId, skipping" }
        return
      }

      val execution = createExecution(handle)

      val job = scope.launch {
        try {
          execution.execute(resumeInfo)
        } catch (e: CancellationException) {
          val s = handle.mutableState.value
          if (s !is DownloadState.Paused &&
            s !is DownloadState.Queued
          ) {
            handle.mutableState.value = DownloadState.Canceled
          }
          throw e
        } catch (e: Exception) {
          if (e is CancellationException) throw e
          val error = when (e) {
            is KetchError -> e
            else -> KetchError.Unknown(e)
          }
          handle.record.update {
            it.copy(
              state = TaskState.FAILED,
              error = error,
              updatedAt = Clock.System.now(),
            )
          }
          handle.mutableState.value = DownloadState.Failed(error)
        } finally {
          withContext(NonCancellable) {
            mutex.withLock { activeDownloads.remove(taskId) }
          }
        }
      }

      activeDownloads[taskId] = ActiveEntry(handle, execution, job)
    }
  }

  private fun createExecution(handle: TaskHandle): DownloadExecution {
    return DownloadExecution(
      handle = handle,
      sourceResolver = sourceResolver,
      fileNameResolver = fileNameResolver,
      config = config,
      globalLimiter = globalLimiter,
      dispatchers = dispatchers,
    )
  }

  fun close() {
    scope.cancel()
  }
}
