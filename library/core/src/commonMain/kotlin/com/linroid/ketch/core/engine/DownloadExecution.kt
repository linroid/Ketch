package com.linroid.ketch.core.engine

import com.linroid.ketch.api.Checksum
import com.linroid.ketch.api.Destination
import com.linroid.ketch.api.DownloadConfig
import com.linroid.ketch.api.DownloadProgress
import com.linroid.ketch.api.DownloadState
import com.linroid.ketch.api.FileSelectionMode
import com.linroid.ketch.api.KetchError
import com.linroid.ketch.api.PauseReason
import com.linroid.ketch.api.ResolveInput
import com.linroid.ketch.api.ResolvedSource
import com.linroid.ketch.api.Segment
import com.linroid.ketch.api.SpeedLimit
import com.linroid.ketch.api.categoryFor
import com.linroid.ketch.api.isDirectory
import com.linroid.ketch.api.isFile
import com.linroid.ketch.api.isName
import com.linroid.ketch.api.log.KetchLogger
import com.linroid.ketch.api.log.describeCauses
import com.linroid.ketch.api.log.redactUrl
import com.linroid.ketch.api.withoutBulkMetadata
import com.linroid.ketch.core.KetchDispatchers
import com.linroid.ketch.core.defaultDownloadDirectory
import com.linroid.ketch.core.digest.newDigest
import com.linroid.ketch.core.file.DefaultFileNameResolver
import com.linroid.ketch.core.file.FileAccessor
import com.linroid.ketch.core.file.FileNameResolver
import com.linroid.ketch.core.file.NoOpFileAccessor
import com.linroid.ketch.core.file.OutputPathReservations
import com.linroid.ketch.core.file.createFileAccessor
import com.linroid.ketch.core.file.createPartialDocument
import com.linroid.ketch.core.file.finishPartialDocument
import com.linroid.ketch.core.file.isInsideDirectory
import com.linroid.ketch.core.file.movePartialToFinal
import com.linroid.ketch.core.file.partialPathFor
import com.linroid.ketch.core.file.platformFileSystem
import com.linroid.ketch.core.file.resolveChildFolder
import com.linroid.ketch.core.file.resolveChildPath
import com.linroid.ketch.core.file.sanitizeFileName
import com.linroid.ketch.core.task.TaskHandle
import com.linroid.ketch.core.task.TaskRecord
import com.linroid.ketch.core.task.TaskState
import com.linroid.ketch.core.task.isKeptWithTask
import kotlin.concurrent.Volatile
import kotlin.time.Clock
import kotlin.time.Instant
import kotlin.time.TimeSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
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
  private val connections: ConnectionReporter = ConnectionReporter.None,
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

  /** The size of the download; a selection delivered while it runs changes it. */
  @Volatile
  var totalBytes: Long = 0

  private val selection = MutableStateFlow(SelectionUpdate(handle.request.selectedFileIds, 0))

  // Written under the task's control lock: once set, the execution takes no more selections.
  @Volatile
  private var finishing = false

  /** Whether the source returned and this execution is completing; it takes no selections. */
  val isFinishing: Boolean get() = finishing

  /**
   * Hands a saved selection of [fileIds], [total] bytes in size, to the running download, which
   * sees it as a new [DownloadContext.selection] revision. The caller holds the task's
   * [TaskHandle.controlLock]. Returns `false` once the execution is finishing.
   */
  fun deliverSelection(fileIds: Set<String>, total: Long): Boolean {
    if (finishing) return false
    totalBytes = total
    val next = SelectionUpdate(fileIds, selection.value.revision + 1)
    selection.value = next
    log.d { "Selection revision ${next.revision} for taskId=$taskId: files=${fileIds.size}" }
    return true
  }

  /** The output paths held in [OutputPathReservations] until this execution stops. */
  private val reservedPaths = mutableListOf<String>()

  /**
   * Executes a download — either fresh or resumed.
   *
   * When [resumeInfo] is non-null, loads the task record and
   * resumes from the saved segments. Otherwise starts a fresh
   * download from the request URL.
   */
  suspend fun execute(resumeInfo: ResumeInfo? = null) {
    try {
      if (resumeInfo != null) {
        executeResume(resumeInfo)
      } else {
        executeFresh()
      }
    } finally {
      reservedPaths.forEach(OutputPathReservations::release)
      reservedPaths.clear()
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
    val stored = storedResolution()
    val source: DownloadSource<*>
    val resolvedUrl: ResolvedSource
    val resolved = request.resolvedSource

    if (stored != null) {
      log.d { "Starting taskId=$taskId from its saved source state, source=${stored.first.type}" }
      source = stored.first
      resolvedUrl = stored.second
    } else if (resolved != null) {
      log.d {
        "Using pre-resolved info for taskId=$taskId: url=${redactUrl(request.url)}, " +
          "source=${resolved.sourceType}"
      }
      source = sourceResolver.resolveByType(resolved.sourceType, resolved.url)
      resolvedUrl = resolved
    } else {
      source = sourceResolver.resolve(request.url)
      log.d {
        "Resolved source '${source.type}' for taskId=$taskId: url=${redactUrl(request.url)}"
      }
      resolvedUrl = downloadWithRetry {
        source.resolve(ResolveInput.Url(request.url, request.headers), config)
      }
    }

    runDownload(source) { prepareFresh(source, resolvedUrl, fromStored = stored != null) }
  }

  /**
   * The source and resolution the task's record saved ([TaskRecord.resolvedSource]), from
   * metadata Ketch holds, so it is preferred over a client's
   * [com.linroid.ketch.api.DownloadRequest.resolvedSource]. `null` when the record has none or
   * its source is gone, and the task resolves as usual.
   */
  private fun storedResolution(): Pair<DownloadSource<*>, ResolvedSource>? {
    val record = handle.record.value
    val resolved = record.resolvedSource ?: return null
    return try {
      val type = record.sourceType ?: resolved.sourceType
      sourceResolver.resolveByType(type, record.request.url) to resolved
    } catch (e: Exception) {
      log.w { "Saved resolution of taskId=$taskId is unusable, resolving: ${e.describeCauses()}" }
      null
    }
  }

  /**
   * The part of a fresh start after the source resolved, run under the task's control lock so a
   * selection saved meanwhile is the one the download starts with. Stops the task to wait for a
   * file selection when its request asks to, and otherwise picks the size and the output path,
   * saves them and returns what the download needs. Returns `null` when nothing is left to
   * download.
   */
  private suspend fun prepareFresh(
    source: DownloadSource<*>,
    resolvedUrl: ResolvedSource,
    fromStored: Boolean,
  ): Prepared? {
    val req = handle.record.value.request
    // Several files or variants, whether any subset or one of them is chosen.
    if (req.awaitFileSelection && req.selectedFileIds.isEmpty() && resolvedUrl.files.size > 1) {
      park(source, resolvedUrl, fromStored)
      return null
    }

    val total = freshTotal(source, resolvedUrl, req.selectedFileIds)
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
      ?: fileNameResolver.resolve(req, resolvedUrl)
    val target = resolveDestPath(
      destination = req.destination,
      // Resolved only when needed: the platform default may need an Android context. Sources
      // that write their own files need a filesystem folder, so a default folder given as a URI,
      // such as an Android content:// tree, leaves them the platform default.
      defaultDir = {
        config.defaultDirectory?.takeUnless { source.managesOwnFileIo && it.contains("://") }
          ?: defaultDownloadDirectory()
      },
      serverFileName = fileName,
      contentType = resolvedUrl.contentType,
      // A source writing its own files names them itself; nothing is moved when it completes.
      partial = !source.managesOwnFileIo,
    )
    val outputPath = target.path
    log.i {
      "Resolved taskId=$taskId: source=${source.type}, totalBytes=$total, " +
        "outputPath=$outputPath"
    }

    if (total == 0L && !source.managesOwnFileIo) {
      completeZeroByteFile(target, source.type, resolvedUrl.checksum)
      return null
    }

    // A fresh run stores its own state as it starts; one from the kept resolution, such as a task
    // that waited for its files, keeps what it saved.
    val sourceState = handle.record.value.sourceState.takeIf { fromStored }
    // A resolution the record keeps leaves the request only what views need: the file list
    // without the bulky metadata.
    val kept = resolvedUrl.takeIf { it.isKeptWithTask() }
    val now = Clock.System.now()
    handle.record.update {
      it.copy(
        outputPath = outputPath,
        partialPath = target.partial,
        state = TaskState.DOWNLOADING,
        totalBytes = total,
        sourceType = source.type,
        sourceState = sourceState,
        resolvedSource = kept,
        request = if (kept != null) {
          it.request.copy(
            resolvedSource = (it.request.resolvedSource ?: resolvedUrl).withoutBulkMetadata(),
          )
        } else {
          it.request
        },
        updatedAt = now,
      )
    }
    return Prepared(target, total, resolvedUrl, state = null)
  }

  /**
   * The size of a fresh download: the chosen files or variant of a source with several, sized
   * from the source's own metadata ([DownloadSource.planSelection]) rather than the client's, or
   * the whole content.
   *
   * @throws IllegalArgumentException when a source offering a single choice is given several
   */
  private suspend fun freshTotal(
    source: DownloadSource<*>,
    resolved: ResolvedSource,
    selected: Set<String>,
  ): Long {
    if (selected.isEmpty()) return resolved.totalBytes
    require(resolved.selectionMode != FileSelectionMode.SINGLE || selected.size == 1) {
      SINGLE_CHOICE_MESSAGE
    }
    return try {
      source.planSelection(
        SelectionRequest(
          taskId = taskId,
          fileIds = selected,
          current = emptySet(),
          sourceState = null,
          resolved = resolved,
          segments = null,
          outputPath = null,
          completed = false,
        )
      ).totalBytes
    } catch (_: UnsupportedOperationException) {
      require(selected.all { id -> resolved.files.any { it.id == id } }) {
        "Unknown selected file"
      }
      resolved.files.filter { it.id in selected }.fold(0L) { sum, file ->
        require(file.size >= 0 && sum <= Long.MAX_VALUE - file.size)
        sum + file.size
      }
    }
  }

  /**
   * Stops the task, which asked to wait for a file selection, once its files are known. The
   * record keeps the source, its state and its resolution, so the task starts from them without
   * resolving again, but no output path and no segments: nothing reserves a folder while it
   * waits.
   * Returning frees its download slot.
   */
  private suspend fun park(
    source: DownloadSource<*>,
    resolved: ResolvedSource,
    fromStored: Boolean,
  ) {
    val kept = resolved.takeIf { it.isKeptWithTask() }
    val now = Clock.System.now()
    handle.record.update {
      it.copy(
        state = TaskState.PAUSED,
        sourceType = source.type,
        sourceState = it.sourceState.takeIf { fromStored },
        resolvedSource = kept,
        totalBytes = resolved.totalBytes,
        outputPath = null,
        partialPath = null,
        segments = null,
        request = it.request.copy(
          resolvedSource = if (kept != null) resolved.withoutBulkMetadata() else resolved,
        ),
        updatedAt = now,
      )
    }
    totalBytes = resolved.totalBytes
    val waiting = DownloadState.Paused(
      DownloadProgress(0, resolved.totalBytes),
      PauseReason.AwaitingFileSelection
    )
    // A pause or cancellation that already published its state keeps it; the record is enough
    // for the task to wait again when it starts.
    handle.mutableState.update { state ->
      if (state.isTerminal || state is DownloadState.Paused) state else waiting
    }
    log.i { "Waiting for a file selection: taskId=$taskId, files=${resolved.files.size}" }
  }

  private suspend fun executeResume(info: ResumeInfo) {
    val taskRecord = info.record

    val sourceType = taskRecord.sourceType
      ?: throw KetchError.Unknown(
        IllegalStateException(
          "No sourceType for taskId=${taskRecord.taskId}",
        ),
      )
    val source = sourceResolver.resolveByType(sourceType, taskRecord.request.url)
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

    runDownload(source) {
      // Read again under the control lock: a selection saved since the resume was decided
      // changed the size and the source's state.
      val record = handle.record.value
      reserve(outputPath)
      totalBytes = record.totalBytes
      val sourceState = record.sourceState
      // A record without a partial file, as tasks saved before them have, keeps writing to its
      // output path: its bytes are there, and nothing is moved when it completes.
      val target = OutputTarget(
        path = outputPath,
        partial = record.partialPath.takeUnless { source.managesOwnFileIo },
        replace = record.request.destination?.isFile() == true,
      )
      Prepared(target, record.totalBytes, record.resolvedSource, sourceState)
    }
  }

  /**
   * What a download needs once it is prepared under the task's control lock.
   *
   * @property state the saved state the run continues from; `null` starts from the beginning
   */
  private class Prepared(
    val target: OutputTarget,
    val total: Long,
    val preResolved: ResolvedSource?,
    val state: SourceState?,
  )

  /**
   * Common download-to-completion sequence: under the task's control lock, runs [prepare],
   * creates a [FileAccessor] and builds the [DownloadContext]; then runs the download with
   * retry, flushes, persists completion, and cleans up. [prepare] returning `null` ends the
   * execution without a download.
   *
   * A selection delivered while the source ran but not acknowledged by it runs the download
   * again from its latest state, so completion always covers the latest selection.
   *
   * When [DownloadSource.managesOwnFileIo] is `true`, the source
   * handles its own file I/O so we use [NoOpFileAccessor] and skip
   * flush/cleanup.
   *
   * Neither the periodic and final saves nor the `finally` blocks take the control lock (see
   * [TaskHandle.controlLock]).
   */
  private suspend fun runDownload(
    source: DownloadSource<*>,
    prepare: suspend () -> Prepared?,
  ) {
    val selfManagedIo = source.managesOwnFileIo
    var opened: FileAccessor? = null
    var completed = false
    try {
      currentCoroutineContext().ensureActive()
      val (prepared, ctx) = handle.controlLock.withLock {
        val prepared = prepare() ?: return
        val accessor = if (selfManagedIo) {
          NoOpFileAccessor
        } else {
          openFile(prepared.target.writePath, dispatchers.disk)
        }
        opened = accessor
        fileAccessor = accessor
        val built = buildContext(
          fileAccessor = accessor,
          totalBytes = prepared.total,
          preResolved = prepared.preResolved,
          outputPath = prepared.target.path,
        )
        context = built
        prepared to built
      }
      val target = prepared.target
      val total = prepared.total
      val fa = ctx.fileAccessor

      // Added to the time saved by earlier runs; a record that never tracked it stays unknown.
      val previousTime = handle.record.value.downloadTime
      val runMark = timeSource.markNow()
      fun downloadTime() = previousTime?.plus(runMark.elapsedNow())

      // Segments are the only progress of a file of known size Ketch writes, so until the source
      // publishes them the record keeps none: a saved [] would resume as a finished transfer of
      // a zero-filled file. Content of unknown size keeps [], and resuming it streams it again.
      fun savedSegments(snapshot: List<Segment>) =
        snapshot.takeUnless { it.isEmpty() && total > 0 && !selfManagedIo }

      coroutineScope {
        val saveJob = launch {
          while (true) {
            delay(config.saveIntervalMs)
            val snapshot = handle.mutableSegments.value
            val updatedResume = source.stored(ctx)
            handle.record.update {
              it.copy(
                segments = savedSegments(snapshot),
                sourceState = updatedResume
                  ?: it.sourceState,
                downloadTime = downloadTime(),
                updatedAt = Clock.System.now(),
              )
            }
          }
        }
        try {
          // Saved before any byte is written, so a resume can tell whether the content changed.
          source.stored(ctx)?.let { state ->
            handle.record.update {
              it.copy(sourceState = state, updatedAt = Clock.System.now())
            }
          }
          // The first attempt continues from the state the run started from; a retry or a rerun
          // from the latest one stored, so the source checks what it already wrote.
          var state = prepared.state
          var rerunFor = -1
          while (true) {
            var retry = false
            downloadWithRetry(ctx) {
              if (retry) state = handle.record.value.sourceState ?: state
              retry = true
              source.downloadSaved(ctx, state)
            }
            val pending = handle.controlLock.withLock {
              val latest = ctx.selection.value.revision
              if (ctx.acknowledgedSelection.value >= latest) {
                finishing = true
                null
              } else {
                latest
              }
            } ?: break
            if (pending == rerunFor) {
              throw KetchError.SourceError(
                sourceType = source.type,
                cause = IllegalStateException("The source did not apply the file selection"),
              )
            }
            rerunFor = pending
            log.i { "Selection of taskId=$taskId changed as it finished; continuing with it" }
            state = source.stored(ctx) ?: handle.record.value.sourceState
          }
        } finally {
          withContext(NonCancellable) {
            saveJob.cancelAndJoin()
            val updatedResume = source.stored(ctx)
            val snapshot = handle.mutableSegments.value
            handle.record.update {
              it.copy(segments = savedSegments(snapshot),
                sourceState = updatedResume ?: it.sourceState,
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

      // Kept current by deliverSelection, so a selection the source took is the size it reports.
      var finalTotal = totalBytes
      var outputPath = target.path
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

      // Hashed after the last flush, so the digest covers the bytes on disk, and before the
      // move, so a file that does not match never takes the final name.
      val verified = if (selfManagedIo) null else verify(fa, ctx.publishedChecksum, finalTotal)
      if (!selfManagedIo) {
        // Closed before the move: some systems, such as Windows, cannot move an open file.
        fa.close()
        outputPath = moveToFinal(target)
      }

      val finalTime = downloadTime()
      val finishedAt = finishTime()
      handle.record.update {
        it.copy(
          state = TaskState.COMPLETED,
          outputPath = outputPath,
          partialPath = null,
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
        checksum = verified,
      )
    } finally {
      val accessor = opened
      if (!selfManagedIo && accessor != null) {
        cleanupAfterExecution(accessor, completed)
      }
    }
  }

  /**
   * Verifies the finished file of [size] bytes against the request's checksum, or else the one
   * the server [published], and returns the checksum it matched; `null` when there is none to
   * check. The task keeps reporting every byte downloaded while it is hashed. A file that does
   * not match is deleted and its progress discarded, so it never stays where it looks complete
   * and a resume downloads it again.
   *
   * @throws KetchError.ChecksumMismatch when the file does not match
   * @throws KetchError.Unsupported when the request has a checksum but the file cannot be read
   */
  private suspend fun verify(fa: FileAccessor, published: Checksum?, size: Long): Checksum? {
    val expected = handle.record.value.request.checksum
    val target = expected ?: published ?: return null
    val origin = if (expected != null) "requested" else "published"
    log.i { "Verifying taskId=$taskId against the $origin ${target.algorithm.label} checksum" }
    val actual = try {
      withContext(dispatchers.disk) { fileDigest(fa, target.algorithm, size) }
    } catch (e: CancellationException) {
      throw e
    } catch (e: UnsupportedOperationException) {
      if (expected == null) {
        log.w { "Cannot read the file of taskId=$taskId to verify it; skipping the check" }
        return null
      }
      throw KetchError.Unsupported(e)
    } catch (e: KetchError) {
      throw e
    } catch (e: Exception) {
      throw KetchError.Disk(e)
    }
    if (target.matches(actual)) {
      log.i { "Verified taskId=$taskId: ${target.algorithm.label} matches" }
      return target
    }
    log.w { "Checksum mismatch for taskId=$taskId: ${target.algorithm.label}, $origin" }
    withContext(NonCancellable) {
      discardProgress()
      try {
        fa.delete()
      } catch (e: Exception) {
        log.w(e) { "Failed to delete the mismatching file of taskId=$taskId" }
      }
    }
    throw mismatch(target, actual, published = expected == null)
  }

  private fun mismatch(target: Checksum, actual: ByteArray, published: Boolean) =
    KetchError.ChecksumMismatch(
      algorithm = target.algorithm,
      expected = target.value,
      actual = Checksum.of(target.algorithm, actual).value,
      published = published,
    )

  /** Keeps the segment layout but drops its progress, so a resume downloads every byte again. */
  private suspend fun discardProgress() {
    val reset = handle.mutableSegments.value.map { it.copy(downloadedBytes = 0) }
    handle.mutableSegments.value = reset
    handle.record.update { it.copy(segments = reset, updatedAt = Clock.System.now()) }
  }

  private suspend fun completeZeroByteFile(
    target: OutputTarget,
    sourceType: String,
    published: Checksum?,
  ) {
    log.i { "Zero-byte file for taskId=$taskId, completing" }
    // An empty file has the digest of no bytes, which needs no reading.
    val expected = handle.record.value.request.checksum
    val wanted = expected ?: published
    val empty = wanted?.algorithm?.newDigest()?.digest()
    if (wanted != null && empty != null && !wanted.matches(empty)) {
      log.w { "Checksum mismatch for empty taskId=$taskId: ${wanted.algorithm.label}" }
      throw mismatch(wanted, empty, published = expected == null)
    }
    val fa = openFile(target.writePath, dispatchers.disk)
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
    val outputPath = moveToFinal(target)
    val finishedAt = finishTime()
    handle.record.update {
      it.copy(
        outputPath = outputPath,
        partialPath = null,
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
      checksum = wanted,
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

  /**
   * The most bytes any attempt of this execution reported or kept in its segments. An attempt
   * that gets past it made progress, and the retries in a row start over.
   */
  @Volatile
  private var furthestProgress =
    handle.record.value.segments.orEmpty().sumOf { it.downloadedBytes }

  /** Updates [furthestProgress] with the bytes the segments hold now, and returns it. */
  private fun progressReached(): Long {
    val kept = handle.mutableSegments.value.sumOf { it.downloadedBytes }
    if (kept > furthestProgress) furthestProgress = kept
    return furthestProgress
  }

  private suspend fun <T> downloadWithRetry(
    ctx: DownloadContext? = null,
    block: suspend () -> T,
  ): T {
    val policy = RetryPolicy(config)
    var retryCount = 0
    while (true) {
      val reached = progressReached()
      try {
        return block()
      } catch (e: CancellationException) {
        throw e
      } catch (e: Exception) {
        val error = when (e) {
          is KetchError -> e
          else -> KetchError.Unknown(e)
        }

        if (retryCount > 0 && progressReached() > reached) {
          log.d { "taskId=$taskId made progress before failing; retries start over" }
          retryCount = 0
        }
        if (!error.isRetryable || !policy.allows(retryCount)) {
          // DownloadCoordinator logs the failure itself, with its stack trace.
          log.i {
            if (error.isRetryable) "Giving up on taskId=$taskId after $retryCount retries"
            else "Not retrying taskId=$taskId: ${error::class.simpleName} is not retryable"
          }
          throw error
        }

        retryCount++
        val limit = if (config.unlimitedRetries) "unlimited" else "${config.retryCount}"

        val delayMs: Long
        if (error is KetchError.Http && error.code == 429) {
          if (ctx != null) reduceConnections(ctx, error.rateLimitRemaining)
          delayMs = error.retryAfterSeconds?.let { it * 1000L } ?: policy.delayMs(retryCount)
          log.w {
            "Rate limited (429) for taskId=$taskId. Retry $retryCount/$limit " +
              "in ${delayMs}ms, connections=" +
              "${ctx?.maxConnections?.value ?: request.connections}"
          }
        } else {
          delayMs = policy.delayMs(retryCount)
          log.w {
            "Retry $retryCount/$limit for taskId=$taskId in ${delayMs}ms: " +
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
    val request = handle.record.value.request
    return DownloadContext(
      taskId = taskId,
      url = request.url,
      request = request,
      fileAccessor = fileAccessor,
      segments = handle.mutableSegments,
      onProgress = { downloaded, total ->
        if (downloaded > furthestProgress) furthestProgress = downloaded
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
      selection = selection,
      connections = connections,
    )
  }

  private fun createLimiter(speedLimit: SpeedLimit): SpeedLimiter {
    return if (speedLimit.isUnlimited) {
      SpeedLimiter.Unlimited
    } else {
      TokenBucket(speedLimit.bytesPerSecond)
    }
  }

  /**
   * Picks the output path and reserves it until the execution stops, along with the partial file
   * the download writes to until it completes when [partial] asks for one ([OutputTarget]). A
   * file [destination] is used as it is, and replaced once the download completes. Otherwise
   * the name, a [destination] name or else [serverFileName] made safe with [sanitizeFileName],
   * is joined to the folder, must stay inside it, and gets a ` (n)` suffix when the file or its
   * partial file exists or another download reserved the path. The folder is a
   * [destination] folder; without one, it is the folder of the first of
   * [DownloadConfig.categories] that the name, [contentType] and the URL host match, or else
   * the default one. In an Android content:// folder the partial document is created now, and
   * stands for the output path until it is renamed ([createPartialDocument]).
   *
   * @throws KetchError.Disk if the name would leave the folder
   */
  private fun resolveDestPath(
    destination: Destination?,
    defaultDir: () -> String,
    serverFileName: String,
    contentType: String?,
    partial: Boolean,
  ): OutputTarget {
    fun partialOf(path: String) =
      if (partial && !path.contains("://")) partialPathFor(path) else null
    if (destination != null && destination.isFile()) {
      val path = destination.value.also(::reserve)
      return OutputTarget(path, partialOf(path), replace = true)
    }
    val fileName = when {
      destination != null && destination.isName() ->
        destination.value
      else -> sanitizeFileName(serverFileName) ?: DefaultFileNameResolver.FALLBACK
    }
    val directory = when {
      destination != null && destination.isDirectory() ->
        destination.value.trimEnd('/', '\\')
      else -> categoryDirectory(defaultDir(), fileName, contentType)
    }
    if (directory.contains("://")) {
      // A content:// document is created now, under a name its provider makes unique.
      val document = if (partial) createPartialDocument(directory, fileName) else null
      if (document != null) {
        return OutputTarget(document.uri, document.uri.takeIf { document.partial }, replace = false)
      }
      return OutputTarget(resolveChildPath(directory, fileName), partial = null, replace = false)
    }
    val outputPath = resolveChildPath(directory, fileName)
    if (!isInsideDirectory(directory, outputPath)) {
      throw KetchError.Disk(
        IllegalArgumentException("File name \"$fileName\" leaves the folder $directory"),
      )
    }
    val reserved = OutputPathReservations.reserveUnique(outputPath).also { reservedPaths += it }
    return OutputTarget(reserved, partialOf(reserved), replace = false)
  }

  /**
   * Moves [target]'s finished partial file to its output path ([movePartialToFinal]), or renames
   * its partial document, and returns where the download is now. A download without a partial
   * file is where it was written, as is a document its provider failed to rename, which keeps
   * the partial name rather than failing a finished download.
   *
   * @throws KetchError.Disk if the file cannot be moved
   */
  private suspend fun moveToFinal(target: OutputTarget): String {
    val partial = target.partial ?: return target.path
    return withContext(dispatchers.disk) {
      try {
        when {
          partial.contains("://") -> finishPartialDocument(partial) ?: partial.also {
            log.w { "Finished taskId=$taskId keeps its partial name: it could not be renamed" }
          }
          // Nothing to move, as when a source wrote nothing; the output path stays as it is.
          !platformFileSystem.exists(partial.toPath()) -> {
            log.w { "No partial file to move for taskId=$taskId" }
            target.path
          }
          else -> movePartialToFinal(partial, target.path, target.replace) { reservedPaths += it }
            .also { moved ->
              if (moved != target.path) {
                log.i { "Output of taskId=$taskId exists, saved as $moved instead" }
              }
            }
        }
      } catch (e: Exception) {
        if (e is CancellationException) throw e
        throw KetchError.Disk(e)
      }
    }
  }

  /**
   * The folder under [defaultDir] of the first of [DownloadConfig.categories] that a download
   * named [fileName], of [contentType], from the request's host matches, or [defaultDir] when it
   * matches none. Each folder name goes through [sanitizeFileName]; folders are created as the
   * file is, except in a content:// tree, where [resolveChildFolder] finds or creates them.
   */
  private fun categoryDirectory(
    defaultDir: String,
    fileName: String,
    contentType: String?,
  ): String {
    val host = DownloadQueue.extractHost(request.url)
    val category = config.categoryFor(fileName, contentType, host) ?: return defaultDir
    val names = category.folder.split('/', '\\').mapNotNull(::sanitizeFileName)
    if (names.isEmpty()) return defaultDir
    log.d { "Category folder \"${category.folder}\" for taskId=$taskId" }
    return try {
      names.fold(defaultDir, ::resolveChildFolder)
    } catch (e: Exception) {
      if (e is CancellationException) throw e
      throw KetchError.Disk(e)
    }
  }

  /** Reserves [path], used as it is, unless it is a content:// document. */
  private fun reserve(path: String) {
    if (path.contains("://")) return
    OutputPathReservations.reserve(path)
    reservedPaths += path
  }

  /**
   * Where a download saves: its output [path], and the [partial] file it writes until it
   * completes and is moved to [path] (`null` to write to [path] itself). An Android content://
   * partial document is its own [path] until it is renamed. A finished download [replace]s an
   * existing [path] only when the caller named that file.
   */
  private class OutputTarget(val path: String, val partial: String?, val replace: Boolean) {
    val writePath: String get() = partial ?: path
  }

  /**
   * Info needed to resume a previously interrupted download.
   */
  internal class ResumeInfo(
    val record: TaskRecord,
    val segments: List<Segment>,
  )

}
