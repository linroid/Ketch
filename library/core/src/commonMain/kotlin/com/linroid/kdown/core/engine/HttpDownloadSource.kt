package com.linroid.kdown.core.engine

import com.linroid.kdown.api.KDownError
import com.linroid.kdown.api.ResolvedSource
import com.linroid.kdown.api.Segment
import com.linroid.kdown.core.file.DefaultFileNameResolver
import com.linroid.kdown.core.file.FileNameResolver
import com.linroid.kdown.core.log.KDownLogger
import com.linroid.kdown.core.segment.SegmentCalculator
import com.linroid.kdown.core.segment.SegmentDownloader
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlin.time.Clock
import kotlin.time.Duration.Companion.milliseconds

/**
 * HTTP/HTTPS download source using the existing [HttpEngine] pipeline.
 *
 * Encapsulates range detection, segment calculation, and parallel
 * segment downloads. This is the default source used for all
 * HTTP/HTTPS URLs.
 */
internal class HttpDownloadSource(
  private val httpEngine: HttpEngine,
  private val fileNameResolver: FileNameResolver,
  private val maxConnections: Int = 4,
  private val progressUpdateIntervalMs: Long = 200,
  private val segmentSaveIntervalMs: Long = 5000,
) : DownloadSource {

  override val type: String = TYPE

  override fun canHandle(url: String): Boolean {
    val lower = url.lowercase()
    return lower.startsWith("http://") || lower.startsWith("https://")
  }

  override suspend fun resolve(
    url: String,
    headers: Map<String, String>,
  ): ResolvedSource {
    val detector = RangeSupportDetector(httpEngine)
    val serverInfo = detector.detect(url, headers)
    val fileName = serverInfo.contentDisposition?.let {
      extractDispositionFileName(it)
    } ?: DefaultFileNameResolver.fromUrl(url)
    return ResolvedSource(
      url = url,
      sourceType = TYPE,
      totalBytes = serverInfo.contentLength ?: -1,
      supportsResume = serverInfo.supportsResume,
      suggestedFileName = fileName,
      maxSegments = if (serverInfo.supportsResume) maxConnections else 1,
      metadata = buildMap {
        serverInfo.etag?.let { put(META_ETAG, it) }
        serverInfo.lastModified?.let { put(META_LAST_MODIFIED, it) }
        if (serverInfo.acceptRanges) put(META_ACCEPT_RANGES, "true")
      },
    )
  }

  override suspend fun download(context: DownloadContext) {
    val resolved = context.preResolved
      ?: resolve(context.url, context.headers)
    val totalBytes = resolved.totalBytes
    if (totalBytes < 0) throw KDownError.Unsupported

    val segments = if (
      resolved.supportsResume && context.request.connections > 1
    ) {
      KDownLogger.i("HttpSource") {
        "Server supports ranges. Using ${context.request.connections} " +
          "connections, totalBytes=$totalBytes"
      }
      SegmentCalculator.calculateSegments(
        totalBytes, context.request.connections
      )
    } else {
      KDownLogger.i("HttpSource") {
        "Single connection, totalBytes=$totalBytes"
      }
      SegmentCalculator.singleSegment(totalBytes)
    }

    context.segments.value = segments

    try {
      context.fileAccessor.preallocate(totalBytes)
    } catch (e: Exception) {
      if (e is CancellationException) throw e
      if (e is KDownError) throw e
      throw KDownError.Disk(e)
    }

    downloadSegments(context, segments, totalBytes)
  }

  override suspend fun resume(
    context: DownloadContext,
    resumeState: SourceResumeState,
  ) {
    val state = Json.decodeFromString<HttpResumeState>(resumeState.data)

    KDownLogger.i("HttpSource") {
      "Resuming download for taskId=${context.taskId}"
    }

    val detector = RangeSupportDetector(httpEngine)
    val serverInfo = detector.detect(context.url, context.headers)

    if (state.etag != null && serverInfo.etag != state.etag) {
      KDownLogger.w("HttpSource") {
        "ETag mismatch - file has changed on server"
      }
      throw KDownError.ValidationFailed(
        "ETag mismatch - file has changed on server"
      )
    }

    if (state.lastModified != null &&
      serverInfo.lastModified != state.lastModified
    ) {
      KDownLogger.w("HttpSource") {
        "Last-Modified mismatch - file has changed on server"
      }
      throw KDownError.ValidationFailed(
        "Last-Modified mismatch - file has changed on server"
      )
    }

    val segments = context.segments.value
    val totalBytes = state.totalBytes

    val validatedSegments = validateLocalFile(
      context, segments, totalBytes
    )

    if (validatedSegments !== segments) {
      context.segments.value = validatedSegments
    }

    downloadSegments(context, validatedSegments, totalBytes)
  }

  private suspend fun validateLocalFile(
    context: DownloadContext,
    segments: List<Segment>,
    totalBytes: Long,
  ): List<Segment> {
    val fileSize = try {
      context.fileAccessor.size()
    } catch (e: Exception) {
      if (e is CancellationException) throw e
      KDownLogger.w("HttpSource") {
        "Cannot read file size for taskId=${context.taskId}, " +
          "resetting segments"
      }
      0L
    }

    val claimedProgress = segments.sumOf { it.downloadedBytes }
    if (fileSize < claimedProgress || fileSize < totalBytes) {
      KDownLogger.w("HttpSource") {
        "Local file integrity check failed for " +
          "taskId=${context.taskId}: fileSize=$fileSize, " +
          "claimedProgress=$claimedProgress, totalBytes=$totalBytes. " +
          "Resetting segments."
      }
      try {
        context.fileAccessor.preallocate(totalBytes)
      } catch (e: Exception) {
        if (e is CancellationException) throw e
        if (e is KDownError) throw e
        throw KDownError.Disk(e)
      }
      return segments.map { it.copy(downloadedBytes = 0) }
    }
    KDownLogger.d("HttpSource") {
      "Local file integrity check passed for " +
        "taskId=${context.taskId}: fileSize=$fileSize, " +
        "claimedProgress=$claimedProgress"
    }
    return segments
  }

  private suspend fun downloadSegments(
    context: DownloadContext,
    segments: List<Segment>,
    totalBytes: Long,
  ) {
    val segmentProgress =
      segments.map { it.downloadedBytes }.toMutableList()
    val segmentMutex = Mutex()
    val updatedSegments = segments.toMutableList()

    var lastProgressUpdate = Clock.System.now()
    val progressMutex = Mutex()

    val incompleteSegments = segments.filter { !it.isComplete }

    suspend fun currentSegments(): List<Segment> {
      return segmentMutex.withLock {
        updatedSegments.mapIndexed { i, seg ->
          seg.copy(downloadedBytes = segmentProgress[i])
        }
      }
    }

    suspend fun updateProgress() {
      val now = Clock.System.now()
      progressMutex.withLock {
        if (now - lastProgressUpdate >=
          progressUpdateIntervalMs.milliseconds
        ) {
          val snapshot = currentSegments()
          val downloaded = snapshot.sumOf { it.downloadedBytes }
          context.onProgress(downloaded, totalBytes)
          context.segments.value = snapshot
          lastProgressUpdate = now
        }
      }
    }

    val downloadedBytes = segments.sumOf { it.downloadedBytes }
    context.onProgress(downloadedBytes, totalBytes)
    context.segments.value = segments

    coroutineScope {
      val saveJob = launch {
        while (true) {
          delay(segmentSaveIntervalMs)
          context.segments.value = currentSegments()
          KDownLogger.v("HttpSource") {
            "Periodic segment save for taskId=${context.taskId}"
          }
        }
      }

      try {
        val results = incompleteSegments.map { segment ->
          async {
            val throttleLimiter = object : SpeedLimiter {
              override suspend fun acquire(bytes: Int) {
                context.throttle(bytes)
              }
            }
            val downloader = SegmentDownloader(
              httpEngine, context.fileAccessor,
              throttleLimiter, SpeedLimiter.Unlimited
            )
            val completed = downloader.download(
              context.url, segment, context.headers
            ) { bytesDownloaded ->
              segmentMutex.withLock {
                segmentProgress[segment.index] = bytesDownloaded
              }
              updateProgress()
            }
            segmentMutex.withLock {
              updatedSegments[completed.index] = completed
            }
            context.segments.value = currentSegments()
            KDownLogger.d("HttpSource") {
              "Segment ${completed.index} completed for " +
                "taskId=${context.taskId}"
            }
            completed
          }
        }
        results.awaitAll()
      } finally {
        saveJob.cancel()
      }

      context.segments.value = currentSegments()
    }

    val finalSegments = currentSegments()
    context.segments.value = finalSegments
    context.onProgress(totalBytes, totalBytes)
  }

  private fun extractDispositionFileName(
    contentDisposition: String,
  ): String? {
    val regex = Regex("""filename\*?=(?:UTF-8''|"?)([^";]+)"?""")
    return regex.find(contentDisposition)?.groupValues?.get(1)
  }

  companion object {
    const val TYPE = "http"
    internal const val META_ETAG = "etag"
    internal const val META_LAST_MODIFIED = "lastModified"
    internal const val META_ACCEPT_RANGES = "acceptRanges"

    fun buildResumeState(
      etag: String?,
      lastModified: String?,
      totalBytes: Long,
    ): SourceResumeState {
      val state = HttpResumeState(
        etag = etag,
        lastModified = lastModified,
        totalBytes = totalBytes,
      )
      return SourceResumeState(
        sourceType = TYPE,
        data = Json.encodeToString(state),
      )
    }
  }

  @Serializable
  internal data class HttpResumeState(
    val etag: String?,
    val lastModified: String?,
    val totalBytes: Long,
  )
}
