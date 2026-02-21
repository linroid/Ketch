package com.linroid.ketch.core.engine

import com.linroid.ketch.api.Destination
import com.linroid.ketch.api.DownloadProgress
import com.linroid.ketch.api.DownloadRequest
import com.linroid.ketch.api.DownloadState
import com.linroid.ketch.api.KetchError
import com.linroid.ketch.api.ResolvedSource
import com.linroid.ketch.api.Segment
import com.linroid.ketch.api.SpeedLimit
import com.linroid.ketch.api.config.DownloadConfig
import com.linroid.ketch.api.isDirectory
import com.linroid.ketch.api.isFile
import com.linroid.ketch.api.isName
import com.linroid.ketch.api.log.KetchLogger
import com.linroid.ketch.core.file.FileAccessor
import com.linroid.ketch.core.file.FileNameResolver
import com.linroid.ketch.core.file.createFileAccessor
import com.linroid.ketch.core.file.resolveChildPath
import com.linroid.ketch.core.task.TaskRecord
import com.linroid.ketch.core.task.TaskState
import com.linroid.ketch.core.task.TaskStore
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
  private val fileNameResolver: FileNameResolver,
  private val globalLimiter: SpeedLimiter = SpeedLimiter.Unlimited,
  private val scope: CoroutineScope,
) {
  private val log = KetchLogger("Coordinator")
  private val mutex = Mutex()
  private val recordMutex = Mutex()
  private val activeDownloads = mutableMapOf<String, ActiveDownload>()

  private class ActiveDownload(
    val job: Job,
    val stateFlow: MutableStateFlow<DownloadState>,
    val segmentsFlow: MutableStateFlow<List<Segment>>,
    var segments: List<Segment>?,
    var fileAccessor: FileAccessor?,
    var totalBytes: Long = 0,
    val taskLimiter: DelegatingSpeedLimiter = DelegatingSpeedLimiter(),
    var context: DownloadContext? = null,
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
            is KetchError -> e
            else -> KetchError.Unknown(e)
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
    taskId: String,
    request: DownloadRequest,
    stateFlow: MutableStateFlow<DownloadState>,
    segmentsFlow: MutableStateFlow<List<Segment>>,
  ) {
    log.i { "Starting from record: taskId=$taskId" }
    updateTaskRecord(taskId) {
      it.copy(
        state = TaskState.QUEUED,
        updatedAt = Clock.System.now(),
      )
    }

    mutex.withLock {
      if (activeDownloads.containsKey(taskId)) {
        log.d {
          "Download already active for taskId=$taskId, " +
            "skipping startFromRecord"
        }
        return
      }

      val job = scope.launch {
        try {
          executeDownload(
            taskId, request, stateFlow, segmentsFlow,
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
            is KetchError -> e
            else -> KetchError.Unknown(e)
          }
          updateTaskState(
            taskId, TaskState.FAILED, error.message,
          )
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

  private suspend fun executeDownload(
    taskId: String,
    request: DownloadRequest,
    stateFlow: MutableStateFlow<DownloadState>,
    segmentsFlow: MutableStateFlow<List<Segment>>,
  ) {
    val resolved = request.resolvedSource
    val source: DownloadSource
    val resolvedUrl: ResolvedSource

    if (resolved != null) {
      log.d {
        "Using pre-resolved info for ${request.url} " +
          "(source=${resolved.sourceType})"
      }
      source = sourceResolver.resolveByType(resolved.sourceType)
      resolvedUrl = resolved
    } else {
      source = sourceResolver.resolve(request.url)
      log.d { "Resolved source '${source.type}' for ${request.url}" }
      resolvedUrl = source.resolve(request.url, request.headers)
    }

    val totalBytes = resolvedUrl.totalBytes
    if (totalBytes < 0) throw KetchError.Unsupported

    val fileName = resolvedUrl.suggestedFileName
      ?: fileNameResolver.resolve(
        request, toServerInfo(resolvedUrl),
      )
    val outputPath = resolveDestPath(
      destination = request.destination,
      defaultDir = config.defaultDirectory,
      serverFileName = fileName,
      deduplicate = true,
    )
    log.d { "Resolved outputPath=$outputPath" }

    if (totalBytes == 0L) {
      log.i { "Zero-byte file for taskId=$taskId, completing" }
      val fileAccessor = createFileAccessor(outputPath)
      try {
        fileAccessor.flush()
      } catch (e: Exception) {
        if (e is CancellationException) throw e
        throw KetchError.Disk(e)
      } finally {
        try {
          fileAccessor.close()
        } catch (e: Exception) {
          log.w(e) { "Failed to close file for taskId=$taskId" }
        }
      }
      updateTaskRecord(taskId) {
        it.copy(
          outputPath = outputPath,
          state = TaskState.COMPLETED,
          totalBytes = 0,
          downloadedBytes = 0,
          segments = null,
          sourceType = source.type,
          updatedAt = Clock.System.now(),
        )
      }
      stateFlow.value = DownloadState.Completed(outputPath)
      withContext(NonCancellable) {
        mutex.withLock { activeDownloads.remove(taskId) }
      }
      return
    }

    val now = Clock.System.now()
    updateTaskRecord(taskId) {
      it.copy(
        outputPath = outputPath,
        state = TaskState.DOWNLOADING,
        totalBytes = totalBytes,
        acceptRanges = resolvedUrl.supportsResume,
        etag = resolvedUrl.metadata[HttpDownloadSource.META_ETAG],
        lastModified = resolvedUrl.metadata[
          HttpDownloadSource.META_LAST_MODIFIED,
        ],
        sourceType = source.type,
        updatedAt = now,
      )
    }

    val fileAccessor = createFileAccessor(outputPath)

    val taskLimiter = mutex.withLock {
      activeDownloads[taskId]?.let {
        it.fileAccessor = fileAccessor
        it.totalBytes = totalBytes
        it.taskLimiter.delegate = createLimiter(request.speedLimit)
        it.taskLimiter
      } ?: throw KetchError.Unknown(
        IllegalStateException("ActiveDownload not found for $taskId"),
      )
    }

    var completed = false
    try {
      val context = buildContext(
        taskId, request.url, request, fileAccessor,
        segmentsFlow, stateFlow, taskLimiter, totalBytes,
        request.headers,
        preResolved = if (resolved != null) resolvedUrl else null,
      )
      mutex.withLock {
        activeDownloads[taskId]?.context = context
      }

      downloadWithRetry(taskId, context) {
        source.download(context)
      }

      try {
        fileAccessor.flush()
      } catch (e: Exception) {
        if (e is CancellationException) throw e
        if (e is KetchError) throw e
        throw KetchError.Disk(e)
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

      completed = true
      log.i { "Download completed successfully for taskId=$taskId" }
      stateFlow.value =
        DownloadState.Completed(outputPath)
    } finally {
      try {
        fileAccessor.close()
      } catch (e: Exception) {
        log.w(e) { "Failed to close file for taskId=$taskId" }
      }
      withContext(NonCancellable) {
        if (!completed && stateFlow.value !is DownloadState.Paused &&
          stateFlow.value !is DownloadState.Queued &&
          stateFlow.value !is DownloadState.Canceled
        ) {
          try {
            fileAccessor.delete()
            log.d { "Deleted partial file for failed taskId=$taskId" }
          } catch (e: Exception) {
            log.w(e) {
              "Failed to delete partial file for taskId=$taskId"
            }
          }
        }
        mutex.withLock {
          activeDownloads.remove(taskId)
        }
      }
    }
  }

  suspend fun pause(taskId: String) {
    mutex.withLock {
      val active = activeDownloads[taskId] ?: return
      log.i { "Pausing download for taskId=$taskId" }

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
        DownloadProgress(pausedDownloaded, active.totalBytes),
      )

      active.job.cancel()

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

      active.fileAccessor?.let { accessor ->
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
      val job = scope.launch {
        try {
          resumeDownload(
            taskId, taskRecord, segments, stateFlow, segmentsFlow,
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
            is KetchError -> e
            else -> KetchError.Unknown(e)
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
    log.i {
      "Resuming download for taskId=$taskId via " +
        "source '${source.type}'"
    }

    val outputPath = taskRecord.outputPath
      ?: throw KetchError.Unknown(
        IllegalStateException(
          "No outputPath for taskId=${taskRecord.taskId}",
        ),
      )
    val fileAccessor = createFileAccessor(outputPath)

    val taskLimiter = mutex.withLock {
      activeDownloads[taskId]?.let {
        it.segments = segments
        it.fileAccessor = fileAccessor
        it.totalBytes = taskRecord.totalBytes
        it.taskLimiter.delegate =
          createLimiter(taskRecord.request.speedLimit)
        it.taskLimiter
      } ?: throw KetchError.Unknown(
        IllegalStateException("ActiveDownload not found for $taskId"),
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
      taskRecord.totalBytes, taskRecord.request.headers,
    )
    mutex.withLock {
      activeDownloads[taskId]?.context = context
    }

    var completed = false
    try {
      downloadWithRetry(taskId, context) {
        source.resume(context, resumeState)
      }

      try {
        fileAccessor.flush()
      } catch (e: Exception) {
        if (e is CancellationException) throw e
        if (e is KetchError) throw e
        throw KetchError.Disk(e)
      }

      updateTaskRecord(taskId) {
        it.copy(
          state = TaskState.COMPLETED,
          downloadedBytes = taskRecord.totalBytes,
          segments = null,
          updatedAt = Clock.System.now(),
        )
      }

      completed = true
      log.i { "Resume completed successfully for taskId=$taskId" }
      stateFlow.value =
        DownloadState.Completed(outputPath)
    } finally {
      try {
        fileAccessor.close()
      } catch (e: Exception) {
        log.w(e) { "Failed to close file for taskId=$taskId" }
      }
      withContext(NonCancellable) {
        if (!completed && stateFlow.value !is DownloadState.Paused &&
          stateFlow.value !is DownloadState.Queued &&
          stateFlow.value !is DownloadState.Canceled
        ) {
          try {
            fileAccessor.delete()
            log.d { "Deleted partial file for failed taskId=$taskId" }
          } catch (e: Exception) {
            log.w(e) { "Failed to delete partial file for taskId=$taskId" }
          }
        }
        mutex.withLock {
          activeDownloads.remove(taskId)
        }
      }
    }
  }

  private suspend fun downloadWithRetry(
    taskId: String,
    context: DownloadContext? = null,
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
          is KetchError -> e
          else -> KetchError.Unknown(e)
        }

        if (!error.isRetryable || retryCount >= config.retryCount) {
          log.e(error) {
            "Download failed after $retryCount retries: " +
              "${error.message}"
          }
          throw error
        }

        retryCount++

        val delayMs: Long
        if (error is KetchError.Http && error.code == 429 &&
          context != null
        ) {
          reduceConnections(
            taskId, context, error.rateLimitRemaining,
          )
          delayMs = error.retryAfterSeconds?.let { it * 1000L }
            ?: (config.retryDelayMs * (1 shl (retryCount - 1)))
          log.w {
            "Rate limited (429). Retry attempt $retryCount " +
              "after ${delayMs}ms delay, connections=" +
              "${context.maxConnections.value}"
          }
        } else {
          delayMs = config.retryDelayMs * (1 shl (retryCount - 1))
          log.w {
            "Retry attempt $retryCount after ${delayMs}ms " +
              "delay: ${error.message}"
          }
        }
        delay(delayMs)
      }
    }
  }

  /**
   * Reduces the number of concurrent connections for a download task
   * that received HTTP 429 (Too Many Requests).
   *
   * When [rateLimitRemaining] is available, uses it directly as the
   * new connection count (minimum 1). Otherwise falls back to halving
   * the current count. Emits the new value to
   * [DownloadContext.maxConnections], triggering live resegmentation
   * in [HttpDownloadSource].
   */
  private fun reduceConnections(
    taskId: String,
    context: DownloadContext,
    rateLimitRemaining: Long? = null,
  ) {
    val current = when {
      context.maxConnections.value > 0 ->
        context.maxConnections.value

      context.request.connections > 0 -> context.request.connections
      else -> config.maxConnections
    }
    val reduced = if (rateLimitRemaining != null &&
      rateLimitRemaining < current
    ) {
      rateLimitRemaining.toInt().coerceAtLeast(1)
    } else {
      (current / 2).coerceAtLeast(1)
    }
    context.maxConnections.value = reduced
    log.w {
      "Reducing connections for taskId=$taskId: " +
        "$current -> $reduced" +
        (rateLimitRemaining?.let {
          " (RateLimit-Remaining=$it)"
        } ?: "")
    }
  }

  suspend fun cancel(taskId: String) {
    log.i { "Canceling download for taskId=$taskId" }
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
    log.d { "Cancel record updated for taskId=$taskId" }
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
  ) = recordMutex.withLock {
    val existing = taskStore.load(taskId) ?: run {
      log.w { "TaskRecord not found for taskId=$taskId" }
      return@withLock
    }
    taskStore.save(update(existing))
  }

  suspend fun setTaskConnections(taskId: String, connections: Int) {
    require(connections > 0) { "Connections must be greater than 0" }
    updateTaskRecord(taskId) {
      it.copy(
        request = it.request.copy(connections = connections),
        updatedAt = Clock.System.now(),
      )
    }
    // Live update — triggers resegmentation in HttpDownloadSource
    mutex.withLock {
      activeDownloads[taskId]?.context
        ?.maxConnections?.value = connections
    }
    log.i { "Task connections updated for taskId=$taskId: $connections" }
  }

  suspend fun setTaskSpeedLimit(taskId: String, limit: SpeedLimit) {
    updateTaskRecord(taskId) {
      it.copy(
        request = it.request.copy(speedLimit = limit),
        updatedAt = Clock.System.now(),
      )
    }
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
      log.i {
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
          DownloadProgress(downloaded, total, speed),
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
      maxConnections = MutableStateFlow(
        request.connections.takeIf { it > 0 } ?: 0,
      ),
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
        HttpDownloadSource.META_LAST_MODIFIED,
      ],
    )
  }

  private fun resolveDestPath(
    destination: Destination?,
    defaultDir: String,
    serverFileName: String?,
    deduplicate: Boolean,
  ): String {
    if (destination != null && destination.isFile()) {
      return destination.value
    }
    val directory = when {
      destination != null && destination.isDirectory() ->
        destination.value.trimEnd('/', '\\')

      else -> defaultDir
    }
    val fileName = when {
      destination != null && destination.isName() ->
        destination.value

      else -> serverFileName
    }
    if (fileName == null) return directory
    // resolveChildPath handles content URIs on Android
    // (DocumentsContract.createDocument auto-deduplicates)
    val outputPath = resolveChildPath(directory, fileName)
    return if (deduplicate && !directory.contains("://")) {
      deduplicatePath(Path(outputPath)).toString()
    } else {
      outputPath
    }
  }

  companion object {
    internal fun deduplicatePath(candidate: Path): Path {
      val fileName = candidate.name
      val directory = candidate.parent ?: return candidate
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
