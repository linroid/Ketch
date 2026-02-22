package com.linroid.ketch.core.engine

import com.linroid.ketch.api.DownloadConfig
import com.linroid.ketch.api.DownloadProgress
import com.linroid.ketch.api.DownloadRequest
import com.linroid.ketch.api.DownloadState
import com.linroid.ketch.api.KetchError
import com.linroid.ketch.api.ResolvedSource
import com.linroid.ketch.api.Segment
import com.linroid.ketch.api.SpeedLimit
import com.linroid.ketch.api.isDirectory
import com.linroid.ketch.api.isFile
import com.linroid.ketch.api.isName
import com.linroid.ketch.api.log.KetchLogger
import com.linroid.ketch.core.KetchDispatchers
import com.linroid.ketch.core.file.FileAccessor
import com.linroid.ketch.core.file.FileNameResolver
import com.linroid.ketch.core.file.createFileAccessor
import com.linroid.ketch.core.file.resolveChildPath
import com.linroid.ketch.core.task.TaskRecord
import com.linroid.ketch.core.task.TaskState
import com.linroid.ketch.core.task.TaskStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlin.time.Clock
import kotlin.time.TimeSource

/**
 * Encapsulates the execution logic for a single download task.
 *
 * Handles both fresh downloads and resume, including retry logic,
 * rate-limit connection reduction, context building, progress
 * reporting, and task record persistence.
 *
 * Created by [DownloadCoordinator] for each active download and
 * discarded after the download completes, fails, or is canceled.
 */
internal class DownloadExecution(
  val taskId: String,
  val request: DownloadRequest,
  val stateFlow: MutableStateFlow<DownloadState>,
  val segmentsFlow: MutableStateFlow<List<Segment>>,
  private val sourceResolver: SourceResolver,
  private val fileNameResolver: FileNameResolver,
  private val config: DownloadConfig,
  private val globalLimiter: SpeedLimiter,
  private val taskStore: TaskStore,
  private val recordMutex: Mutex,
  private val dispatchers: KetchDispatchers,
) {
  private val log = KetchLogger("Execution")

  val taskLimiter = DelegatingSpeedLimiter()
  var context: DownloadContext? = null
  var fileAccessor: FileAccessor? = null
  var totalBytes: Long = 0

  /**
   * Executes a download — either fresh or resumed.
   *
   * When [resumeState] is non-null, loads the task record and
   * resumes from the saved segments. Otherwise starts a fresh
   * download from the request URL.
   */
  suspend fun execute(resumeState: ResumeInfo? = null) {
    if (resumeState != null) {
      executeResume(resumeState)
    } else {
      executeFresh()
    }
  }

  suspend fun setSpeedLimit(limit: SpeedLimit) {
    updateTaskRecord(taskId) {
      it.copy(
        request = it.request.copy(speedLimit = limit),
        updatedAt = Clock.System.now(),
      )
    }
    val current = taskLimiter.delegate
    if (limit.isUnlimited) {
      taskLimiter.delegate = SpeedLimiter.Unlimited
    } else if (current is TokenBucket) {
      current.updateRate(limit.bytesPerSecond)
    } else {
      taskLimiter.delegate = TokenBucket(limit.bytesPerSecond)
    }
    log.i {
      "Task speed limit updated for taskId=$taskId: " +
        "${limit.bytesPerSecond} bytes/sec"
    }
  }

  suspend fun setConnections(connections: Int) {
    require(connections > 0) { "Connections must be greater than 0" }
    updateTaskRecord(taskId) {
      it.copy(
        request = it.request.copy(connections = connections),
        updatedAt = Clock.System.now(),
      )
    }
    context?.maxConnections?.value = connections
    log.i { "Task connections updated for taskId=$taskId: $connections" }
  }

  private suspend fun executeFresh() {
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

    val total = resolvedUrl.totalBytes
    if (total < 0) throw KetchError.Unsupported
    totalBytes = total

    val fileName = resolvedUrl.suggestedFileName
      ?: fileNameResolver.resolve(
        request, toServerInfo(resolvedUrl),
      )
    val outputPath = resolveDestPath(
      destination = request.destination,
      defaultDir = config.defaultDirectory ?: "downloads",
      serverFileName = fileName,
      deduplicate = true,
    )
    log.d { "Resolved outputPath=$outputPath" }

    if (total == 0L) {
      log.i { "Zero-byte file for taskId=$taskId, completing" }
      val fa = createFileAccessor(outputPath, dispatchers.io)
      try {
        fa.flush()
      } catch (e: Exception) {
        if (e is CancellationException) throw e
        throw KetchError.Disk(e)
      } finally {
        try {
          fa.close()
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
      return
    }

    val now = Clock.System.now()
    updateTaskRecord(taskId) {
      it.copy(
        outputPath = outputPath,
        state = TaskState.DOWNLOADING,
        totalBytes = total,
        acceptRanges = resolvedUrl.supportsResume,
        etag = resolvedUrl.metadata[HttpDownloadSource.META_ETAG],
        lastModified = resolvedUrl.metadata[
          HttpDownloadSource.META_LAST_MODIFIED,
        ],
        sourceType = source.type,
        updatedAt = now,
      )
    }

    val fa = createFileAccessor(outputPath, dispatchers.io)
    fileAccessor = fa
    taskLimiter.delegate = createLimiter(request.speedLimit)

    var completed = false
    try {
      val ctx = buildContext(
        taskId, request.url, request, fa,
        segmentsFlow, stateFlow, taskLimiter, total,
        request.headers,
        preResolved = if (resolved != null) resolvedUrl else null,
      )
      context = ctx

      downloadWithRetry(taskId, ctx) {
        source.download(ctx)
      }

      try {
        fa.flush()
      } catch (e: Exception) {
        if (e is CancellationException) throw e
        if (e is KetchError) throw e
        throw KetchError.Disk(e)
      }

      val resumeState = buildHttpResumeState(taskId)
      updateTaskRecord(taskId) {
        it.copy(
          state = TaskState.COMPLETED,
          downloadedBytes = total,
          segments = null,
          sourceResumeState = resumeState,
          updatedAt = Clock.System.now(),
        )
      }

      completed = true
      log.i { "Download completed successfully for taskId=$taskId" }
      stateFlow.value = DownloadState.Completed(outputPath)
    } finally {
      cleanupAfterExecution(fa, completed)
    }
  }

  private suspend fun executeResume(info: ResumeInfo) {
    val taskRecord = info.record
    val segments = info.segments

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
    val fa = createFileAccessor(outputPath, dispatchers.io)
    fileAccessor = fa
    totalBytes = taskRecord.totalBytes
    taskLimiter.delegate = createLimiter(taskRecord.request.speedLimit)

    val resumeState = taskRecord.sourceResumeState
      ?: HttpDownloadSource.buildResumeState(
        etag = taskRecord.etag,
        lastModified = taskRecord.lastModified,
        totalBytes = taskRecord.totalBytes,
      )

    val ctx = buildContext(
      taskId, taskRecord.request.url, taskRecord.request,
      fa, segmentsFlow, stateFlow, taskLimiter,
      taskRecord.totalBytes, taskRecord.request.headers,
    )
    context = ctx

    var completed = false
    try {
      downloadWithRetry(taskId, ctx) {
        source.resume(ctx, resumeState)
      }

      try {
        fa.flush()
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
      stateFlow.value = DownloadState.Completed(outputPath)
    } finally {
      cleanupAfterExecution(fa, completed)
    }
  }

  private suspend fun cleanupAfterExecution(
    fa: FileAccessor,
    completed: Boolean,
  ) {
    try {
      fa.close()
    } catch (e: Exception) {
      log.w(e) { "Failed to close file for taskId=$taskId" }
    }
    withContext(NonCancellable) {
      if (!completed && stateFlow.value !is DownloadState.Paused &&
        stateFlow.value !is DownloadState.Queued &&
        stateFlow.value !is DownloadState.Canceled
      ) {
        try {
          fa.delete()
          log.d { "Deleted partial file for failed taskId=$taskId" }
        } catch (e: Exception) {
          log.w(e) {
            "Failed to delete partial file for taskId=$taskId"
          }
        }
      }
    }
  }

  private suspend fun downloadWithRetry(
    taskId: String,
    ctx: DownloadContext? = null,
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
          ctx != null
        ) {
          reduceConnections(ctx, error.rateLimitRemaining)
          delayMs = error.retryAfterSeconds?.let { it * 1000L }
            ?: (config.retryDelayMs * (1 shl (retryCount - 1)))
          log.w {
            "Rate limited (429). Retry attempt $retryCount " +
              "after ${delayMs}ms delay, connections=" +
              "${ctx.maxConnections.value}"
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

  private fun reduceConnections(
    ctx: DownloadContext,
    rateLimitRemaining: Long? = null,
  ) {
    val current = when {
      ctx.maxConnections.value > 0 -> ctx.maxConnections.value
      ctx.request.connections > 0 -> ctx.request.connections
      else -> config.maxConnectionsPerDownload
    }
    val reduced = if (rateLimitRemaining != null &&
      rateLimitRemaining < current
    ) {
      rateLimitRemaining.toInt().coerceAtLeast(1)
    } else {
      (current / 2).coerceAtLeast(1)
    }
    ctx.maxConnections.value = reduced
    log.w {
      "Reducing connections for taskId=$taskId: " +
        "$current -> $reduced" +
        (rateLimitRemaining?.let {
          " (RateLimit-Remaining=$it)"
        } ?: "")
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

  private fun createLimiter(speedLimit: SpeedLimit): SpeedLimiter {
    return if (speedLimit.isUnlimited) {
      SpeedLimiter.Unlimited
    } else {
      TokenBucket(speedLimit.bytesPerSecond)
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
    destination: com.linroid.ketch.api.Destination?,
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
    val outputPath = resolveChildPath(directory, fileName)
    return if (deduplicate && !directory.contains("://")) {
      deduplicatePath(Path(outputPath)).toString()
    } else {
      outputPath
    }
  }

  /**
   * Info needed to resume a previously interrupted download.
   */
  internal class ResumeInfo(
    val record: TaskRecord,
    val segments: List<Segment>,
  )

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
