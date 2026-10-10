package com.linroid.ketch.core.engine

import com.linroid.ketch.api.Destination
import com.linroid.ketch.api.DownloadConfig
import com.linroid.ketch.api.DownloadProgress
import com.linroid.ketch.api.DownloadRequest
import com.linroid.ketch.api.DownloadState
import com.linroid.ketch.api.KetchError
import com.linroid.ketch.api.PauseReason
import com.linroid.ketch.api.SpeedLimit
import com.linroid.ketch.api.log.KetchLogger
import com.linroid.ketch.api.log.describeCauses
import com.linroid.ketch.api.log.redactUrl
import com.linroid.ketch.core.KetchDispatchers
import com.linroid.ketch.core.file.FileNameResolver
import com.linroid.ketch.core.file.NoOpFileAccessor
import com.linroid.ketch.core.file.createFileAccessor
import com.linroid.ketch.core.task.TaskHandle
import com.linroid.ketch.core.task.TaskRecord
import com.linroid.ketch.core.task.TaskState
import com.linroid.ketch.core.task.savedProgress
import kotlin.concurrent.Volatile
import kotlin.time.Clock
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Starts, resumes and stops download executions.
 *
 * @param config provides the current global configuration; each start or
 *   resume takes a snapshot of it, with the task's own [DownloadRequest.proxy] if it has one
 * @param clock stamps when downloads complete
 * @param connections the reporter of a task's live connections
 */
internal class DownloadCoordinator(
  private val sourceResolver: SourceResolver,
  private val config: () -> DownloadConfig,
  private val fileNameResolver: FileNameResolver,
  private val globalLimiter: SpeedLimiter = SpeedLimiter.Unlimited,
  private val dispatchers: KetchDispatchers,
  private val clock: Clock = Clock.System,
  private val connections: (taskId: String) -> ConnectionReporter = { ConnectionReporter.None },
) {
  private val scope: CoroutineScope = CoroutineScope(dispatchers.network)
  private val log = KetchLogger("Coordinator")
  private val mutex = Mutex()
  private val activeDownloads = mutableMapOf<String, ActiveEntry>()
  private val stoppingDownloads = mutableMapOf<String, Job>()

  // Set by close(): executions cancelled from then on pause for PauseReason.Shutdown.
  @Volatile
  private var closing = false

  private data class ActiveEntry(
    val handle: TaskHandle,
    val execution: DownloadExecution,
    val job: Job,
  )

  suspend fun start(handle: TaskHandle) {
    val taskId = handle.taskId
    log.i { "Starting download: taskId=$taskId, url=${redactUrl(handle.request.url)}" }
    handle.record.update {
      it.copy(state = TaskState.QUEUED, updatedAt = Clock.System.now())
    }

    launchExecution(handle)
  }

  /**
   * Stops the task's execution, keeping its progress, and shows it paused for [reason].
   * A task paused for [PauseReason.Preempted] keeps a QUEUED record, so a restart enqueues
   * it again. A task that already completed, failed or was canceled keeps its state.
   */
  suspend fun pause(taskId: String, reason: PauseReason = PauseReason.User) {
    val job = mutex.withLock {
      val entry = activeDownloads[taskId] ?: return@withLock stoppingDownloads[taskId]
      val handle = entry.handle
      val execution = entry.execution
      log.i { "Pausing download for taskId=$taskId" }

      val currentSegments = handle.mutableSegments.value
        .ifEmpty { null }

      val pausedDownloaded =
        currentSegments?.sumOf { it.downloadedBytes } ?: 0L

      execution.stopReportingProgress()
      // The execution may have finished just before; its finally has not removed it yet.
      handle.mutableState.update { state ->
        if (state.isTerminal) state
        else DownloadState.Paused(DownloadProgress(pausedDownloaded, execution.totalBytes), reason)
      }

      entry.job.cancel()

      if (currentSegments != null && handle.record.value.state !in FINISHED_STATES) {
        log.d { "Saving pause state for taskId=$taskId" }
        // Segments are left to the execution: once its source runs, it saves its final
        // segments while stopping, and this earlier snapshot could land after and replace
        // them. Before that, the record already holds the segments being resumed from.
        // A preempted task is saved as waiting before the join below, which can take seconds,
        // so a process death meanwhile does not restore it as paused by the user.
        val saved = if (reason is PauseReason.Preempted) TaskState.QUEUED else TaskState.PAUSED
        handle.record.update {
          if (it.state in FINISHED_STATES) it
          else it.copy(state = saved, updatedAt = Clock.System.now())
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
      stoppingDownloads[taskId] = entry.job
      entry.job
    }
    // Source checkpointing and file closure finish before an immediate resume can start.
    job?.join()
  }

  suspend fun awaitCompletion(taskId: String) {
    val job = mutex.withLock { activeDownloads[taskId]?.job ?: stoppingDownloads[taskId] }
    job?.join()
  }

  /**
   * Joins the task's stopping execution, if any, without touching a running one.
   */
  suspend fun awaitStopping(taskId: String) {
    mutex.withLock { stoppingDownloads[taskId] }?.join()
  }

  /** Whether the task has a running execution. */
  suspend fun isActive(taskId: String): Boolean = mutex.withLock {
    activeDownloads.containsKey(taskId)
  }

  /**
   * Whether the task's running execution is finishing: its source returned and it is completing,
   * so it takes no more selections.
   */
  suspend fun isFinishing(taskId: String): Boolean = mutex.withLock {
    activeDownloads[taskId]?.execution?.isFinishing == true
  }

  /**
   * Hands a saved selection to the task's running execution. The caller holds the task's
   * [TaskHandle.controlLock] and saved the selection first.
   */
  suspend fun deliverSelection(
    taskId: String,
    fileIds: Set<String>,
    totalBytes: Long,
  ): SelectionDelivery = mutex.withLock {
    val entry = activeDownloads[taskId] ?: return@withLock SelectionDelivery.NOT_RUNNING
    if (entry.execution.deliverSelection(fileIds, totalBytes)) {
      SelectionDelivery.DELIVERED
    } else {
      SelectionDelivery.FINISHING
    }
  }

  suspend fun resume(
    handle: TaskHandle,
    destination: Destination? = null,
  ): Boolean {
    val taskId = handle.taskId
    mutex.withLock { stoppingDownloads[taskId] }?.join()
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
    if (segments.isEmpty() && taskRecord.totalBytes > 0 && !managesOwnFileIo(taskRecord)) {
      // Older versions saved [] when a download stopped before its first segments.
      log.i { "No saved progress for taskId=$taskId, starting it again" }
      return false
    }
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

  /**
   * Stops the task's download, if any, and marks it canceled.
   *
   * @param deletePartialFile whether a running download deletes the file it was writing: `true`
   *   for an explicit cancel or `remove(deleteFiles = true)`, `false` for
   *   `remove(deleteFiles = false)`
   */
  suspend fun cancel(handle: TaskHandle, deletePartialFile: Boolean) {
    val taskId = handle.taskId
    log.i { "Canceling download for taskId=$taskId, deletePartialFile=$deletePartialFile" }
    val job = mutex.withLock {
      val entry = activeDownloads[taskId]
      val stopping = entry?.job ?: stoppingDownloads[taskId]
      entry?.execution?.let { execution ->
        execution.stopReportingProgress()
        if (deletePartialFile) execution.discardPartialFile()
      }
      stopping?.cancel()
      activeDownloads.remove(taskId)
      stopping
    }
    // Await the job's finally chain (including FileAccessor.close)
    // before returning, so callers can safely follow up with file
    // cleanup. Joining must happen outside the mutex because the
    // job's own finally re-acquires it to clean up activeDownloads.
    job?.join()
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

  /**
   * Applies [limit] to the task's running execution, if any. Callers
   * persist the limit first so an execution created later picks it up.
   */
  suspend fun setTaskSpeedLimit(taskId: String, limit: SpeedLimit) {
    mutex.withLock {
      val entry = activeDownloads[taskId] ?: return
      entry.execution.setSpeedLimit(limit)
    }
  }

  /** Like [setTaskSpeedLimit], for the task's connection count. */
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
          when {
            // A download that completed just before the cancellation keeps its state.
            s.isTerminal || s is DownloadState.Paused -> Unit
            // Closing keeps the task resumable, also one still starting in its slot: its
            // record stays DOWNLOADING or QUEUED, so the next start() resumes it.
            closing -> handle.mutableState.value = DownloadState.Paused(
              if (s is DownloadState.Queued) {
                // Not resolved yet: what the record saved from earlier runs.
                handle.record.value.savedProgress()
              } else {
                DownloadProgress(
                  handle.mutableSegments.value.sumOf { it.downloadedBytes },
                  execution.totalBytes,
                )
              },
              PauseReason.Shutdown,
            )
            // Put back in the queue, it waits for a slot again.
            s is DownloadState.Queued -> Unit
            else -> handle.mutableState.value = DownloadState.Canceled
          }
          throw e
        } catch (e: Exception) {
          if (e is CancellationException) throw e
          val error = when (e) {
            is KetchError -> e
            else -> KetchError.Unknown(e)
          }
          log.e(error) { "Download failed for taskId=$taskId: ${error.describeCauses()}" }
          handle.record.update {
            it.copy(
              state = TaskState.FAILED,
              error = error,
              updatedAt = Clock.System.now(),
            )
          }
          handle.mutableState.value = DownloadState.Failed(error)
        } finally {
          val finished = currentCoroutineContext()[Job]
          withContext(NonCancellable) {
            mutex.withLock {
              if (activeDownloads[taskId]?.execution === execution) activeDownloads.remove(taskId)
              if (stoppingDownloads[taskId] === finished) stoppingDownloads.remove(taskId)
            }
          }
        }
      }

      activeDownloads[taskId] = ActiveEntry(handle, execution, job)
    }
  }

  /**
   * Whether the record's source writes its own files, like torrents, whose saved segments only
   * mirror progress the source checks itself. Unknown sources count as engine-managed.
   */
  private fun managesOwnFileIo(record: TaskRecord): Boolean {
    val sourceType = record.sourceType ?: return false
    return try {
      sourceResolver.resolveByType(sourceType, record.request.url).managesOwnFileIo
    } catch (_: KetchError) {
      false
    }
  }

  private fun createExecution(handle: TaskHandle): DownloadExecution {
    return DownloadExecution(
      handle = handle,
      sourceResolver = sourceResolver,
      fileNameResolver = fileNameResolver,
      config = config().forRequest(handle.request),
      globalLimiter = globalLimiter,
      dispatchers = dispatchers,
      clock = clock,
      connections = connections(handle.taskId),
    )
  }

  /**
   * Lets the task's source stop work that outlived the download via
   * [DownloadSource.release]. The caller must have cancelled the active
   * download first. No-op for tasks without a recorded source type.
   */
  suspend fun release(handle: TaskHandle) {
    val record = handle.record.value
    val sourceType = record.sourceType ?: return
    val source = try {
      sourceResolver.resolveByType(sourceType, record.request.url)
    } catch (e: KetchError) {
      log.w(e) { "Skipping release for taskId=${handle.taskId}: unknown source '$sourceType'" }
      return
    }
    source.release(handle.taskId, record.sourceResumeState)
  }

  /**
   * Deletes any data produced for the given task by dispatching to its
   * [DownloadSource.cleanup]. The caller must have cancelled the active
   * download (if any) before invoking this. No-op if the task has no
   * recorded sourceType or outputPath (nothing to clean up).
   */
  suspend fun cleanup(handle: TaskHandle) {
    val record = handle.record.value
    val sourceType = record.sourceType
    val outputPath = record.outputPath
    if (sourceType == null || outputPath == null) {
      log.d {
        "Skipping cleanup for taskId=${handle.taskId} " +
          "(sourceType=$sourceType, outputPath=$outputPath)"
      }
      return
    }
    val source = try {
      sourceResolver.resolveByType(sourceType, record.request.url)
    } catch (e: KetchError) {
      log.w(e) {
        "Skipping cleanup for taskId=${handle.taskId}: " +
          "unknown source type '$sourceType'"
      }
      return
    }
    val fa = if (source.managesOwnFileIo) {
      NoOpFileAccessor
    } else {
      createFileAccessor(outputPath, dispatchers.io)
    }
    val ctx = DownloadContext(
      taskId = handle.taskId,
      url = handle.request.url,
      request = handle.request,
      fileAccessor = fa,
      segments = MutableStateFlow(handle.mutableSegments.value),
      onProgress = { _, _ -> },
      throttle = { _ -> },
      headers = handle.request.headers,
      outputPath = outputPath,
      config = config().forRequest(handle.request),
    )
    try {
      source.cleanup(ctx, record.sourceResumeState)
    } finally {
      if (!source.managesOwnFileIo) {
        fa.close()
      }
    }
  }

  /**
   * Stops every execution. Running tasks pause for [PauseReason.Shutdown] and keep their
   * partial files and DOWNLOADING records.
   */
  fun close() {
    closing = true
    scope.cancel()
  }

  private companion object {
    val FINISHED_STATES = setOf(TaskState.COMPLETED, TaskState.FAILED, TaskState.CANCELED)
  }
}

/** What [DownloadCoordinator.deliverSelection] did with a selection. */
internal enum class SelectionDelivery {
  /** The running execution took it. */
  DELIVERED,

  /** The task has no running execution; the next one reads the saved selection. */
  NOT_RUNNING,

  /** The running execution is finishing and takes no more selections. */
  FINISHING
}

/** This configuration as [request] downloads with it: with its own proxy, if it has one. */
internal fun DownloadConfig.forRequest(request: DownloadRequest): DownloadConfig =
  request.proxy?.let { copy(proxy = it) } ?: this
