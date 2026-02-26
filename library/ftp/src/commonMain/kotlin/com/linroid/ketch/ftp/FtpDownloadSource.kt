package com.linroid.ketch.ftp

import com.linroid.ketch.api.KetchError
import com.linroid.ketch.api.ResolvedSource
import com.linroid.ketch.api.Segment
import com.linroid.ketch.api.log.KetchLogger
import com.linroid.ketch.core.engine.DownloadContext
import com.linroid.ketch.core.engine.DownloadSource
import com.linroid.ketch.core.engine.SourceResumeState
import com.linroid.ketch.core.segment.SegmentCalculator
import com.linroid.ketch.core.segment.SegmentedDownloadHelper
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.serialization.json.Json

/**
 * FTP/FTPS download source using the [FtpClient] protocol layer.
 *
 * Supports segmented parallel downloads via multiple FTP connections
 * with REST (restart) offsets. Each segment opens its own FTP data
 * connection to download a portion of the file.
 *
 * Register with `Ketch(additionalSources = listOf(FtpDownloadSource()))`.
 *
 * @param maxConnections default number of parallel FTP connections
 * @param progressIntervalMs minimum interval between progress reports
 * @param clientFactory factory for creating [FtpClient] instances;
 *   defaults to [RealFtpClient]. Override for testing.
 */
class FtpDownloadSource(
  private val maxConnections: Int = 4,
  private val progressIntervalMs: Long = 200,
) : DownloadSource {

  internal var clientFactory: (FtpUrl) -> FtpClient = { url ->
    RealFtpClient(url.host, url.port)
  }
  private val log = KetchLogger("FtpSource")

  override val type: String = TYPE

  override fun canHandle(url: String): Boolean {
    val lower = url.lowercase()
    return lower.startsWith("ftp://") || lower.startsWith("ftps://")
  }

  override fun buildResumeState(
    resolved: ResolvedSource,
    totalBytes: Long,
  ): SourceResumeState {
    return buildResumeState(
      totalBytes = totalBytes,
      mdtm = resolved.metadata[META_MDTM],
    )
  }

  override suspend fun resolve(
    url: String,
    properties: Map<String, String>,
  ): ResolvedSource {
    val ftpUrl = try {
      FtpUrl.parse(url)
    } catch (e: IllegalArgumentException) {
      throw KetchError.SourceError(TYPE, e)
    }

    val client = clientFactory(ftpUrl)
    try {
      client.connect()
      if (ftpUrl.isTls) client.upgradeToTls()
      client.login(ftpUrl.username, ftpUrl.password)
      client.setBinaryMode()

      val fileSize = client.size(ftpUrl.path)
      val supportsRest = client.supportsRest()
      val mdtm = client.mdtm(ftpUrl.path)
      val fileName = ftpUrl.path.substringAfterLast('/')
        .ifEmpty { null }

      return ResolvedSource(
        url = url,
        sourceType = TYPE,
        totalBytes = fileSize ?: -1,
        supportsResume = supportsRest,
        suggestedFileName = fileName,
        maxSegments = if (supportsRest && fileSize != null) {
          maxConnections
        } else {
          1
        },
        metadata = buildMap {
          mdtm?.let { put(META_MDTM, it) }
          fileSize?.let { put(META_SIZE, it.toString()) }
          if (supportsRest) put(META_REST, "true")
        },
      )
    } finally {
      client.disconnect()
    }
  }

  override suspend fun download(context: DownloadContext) {
    val resolved = context.preResolved
      ?: resolve(context.url, context.headers)
    val totalBytes = resolved.totalBytes
    if (totalBytes < 0) {
      log.e { "Unknown file size for ${context.url} — file may not exist" }
      throw KetchError.SourceError(
        sourceType = TYPE,
        cause = Exception(
          "Unknown file size — file may not exist: " +
            context.url,
        ),
      )
    }

    val connections = effectiveConnections(context)

    val segments = if (
      resolved.supportsResume && connections > 1
    ) {
      log.i {
        "Server supports REST. Using $connections " +
          "connections, totalBytes=$totalBytes"
      }
      SegmentCalculator.calculateSegments(totalBytes, connections)
    } else {
      log.i { "Single connection, totalBytes=$totalBytes" }
      SegmentCalculator.singleSegment(totalBytes)
    }

    context.segments.value = segments

    try {
      context.fileAccessor.preallocate(totalBytes)
    } catch (e: Exception) {
      if (e is CancellationException) throw e
      if (e is KetchError) throw e
      throw KetchError.Disk(e)
    }

    downloadSegments(context, segments, totalBytes)
  }

  override suspend fun resume(
    context: DownloadContext,
    resumeState: SourceResumeState,
  ) {
    val state = try {
      Json.decodeFromString<FtpResumeState>(resumeState.data)
    } catch (e: Exception) {
      if (e is CancellationException) throw e
      throw KetchError.CorruptResumeState(e.message, e)
    }

    log.i { "Resuming download for taskId=${context.taskId}" }

    val ftpUrl = FtpUrl.parse(context.url)
    val client = clientFactory(ftpUrl)
    try {
      client.connect()
      if (ftpUrl.isTls) client.upgradeToTls()
      client.login(ftpUrl.username, ftpUrl.password)
      client.setBinaryMode()

      // Validate that the remote file hasn't changed
      if (state.mdtm != null) {
        val currentMdtm = client.mdtm(ftpUrl.path)
        if (currentMdtm != null && currentMdtm != state.mdtm) {
          log.w { "MDTM mismatch - file has changed on server" }
          throw KetchError.FileChanged(
            "MDTM mismatch - file has changed on server",
          )
        }
      }
    } finally {
      client.disconnect()
    }

    var segments = context.segments.value
    val totalBytes = state.totalBytes

    val connections = effectiveConnections(context)
    val incompleteCount = segments.count { !it.isComplete }
    if (incompleteCount > 0 && connections != incompleteCount) {
      log.i {
        "Resegmenting for taskId=${context.taskId}: " +
          "$incompleteCount -> $connections connections"
      }
      segments = SegmentCalculator.resegment(segments, connections)
      context.segments.value = segments
    }

    val validatedSegments = validateLocalFile(
      context, segments, totalBytes,
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
          "claimedProgress=$claimedProgress, " +
          "totalBytes=$totalBytes. Resetting segments."
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
    tag = "FtpSource",
  )

  /**
   * Downloads segments via FTP connections with dynamic
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
      downloadSegment(context, segment, onProgress)
    }
  }

  /**
   * Downloads a single segment via its own FTP connection.
   *
   * Opens a fresh FTP connection, authenticates, seeks to the
   * segment offset via REST, and downloads data via RETR.
   *
   * Unlike HTTP Range requests, FTP RETR sends all data from the
   * REST offset to EOF. This method tracks bytes received and
   * truncates/stops once the segment's byte range is fully covered.
   */
  private suspend fun downloadSegment(
    context: DownloadContext,
    segment: Segment,
    onProgress: suspend (bytesDownloaded: Long) -> Unit,
  ): Segment {
    if (segment.isComplete) {
      log.d { "Skipping complete segment ${segment.index}" }
      return segment
    }

    val ftpUrl = FtpUrl.parse(context.url)
    val client = clientFactory(ftpUrl)
    var downloadedBytes = segment.downloadedBytes
    val remaining = segment.totalBytes - downloadedBytes

    try {
      client.connect()
      if (ftpUrl.isTls) client.upgradeToTls()
      client.login(ftpUrl.username, ftpUrl.password)
      client.setBinaryMode()

      val fileOffset = segment.start + downloadedBytes

      log.d {
        "Starting segment ${segment.index}: " +
          "offset=$fileOffset, " +
          "remaining=$remaining bytes"
      }

      client.retrieve(
        ftpUrl.path,
        offset = fileOffset,
      ) { data ->
        currentCoroutineContext().ensureActive()

        val bytesNeeded = segment.totalBytes - downloadedBytes
        if (bytesNeeded <= 0) {
          throw SegmentCompleteException()
        }

        // Truncate if this chunk would exceed our segment boundary
        val chunk = if (data.size.toLong() > bytesNeeded) {
          data.copyOf(bytesNeeded.toInt())
        } else {
          data
        }

        context.throttle(chunk.size)

        val writeOffset = segment.start + downloadedBytes
        try {
          context.fileAccessor.writeAt(writeOffset, chunk)
        } catch (e: Exception) {
          if (e is CancellationException) throw e
          if (e is KetchError) throw e
          throw KetchError.Disk(e)
        }
        downloadedBytes += chunk.size
        onProgress(downloadedBytes)

        // Stop reading once segment is complete
        if (downloadedBytes >= segment.totalBytes) {
          throw SegmentCompleteException()
        }
      }
    } catch (_: SegmentCompleteException) {
      // Expected: segment boundary reached, stop reading
    } finally {
      client.disconnect()
    }

    if (downloadedBytes < segment.totalBytes) {
      log.w {
        "Incomplete segment ${segment.index}: " +
          "downloaded $downloadedBytes/${segment.totalBytes} bytes"
      }
      throw KetchError.Network(
        Exception(
          "FTP connection closed prematurely: received " +
            "$downloadedBytes of ${segment.totalBytes} bytes",
        ),
      )
    }

    log.d {
      "Completed segment ${segment.index}: " +
        "downloaded $downloadedBytes bytes"
    }

    return segment.copy(downloadedBytes = downloadedBytes)
  }

  /**
   * Thrown internally to break out of the [FtpClient.retrieve]
   * callback loop when a segment has received all its bytes.
   */
  private class SegmentCompleteException : Exception()

  private fun effectiveConnections(context: DownloadContext): Int {
    return when {
      context.maxConnections.value > 0 ->
        context.maxConnections.value

      context.request.connections > 0 -> context.request.connections
      else -> maxConnections
    }
  }

  companion object {
    const val TYPE = "ftp"
    internal const val META_MDTM = "mdtm"
    internal const val META_SIZE = "size"
    internal const val META_REST = "rest"

    fun buildResumeState(
      totalBytes: Long,
      mdtm: String?,
    ): SourceResumeState {
      val state = FtpResumeState(
        totalBytes = totalBytes,
        mdtm = mdtm,
      )
      return SourceResumeState(
        sourceType = TYPE,
        data = Json.encodeToString(state),
      )
    }
  }
}
