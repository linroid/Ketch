package com.linroid.kdown.internal

import com.linroid.kdown.DownloadConfig
import com.linroid.kdown.DownloadRequest
import com.linroid.kdown.FileAccessor
import com.linroid.kdown.HttpEngine
import com.linroid.kdown.KDownLogger
import com.linroid.kdown.MetadataStore
import com.linroid.kdown.error.KDownError
import com.linroid.kdown.model.DownloadMetadata
import com.linroid.kdown.model.DownloadProgress
import com.linroid.kdown.model.DownloadState
import kotlinx.coroutines.CancellationException
import kotlinx.io.files.Path
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

internal class DownloadCoordinator(
  private val httpEngine: HttpEngine,
  private val metadataStore: MetadataStore,
  private val config: DownloadConfig,
  private val fileAccessorFactory: (Path) -> FileAccessor
) {
  private val mutex = Mutex()
  private val activeDownloads = mutableMapOf<String, ActiveDownload>()

  private class ActiveDownload(
    val job: Job,
    val stateFlow: MutableStateFlow<DownloadState>,
    var metadata: DownloadMetadata?,
    var fileAccessor: FileAccessor?
  )

  suspend fun start(
    request: DownloadRequest,
    scope: CoroutineScope
  ): StateFlow<DownloadState> {
    val stateFlow = MutableStateFlow<DownloadState>(DownloadState.Pending)

    val job = scope.launch {
      try {
        executeDownload(request, stateFlow)
      } catch (e: CancellationException) {
        stateFlow.value = DownloadState.Canceled
        throw e
      } catch (e: Exception) {
        val error = when (e) {
          is KDownError -> e
          else -> KDownError.Unknown(e)
        }
        stateFlow.value = DownloadState.Failed(error)
      }
    }

    mutex.withLock {
      activeDownloads[request.taskId] = ActiveDownload(
        job = job,
        stateFlow = stateFlow,
        metadata = null,
        fileAccessor = null
      )
    }

    return stateFlow.asStateFlow()
  }

  private suspend fun executeDownload(
    request: DownloadRequest,
    stateFlow: MutableStateFlow<DownloadState>
  ) {
    KDownLogger.d("Coordinator") { "Detecting server capabilities for ${request.url}" }
    val detector = RangeSupportDetector(httpEngine)
    val serverInfo = detector.detect(request.url, request.headers)

    val totalBytes = serverInfo.contentLength
      ?: throw KDownError.Unsupported

    val segments = if (serverInfo.supportsResume && request.connections > 1) {
      KDownLogger.i("Coordinator") {
        "Server supports range requests. Using ${request.connections} connections, totalBytes=$totalBytes"
      }
      SegmentCalculator.calculateSegments(totalBytes, request.connections)
    } else {
      KDownLogger.i("Coordinator") {
        "Server does not support range requests or single connection requested. Using 1 connection, totalBytes=$totalBytes"
      }
      SegmentCalculator.singleSegment(totalBytes)
    }

    val now = currentTimeMillis()
    val metadata = DownloadMetadata(
      taskId = request.taskId,
      url = request.url,
      destPath = request.destPath,
      totalBytes = totalBytes,
      acceptRanges = serverInfo.acceptRanges,
      etag = serverInfo.etag,
      lastModified = serverInfo.lastModified,
      segments = segments,
      headers = request.headers,
      createdAt = now,
      updatedAt = now
    )

    val fileAccessor = fileAccessorFactory(request.destPath)

    mutex.withLock {
      activeDownloads[request.taskId]?.let {
        it.metadata = metadata
        it.fileAccessor = fileAccessor
      }
    }

    try {
      KDownLogger.d("Coordinator") { "Preallocating $totalBytes bytes for taskId=${request.taskId}" }
      fileAccessor.preallocate(totalBytes)
      KDownLogger.d("Coordinator") { "Saving metadata for taskId=${request.taskId}" }
      metadataStore.save(request.taskId, metadata)

      downloadWithRetry(request.taskId, metadata, fileAccessor, stateFlow)

      fileAccessor.flush()
      metadataStore.clear(request.taskId)
      KDownLogger.i("Coordinator") { "Download completed successfully for taskId=${request.taskId}" }
      stateFlow.value = DownloadState.Completed(request.destPath)
    } finally {
      fileAccessor.close()
      mutex.withLock {
        activeDownloads.remove(request.taskId)
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
          KDownLogger.e("Coordinator") { "Download failed after $retryCount retries: ${error.message}" }
          throw error
        }

        retryCount++
        val delayMs = config.retryDelayMs * (1 shl (retryCount - 1))
        KDownLogger.w("Coordinator") { "Retry attempt $retryCount after ${delayMs}ms delay: ${error.message}" }
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
    val segmentProgress = metadata.segments.map { it.downloadedBytes }.toMutableList()
    val segmentMutex = Mutex()

    var lastProgressUpdate = currentTimeMillis()
    val progressMutex = Mutex()

    suspend fun updateProgress() {
      val now = currentTimeMillis()
      progressMutex.withLock {
        if (now - lastProgressUpdate >= config.progressUpdateIntervalMs) {
          val downloaded = segmentMutex.withLock { segmentProgress.sum() }
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
          downloader.download(metadata.url, segment, metadata.headers) { bytesDownloaded ->
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
      KDownLogger.i("Coordinator") { "Pausing download for taskId=$taskId" }
      active.job.cancel()

      active.metadata?.let { metadata ->
        KDownLogger.d("Coordinator") { "Saving pause state for taskId=$taskId" }
        metadataStore.save(taskId, metadata.copy(updatedAt = currentTimeMillis()))
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

    val job = scope.launch {
      try {
        resumeDownload(taskId, metadata, stateFlow)
      } catch (e: CancellationException) {
        stateFlow.value = DownloadState.Canceled
        throw e
      } catch (e: Exception) {
        val error = when (e) {
          is KDownError -> e
          else -> KDownError.Unknown(e)
        }
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
    KDownLogger.i("Coordinator") { "Resuming download for taskId=$taskId, url=${metadata.url}" }
    KDownLogger.d("Coordinator") { "Validating server state for resume" }
    val detector = RangeSupportDetector(httpEngine)
    val serverInfo = detector.detect(metadata.url, metadata.headers)

    if (metadata.etag != null && serverInfo.etag != metadata.etag) {
      KDownLogger.w("Coordinator") { "ETag mismatch - file has changed on server" }
      throw KDownError.ValidationFailed("ETag mismatch - file has changed on server")
    }

    if (metadata.lastModified != null && serverInfo.lastModified != metadata.lastModified) {
      KDownLogger.w("Coordinator") { "Last-Modified mismatch - file has changed on server" }
      throw KDownError.ValidationFailed("Last-Modified mismatch - file has changed on server")
    }
    KDownLogger.d("Coordinator") { "Server validation passed, continuing resume" }

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
      stateFlow.value = DownloadState.Completed(metadata.destPath)
    } finally {
      fileAccessor.close()
      mutex.withLock {
        activeDownloads.remove(taskId)
      }
    }
  }

  suspend fun cancel(taskId: String) {
    KDownLogger.i("Coordinator") { "Canceling download for taskId=$taskId" }
    mutex.withLock {
      val active = activeDownloads[taskId]
      active?.job?.cancel()
      active?.stateFlow?.value = DownloadState.Canceled
      activeDownloads.remove(taskId)
    }
    metadataStore.clear(taskId)
  }

  suspend fun getState(taskId: String): DownloadState? {
    return mutex.withLock {
      activeDownloads[taskId]?.stateFlow?.value
    }
  }
}
