package com.linroid.kdown.internal

import com.linroid.kdown.DownloadConfig
import com.linroid.kdown.DownloadRequest
import com.linroid.kdown.FileAccessor
import com.linroid.kdown.HttpEngine
import com.linroid.kdown.MetadataStore
import com.linroid.kdown.error.KDownError
import com.linroid.kdown.model.DownloadMetadata
import com.linroid.kdown.model.DownloadProgress
import com.linroid.kdown.model.DownloadState
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

internal class DownloadCoordinator(
  private val httpEngine: HttpEngine,
  private val metadataStore: MetadataStore,
  private val config: DownloadConfig,
  private val fileAccessorFactory: (String) -> FileAccessor
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
    val detector = RangeSupportDetector(httpEngine)
    val serverInfo = detector.detect(request.url)

    val totalBytes = serverInfo.contentLength
      ?: throw KDownError.Unsupported

    val segments = if (serverInfo.supportsResume && request.connections > 1) {
      SegmentCalculator.calculateSegments(totalBytes, request.connections)
    } else {
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
      fileAccessor.preallocate(totalBytes)
      metadataStore.save(request.taskId, metadata)

      downloadWithRetry(request.taskId, metadata, fileAccessor, stateFlow)

      fileAccessor.flush()
      metadataStore.clear(request.taskId)
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
          throw error
        }

        retryCount++
        val delayMs = config.retryDelayMs * (1 shl (retryCount - 1))
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
          downloader.download(metadata.url, segment) { bytesDownloaded ->
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
      active.job.cancel()

      active.metadata?.let { metadata ->
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
    val detector = RangeSupportDetector(httpEngine)
    val serverInfo = detector.detect(metadata.url)

    if (metadata.etag != null && serverInfo.etag != metadata.etag) {
      throw KDownError.ValidationFailed("ETag mismatch - file has changed on server")
    }

    if (metadata.lastModified != null && serverInfo.lastModified != metadata.lastModified) {
      throw KDownError.ValidationFailed("Last-Modified mismatch - file has changed on server")
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
      stateFlow.value = DownloadState.Completed(metadata.destPath)
    } finally {
      fileAccessor.close()
      mutex.withLock {
        activeDownloads.remove(taskId)
      }
    }
  }

  suspend fun cancel(taskId: String) {
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
