package com.linroid.ketch.core.engine

import com.linroid.ketch.api.DownloadConfig
import com.linroid.ketch.api.KetchError
import com.linroid.ketch.api.ResolveInput
import com.linroid.ketch.api.ResolvedSource
import com.linroid.ketch.api.Segment
import com.linroid.ketch.core.file.DefaultFileNameResolver
import com.linroid.ketch.api.log.KetchLogger
import com.linroid.ketch.core.segment.SegmentCalculator
import com.linroid.ketch.core.segment.SegmentDownloader
import com.linroid.ketch.core.segment.SegmentedDownloadHelper
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.TimeSource

/**
 * HTTP/HTTPS download source using the existing [HttpEngine] pipeline.
 *
 * Encapsulates range detection, segment calculation, and parallel
 * segment downloads. This is the default source used for all
 * HTTP/HTTPS URLs. Connection count and progress interval defaults come
 * from [DownloadContext.config], so global configuration changes apply
 * to downloads started or resumed afterwards. Servers without byte-range
 * support use a single connection, and a retry or resume restarts it
 * from byte zero. Content whose size the server does not report, such as
 * an archive generated on request, is streamed to its end the same way.
 * Requests go through [DownloadConfig.proxy] ([HttpEngine.withProxy]).
 */
internal class HttpDownloadSource(
  private val httpEngine: HttpEngine,
) : DownloadSource<HttpSourceState> {
  private val log = KetchLogger("HttpSource")

  override val type: String = TYPE

  override val stateSerializer: KSerializer<HttpSourceState> = HttpSourceState.serializer()

  override fun canHandle(input: ResolveInput): Boolean {
    val url = (input as? ResolveInput.Url)?.url ?: return false
    val lower = url.lowercase()
    return lower.startsWith("http://") || lower.startsWith("https://")
  }

  override suspend fun resolve(input: ResolveInput, config: DownloadConfig): ResolvedSource {
    val (url, headers) = input as ResolveInput.Url
    val engine = httpEngine.through(config.proxy)
    val detector = RangeSupportDetector(engine, TransferWatchdog.of(config))
    val serverInfo = detector.detect(url, headers)
    val fileName = serverInfo.contentDisposition?.let {
      DefaultFileNameResolver.fromContentDisposition(it)
    } ?: DefaultFileNameResolver.fromUrls(url, serverInfo.url)
    return ResolvedSource(
      url = url,
      sourceType = TYPE,
      totalBytes = serverInfo.contentLength ?: -1,
      supportsResume = serverInfo.supportsResume,
      suggestedFileName = fileName,
      maxSegments = if (serverInfo.supportsResume) config.maxConnectionsPerDownload else 1,
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
      contentType = serverInfo.contentType,
      checksum = serverInfo.checksum,
    )
  }

  override suspend fun download(context: DownloadContext, state: HttpSourceState?) {
    if (state == null) downloadFresh(context) else continueFrom(context, state)
  }

  private suspend fun downloadFresh(context: DownloadContext) {
    val resolved = context.preResolved
      ?: resolve(context.url, context.headers, context.config)
        .also { context.publishedChecksum = it.checksum }
    val totalBytes = resolved.totalBytes
    if (totalBytes < 0) {
      downloadUnknownSize(context)
      return
    }

    val remaining = resolved.metadata[META_RATE_LIMIT_REMAINING]
      ?.toLongOrNull()
    val reset = resolved.metadata[META_RATE_LIMIT_RESET]
      ?.toLongOrNull()
    // The segments are sized from this value; downloadSegments resegments for any later change.
    val requested = context.maxConnections.value
    val connections = applyRateLimit(
      context.taskId,
      rangeLimitedConnections(context, requested, resolved.supportsResume),
      remaining,
      reset
    )

    // Reuse existing segments with progress on retry (e.g., after
    // HTTP 429) instead of recalculating from scratch. Without range
    // support the transfer can only restart at byte zero.
    val existing = context.segments.value
    val segments = if (
      resolved.supportsResume &&
      existing.any { it.downloadedBytes > 0 }
    ) {
      log.i {
        "Reusing segments with existing progress for taskId=${context.taskId}, " +
          "resegmenting to $connections connections"
      }
      SegmentCalculator.resegment(existing, connections)
    } else if (connections > 1) {
      log.i {
        "Server supports ranges. Using $connections connections for " +
          "taskId=${context.taskId}, totalBytes=$totalBytes"
      }
      SegmentCalculator.calculateSegments(totalBytes, connections)
    } else {
      log.i { "Single connection for taskId=${context.taskId}, totalBytes=$totalBytes" }
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

    downloadRanges(
      context, segments, totalBytes, resolved.supportsResume, requested,
      quotaLimited = remaining != null,
      validator = resolved.metadata.let { rangeValidator(it[META_ETAG], it[META_LAST_MODIFIED]) },
    )
  }

  /** Continues from [context]'s segments once the server's identity still matches [state]. */
  private suspend fun continueFrom(context: DownloadContext, state: HttpSourceState) {
    log.i { "Resuming download for taskId=${context.taskId}" }
    if (state.totalBytes < 0) {
      // Bytes written without a known size cannot be continued, so a changed file is no concern.
      downloadUnknownSize(context)
      return
    }

    val detector = RangeSupportDetector(httpEngine.through(context.config.proxy), context.watchdog)
    val serverInfo = detector.detect(context.url, context.headers)
    context.publishedChecksum = serverInfo.checksum

    if (state.etag != null && serverInfo.etag != state.etag) {
      log.w {
        "ETag mismatch for taskId=${context.taskId} - file has changed on server: " +
          "saved=${state.etag}, server=${serverInfo.etag}"
      }
      throw KetchError.FileChanged(
        "ETag mismatch - file has changed on server",
      )
    }

    if (state.lastModified != null &&
      serverInfo.lastModified != state.lastModified
    ) {
      log.w {
        "Last-Modified mismatch for taskId=${context.taskId} - file has changed on server: " +
          "saved=${state.lastModified}, server=${serverInfo.lastModified}"
      }
      throw KetchError.FileChanged(
        "Last-Modified mismatch - file has changed on server",
      )
    }

    var segments = context.segments.value
    val totalBytes = state.totalBytes

    val requested = context.maxConnections.value
    val connections = applyRateLimit(
      context.taskId,
      rangeLimitedConnections(context, requested, serverInfo.supportsResume),
      serverInfo.rateLimitRemaining,
      serverInfo.rateLimitReset
    )
    val incompleteCount = segments.count { !it.isComplete }
    if (segments.isEmpty() && totalBytes > 0) {
      // Stopped before any segments were saved: nothing was downloaded, whatever the file size.
      log.w { "No saved segments for taskId=${context.taskId}, restarting from zero" }
      segments = SegmentCalculator.calculateSegments(totalBytes, connections)
      context.segments.value = segments
    } else if (incompleteCount > 0 && !serverInfo.supportsResume) {
      // Without range support every transfer starts at byte zero, so saved progress cannot be used.
      log.w { "Server does not support ranges, restarting taskId=${context.taskId} from zero" }
      segments = SegmentCalculator.singleSegment(totalBytes)
      context.segments.value = segments
    } else if (incompleteCount > 0 && connections != incompleteCount) {
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

    downloadRanges(
      context, validatedSegments, totalBytes, serverInfo.supportsResume, requested,
      quotaLimited = serverInfo.rateLimitRemaining != null,
      validator = rangeValidator(state.etag, state.lastModified),
    )
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
   * [downloadSegments], restarting over one connection from byte zero once the server answers a
   * range with the whole resource ([RangeIgnoredException]), whatever its `Accept-Ranges` said.
   *
   * Ranges ask `If-Range` with [validator], the file's ETag or modification time as the download
   * started, so a file replaced while it downloads comes back whole instead of as parts of two
   * files: the restart then saves the new file, unless its size changed too, which fails the
   * download with [KetchError.FileChanged].
   */
  private suspend fun downloadRanges(
    context: DownloadContext,
    segments: List<Segment>,
    totalBytes: Long,
    supportsRanges: Boolean,
    requestedConnections: Int,
    quotaLimited: Boolean,
    validator: String?,
  ) {
    val headers = if (supportsRanges && validator != null) {
      context.headers + (IF_RANGE to validator)
    } else {
      context.headers
    }
    try {
      downloadSegments(
        context, segments, totalBytes, supportsRanges, requestedConnections, quotaLimited,
        headers,
      )
    } catch (e: KetchError.Unsupported) {
      if (!supportsRanges || e.cause !is RangeIgnoredException) throw e
      log.w {
        "Server ignored ranges for taskId=${context.taskId}, restarting from zero " +
          "over one connection"
      }
      val whole = SegmentCalculator.singleSegment(totalBytes)
      context.segments.value = whole
      try {
        downloadSegments(
          context, whole, totalBytes, supportsRanges = false, requestedConnections, quotaLimited,
          headers,
        )
      } catch (again: KetchError.Unsupported) {
        // The whole file, of another size than when the download started.
        if (headers === context.headers || again.cause !is RangeIgnoredException) throw again
        throw KetchError.FileChanged("The file changed on the server while it downloaded", again)
      }
    }
  }

  /** The `If-Range` validator of a file: its ETag unless weak, else its modification time. */
  private fun rangeValidator(etag: String?, lastModified: String?): String? =
    etag?.takeUnless { it.startsWith("W/") } ?: lastModified

  /**
   * Downloads segments via HTTP Range requests with dynamic
   * resegmentation support. Delegates the concurrent batch loop
   * to [SegmentedDownloadHelper], whose connections take over the
   * remaining ranges of slower ones with requests of their own.
   *
   * @param quotaLimited whether the server counts the requests left (`RateLimit-Remaining`).
   *   Taking over a range costs a request, so such downloads keep to their segments' requests.
   */
  private suspend fun downloadSegments(
    context: DownloadContext,
    segments: List<Segment>,
    totalBytes: Long,
    supportsRanges: Boolean,
    requestedConnections: Int,
    quotaLimited: Boolean,
    headers: Map<String, String>,
  ) {
    val segmentHelper = if (quotaLimited) {
      SegmentedDownloadHelper(
        progressIntervalMs = context.config.progressIntervalMs,
        tag = "HttpSource",
        minSplitBytes = Long.MAX_VALUE,
      )
    } else {
      SegmentedDownloadHelper(
        progressIntervalMs = context.config.progressIntervalMs,
        tag = "HttpSource",
      )
    }
    val engine = httpEngine.through(context.config.proxy)
    segmentHelper.downloadAll(
      context, segments, totalBytes, supportsRanges, requestedConnections,
    ) { lane ->
      val throttleLimiter = object : SpeedLimiter {
        override suspend fun acquire(bytes: Int) {
          context.throttle(bytes)
        }
      }
      val downloader = SegmentDownloader(
        engine, context.fileAccessor,
        throttleLimiter, SpeedLimiter.Unlimited, context.taskId, context.watchdog,
      )
      // One connection per range request; a range another connection already took sends none.
      val spec = httpConnectionSpec(TYPE, context.url, context.config.proxy)
      val handle = if (lane.isComplete) ConnectionHandle.None else context.connections.open(spec)
      try {
        observeExchanges(handle, spec) {
          downloader.download(context.url, lane, headers, handle)
        }
      } finally {
        handle.close()
      }
    }
  }

  /**
   * Streams content whose size the server does not report over one connection, from byte zero
   * to the end of the response. Such a transfer can neither be split nor continued, so every
   * attempt empties the file first and [DownloadContext.segments] stays empty.
   */
  private suspend fun downloadUnknownSize(context: DownloadContext) {
    log.i { "Unknown size, streaming taskId=${context.taskId} over one connection" }
    context.segments.value = emptyList()
    try {
      context.fileAccessor.preallocate(0)
    } catch (e: Exception) {
      if (e is CancellationException) throw e
      if (e is KetchError) throw e
      throw KetchError.Disk(e)
    }
    val progressInterval = context.config.progressIntervalMs.milliseconds
    var lastProgress = TimeSource.Monotonic.markNow()
    var downloaded = 0L
    context.onProgress(0, 0)
    val engine = httpEngine.through(context.config.proxy)
    val spec = httpConnectionSpec(TYPE, context.url, context.config.proxy)
    val handle = context.connections.open(spec)
    try {
      observeExchanges(handle, spec) {
        context.watchdog.watch { monitor ->
          engine.download(context.url, null, context.headers) { data ->
            currentCoroutineContext().ensureActive()
            monitor.received(data.size)
            monitor.throttled { context.throttle(data.size) }
            try {
              context.fileAccessor.writeAt(downloaded, data)
            } catch (e: Exception) {
              if (e is CancellationException) throw e
              if (e is KetchError) throw e
              throw KetchError.Disk(e)
            }
            downloaded += data.size
            handle.received(data.size)
            if (lastProgress.elapsedNow() >= progressInterval) {
              context.onProgress(downloaded, 0)
              lastProgress = TimeSource.Monotonic.markNow()
            }
          }
        }
      }
    } finally {
      handle.close()
    }
    log.i { "Streamed $downloaded bytes for taskId=${context.taskId}" }
    context.onProgress(downloaded, downloaded)
  }

  /**
   * Returns [DownloadContext.effectiveConnections] for [requested], or 1 when
   * the server does not advertise byte-range support: extra segments would
   * need offsets such a server cannot serve.
   */
  private fun rangeLimitedConnections(
    context: DownloadContext,
    requested: Int,
    supportsRanges: Boolean,
  ): Int {
    val connections = context.effectiveConnections(requested)
    if (supportsRanges || connections <= 1) return connections
    log.i {
      "Server does not support ranges, using 1 of $connections connections " +
        "for taskId=${context.taskId}"
    }
    return 1
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
    taskId: String,
    connections: Int,
    remaining: Long?,
    reset: Long?,
  ): Int {
    if (remaining == null) return connections
    if (remaining == 0L) {
      val delaySec = reset?.coerceAtLeast(1) ?: 1L
      log.i {
        "Rate limit exhausted (remaining=0) for taskId=$taskId, " +
          "delaying ${delaySec}s before download"
      }
      delay(delaySec * 1000)
      return connections
    }
    if (remaining < connections) {
      val capped = remaining.toInt().coerceAtLeast(1)
      log.i {
        "Capping connections for taskId=$taskId from $connections to $capped " +
          "based on RateLimit-Remaining=$remaining"
      }
      return capped
    }
    return connections
  }

  /** The server's identity of the content as the download started, once; a resume keeps it. */
  override suspend fun store(context: DownloadContext): HttpSourceState? =
    context.preResolved?.let { resolved ->
      HttpSourceState(
        etag = resolved.metadata[META_ETAG],
        lastModified = resolved.metadata[META_LAST_MODIFIED],
        totalBytes = resolved.totalBytes,
      )
    }

  companion object {
    const val TYPE = "http"
    internal const val META_ETAG = "etag"
    internal const val META_LAST_MODIFIED = "lastModified"
    internal const val META_ACCEPT_RANGES = "acceptRanges"
    internal const val META_CONTENT_DISPOSITION = "contentDisposition"
    internal const val META_RATE_LIMIT_REMAINING = "rateLimitRemaining"
    internal const val META_RATE_LIMIT_RESET = "rateLimitReset"
    private const val IF_RANGE = "If-Range"
  }
}

/**
 * What [HttpDownloadSource] saves to resume a download.
 *
 * @property etag the server's ETag when the download started
 * @property lastModified the server's Last-Modified when the download started
 * @property totalBytes the content size, `-1` when the server did not report it
 */
@Serializable
internal data class HttpSourceState(
  val etag: String?,
  val lastModified: String?,
  val totalBytes: Long,
)
