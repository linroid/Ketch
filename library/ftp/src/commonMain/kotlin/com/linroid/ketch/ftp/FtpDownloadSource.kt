package com.linroid.ketch.ftp

import com.linroid.ketch.api.KetchError
import com.linroid.ketch.api.ResolvedSource
import com.linroid.ketch.api.Segment
import com.linroid.ketch.api.log.KetchLogger
import com.linroid.ketch.core.engine.DownloadContext
import com.linroid.ketch.core.engine.DownloadSource
import com.linroid.ketch.core.engine.SourceResumeState
import com.linroid.ketch.core.segment.SegmentCalculator
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlin.time.Clock
import kotlin.time.Duration.Companion.milliseconds

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

  /**
   * Downloads segments with dynamic resegmentation support.
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
        context, currentSegments, incomplete, totalBytes,
      )

      if (batchCompleted) break

      val newCount = context.pendingResegment
      context.pendingResegment = 0
      currentSegments = SegmentCalculator.resegment(
        context.segments.value, newCount,
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
   * Each segment gets its own FTP connection. The watcher coroutine
   * monitors connection count changes for live resegmentation.
   *
   * @return true if all segments completed, false if interrupted
   *   for resegmentation
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
          progressIntervalMs.milliseconds
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
        // Watcher for connection count changes
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
              downloadSegment(
                context, segment,
              ) { bytesDownloaded ->
                segmentMutex.withLock {
                  segmentProgress[segment.index] =
                    bytesDownloaded
                }
                updateProgress()
              }
            }
          }

          for ((i, deferred) in results.withIndex()) {
            val completed = deferred.await()
            segmentMutex.withLock {
              updatedSegments[completed.index] = completed
            }
            context.segments.value = currentSegments()
            log.d {
              "Segment ${completed.index} completed for " +
                "taskId=${context.taskId}"
            }
          }
        } finally {
          watcherJob.cancel()
        }

        context.segments.value = currentSegments()
        true
      }
    } catch (e: CancellationException) {
      if (context.pendingResegment > 0) {
        withContext(NonCancellable) {
          context.segments.value = currentSegments()
        }
        false
      } else {
        throw e
      }
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
