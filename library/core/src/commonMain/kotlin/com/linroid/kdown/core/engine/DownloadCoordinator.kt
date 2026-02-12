package com.linroid.kdown.core.engine

import com.linroid.kdown.api.DownloadProgress
import com.linroid.kdown.api.DownloadRequest
import com.linroid.kdown.api.DownloadState
import com.linroid.kdown.api.KDownError
import com.linroid.kdown.api.ResolvedSource
import com.linroid.kdown.api.Segment
import com.linroid.kdown.api.SpeedLimit
import com.linroid.kdown.core.DownloadConfig
import com.linroid.kdown.core.file.FileAccessor
import com.linroid.kdown.core.file.FileNameResolver
import com.linroid.kdown.core.log.KDownLogger
import com.linroid.kdown.core.task.TaskRecord
import com.linroid.kdown.core.task.TaskState
import com.linroid.kdown.core.task.TaskStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlin.time.Clock
import kotlin.time.TimeSource

internal class DownloadCoordinator(
  private val sourceResolver: SourceResolver,
  private val taskStore: TaskStore,
  private val config: DownloadConfig,
  private val fileAccessorFactory: (Path) -> FileAccessor,
  private val fileNameResolver: FileNameResolver,
  private val globalLimiter: SpeedLimiter = SpeedLimiter.Unlimited,
) {
  private val mutex = Mutex()
  private val activeDownloads = mutableMapOf<String, ActiveDownload>()

  private class ActiveDownload(
    val job: Job,
    val stateFlow: MutableStateFlow<DownloadState>,
    val segmentsFlow: MutableStateFlow<List<Segment>>,
    var segments: List<Segment>?,
    var fileAccessor: FileAccessor?,
    var totalBytes: Long = 0,
    val taskLimiter: DelegatingSpeedLimiter = DelegatingSpeedLimiter(),
  )

  suspend fun start(
    taskId: String,
    request: DownloadRequest,
    scope: CoroutineScope,
    stateFlow: MutableStateFlow<DownloadState>,
    segmentsFlow: MutableStateFlow<List<Segment>>,
  ) {
    val directory = request.directory?.let { Path(it) }
      ?: Path(config.defaultDirectory)
    val initialDestPath = request.fileName?.let {
      Path(directory, it)
    } ?: directory

    val now = Clock.System.now()
    taskStore.save(
      TaskRecord(
        taskId = taskId,
        request = request,
        destPath = initialDestPath,
        state = TaskState.PENDING,
        createdAt = now,
        updatedAt = now,
      )
    )

    mutex.withLock {
      if (activeDownloads.containsKey(taskId)) {
        KDownLogger.d("Coordinator") {
          "Download already active for taskId=$taskId, skipping start"
        }
        return
      }

      val job = scope.launch {
        try {
          executeDownload(taskId, request, stateFlow, segmentsFlow)
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
            is KDownError -> e
            else -> KDownError.Unknown(e)
          }
          updateTaskState(taskId, TaskState.FAILED, error.message)
          stateFlow.value = DownloadState.Failed(error)
        }
      }

      activeDownloads[taskId] = ActiveDownload(
        job = job,
        stateFlow = stateFlow,
        segmentsFlow = segmentsFlow,
        segments = null,
        fileAccessor = null,
      )
    }
  }

  suspend fun startFromRecord(
    record: TaskRecord,
    scope: CoroutineScope,
    stateFlow: MutableStateFlow<DownloadState>,
    segmentsFlow: MutableStateFlow<List<Segment>>,
  ) {
    updateTaskRecord(record.taskId) {
      it.copy(
        state = TaskState.PENDING,
        updatedAt = Clock.System.now(),
      )
    }

    mutex.withLock {
      if (activeDownloads.containsKey(record.taskId)) {
        KDownLogger.d("Coordinator") {
          "Download already active for taskId=${record.taskId}, " +
            "skipping startFromRecord"
        }
        return
      }

      val job = scope.launch {
        try {
          executeDownload(
            record.taskId, record.request, stateFlow, segmentsFlow
          )
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
            is KDownError -> e
            else -> KDownError.Unknown(e)
          }
          updateTaskState(
            record.taskId, TaskState.FAILED, error.message
          )
          stateFlow.value = DownloadState.Failed(error)
        }
      }

      activeDownloads[record.taskId] = ActiveDownload(
        job = job,
        stateFlow = stateFlow,
        segmentsFlow = segmentsFlow,
        segments = null,
        fileAccessor = null,
      )
    }
  }

  private suspend fun executeDownload(
    taskId: String,
    request: DownloadRequest,
    stateFlow: MutableStateFlow<DownloadState>,
    segmentsFlow: MutableStateFlow<List<Segment>>,
  ) {
    val resolved = request.resolvedUrl
    val source: DownloadSource
    val resolvedUrl: ResolvedSource

    if (resolved != null) {
      KDownLogger.d("Coordinator") {
        "Using pre-resolved info for ${request.url} " +
          "(source=${resolved.sourceType})"
      }
      source = sourceResolver.resolveByType(resolved.sourceType)
      resolvedUrl = resolved
    } else {
      source = sourceResolver.resolve(request.url)
      KDownLogger.d("Coordinator") {
        "Resolved source '${source.type}' for ${request.url}"
      }
      resolvedUrl = source.resolve(request.url, request.headers)
    }

    val totalBytes = resolvedUrl.totalBytes
    if (totalBytes < 0) throw KDownError.Unsupported

    val fileName = resolvedUrl.suggestedFileName
      ?: fileNameResolver.resolve(
        request, toServerInfo(resolvedUrl),
      )
    val dir = request.directory?.let { Path(it) }
      ?: Path(config.defaultDirectory)
    val destPath = deduplicatePath(dir, fileName)

    val now = Clock.System.now()
    updateTaskRecord(taskId) {
      it.copy(
        destPath = destPath,
        state = TaskState.DOWNLOADING,
        totalBytes = totalBytes,
        acceptRanges = resolvedUrl.supportsResume,
        etag = resolvedUrl.metadata[HttpDownloadSource.META_ETAG],
        lastModified = resolvedUrl.metadata[
          HttpDownloadSource.META_LAST_MODIFIED
        ],
        sourceType = source.type,
        updatedAt = now,
      )
    }

    val fileAccessor = fileAccessorFactory(destPath)

    val taskLimiter = mutex.withLock {
      activeDownloads[taskId]?.let {
        it.fileAccessor = fileAccessor
        it.totalBytes = totalBytes
        it.taskLimiter.delegate = createLimiter(request.speedLimit)
        it.taskLimiter
      } ?: throw KDownError.Unknown(
        IllegalStateException("ActiveDownload not found for $taskId")
      )
    }

    try {
      val context = buildContext(
        taskId, request.url, request, fileAccessor,
        segmentsFlow, stateFlow, taskLimiter, totalBytes,
        request.headers,
        preResolved = if (resolved != null) resolvedUrl else null,
      )

      downloadWithRetry(taskId) { source.download(context) }

      try {
        fileAccessor.flush()
      } catch (e: Exception) {
        if (e is CancellationException) throw e
        if (e is KDownError) throw e
        throw KDownError.Disk(e)
      }

      // Save resume state for potential future resume
      val resumeState = buildHttpResumeState(taskId)
      updateTaskRecord(taskId) {
        it.copy(
          state = TaskState.COMPLETED,
          downloadedBytes = totalBytes,
          segments = null,
          sourceResumeState = resumeState,
          updatedAt = Clock.System.now(),
        )
      }

      KDownLogger.i("Coordinator") {
        "Download completed successfully for taskId=$taskId"
      }
      stateFlow.value =
        DownloadState.Completed(destPath.toString())
    } finally {
      fileAccessor.close()
      withContext(NonCancellable) {
        mutex.withLock {
          activeDownloads.remove(taskId)
        }
      }
    }
  }

  suspend fun pause(taskId: String) {
    mutex.withLock {
      val active = activeDownloads[taskId] ?: return
      KDownLogger.i("Coordinator") {
        "Pausing download for taskId=$taskId"
      }

      // Use segmentsFlow as the source of truth — it is
      // continuously updated by the download source with the
      // latest per-segment progress.
      val currentSegments = active.segmentsFlow.value
        .ifEmpty { null }
        ?: active.segments

      val pausedDownloaded =
        currentSegments?.sumOf { it.downloadedBytes } ?: 0L

      // Set Paused BEFORE cancelling the job so the
      // CancellationException handler won't set Canceled.
      active.stateFlow.value = DownloadState.Paused(
        DownloadProgress(pausedDownloaded, active.totalBytes)
      )

      active.job.cancel()

      currentSegments?.let { segments ->
        KDownLogger.d("Coordinator") {
          "Saving pause state for taskId=$taskId"
        }
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

      active.fileAccessor?.let { accessor ->
        try {
          accessor.flush()
        } catch (e: Exception) {
          KDownLogger.w("Coordinator", e) {
            "Failed to flush file during pause for taskId=$taskId"
          }
        }
      }

      activeDownloads.remove(taskId)
    }
  }

  suspend fun resume(
    taskId: String,
    scope: CoroutineScope,
    stateFlow: MutableStateFlow<DownloadState>,
    segmentsFlow: MutableStateFlow<List<Segment>>,
  ): Boolean {
    mutex.withLock {
      if (activeDownloads.containsKey(taskId)) {
        KDownLogger.d("Coordinator") {
          "Download already active for taskId=$taskId, " +
            "skipping resume"
        }
        return true
      }
    }

    val taskRecord = taskStore.load(taskId) ?: return false
    val segments = taskRecord.segments ?: return false

    stateFlow.value = DownloadState.Pending
    segmentsFlow.value = segments

    updateTaskRecord(taskId) {
      it.copy(
        state = TaskState.DOWNLOADING,
        updatedAt = Clock.System.now(),
      )
    }

    mutex.withLock {
      val job = scope.launch {
        try {
          resumeDownload(
            taskId, taskRecord, segments, stateFlow, segmentsFlow
          )
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
            is KDownError -> e
            else -> KDownError.Unknown(e)
          }
          updateTaskState(taskId, TaskState.FAILED, error.message)
          stateFlow.value = DownloadState.Failed(error)
        }
      }

      activeDownloads[taskId] = ActiveDownload(
        job = job,
        stateFlow = stateFlow,
        segmentsFlow = segmentsFlow,
        segments = segments,
        fileAccessor = null,
      )
    }

    return true
  }

  private suspend fun resumeDownload(
    taskId: String,
    taskRecord: TaskRecord,
    segments: List<Segment>,
    stateFlow: MutableStateFlow<DownloadState>,
    segmentsFlow: MutableStateFlow<List<Segment>>,
  ) {
    val sourceType = taskRecord.sourceType ?: HttpDownloadSource.TYPE
    val source = sourceResolver.resolveByType(sourceType)
    KDownLogger.i("Coordinator") {
      "Resuming download for taskId=$taskId via " +
        "source '${source.type}'"
    }

    val fileAccessor = fileAccessorFactory(taskRecord.destPath)

    val taskLimiter = mutex.withLock {
      activeDownloads[taskId]?.let {
        it.segments = segments
        it.fileAccessor = fileAccessor
        it.totalBytes = taskRecord.totalBytes
        it.taskLimiter.delegate =
          createLimiter(taskRecord.request.speedLimit)
        it.taskLimiter
      } ?: throw KDownError.Unknown(
        IllegalStateException("ActiveDownload not found for $taskId")
      )
    }

    val resumeState = taskRecord.sourceResumeState
      ?: HttpDownloadSource.buildResumeState(
        etag = taskRecord.etag,
        lastModified = taskRecord.lastModified,
        totalBytes = taskRecord.totalBytes,
      )

    val context = buildContext(
      taskId, taskRecord.request.url, taskRecord.request,
      fileAccessor, segmentsFlow, stateFlow, taskLimiter,
      taskRecord.totalBytes, taskRecord.request.headers
    )

    try {
      downloadWithRetry(taskId) {
        source.resume(context, resumeState)
      }

      try {
        fileAccessor.flush()
      } catch (e: Exception) {
        if (e is CancellationException) throw e
        if (e is KDownError) throw e
        throw KDownError.Disk(e)
      }

      updateTaskRecord(taskId) {
        it.copy(
          state = TaskState.COMPLETED,
          downloadedBytes = taskRecord.totalBytes,
          segments = null,
          updatedAt = Clock.System.now(),
        )
      }

      stateFlow.value =
        DownloadState.Completed(taskRecord.destPath.toString())
    } finally {
      fileAccessor.close()
      withContext(NonCancellable) {
        mutex.withLock {
          activeDownloads.remove(taskId)
        }
      }
    }
  }

  private suspend fun downloadWithRetry(
    taskId: String,
    block: suspend () -> Unit,
  ) {
    var retryCount = 0
    while (true) {
      try {
        block()
        return
      } catch (e: CancellationException) {
        throw e
      } catch (e: Exception) {
        val error = when (e) {
          is KDownError -> e
          else -> KDownError.Unknown(e)
        }

        if (!error.isRetryable || retryCount >= config.retryCount) {
          KDownLogger.e("Coordinator") {
            "Download failed after $retryCount retries: " +
              "${error.message}"
          }
          throw error
        }

        retryCount++
        val delayMs = config.retryDelayMs * (1 shl (retryCount - 1))
        KDownLogger.w("Coordinator") {
          "Retry attempt $retryCount after ${delayMs}ms delay: " +
            "${error.message}"
        }
        delay(delayMs)
      }
    }
  }

  suspend fun cancel(taskId: String) {
    KDownLogger.i("Coordinator") {
      "Canceling download for taskId=$taskId"
    }
    mutex.withLock {
      val active = activeDownloads[taskId]
      active?.job?.cancel()
      active?.stateFlow?.value = DownloadState.Canceled
      activeDownloads.remove(taskId)
    }
    updateTaskRecord(taskId) {
      it.copy(
        state = TaskState.CANCELED,
        segments = null,
        updatedAt = Clock.System.now(),
      )
    }
  }

  suspend fun getState(taskId: String): DownloadState? {
    return mutex.withLock {
      activeDownloads[taskId]?.stateFlow?.value
    }
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
  ) {
    val existing = taskStore.load(taskId)
    if (existing == null) {
      KDownLogger.w("Coordinator") {
        "TaskRecord not found for taskId=$taskId, skipping update"
      }
      return
    }
    taskStore.save(update(existing))
  }

  suspend fun setTaskSpeedLimit(taskId: String, limit: SpeedLimit) {
    mutex.withLock {
      val active = activeDownloads[taskId] ?: return
      val current = active.taskLimiter.delegate
      if (limit.isUnlimited) {
        active.taskLimiter.delegate = SpeedLimiter.Unlimited
      } else if (current is TokenBucket) {
        current.updateRate(limit.bytesPerSecond)
      } else {
        active.taskLimiter.delegate = TokenBucket(limit.bytesPerSecond)
      }
      KDownLogger.i("Coordinator") {
        "Task speed limit updated for taskId=$taskId: " +
          "${limit.bytesPerSecond} bytes/sec"
      }
    }
  }

  private fun createLimiter(speedLimit: SpeedLimit): SpeedLimiter {
    return if (speedLimit.isUnlimited) {
      SpeedLimiter.Unlimited
    } else {
      TokenBucket(speedLimit.bytesPerSecond)
    }
  }

  private fun buildContext(
    taskId: String,
    url: String,
    request: DownloadRequest,
    fileAccessor: FileAccessor,
    segmentsFlow: MutableStateFlow<List<Segment>>,
    stateFlow: MutableStateFlow<DownloadState>,
    taskLimiter: SpeedLimiter,
    totalBytes: Long,
    headers: Map<String, String>,
    preResolved: ResolvedSource? = null,
  ): DownloadContext {
    var lastBytes = 0L
    var lastMark = TimeSource.Monotonic.markNow()
    var speed = 0L
    return DownloadContext(
      taskId = taskId,
      url = url,
      request = request,
      fileAccessor = fileAccessor,
      segments = segmentsFlow,
      onProgress = { downloaded, total ->
        val now = TimeSource.Monotonic.markNow()
        val elapsed = (now - lastMark).inWholeMilliseconds
        if (elapsed >= 500) {
          val delta = downloaded - lastBytes
          speed = if (elapsed > 0) delta * 1000 / elapsed else 0L
          lastBytes = downloaded
          lastMark = now
        }
        stateFlow.value = DownloadState.Downloading(
          DownloadProgress(downloaded, total, speed)
        )
        // Update segments in task record periodically
        val snapshot = segmentsFlow.value
        updateTaskRecord(taskId) {
          it.copy(
            segments = snapshot,
            downloadedBytes = downloaded,
            updatedAt = Clock.System.now(),
          )
        }
      },
      throttle = { bytes ->
        taskLimiter.acquire(bytes)
        globalLimiter.acquire(bytes)
      },
      headers = headers,
      preResolved = preResolved,
    )
  }

  private suspend fun buildHttpResumeState(
    taskId: String,
  ): SourceResumeState? {
    val record = taskStore.load(taskId) ?: return null
    return HttpDownloadSource.buildResumeState(
      etag = record.etag,
      lastModified = record.lastModified,
      totalBytes = record.totalBytes,
    )
  }

  private fun toServerInfo(resolved: ResolvedSource): ServerInfo {
    return ServerInfo(
      contentLength = resolved.totalBytes,
      acceptRanges = resolved.supportsResume,
      etag = resolved.metadata[HttpDownloadSource.META_ETAG],
      lastModified = resolved.metadata[
        HttpDownloadSource.META_LAST_MODIFIED
      ],
    )
  }

  companion object {
    internal fun deduplicatePath(
      directory: Path,
      fileName: String,
    ): Path {
      val candidate = Path(directory, fileName)
      if (!SystemFileSystem.exists(candidate)) return candidate

      val dotIndex = fileName.lastIndexOf('.')
      val baseName: String
      val extension: String
      if (dotIndex > 0) {
        baseName = fileName.take(dotIndex)
        extension = fileName.substring(dotIndex)
      } else {
        baseName = fileName
        extension = ""
      }

      var seq = 1
      while (true) {
        val path = Path(directory, "$baseName ($seq)$extension")
        if (!SystemFileSystem.exists(path)) return path
        seq++
      }
    }
  }
}
