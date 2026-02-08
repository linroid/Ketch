package com.linroid.kdown.internal

import com.linroid.kdown.DownloadConfig
import com.linroid.kdown.DownloadRequest
import com.linroid.kdown.FileAccessor
import com.linroid.kdown.FileNameResolver
import com.linroid.kdown.HttpEngine
import com.linroid.kdown.KDownLogger
import com.linroid.kdown.MetadataStore
import com.linroid.kdown.TaskStore
import com.linroid.kdown.error.KDownError
import com.linroid.kdown.model.DownloadMetadata
import com.linroid.kdown.model.DownloadProgress
import com.linroid.kdown.model.DownloadState
import com.linroid.kdown.model.TaskRecord
import kotlinx.io.files.SystemFileSystem
import com.linroid.kdown.model.TaskState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.io.files.Path

internal class DownloadCoordinator(
  private val httpEngine: HttpEngine,
  private val metadataStore: MetadataStore,
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
    var metadata: DownloadMetadata?,
    var fileAccessor: FileAccessor?,
    var segmentProgress: MutableList<Long>? = null
  )

  suspend fun start(
    taskId: String,
    request: DownloadRequest,
    scope: CoroutineScope
  ): StateFlow<DownloadState> {
    val stateFlow = MutableStateFlow<DownloadState>(DownloadState.Pending)

    val initialDestPath = request.fileName?.let {
      Path(request.directory, it)
    } ?: request.directory

    val now = currentTimeMillis()
    taskStore.save(
      TaskRecord(
        taskId = taskId,
        url = request.url,
        destPath = initialDestPath,
        connections = request.connections,
        headers = request.headers,
        state = TaskState.PENDING,
        createdAt = now,
        updatedAt = now
      )
    )

    val job = scope.launch {
      try {
        executeDownload(taskId, request, stateFlow)
      } catch (e: CancellationException) {
        if (stateFlow.value !is DownloadState.Paused) {
          stateFlow.value = DownloadState.Canceled
        }
        throw e
      } catch (e: Exception) {
        val error = when (e) {
          is KDownError -> e
          else -> KDownError.Unknown(e)
        }
        updateTaskState(taskId, TaskState.FAILED, error.message)
        stateFlow.value = DownloadState.Failed(error)
      }
    }

    mutex.withLock {
      activeDownloads[taskId] = ActiveDownload(
        job = job,
        stateFlow = stateFlow,
        metadata = null,
        fileAccessor = null
      )
    }

    return stateFlow.asStateFlow()
  }

  private suspend fun executeDownload(
    taskId: String,
    request: DownloadRequest,
    stateFlow: MutableStateFlow<DownloadState>
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

    updateTaskRecord(taskId) {
      it.copy(destPath = destPath, updatedAt = currentTimeMillis())
    }

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

    val now = currentTimeMillis()
    val metadata = DownloadMetadata(
      taskId = taskId,
      url = request.url,
      destPath = destPath,
      totalBytes = totalBytes,
      acceptRanges = serverInfo.acceptRanges,
      etag = serverInfo.etag,
      lastModified = serverInfo.lastModified,
      segments = segments,
      headers = request.headers,
      createdAt = now,
      updatedAt = now
    )

    val fileAccessor = fileAccessorFactory(destPath)

    mutex.withLock {
      activeDownloads[taskId]?.let {
        it.metadata = metadata
        it.fileAccessor = fileAccessor
      }
    }

    updateTaskRecord(taskId) {
      it.copy(
        state = TaskState.DOWNLOADING,
        totalBytes = totalBytes,
        updatedAt = now
      )
    }

    try {
      KDownLogger.d("Coordinator") {
        "Preallocating $totalBytes bytes for taskId=$taskId"
      }
      fileAccessor.preallocate(totalBytes)
      KDownLogger.d("Coordinator") {
        "Saving metadata for taskId=$taskId"
      }
      metadataStore.save(taskId, metadata)

      downloadWithRetry(taskId, metadata, fileAccessor, stateFlow)

      fileAccessor.flush()
      metadataStore.clear(taskId)

      updateTaskRecord(taskId) {
        it.copy(
          state = TaskState.COMPLETED,
          downloadedBytes = totalBytes,
          updatedAt = currentTimeMillis()
        )
      }

      KDownLogger.i("Coordinator") {
        "Download completed successfully for taskId=$taskId"
      }
      stateFlow.value = DownloadState.Completed(destPath)
    } finally {
      fileAccessor.close()
      mutex.withLock {
        activeDownloads.remove(taskId)
      }
    }
  }

  private suspend fun downloadWithRetry(
    taskId: String,
    initialMetadata: DownloadMetadata,
    fileAccessor: FileAccessor,
    stateFlow: MutableStateFlow<DownloadState>
  ) {
    var metadata = initialMetadata
    var retryCount = 0

    while (true) {
      try {
        downloadSegments(taskId, metadata, fileAccessor, stateFlow)
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

        metadata = metadataStore.load(taskId) ?: metadata
      }
    }
  }

  private suspend fun downloadSegments(
    taskId: String,
    metadata: DownloadMetadata,
    fileAccessor: FileAccessor,
    stateFlow: MutableStateFlow<DownloadState>
  ): DownloadMetadata {
    val segmentProgress =
      metadata.segments.map { it.downloadedBytes }.toMutableList()
    val segmentMutex = Mutex()

    mutex.withLock {
      activeDownloads[taskId]?.segmentProgress = segmentProgress
    }

    var lastProgressUpdate = currentTimeMillis()
    val progressMutex = Mutex()

    suspend fun updateProgress() {
      val now = currentTimeMillis()
      progressMutex.withLock {
        if (now - lastProgressUpdate >= config.progressUpdateIntervalMs) {
          val downloaded =
            segmentMutex.withLock { segmentProgress.sum() }
          stateFlow.value = DownloadState.Downloading(
            DownloadProgress(
              downloadedBytes = downloaded,
              totalBytes = metadata.totalBytes
            )
          )
          lastProgressUpdate = now
        }
      }
    }

    stateFlow.value = DownloadState.Downloading(
      DownloadProgress(
        downloadedBytes = metadata.downloadedBytes,
        totalBytes = metadata.totalBytes
      )
    )

    val incompleteSegments = metadata.segments.filter { !it.isComplete }

    coroutineScope {
      val results = incompleteSegments.map { segment ->
        async {
          val downloader = SegmentDownloader(httpEngine, fileAccessor)
          downloader.download(
            metadata.url, segment, metadata.headers
          ) { bytesDownloaded ->
            segmentMutex.withLock {
              segmentProgress[segment.index] = bytesDownloaded
            }
            updateProgress()
          }
        }
      }

      val completedSegments = results.awaitAll()

      val updatedSegments = metadata.segments.toMutableList()
      for (completed in completedSegments) {
        updatedSegments[completed.index] = completed
      }

      val updatedMetadata = metadata.copy(
        segments = updatedSegments,
        updatedAt = currentTimeMillis()
      )
      metadataStore.save(taskId, updatedMetadata)
    }

    stateFlow.value = DownloadState.Downloading(
      DownloadProgress(
        downloadedBytes = metadata.totalBytes,
        totalBytes = metadata.totalBytes
      )
    )

    return metadataStore.load(taskId) ?: metadata
  }

  suspend fun pause(taskId: String) {
    mutex.withLock {
      val active = activeDownloads[taskId] ?: return
      KDownLogger.i("Coordinator") {
        "Pausing download for taskId=$taskId"
      }
      active.job.cancel()

      active.metadata?.let { metadata ->
        KDownLogger.d("Coordinator") {
          "Saving pause state for taskId=$taskId"
        }
        val now = currentTimeMillis()
        val progress = active.segmentProgress
        val updatedMetadata = if (progress != null) {
          val updatedSegments = metadata.segments.mapIndexed { i, seg ->
            seg.copy(downloadedBytes = progress[i])
          }
          metadata.copy(segments = updatedSegments, updatedAt = now)
        } else {
          metadata.copy(updatedAt = now)
        }
        metadataStore.save(taskId, updatedMetadata)

        updateTaskRecord(taskId) {
          it.copy(
            state = TaskState.PAUSED,
            downloadedBytes = updatedMetadata.downloadedBytes,
            updatedAt = now
          )
        }
      }

      active.fileAccessor?.let { accessor ->
        try {
          accessor.flush()
        } catch (_: Exception) {
        }
      }

      active.stateFlow.value = DownloadState.Paused
      activeDownloads.remove(taskId)
    }
  }

  suspend fun resume(
    taskId: String,
    scope: CoroutineScope
  ): StateFlow<DownloadState>? {
    val metadata = metadataStore.load(taskId) ?: return null

    val stateFlow = MutableStateFlow<DownloadState>(DownloadState.Pending)

    updateTaskRecord(taskId) {
      it.copy(
        state = TaskState.DOWNLOADING,
        updatedAt = currentTimeMillis()
      )
    }

    val job = scope.launch {
      try {
        resumeDownload(taskId, metadata, stateFlow)
      } catch (e: CancellationException) {
        if (stateFlow.value !is DownloadState.Paused) {
          stateFlow.value = DownloadState.Canceled
        }
        throw e
      } catch (e: Exception) {
        val error = when (e) {
          is KDownError -> e
          else -> KDownError.Unknown(e)
        }
        updateTaskState(taskId, TaskState.FAILED, error.message)
        stateFlow.value = DownloadState.Failed(error)
      }
    }

    mutex.withLock {
      activeDownloads[taskId] = ActiveDownload(
        job = job,
        stateFlow = stateFlow,
        metadata = metadata,
        fileAccessor = null
      )
    }

    return stateFlow.asStateFlow()
  }

  private suspend fun resumeDownload(
    taskId: String,
    metadata: DownloadMetadata,
    stateFlow: MutableStateFlow<DownloadState>
  ) {
    KDownLogger.i("Coordinator") {
      "Resuming download for taskId=$taskId, url=${metadata.url}"
    }
    KDownLogger.d("Coordinator") {
      "Validating server state for resume"
    }
    val detector = RangeSupportDetector(httpEngine)
    val serverInfo = detector.detect(metadata.url, metadata.headers)

    if (metadata.etag != null && serverInfo.etag != metadata.etag) {
      KDownLogger.w("Coordinator") {
        "ETag mismatch - file has changed on server"
      }
      throw KDownError.ValidationFailed(
        "ETag mismatch - file has changed on server"
      )
    }

    if (metadata.lastModified != null &&
      serverInfo.lastModified != metadata.lastModified
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

    val fileAccessor = fileAccessorFactory(metadata.destPath)

    mutex.withLock {
      activeDownloads[taskId]?.let {
        it.metadata = metadata
        it.fileAccessor = fileAccessor
      }
    }

    try {
      downloadWithRetry(taskId, metadata, fileAccessor, stateFlow)

      fileAccessor.flush()
      metadataStore.clear(taskId)

      updateTaskRecord(taskId) {
        it.copy(
          state = TaskState.COMPLETED,
          downloadedBytes = metadata.totalBytes,
          updatedAt = currentTimeMillis()
        )
      }

      stateFlow.value = DownloadState.Completed(metadata.destPath)
    } finally {
      fileAccessor.close()
      mutex.withLock {
        activeDownloads.remove(taskId)
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
    metadataStore.clear(taskId)
    updateTaskState(taskId, TaskState.CANCELED)
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
    val existing = taskStore.load(taskId) ?: return
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
