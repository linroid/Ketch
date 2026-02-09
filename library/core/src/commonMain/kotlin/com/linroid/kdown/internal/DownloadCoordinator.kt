package com.linroid.kdown.internal

import com.linroid.kdown.DownloadConfig
import com.linroid.kdown.DownloadRequest
import com.linroid.kdown.FileAccessor
import com.linroid.kdown.FileNameResolver
import com.linroid.kdown.HttpEngine
import com.linroid.kdown.KDownLogger
import com.linroid.kdown.TaskStore
import com.linroid.kdown.error.KDownError
import com.linroid.kdown.model.DownloadProgress
import com.linroid.kdown.model.DownloadState
import com.linroid.kdown.model.Segment
import com.linroid.kdown.model.TaskRecord
import kotlinx.io.files.SystemFileSystem
import com.linroid.kdown.model.TaskState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.io.files.Path

internal class DownloadCoordinator(
  private val httpEngine: HttpEngine,
  private val taskStore: TaskStore,
  private val config: DownloadConfig,
  private val fileAccessorFactory: (Path) -> FileAccessor,
  private val fileNameResolver: FileNameResolver
) {
  private val mutex = Mutex()
  private val activeDownloads = mutableMapOf<String, ActiveDownload>()

  private class ActiveDownload(
    val job: Job,
    val stateFlow: MutableStateFlow<DownloadState>,
    val segmentsFlow: MutableStateFlow<List<Segment>>,
    var segments: List<Segment>?,
    var fileAccessor: FileAccessor?,
    var segmentProgress: MutableList<Long>? = null,
    var totalBytes: Long = 0
  )

  suspend fun start(
    taskId: String,
    request: DownloadRequest,
    scope: CoroutineScope,
    stateFlow: MutableStateFlow<DownloadState>,
    segmentsFlow: MutableStateFlow<List<Segment>>
  ) {
    val initialDestPath = request.fileName?.let {
      Path(request.directory, it)
    } ?: request.directory

    val now = currentTimeMillis()
    taskStore.save(
      TaskRecord(
        taskId = taskId,
        request = request,
        destPath = initialDestPath,
        state = TaskState.PENDING,
        createdAt = now,
        updatedAt = now
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
          if (stateFlow.value !is DownloadState.Paused) {
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
        fileAccessor = null
      )
    }
  }

  suspend fun startFromRecord(
    record: TaskRecord,
    scope: CoroutineScope,
    stateFlow: MutableStateFlow<DownloadState>,
    segmentsFlow: MutableStateFlow<List<Segment>>
  ) {
    updateTaskRecord(record.taskId) {
      it.copy(
        state = TaskState.PENDING,
        updatedAt = currentTimeMillis()
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
          executeDownload(record.taskId, record.request, stateFlow, segmentsFlow)
        } catch (e: CancellationException) {
          if (stateFlow.value !is DownloadState.Paused) {
            stateFlow.value = DownloadState.Canceled
          }
          throw e
        } catch (e: Exception) {
          if (e is CancellationException) throw e
          val error = when (e) {
            is KDownError -> e
            else -> KDownError.Unknown(e)
          }
          updateTaskState(record.taskId, TaskState.FAILED, error.message)
          stateFlow.value = DownloadState.Failed(error)
        }
      }

      activeDownloads[record.taskId] = ActiveDownload(
        job = job,
        stateFlow = stateFlow,
        segmentsFlow = segmentsFlow,
        segments = null,
        fileAccessor = null
      )
    }
  }

  private suspend fun executeDownload(
    taskId: String,
    request: DownloadRequest,
    stateFlow: MutableStateFlow<DownloadState>,
    segmentsFlow: MutableStateFlow<List<Segment>>
  ) {
    KDownLogger.d("Coordinator") {
      "Detecting server capabilities for ${request.url}"
    }
    val detector = RangeSupportDetector(httpEngine)
    val serverInfo = detector.detect(request.url, request.headers)

    val totalBytes = serverInfo.contentLength
      ?: throw KDownError.Unsupported

    val fileName = fileNameResolver.resolve(request, serverInfo)
    val destPath = deduplicatePath(request.directory, fileName)

    val segments = if (serverInfo.supportsResume && request.connections > 1) {
      KDownLogger.i("Coordinator") {
        "Server supports range requests. " +
          "Using ${request.connections} connections, totalBytes=$totalBytes"
      }
      SegmentCalculator.calculateSegments(totalBytes, request.connections)
    } else {
      KDownLogger.i("Coordinator") {
        "Server does not support range requests or single connection " +
          "requested. Using 1 connection, totalBytes=$totalBytes"
      }
      SegmentCalculator.singleSegment(totalBytes)
    }

    segmentsFlow.value = segments

    val now = currentTimeMillis()
    updateTaskRecord(taskId) {
      it.copy(
        destPath = destPath,
        state = TaskState.DOWNLOADING,
        totalBytes = totalBytes,
        acceptRanges = serverInfo.acceptRanges,
        etag = serverInfo.etag,
        lastModified = serverInfo.lastModified,
        segments = segments,
        updatedAt = now
      )
    }

    val fileAccessor = fileAccessorFactory(destPath)

    mutex.withLock {
      activeDownloads[taskId]?.let {
        it.segments = segments
        it.fileAccessor = fileAccessor
        it.totalBytes = totalBytes
      }
    }

    try {
      KDownLogger.d("Coordinator") {
        "Preallocating $totalBytes bytes for taskId=$taskId"
      }
      try {
        fileAccessor.preallocate(totalBytes)
      } catch (e: Exception) {
        if (e is CancellationException) throw e
        if (e is KDownError) throw e
        throw KDownError.Disk(e)
      }

      val taskRecord = taskStore.load(taskId)
        ?: throw KDownError.Unknown(
          IllegalStateException("TaskRecord not found for $taskId")
        )
      downloadWithRetry(
        taskId, taskRecord, segments, fileAccessor, stateFlow, segmentsFlow
      )

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
          downloadedBytes = totalBytes,
          segments = null,
          updatedAt = currentTimeMillis()
        )
      }

      KDownLogger.i("Coordinator") {
        "Download completed successfully for taskId=$taskId"
      }
      stateFlow.value = DownloadState.Completed(destPath)
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
    taskRecord: TaskRecord,
    initialSegments: List<Segment>,
    fileAccessor: FileAccessor,
    stateFlow: MutableStateFlow<DownloadState>,
    segmentsFlow: MutableStateFlow<List<Segment>>
  ) {
    var segments = initialSegments
    var retryCount = 0

    while (true) {
      try {
        downloadSegments(
          taskId, taskRecord, segments, fileAccessor, stateFlow, segmentsFlow
        )
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
            "Download failed after $retryCount retries: ${error.message}"
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

        segments = taskStore.load(taskId)?.segments ?: segments
      }
    }
  }

  private suspend fun downloadSegments(
    taskId: String,
    taskRecord: TaskRecord,
    segments: List<Segment>,
    fileAccessor: FileAccessor,
    stateFlow: MutableStateFlow<DownloadState>,
    segmentsFlow: MutableStateFlow<List<Segment>>
  ) {
    val totalBytes = taskRecord.totalBytes
    val segmentProgress =
      segments.map { it.downloadedBytes }.toMutableList()
    val segmentMutex = Mutex()

    mutex.withLock {
      activeDownloads[taskId]?.segmentProgress = segmentProgress
    }

    var lastProgressUpdate = currentTimeMillis()
    val progressMutex = Mutex()

    val incompleteSegments = segments.filter { !it.isComplete }
    val updatedSegments = segments.toMutableList()

    suspend fun currentSegments(): List<Segment> {
      return segmentMutex.withLock {
        updatedSegments.mapIndexed { i, seg ->
          seg.copy(downloadedBytes = segmentProgress[i])
        }
      }
    }

    suspend fun updateProgress() {
      val now = currentTimeMillis()
      progressMutex.withLock {
        if (now - lastProgressUpdate >= config.progressUpdateIntervalMs) {
          val snapshot = currentSegments()
          val downloaded = snapshot.sumOf { it.downloadedBytes }
          stateFlow.value = DownloadState.Downloading(
            DownloadProgress(
              downloadedBytes = downloaded,
              totalBytes = totalBytes
            )
          )
          segmentsFlow.value = snapshot
          lastProgressUpdate = now
        }
      }
    }

    val downloadedBytes = segments.sumOf { it.downloadedBytes }
    stateFlow.value = DownloadState.Downloading(
      DownloadProgress(
        downloadedBytes = downloadedBytes,
        totalBytes = totalBytes
      )
    )
    segmentsFlow.value = segments

    suspend fun saveSegments() {
      val snapshot = currentSegments()
      updateTaskRecord(taskId) {
        it.copy(
          segments = snapshot,
          downloadedBytes = snapshot.sumOf { s -> s.downloadedBytes },
          updatedAt = currentTimeMillis()
        )
      }
    }

    coroutineScope {
      val flushJob = launch {
        while (true) {
          delay(config.segmentSaveIntervalMs)
          saveSegments()
          KDownLogger.v("Coordinator") {
            "Periodic segment save for taskId=$taskId"
          }
        }
      }

      try {
        val results = incompleteSegments.map { segment ->
          async {
            val downloader = SegmentDownloader(httpEngine, fileAccessor)
            val completed = downloader.download(
              taskRecord.request.url, segment, taskRecord.request.headers
            ) { bytesDownloaded ->
              segmentMutex.withLock {
                segmentProgress[segment.index] = bytesDownloaded
              }
              updateProgress()
            }
            segmentMutex.withLock {
              updatedSegments[completed.index] = completed
            }
            saveSegments()
            KDownLogger.d("Coordinator") {
              "Segment ${completed.index} completed for taskId=$taskId"
            }
            completed
          }
        }

        results.awaitAll()
      } finally {
        flushJob.cancel()
      }

      saveSegments()
    }

    val finalSegments = currentSegments()
    segmentsFlow.value = finalSegments
    stateFlow.value = DownloadState.Downloading(
      DownloadProgress(
        downloadedBytes = totalBytes,
        totalBytes = totalBytes
      )
    )
  }

  suspend fun pause(taskId: String) {
    mutex.withLock {
      val active = activeDownloads[taskId] ?: return
      KDownLogger.i("Coordinator") {
        "Pausing download for taskId=$taskId"
      }
      active.job.cancel()

      active.segments?.let { segments ->
        KDownLogger.d("Coordinator") {
          "Saving pause state for taskId=$taskId"
        }
        val now = currentTimeMillis()
        val progress = active.segmentProgress
        val updatedSegments = if (progress != null) {
          segments.mapIndexed { i, seg ->
            seg.copy(downloadedBytes = progress[i])
          }
        } else {
          segments
        }

        val downloadedBytes = updatedSegments.sumOf { it.downloadedBytes }
        updateTaskRecord(taskId) {
          it.copy(
            state = TaskState.PAUSED,
            downloadedBytes = downloadedBytes,
            segments = updatedSegments,
            updatedAt = now
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

      val pausedDownloaded = active.segments?.let { segs ->
        val prog = active.segmentProgress
        if (prog != null) {
          segs.mapIndexed { i, seg ->
            seg.copy(downloadedBytes = prog[i])
          }.sumOf { it.downloadedBytes }
        } else {
          segs.sumOf { it.downloadedBytes }
        }
      } ?: 0L
      active.stateFlow.value = DownloadState.Paused(
        DownloadProgress(pausedDownloaded, active.totalBytes)
      )
      activeDownloads.remove(taskId)
    }
  }

  suspend fun resume(
    taskId: String,
    scope: CoroutineScope,
    stateFlow: MutableStateFlow<DownloadState>,
    segmentsFlow: MutableStateFlow<List<Segment>>
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
        updatedAt = currentTimeMillis()
      )
    }

    mutex.withLock {
      val job = scope.launch {
        try {
          resumeDownload(taskId, taskRecord, segments, stateFlow, segmentsFlow)
        } catch (e: CancellationException) {
          if (stateFlow.value !is DownloadState.Paused) {
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
        fileAccessor = null
      )
    }

    return true
  }

  private suspend fun resumeDownload(
    taskId: String,
    taskRecord: TaskRecord,
    segments: List<Segment>,
    stateFlow: MutableStateFlow<DownloadState>,
    segmentsFlow: MutableStateFlow<List<Segment>>
  ) {
    KDownLogger.i("Coordinator") {
      "Resuming download for taskId=$taskId, url=${taskRecord.request.url}"
    }
    KDownLogger.d("Coordinator") {
      "Validating server state for resume"
    }
    val detector = RangeSupportDetector(httpEngine)
    val serverInfo = detector.detect(
      taskRecord.request.url, taskRecord.request.headers
    )

    if (taskRecord.etag != null && serverInfo.etag != taskRecord.etag) {
      KDownLogger.w("Coordinator") {
        "ETag mismatch - file has changed on server"
      }
      throw KDownError.ValidationFailed(
        "ETag mismatch - file has changed on server"
      )
    }

    if (taskRecord.lastModified != null &&
      serverInfo.lastModified != taskRecord.lastModified
    ) {
      KDownLogger.w("Coordinator") {
        "Last-Modified mismatch - file has changed on server"
      }
      throw KDownError.ValidationFailed(
        "Last-Modified mismatch - file has changed on server"
      )
    }
    KDownLogger.d("Coordinator") {
      "Server validation passed, continuing resume"
    }

    val fileAccessor = fileAccessorFactory(taskRecord.destPath)

    val validatedSegments = validateLocalFile(
      taskId, fileAccessor, segments, taskRecord.totalBytes
    )

    mutex.withLock {
      activeDownloads[taskId]?.let {
        it.segments = validatedSegments
        it.fileAccessor = fileAccessor
        it.totalBytes = taskRecord.totalBytes
      }
    }

    if (validatedSegments !== segments) {
      segmentsFlow.value = validatedSegments
      updateTaskRecord(taskId) {
        it.copy(
          segments = validatedSegments,
          downloadedBytes = validatedSegments.sumOf { s -> s.downloadedBytes },
          updatedAt = currentTimeMillis()
        )
      }
    }

    try {
      downloadWithRetry(
        taskId, taskRecord, validatedSegments, fileAccessor,
        stateFlow, segmentsFlow
      )

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
          updatedAt = currentTimeMillis()
        )
      }

      stateFlow.value = DownloadState.Completed(taskRecord.destPath)
    } finally {
      fileAccessor.close()
      withContext(NonCancellable) {
        mutex.withLock {
          activeDownloads.remove(taskId)
        }
      }
    }
  }

  private suspend fun validateLocalFile(
    taskId: String,
    fileAccessor: FileAccessor,
    segments: List<Segment>,
    totalBytes: Long
  ): List<Segment> {
    val fileSize = try {
      fileAccessor.size()
    } catch (e: Exception) {
      if (e is CancellationException) throw e
      KDownLogger.w("Coordinator") {
        "Cannot read file size for taskId=$taskId, resetting segments"
      }
      0L
    }

    val claimedProgress = segments.sumOf { it.downloadedBytes }
    if (fileSize < claimedProgress || fileSize < totalBytes) {
      KDownLogger.w("Coordinator") {
        "Local file integrity check failed for taskId=$taskId: " +
          "fileSize=$fileSize, claimedProgress=$claimedProgress, " +
          "totalBytes=$totalBytes. Resetting segments."
      }
      try {
        fileAccessor.preallocate(totalBytes)
      } catch (e: Exception) {
        if (e is CancellationException) throw e
        if (e is KDownError) throw e
        throw KDownError.Disk(e)
      }
      return segments.map { it.copy(downloadedBytes = 0) }
    }
    KDownLogger.d("Coordinator") {
      "Local file integrity check passed for taskId=$taskId: " +
        "fileSize=$fileSize, claimedProgress=$claimedProgress"
    }
    return segments
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
        updatedAt = currentTimeMillis()
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
    errorMessage: String? = null
  ) {
    updateTaskRecord(taskId) {
      it.copy(
        state = state,
        errorMessage = errorMessage,
        updatedAt = currentTimeMillis()
      )
    }
  }

  private suspend fun updateTaskRecord(
    taskId: String,
    update: (TaskRecord) -> TaskRecord
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

  companion object {
    internal fun deduplicatePath(directory: Path, fileName: String): Path {
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
