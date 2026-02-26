package com.linroid.ketch.core.engine

import com.linroid.ketch.api.DownloadConfig
import com.linroid.ketch.api.DownloadProgress
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
import com.linroid.ketch.core.file.NoOpFileAccessor
import com.linroid.ketch.core.file.createFileAccessor
import com.linroid.ketch.core.file.resolveChildPath
import com.linroid.ketch.core.task.TaskHandle
import com.linroid.ketch.core.task.TaskRecord
import com.linroid.ketch.core.task.TaskState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
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
  private val handle: TaskHandle,
  private val sourceResolver: SourceResolver,
  private val fileNameResolver: FileNameResolver,
  private val config: DownloadConfig,
  private val globalLimiter: SpeedLimiter,
  private val dispatchers: KetchDispatchers,
) {
  private val log = KetchLogger("Execution")

  private val taskId get() = handle.taskId
  private val request get() = handle.request

  val taskLimiter = DelegatingSpeedLimiter()
  var context: DownloadContext? = null
  var fileAccessor: FileAccessor? = null
  var totalBytes: Long = 0

  /**
   * Executes a download — either fresh or resumed.
   *
   * When [resumeInfo] is non-null, loads the task record and
   * resumes from the saved segments. Otherwise starts a fresh
   * download from the request URL.
   */
  suspend fun execute(resumeInfo: ResumeInfo? = null) {
    if (resumeInfo != null) {
      executeResume(resumeInfo)
    } else {
      executeFresh()
    }
  }

  suspend fun setSpeedLimit(limit: SpeedLimit) {
    handle.record.update {
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
    handle.record.update {
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
    if (total < 0) {
      log.e { "Unknown file size for ${request.url}" }
      throw KetchError.SourceError(
        sourceType = source.type,
        cause = Exception(
          "Unknown file size for ${request.url}"
        ),
      )
    }
    totalBytes = total

    val fileName = resolvedUrl.suggestedFileName
      ?: fileNameResolver.resolve(request, resolvedUrl)
    val outputPath = resolveDestPath(
      destination = request.destination,
      defaultDir = config.defaultDirectory ?: "downloads",
      serverFileName = fileName,
      deduplicate = true,
    )
    log.d { "Resolved outputPath=$outputPath" }

    if (total == 0L) {
      completeZeroByteFile(outputPath, source.type)
      return
    }

    val now = Clock.System.now()
    handle.record.update {
      it.copy(
        outputPath = outputPath,
        state = TaskState.DOWNLOADING,
        totalBytes = total,
        sourceType = source.type,
        sourceResumeState = source.buildResumeState(
          resolvedUrl, total,
        ),
        updatedAt = now,
      )
    }

    taskLimiter.delegate = createLimiter(request.speedLimit)

    val preResolved = if (resolved != null) resolvedUrl else null
    runDownload(
      outputPath, total, source.managesOwnFileIo, preResolved,
    ) { ctx ->
      source.download(ctx)
    }
  }

  private suspend fun executeResume(info: ResumeInfo) {
    val taskRecord = info.record

    val sourceType = taskRecord.sourceType
      ?: throw KetchError.Unknown(
        IllegalStateException(
          "No sourceType for taskId=${taskRecord.taskId}",
        ),
      )
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
    totalBytes = taskRecord.totalBytes
    taskLimiter.delegate = createLimiter(taskRecord.request.speedLimit)

    val resumeState = taskRecord.sourceResumeState
      ?: throw KetchError.CorruptResumeState(
        "No resume state for taskId=${taskRecord.taskId}",
      )

    runDownload(
      outputPath, taskRecord.totalBytes, source.managesOwnFileIo,
    ) { ctx ->
      context = ctx
      source.resume(ctx, resumeState)
    }
  }

  /**
   * Common download-to-completion sequence: creates a [FileAccessor],
   * builds the [DownloadContext], runs [downloadBlock] with retry,
   * flushes, persists completion, and cleans up.
   *
   * When [selfManagedIo] is `true`, the source handles its own file
   * I/O so we use [NoOpFileAccessor] and skip flush/cleanup.
   */
  private suspend fun runDownload(
    outputPath: String,
    total: Long,
    selfManagedIo: Boolean = false,
    preResolved: ResolvedSource? = null,
    downloadBlock: suspend (DownloadContext) -> Unit,
  ) {
    val fa = if (selfManagedIo) {
      NoOpFileAccessor
    } else {
      createFileAccessor(outputPath, dispatchers.io)
    }
    fileAccessor = fa

    var completed = false
    try {
      val ctx = buildContext(fa, total, preResolved)
      context = ctx

      coroutineScope {
        val saveJob = launch {
          while (true) {
            delay(config.saveIntervalMs)
            val snapshot = handle.mutableSegments.value
            val downloaded = snapshot.sumOf { it.downloadedBytes }
            handle.record.update {
              it.copy(
                segments = snapshot,
                updatedAt = Clock.System.now(),
              )
            }
          }
        }
        try {
          downloadWithRetry(ctx) { downloadBlock(ctx) }
        } finally {
          saveJob.cancel()
        }
      }

      if (!selfManagedIo) {
        try {
          fa.flush()
        } catch (e: Exception) {
          if (e is CancellationException) throw e
          if (e is KetchError) throw e
          throw KetchError.Disk(e)
        }
      }

      handle.record.update {
        it.copy(
          state = TaskState.COMPLETED,
          segments = null,
          updatedAt = Clock.System.now(),
        )
      }

      completed = true
      log.i { "Download completed for taskId=$taskId" }
      handle.mutableState.value = DownloadState.Completed(outputPath)
    } finally {
      if (!selfManagedIo) {
        cleanupAfterExecution(fa, completed)
      }
    }
  }

  private suspend fun completeZeroByteFile(
    outputPath: String,
    sourceType: String,
  ) {
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
    handle.record.update {
      it.copy(
        outputPath = outputPath,
        state = TaskState.COMPLETED,
        totalBytes = 0,
        segments = null,
        sourceType = sourceType,
        updatedAt = Clock.System.now(),
      )
    }
    handle.mutableState.value = DownloadState.Completed(outputPath)
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
      val state = handle.mutableState.value
      if (!completed && state !is DownloadState.Paused &&
        state !is DownloadState.Queued &&
        state !is DownloadState.Canceled
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
    fileAccessor: FileAccessor,
    totalBytes: Long,
    preResolved: ResolvedSource? = null,
  ): DownloadContext {
    var lastBytes = 0L
    var lastMark = TimeSource.Monotonic.markNow()
    var speed = 0L
    return DownloadContext(
      taskId = taskId,
      url = request.url,
      request = request,
      fileAccessor = fileAccessor,
      segments = handle.mutableSegments,
      onProgress = { downloaded, total ->
        val now = TimeSource.Monotonic.markNow()
        val elapsed = (now - lastMark).inWholeMilliseconds
        if (elapsed >= 500) {
          val delta = downloaded - lastBytes
          speed = if (elapsed > 0) delta * 1000 / elapsed else 0L
          lastBytes = downloaded
          lastMark = now
        }
        handle.mutableState.value = DownloadState.Downloading(
          DownloadProgress(downloaded, total, speed),
        )
      },
      throttle = { bytes ->
        taskLimiter.acquire(bytes)
        globalLimiter.acquire(bytes)
      },
      headers = request.headers,
      preResolved = preResolved,
      maxConnections = MutableStateFlow(
        request.connections.takeIf { it > 0 } ?: 0,
      ),
    )
  }

  private fun createLimiter(speedLimit: SpeedLimit): SpeedLimiter {
    return if (speedLimit.isUnlimited) {
      SpeedLimiter.Unlimited
    } else {
      TokenBucket(speedLimit.bytesPerSecond)
    }
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
