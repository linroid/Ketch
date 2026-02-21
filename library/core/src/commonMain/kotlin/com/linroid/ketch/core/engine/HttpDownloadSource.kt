package com.linroid.ketch.core.engine

import com.linroid.ketch.api.KetchError
import com.linroid.ketch.api.ResolvedSource
import com.linroid.ketch.api.Segment
import com.linroid.ketch.api.DownloadRequest
import com.linroid.ketch.core.file.DefaultFileNameResolver
import com.linroid.ketch.api.log.KetchLogger
import com.linroid.ketch.core.segment.SegmentCalculator
import com.linroid.ketch.core.segment.SegmentDownloader
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
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
  private val maxConnections: Int = 4,
  private val progressUpdateIntervalMs: Long = 200,
  private val segmentSaveIntervalMs: Long = 5000,
) : DownloadSource {
  private val log = KetchLogger("HttpSource")

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
    if (totalBytes < 0) throw KetchError.Unsupported

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
    val state = Json.decodeFromString<HttpResumeState>(resumeState.data)

    log.i { "Resuming download for taskId=${context.taskId}" }

    val detector = RangeSupportDetector(httpEngine)
    val serverInfo = detector.detect(context.url, context.headers)

    if (state.etag != null && serverInfo.etag != state.etag) {
      log.w { "ETag mismatch - file has changed on server" }
      throw KetchError.ValidationFailed(
        "ETag mismatch - file has changed on server"
      )
    }

    if (state.lastModified != null &&
      serverInfo.lastModified != state.lastModified
    ) {
      log.w { "Last-Modified mismatch - file has changed on server" }
      throw KetchError.ValidationFailed(
        "Last-Modified mismatch - file has changed on server"
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

  /**
   * Downloads segments with dynamic resegmentation support.
   *
   * Uses a while loop that repeatedly calls [downloadBatch] for the
   * current set of incomplete segments. When the connection count
   * changes (via [DownloadContext.maxConnections]), the batch is
   * canceled, progress is snapshotted, segments are merged/split
   * via [SegmentCalculator.resegment], and a new batch starts.
   */
  private suspend fun downloadSegments(
    context: DownloadContext,
    segments: List<Segment>,
    totalBytes: Long,
  ) {
    var currentSegments = segments

    while (true) {
      val incomplete = currentSegments.filter { !it.isComplete }
      if (incomplete.isEmpty()) break

      val batchCompleted = downloadBatch(
        context, currentSegments, incomplete, totalBytes
      )

      if (batchCompleted) break

      // Resegment with the new connection count
      val newCount = context.pendingResegment
      context.pendingResegment = 0
      currentSegments = SegmentCalculator.resegment(
        context.segments.value, newCount
      )
      context.segments.value = currentSegments
      log.i {
        "Resegmented to $newCount connections for " +
          "taskId=${context.taskId}"
      }
    }

    context.segments.value = currentSegments
    context.onProgress(totalBytes, totalBytes)
  }

  /**
   * Downloads one batch of incomplete segments concurrently.
   *
   * A watcher coroutine monitors [DownloadContext.maxConnections]
   * for changes. When the connection count changes, it sets
   * [DownloadContext.pendingResegment] and cancels the scope,
   * causing all segment coroutines to stop. Progress is
   * snapshotted before returning.
   *
   * @return `true` if all segments completed, `false` if
   *   interrupted for resegmentation
   */
  private suspend fun downloadBatch(
    context: DownloadContext,
    allSegments: List<Segment>,
    incompleteSegments: List<Segment>,
    totalBytes: Long,
  ): Boolean {
    val segmentProgress =
      allSegments.map { it.downloadedBytes }.toMutableList()
    val segmentMutex = Mutex()
    val updatedSegments = allSegments.toMutableList()

    var lastProgressUpdate = Clock.System.now()
    val progressMutex = Mutex()

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

    val downloadedBytes = allSegments.sumOf { it.downloadedBytes }
    context.onProgress(downloadedBytes, totalBytes)
    context.segments.value = allSegments

    return try {
      coroutineScope {
        val saveJob = launch {
          while (true) {
            delay(segmentSaveIntervalMs)
            context.segments.value = currentSegments()
            log.v { "Periodic segment save for taskId=${context.taskId}" }
          }
        }

        // Watcher: detect connection count changes and trigger
        // resegmentation by canceling the scope. Compares against the
        // last-seen flow value (not segment count) to avoid an infinite
        // loop when fewer segments can be created than requested.
        val watcherJob = launch {
          val lastSeen = context.maxConnections.value
          context.maxConnections.first { count ->
            count > 0 && count != lastSeen
          }
          context.pendingResegment =
            context.maxConnections.value
          log.i {
            "Connection change detected for " +
              "taskId=${context.taskId}: " +
              "$lastSeen -> ${context.pendingResegment}"
          }
          throw CancellationException("Resegmenting")
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
                  segmentProgress[segment.index] =
                    bytesDownloaded
                }
                updateProgress()
              }
              segmentMutex.withLock {
                updatedSegments[completed.index] = completed
              }
              context.segments.value = currentSegments()
              log.d {
                "Segment ${completed.index} completed for " +
                  "taskId=${context.taskId}"
              }
              completed
            }
          }
          results.awaitAll()
        } finally {
          watcherJob.cancel()
          saveJob.cancel()
        }

        context.segments.value = currentSegments()
        true // All segments completed
      }
    } catch (e: CancellationException) {
      if (context.pendingResegment > 0) {
        // Snapshot progress before resegmentation
        withContext(NonCancellable) {
          context.segments.value = currentSegments()
        }
        false // Signal outer loop to resegment
      } else {
        throw e // External cancellation — propagate
      }
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
