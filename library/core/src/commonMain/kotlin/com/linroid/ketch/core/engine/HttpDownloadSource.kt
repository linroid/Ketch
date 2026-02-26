package com.linroid.ketch.core.engine

import com.linroid.ketch.api.KetchError
import com.linroid.ketch.api.ResolvedSource
import com.linroid.ketch.api.Segment
import com.linroid.ketch.core.file.DefaultFileNameResolver
import com.linroid.ketch.api.log.KetchLogger
import com.linroid.ketch.core.segment.SegmentCalculator
import com.linroid.ketch.core.segment.SegmentDownloader
import com.linroid.ketch.core.segment.SegmentedDownloadHelper
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * HTTP/HTTPS download source using the existing [HttpEngine] pipeline.
 *
 * Encapsulates range detection, segment calculation, and parallel
 * segment downloads. This is the default source used for all
 * HTTP/HTTPS URLs.
 */
internal class HttpDownloadSource(
  private val httpEngine: HttpEngine,
  private val maxConnections: Int = 4,
  private val progressIntervalMs: Long = 200,
) : DownloadSource {
  private val log = KetchLogger("HttpSource")

  override val type: String = TYPE

  override fun canHandle(url: String): Boolean {
    val lower = url.lowercase()
    return lower.startsWith("http://") || lower.startsWith("https://")
  }

  override suspend fun resolve(
    url: String,
    properties: Map<String, String>,
  ): ResolvedSource {
    val detector = RangeSupportDetector(httpEngine)
    val serverInfo = detector.detect(url, properties)
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
        serverInfo.contentDisposition?.let {
          put(META_CONTENT_DISPOSITION, it)
        }
        serverInfo.rateLimitRemaining?.let {
          put(META_RATE_LIMIT_REMAINING, it.toString())
        }
        serverInfo.rateLimitReset?.let {
          put(META_RATE_LIMIT_RESET, it.toString())
        }
      },
    )
  }

  override suspend fun download(context: DownloadContext) {
    val resolved = context.preResolved
      ?: resolve(context.url, context.headers)
    val totalBytes = resolved.totalBytes
    if (totalBytes < 0) throw KetchError.Unsupported()

    val remaining = resolved.metadata[META_RATE_LIMIT_REMAINING]
      ?.toLongOrNull()
    val reset = resolved.metadata[META_RATE_LIMIT_RESET]
      ?.toLongOrNull()
    val connections = applyRateLimit(
      effectiveConnections(context), remaining, reset,
    )

    // Reuse existing segments with progress on retry (e.g., after
    // HTTP 429) instead of recalculating from scratch.
    val existing = context.segments.value
    val segments = if (
      existing.isNotEmpty() &&
      existing.any { it.downloadedBytes > 0 }
    ) {
      log.i {
        "Reusing segments with existing progress, " +
          "resegmenting to $connections connections"
      }
      SegmentCalculator.resegment(existing, connections)
    } else if (resolved.supportsResume && connections > 1) {
      log.i {
        "Server supports ranges. Using $connections " +
          "connections, totalBytes=$totalBytes"
      }
      SegmentCalculator.calculateSegments(totalBytes, connections)
    } else {
      log.i { "Single connection, totalBytes=$totalBytes" }
      SegmentCalculator.singleSegment(totalBytes)
    }

    context.segments.value = segments

    if (existing.isEmpty()) {
      try {
        context.fileAccessor.preallocate(totalBytes)
      } catch (e: Exception) {
        if (e is CancellationException) throw e
        if (e is KetchError) throw e
        throw KetchError.Disk(e)
      }
    }

    downloadSegments(context, segments, totalBytes)
  }

  override suspend fun resume(
    context: DownloadContext,
    resumeState: SourceResumeState,
  ) {
    val state = try {
      Json.decodeFromString<HttpResumeState>(resumeState.data)
    } catch (e: Exception) {
      if (e is CancellationException) throw e
      throw KetchError.CorruptResumeState(e.message, e)
    }

    log.i { "Resuming download for taskId=${context.taskId}" }

    val detector = RangeSupportDetector(httpEngine)
    val serverInfo = detector.detect(context.url, context.headers)

    if (state.etag != null && serverInfo.etag != state.etag) {
      log.w { "ETag mismatch - file has changed on server" }
      throw KetchError.FileChanged(
        "ETag mismatch - file has changed on server",
      )
    }

    if (state.lastModified != null &&
      serverInfo.lastModified != state.lastModified
    ) {
      log.w { "Last-Modified mismatch - file has changed on server" }
      throw KetchError.FileChanged(
        "Last-Modified mismatch - file has changed on server",
      )
    }

    var segments = context.segments.value
    val totalBytes = state.totalBytes

    val connections = applyRateLimit(
      effectiveConnections(context),
      serverInfo.rateLimitRemaining,
      serverInfo.rateLimitReset,
    )
    val incompleteCount = segments.count { !it.isComplete }
    if (incompleteCount > 0 && connections != incompleteCount) {
      log.i {
        "Resegmenting for taskId=${context.taskId}: " +
          "$incompleteCount -> $connections connections"
      }
      segments = SegmentCalculator.resegment(
        segments, connections,
      )
      context.segments.value = segments
    }

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
      log.w {
        "Cannot read file size for taskId=${context.taskId}, " +
          "resetting segments"
      }
      0L
    }

    val claimedProgress = segments.sumOf { it.downloadedBytes }
    if (fileSize < claimedProgress || fileSize < totalBytes) {
      log.w {
        "Local file integrity check failed for " +
          "taskId=${context.taskId}: fileSize=$fileSize, " +
          "claimedProgress=$claimedProgress, totalBytes=$totalBytes. " +
          "Resetting segments."
      }
      try {
        context.fileAccessor.preallocate(totalBytes)
      } catch (e: Exception) {
        if (e is CancellationException) throw e
        if (e is KetchError) throw e
        throw KetchError.Disk(e)
      }
      return segments.map { it.copy(downloadedBytes = 0) }
    }
    log.d {
      "Local file integrity check passed for " +
        "taskId=${context.taskId}: fileSize=$fileSize, " +
        "claimedProgress=$claimedProgress"
    }
    return segments
  }

  private val segmentHelper = SegmentedDownloadHelper(
    progressIntervalMs = progressIntervalMs,
    tag = "HttpSource",
  )

  /**
   * Downloads segments via HTTP Range requests with dynamic
   * resegmentation support. Delegates the concurrent batch loop
   * to [SegmentedDownloadHelper].
   */
  private suspend fun downloadSegments(
    context: DownloadContext,
    segments: List<Segment>,
    totalBytes: Long,
  ) {
    segmentHelper.downloadAll(
      context, segments, totalBytes,
    ) { segment, onProgress ->
      val throttleLimiter = object : SpeedLimiter {
        override suspend fun acquire(bytes: Int) {
          context.throttle(bytes)
        }
      }
      val downloader = SegmentDownloader(
        httpEngine, context.fileAccessor,
        throttleLimiter, SpeedLimiter.Unlimited,
      )
      downloader.download(
        context.url, segment, context.headers, onProgress,
      )
    }
  }

  /**
   * Returns the number of connections to use, honoring
   * [DownloadContext.maxConnections] override (set on rate-limit
   * retries or dynamic adjustment), then
   * [DownloadRequest.connections], then the engine-level
   * [maxConnections] default.
   */
  private fun effectiveConnections(context: DownloadContext): Int {
    return when {
      context.maxConnections.value > 0 ->
        context.maxConnections.value
      context.request.connections > 0 -> context.request.connections
      else -> maxConnections
    }
  }

  /**
   * Applies proactive rate limit capping based on server-reported
   * `RateLimit-Remaining` and `RateLimit-Reset` headers.
   *
   * - If `remaining > 0` and less than [connections], caps to
   *   `remaining` (minimum 1).
   * - If `remaining == 0` and `reset > 0`, delays until the rate
   *   limit window resets, then returns [connections] unchanged.
   * - If `remaining == 0` and reset is unknown, delays 1 second
   *   as a conservative fallback.
   */
  private suspend fun applyRateLimit(
    connections: Int,
    remaining: Long?,
    reset: Long?,
  ): Int {
    if (remaining == null) return connections
    if (remaining == 0L) {
      val delaySec = reset?.coerceAtLeast(1) ?: 1L
      log.i {
        "Rate limit exhausted (remaining=0), " +
          "delaying ${delaySec}s before download"
      }
      delay(delaySec * 1000)
      return connections
    }
    if (remaining < connections) {
      val capped = remaining.toInt().coerceAtLeast(1)
      log.i {
        "Capping connections from $connections to $capped " +
          "based on RateLimit-Remaining=$remaining"
      }
      return capped
    }
    return connections
  }

  override fun buildResumeState(
    resolved: ResolvedSource,
    totalBytes: Long,
  ): SourceResumeState {
    return buildResumeState(
      etag = resolved.metadata[META_ETAG],
      lastModified = resolved.metadata[META_LAST_MODIFIED],
      totalBytes = totalBytes,
    )
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
    internal const val META_CONTENT_DISPOSITION = "contentDisposition"
    internal const val META_RATE_LIMIT_REMAINING = "rateLimitRemaining"
    internal const val META_RATE_LIMIT_RESET = "rateLimitReset"

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
