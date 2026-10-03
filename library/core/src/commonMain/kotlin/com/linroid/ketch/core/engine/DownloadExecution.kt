package com.linroid.ketch.core.engine

import com.linroid.ketch.api.DownloadConfig
import com.linroid.ketch.api.DownloadProgress
import com.linroid.ketch.api.DownloadState
import com.linroid.ketch.api.FileSelectionMode
import com.linroid.ketch.api.KetchError
import com.linroid.ketch.api.ResolvedSource
import com.linroid.ketch.api.Segment
import com.linroid.ketch.api.SpeedLimit
import com.linroid.ketch.api.isDirectory
import com.linroid.ketch.api.isFile
import com.linroid.ketch.api.isName
import com.linroid.ketch.api.log.KetchLogger
import com.linroid.ketch.api.log.describeCauses
import com.linroid.ketch.api.log.redactUrl
import com.linroid.ketch.core.KetchDispatchers
import com.linroid.ketch.core.defaultDownloadDirectory
import com.linroid.ketch.core.file.FileAccessor
import com.linroid.ketch.core.file.FileNameResolver
import com.linroid.ketch.core.file.NoOpFileAccessor
import com.linroid.ketch.core.file.createFileAccessor
import com.linroid.ketch.core.file.platformFileSystem
import com.linroid.ketch.core.file.resolveChildPath
import com.linroid.ketch.core.task.TaskHandle
import com.linroid.ketch.core.task.TaskRecord
import com.linroid.ketch.core.task.TaskState
import kotlin.concurrent.Volatile
import kotlin.time.Clock
import kotlin.time.Instant
import kotlin.time.TimeSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okio.Path
import okio.Path.Companion.toPath

/**
 * Encapsulates the execution logic for a single download task.
 *
 * Handles both fresh downloads and resume, including retry logic,
 * rate-limit connection reduction, context building, progress
 * reporting, and task record persistence.
 *
 * Created by [DownloadCoordinator] for each active download and
 * discarded after the download completes, fails, or is canceled.
 *
 * @param config snapshot of the global configuration taken when this
 *   execution was created; later [com.linroid.ketch.api.KetchApi.updateConfig]
 *   calls apply to the next start or resume
 * @param timeSource measures download speed and the time added to [TaskRecord.downloadTime]
 * @param clock stamps [TaskRecord.completedAt] when the download completes
 * @param openFile opens the output file of a source that does not write its own files
 */
internal class DownloadExecution(
  private val handle: TaskHandle,
  private val sourceResolver: SourceResolver,
  private val fileNameResolver: FileNameResolver,
  private val config: DownloadConfig,
  private val globalLimiter: SpeedLimiter,
  private val dispatchers: KetchDispatchers,
  private val timeSource: TimeSource.WithComparableMarks = TimeSource.Monotonic,
  private val clock: Clock = Clock.System,
  private val openFile: (path: String, ioDispatcher: CoroutineDispatcher) -> FileAccessor =
    ::createFileAccessor,
) {
  private val log = KetchLogger("Execution")

  private val taskId get() = handle.taskId
  private val request get() = handle.request

  private val reportsProgress = MutableStateFlow(true)

  /** Stop callbacks before a pause/cancel state is published while a source is still joining. */
  fun stopReportingProgress() { reportsProgress.value = false }

  @Volatile
  private var discardsPartialFile = false

  /**
   * Deletes the partial file once this execution stops. Set before an explicit cancel or
   * `remove(deleteFiles = true)` cancels it; every other stop (pause, failure, `Ketch.close()`,
   * `remove(deleteFiles = false)`) keeps the file that the saved segments describe.
   */
  fun discardPartialFile() { discardsPartialFile = true }

  /**
   * Per-task limiter, created from the persisted request so a limit set
   * while the task was not running applies from the first byte.
   */
  val taskLimiter = DelegatingSpeedLimiter(createLimiter(handle.request.speedLimit))
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

  /** Applies [limit] to the running transfer. The caller persists it in the task record. */
  suspend fun setSpeedLimit(limit: SpeedLimit) {
    val current = taskLimiter.delegate
    if (limit.isUnlimited) {
      (current as? TokenBucket)?.updateRate(0)
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

  /**
   * Applies [connections] to the running transfer. The caller persists it
   * in the task record; a context built later reads the persisted value.
   */
  fun setConnections(connections: Int) {
    require(connections >= 0) { "Connections must not be negative" }
    context?.maxConnections?.value = connections
    val label = if (connections == 0) "auto" else connections.toString()
    log.i { "Task connections updated for taskId=$taskId: $label" }
  }

  private suspend fun executeFresh() {
    val resolved = request.resolvedSource
    val source: DownloadSource
    val resolvedUrl: ResolvedSource

    if (resolved != null) {
      log.d {
        "Using pre-resolved info for taskId=$taskId: url=${redactUrl(request.url)}, " +
          "source=${resolved.sourceType}"
      }
      source = sourceResolver.resolveByType(resolved.sourceType)
      resolvedUrl = resolved
    } else {
      source = sourceResolver.resolve(request.url)
      log.d {
        "Resolved source '${source.type}' for taskId=$taskId: url=${redactUrl(request.url)}"
      }
      resolvedUrl = downloadWithRetry { source.resolve(request.url, request.headers, config) }
    }

    val total = if (resolvedUrl.selectionMode == FileSelectionMode.MULTIPLE &&
      request.selectedFileIds.isNotEmpty()) {
      require(request.selectedFileIds.all { id -> resolvedUrl.files.any { it.id == id } }) {
        "Unknown selected file"
      }
      resolvedUrl.files.filter { it.id in request.selectedFileIds }.fold(0L) { sum, file ->
        require(file.size >= 0 && sum <= Long.MAX_VALUE - file.size)
        sum + file.size
      }
    } else resolvedUrl.totalBytes
    // A source streams content of unknown size (-1) or fails, and the engine then measures the
    // file it wrote. A source that writes its own files leaves the engine nothing to measure.
    if (total < 0 && source.managesOwnFileIo) {
      log.e { "Unknown file size for taskId=$taskId: url=${redactUrl(request.url)}" }
      throw KetchError.SourceError(
        sourceType = source.type,
        cause = Exception("Unknown file size for ${redactUrl(request.url)}"),
      )
    }
    totalBytes = total

    val fileName = resolvedUrl.suggestedFileName
      ?: fileNameResolver.resolve(request, resolvedUrl)
    val outputPath = resolveDestPath(
      destination = request.destination,
      // Resolved only when needed: the platform default may need an Android context. Sources
      // that write their own files need a filesystem folder, so a default folder given as a URI,
      // such as an Android content:// tree, leaves them the platform default.
      defaultDir = {
        config.defaultDirectory?.takeUnless { source.managesOwnFileIo && it.contains("://") }
          ?: defaultDownloadDirectory()
      },
      serverFileName = fileName,
      deduplicate = true,
    )
    log.i {
      "Resolved taskId=$taskId: source=${source.type}, totalBytes=$total, " +
        "outputPath=$outputPath"
    }

    if (total == 0L && !source.managesOwnFileIo) {
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

    val preResolved = resolvedUrl
    runDownload(outputPath, total, source, preResolved) { ctx ->
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

    val resumeState = taskRecord.sourceResumeState
      ?: throw KetchError.CorruptResumeState(
        "No resume state for taskId=${taskRecord.taskId}",
      )

    runDownload(outputPath, taskRecord.totalBytes, source) { ctx ->
      source.resume(ctx, resumeState)
    }
  }

  /**
   * Common download-to-completion sequence: creates a [FileAccessor],
   * builds the [DownloadContext], runs [downloadBlock] with retry,
   * flushes, persists completion, and cleans up.
   *
   * When [DownloadSource.managesOwnFileIo] is `true`, the source
   * handles its own file I/O so we use [NoOpFileAccessor] and skip
   * flush/cleanup.
   */
  private suspend fun runDownload(
    outputPath: String,
    total: Long,
    source: DownloadSource,
    preResolved: ResolvedSource? = null,
    downloadBlock: suspend (DownloadContext) -> Unit,
  ) {
    val selfManagedIo = source.managesOwnFileIo
    val fa = if (selfManagedIo) {
      NoOpFileAccessor
    } else {
      openFile(outputPath, dispatchers.io)
    }
    fileAccessor = fa

    // Added to the time saved by earlier runs; a record that never tracked it stays unknown.
    val previousTime = handle.record.value.downloadTime
    val runMark = timeSource.markNow()
    fun downloadTime() = previousTime?.plus(runMark.elapsedNow())

    // Segments are the only progress of a file of known size Ketch writes, so until the source
    // publishes them the record keeps none: a saved [] would resume as a finished transfer of a
    // zero-filled file. Content of unknown size keeps [], and resuming it streams it again.
    fun savedSegments(snapshot: List<Segment>) =
      snapshot.takeUnless { it.isEmpty() && total > 0 && !selfManagedIo }

    var completed = false
    try {
      val ctx = buildContext(fa, total, preResolved, outputPath)
      context = ctx

      coroutineScope {
        val saveJob = launch {
          while (true) {
            delay(config.saveIntervalMs)
            val snapshot = handle.mutableSegments.value
            val updatedResume = source.updateResumeState(ctx)
            handle.record.update {
              it.copy(
                segments = savedSegments(snapshot),
                sourceResumeState = updatedResume
                  ?: it.sourceResumeState,
                downloadTime = downloadTime(),
                updatedAt = Clock.System.now(),
              )
            }
          }
        }
        try {
          downloadWithRetry(ctx) { downloadBlock(ctx) }
        } finally {
          withContext(NonCancellable) {
            saveJob.cancelAndJoin()
            val updatedResume = source.updateResumeState(ctx)
            val snapshot = handle.mutableSegments.value
            handle.record.update {
              it.copy(segments = savedSegments(snapshot),
                sourceResumeState = updatedResume ?: it.sourceResumeState,
                downloadTime = downloadTime(),
                updatedAt = Clock.System.now())
            }
            if (snapshot.isNotEmpty()) {
              handle.mutableState.update { state ->
                if (state is DownloadState.Paused) {
                  state.copy(progress = state.progress.copy(
                    downloadedBytes = snapshot.sumOf { it.downloadedBytes },
                  ))
                } else state
              }
            }
          }
        }
      }

      var finalTotal = total
      if (!selfManagedIo) {
        try {
          fa.flush()
          // Content of unknown size was streamed to its end, so the file holds all of it.
          if (total < 0) finalTotal = fa.size()
        } catch (e: Exception) {
          if (e is CancellationException) throw e
          // The written bytes may not have reached the disk, so none of them count as progress.
          withContext(NonCancellable) { discardProgress() }
          if (e is KetchError) throw e
          throw KetchError.Disk(e)
        }
      }
      totalBytes = finalTotal

      val finalTime = downloadTime()
      val finishedAt = finishTime()
      handle.record.update {
        it.copy(
          state = TaskState.COMPLETED,
          totalBytes = finalTotal,
          segments = null,
          downloadTime = finalTime,
          completedAt = finishedAt,
          updatedAt = finishedAt,
        )
      }

      completed = true
      log.i { "Download completed for taskId=$taskId" }
      handle.mutableState.value = DownloadState.Completed(
        outputPath = outputPath,
        totalBytes = finalTotal.takeIf { it >= 0 },
        downloadTime = finalTime,
        completedAt = finishedAt,
      )
    } finally {
      if (!selfManagedIo) {
        cleanupAfterExecution(fa, completed)
      }
    }
  }

  /** Keeps the segment layout but drops its progress, so a resume downloads every byte again. */
  private suspend fun discardProgress() {
    val reset = handle.mutableSegments.value.map { it.copy(downloadedBytes = 0) }
    handle.mutableSegments.value = reset
    handle.record.update { it.copy(segments = reset, updatedAt = Clock.System.now()) }
  }

  private suspend fun completeZeroByteFile(
    outputPath: String,
    sourceType: String,
  ) {
    log.i { "Zero-byte file for taskId=$taskId, completing" }
    val fa = openFile(outputPath, dispatchers.io)
    try {
      // Create or truncate the destination; flushing a lazy accessor alone is insufficient.
      fa.preallocate(0)
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
    val finishedAt = finishTime()
    handle.record.update {
      it.copy(
        outputPath = outputPath,
        state = TaskState.COMPLETED,
        totalBytes = 0,
        segments = null,
        sourceType = sourceType,
        completedAt = finishedAt,
        updatedAt = finishedAt,
      )
    }
    handle.mutableState.value = DownloadState.Completed(
      outputPath = outputPath,
      totalBytes = 0,
      downloadTime = handle.record.value.downloadTime,
      completedAt = finishedAt,
    )
  }

  /**
   * Now, in whole milliseconds: the precision task stores keep, so the finish time a task
   * reports does not change when it is saved and loaded again.
   */
  private fun finishTime(): Instant =
    Instant.fromEpochMilliseconds(clock.now().toEpochMilliseconds())

  private suspend fun cleanupAfterExecution(
    fa: FileAccessor,
    completed: Boolean,
  ) {
    try {
      fa.close()
    } catch (e: Exception) {
      log.w(e) { "Failed to close file for taskId=$taskId" }
    }
    // Decided by the caller, not the state: a failure, Ketch.close() or remove(false) stops
    // the execution while it still reports Downloading, and the record keeps its segments.
    if (completed || !discardsPartialFile) return
    withContext(NonCancellable) {
      try {
        fa.delete()
        log.d { "Deleted partial file for discarded taskId=$taskId" }
      } catch (e: Exception) {
        log.w(e) { "Failed to delete partial file for taskId=$taskId" }
      }
    }
  }

  private suspend fun <T> downloadWithRetry(
    ctx: DownloadContext? = null,
    block: suspend () -> T,
  ): T {
    var retryCount = 0
    while (true) {
      try {
        return block()
      } catch (e: CancellationException) {
        throw e
      } catch (e: Exception) {
        val error = when (e) {
          is KetchError -> e
          else -> KetchError.Unknown(e)
        }

        if (!error.isRetryable || retryCount >= config.retryCount) {
          // DownloadCoordinator logs the failure itself, with its stack trace.
          log.i {
            if (error.isRetryable) "Giving up on taskId=$taskId after $retryCount retries"
            else "Not retrying taskId=$taskId: ${error::class.simpleName} is not retryable"
          }
          throw error
        }

        retryCount++

        val delayMs: Long
        if (error is KetchError.Http && error.code == 429) {
          if (ctx != null) reduceConnections(ctx, error.rateLimitRemaining)
          delayMs = error.retryAfterSeconds?.let { it * 1000L }
            ?: (config.retryDelayMs * (1 shl (retryCount - 1)))
          log.w {
            "Rate limited (429) for taskId=$taskId. Retry $retryCount/${config.retryCount} " +
              "in ${delayMs}ms, connections=" +
              "${ctx?.maxConnections?.value ?: request.connections}"
          }
        } else {
          delayMs = config.retryDelayMs * (1 shl (retryCount - 1))
          log.w {
            "Retry $retryCount/${config.retryCount} for taskId=$taskId in ${delayMs}ms: " +
              error.describeCauses()
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
    val current = ctx.effectiveConnections()
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
    outputPath: String,
  ): DownloadContext {
    var lastBytes = 0L
    var lastMark = timeSource.markNow()
    var speed = 0L
    val reportedSpeed = MutableStateFlow<Long?>(null)
    return DownloadContext(
      taskId = taskId,
      url = request.url,
      request = request,
      fileAccessor = fileAccessor,
      segments = handle.mutableSegments,
      onProgress = { downloaded, total ->
        val now = timeSource.markNow()
        val elapsed = (now - lastMark).inWholeMilliseconds
        if (elapsed >= 500) {
          val delta = downloaded - lastBytes
          speed = if (elapsed > 0) delta * 1000 / elapsed else 0L
          lastBytes = downloaded
          lastMark = now
        }
        handle.mutableState.update { current ->
          if (reportsProgress.value) DownloadState.Downloading(
            DownloadProgress(downloaded, total, reportedSpeed.value ?: speed),
          ) else current
        }
      },
      throttle = { bytes ->
        taskLimiter.acquire(bytes)
        globalLimiter.acquire(bytes)
      },
      headers = request.headers,
      preResolved = preResolved,
      outputPath = outputPath,
      reportedSpeed = reportedSpeed,
      maxConnections = MutableStateFlow(request.connections),
      config = config,
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
    defaultDir: () -> String,
    serverFileName: String?,
    deduplicate: Boolean,
  ): String {
    if (destination != null && destination.isFile()) {
      return destination.value
    }
    val directory = when {
      destination != null && destination.isDirectory() ->
        destination.value.trimEnd('/', '\\')
      else -> defaultDir()
    }
    val fileName = when {
      destination != null && destination.isName() ->
        destination.value
      else -> serverFileName
    }
    if (fileName == null) return directory
    val outputPath = resolveChildPath(directory, fileName)
    return if (deduplicate && !directory.contains("://")) {
      deduplicatePath(outputPath.toPath()).toString()
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
      if (!platformFileSystem.exists(candidate)) return candidate

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
        val path = directory / "$baseName ($seq)$extension"
        if (!platformFileSystem.exists(path)) return path
        seq++
      }
    }
  }
}
