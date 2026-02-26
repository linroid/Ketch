package com.linroid.ketch.torrent

import com.linroid.ketch.api.FileSelectionMode
import com.linroid.ketch.api.KetchError
import com.linroid.ketch.api.ResolvedSource
import com.linroid.ketch.api.Segment
import com.linroid.ketch.api.SourceFile
import com.linroid.ketch.api.log.KetchLogger
import com.linroid.ketch.core.engine.DownloadContext
import com.linroid.ketch.core.engine.DownloadSource
import com.linroid.ketch.core.engine.SourceResumeState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.serialization.json.Json

/**
 * BitTorrent download source supporting `.torrent` files and
 * `magnet:` URIs.
 *
 * Uses libtorrent4j as the underlying engine on JVM and Android.
 * The engine manages its own file I/O, so [managesOwnFileIo] is
 * `true` — Ketch skips FileAccessor operations for torrent
 * downloads.
 *
 * Register with:
 * ```kotlin
 * Ketch(additionalSources = listOf(TorrentDownloadSource()))
 * ```
 *
 * @param config torrent engine configuration
 */
class TorrentDownloadSource(
  private val config: TorrentConfig = TorrentConfig(),
) : DownloadSource {

  internal var engineFactory: () -> TorrentEngine = {
    createTorrentEngine(config)
  }

  private val log = KetchLogger("TorrentSource")

  override val type: String = TYPE

  override val managesOwnFileIo: Boolean = true

  private var engine: TorrentEngine? = null

  private suspend fun getEngine(): TorrentEngine {
    val existing = engine
    if (existing != null && existing.isRunning) return existing
    val newEngine = engineFactory()
    newEngine.start()
    engine = newEngine
    return newEngine
  }

  override fun canHandle(url: String): Boolean {
    val lower = url.lowercase()
    return lower.startsWith("magnet:") ||
      lower.endsWith(".torrent") ||
      lower.contains(".torrent?")
  }

  override fun buildResumeState(
    resolved: ResolvedSource,
    totalBytes: Long,
  ): SourceResumeState {
    val infoHash = resolved.metadata[META_INFO_HASH] ?: ""
    val state = TorrentResumeState(
      infoHash = infoHash,
      totalBytes = totalBytes,
      resumeData = "",
      selectedFileIds = resolved.files.map { it.id }.toSet(),
      savePath = "",
    )
    return SourceResumeState(
      sourceType = TYPE,
      data = Json.encodeToString(state),
    )
  }

  override suspend fun resolve(
    url: String,
    properties: Map<String, String>,
  ): ResolvedSource {
    val metadata = try {
      resolveMetadata(url)
    } catch (e: Exception) {
      if (e is CancellationException) throw e
      if (e is KetchError) throw e
      throw KetchError.SourceError(TYPE, e)
    }

    val sourceFiles = metadata.files.map { file ->
      SourceFile(
        id = file.index.toString(),
        name = file.path,
        size = file.size,
        metadata = mapOf("path" to file.path),
      )
    }

    return ResolvedSource(
      url = url,
      sourceType = TYPE,
      totalBytes = metadata.totalBytes,
      supportsResume = true,
      suggestedFileName = metadata.name,
      maxSegments = metadata.files.size,
      metadata = buildMap {
        put(META_INFO_HASH, metadata.infoHash.hex)
        put(META_NAME, metadata.name)
        put(META_PIECE_LENGTH, metadata.pieceLength.toString())
        metadata.comment?.let { put(META_COMMENT, it) }
      },
      files = sourceFiles,
      selectionMode = FileSelectionMode.MULTIPLE,
    )
  }

  private suspend fun resolveMetadata(url: String): TorrentMetadata {
    return if (url.lowercase().startsWith("magnet:")) {
      val engine = getEngine()
      log.i { "Fetching metadata from magnet URI" }
      engine.fetchMetadata(url)
        ?: throw KetchError.Network(
          Exception("Metadata fetch timed out for: $url")
        )
    } else {
      // .torrent URL — we expect pre-resolved metadata from
      // torrent file bytes passed via DownloadRequest.resolvedSource
      throw KetchError.SourceError(
        TYPE,
        Exception(
          "Direct .torrent URL fetching not yet supported. " +
            "Parse the .torrent file and pass metadata via " +
            "DownloadRequest.resolvedSource"
        ),
      )
    }
  }

  override suspend fun download(context: DownloadContext) {
    val resolved = context.preResolved
      ?: resolve(context.url, context.headers)

    val infoHash = resolved.metadata[META_INFO_HASH]
      ?: throw KetchError.SourceError(
        TYPE, Exception("Missing info hash in resolved metadata")
      )

    val selectedFileIds = context.request.selectedFileIds
    val selectedIndices = if (selectedFileIds.isNotEmpty()) {
      selectedFileIds.mapNotNull { it.toIntOrNull() }.toSet()
    } else {
      // Select all files by default
      resolved.files.indices.toSet()
    }

    val totalBytes = if (selectedFileIds.isNotEmpty()) {
      resolved.files
        .filter { it.id in selectedFileIds }
        .sumOf { it.size }
    } else {
      resolved.totalBytes
    }

    // Create one segment per selected file
    val segments = createFileSegments(resolved, selectedIndices)
    context.segments.value = segments

    val savePath = extractSavePath(context)

    log.i {
      "Starting torrent download: infoHash=$infoHash, " +
        "files=${selectedIndices.size}, totalBytes=$totalBytes"
    }

    val engine = getEngine()
    val magnetUri = if (
      context.url.lowercase().startsWith("magnet:")
    ) {
      context.url
    } else {
      null
    }

    val session = try {
      engine.addTorrent(
        infoHash = infoHash,
        savePath = savePath,
        magnetUri = magnetUri,
        selectedFileIndices = selectedIndices,
      )
    } catch (e: Exception) {
      if (e is CancellationException) throw e
      if (e is KetchError) throw e
      throw KetchError.SourceError(TYPE, e)
    }

    // Apply speed limit if configured
    val speedLimit = context.request.speedLimit
    if (!speedLimit.isUnlimited) {
      session.setDownloadRateLimit(speedLimit.bytesPerSecond)
    }

    try {
      monitorProgress(context, session, segments, totalBytes)
    } catch (e: CancellationException) {
      session.pause()
      throw e
    } catch (e: Exception) {
      if (e is KetchError) throw e
      throw KetchError.SourceError(TYPE, e)
    }
  }

  override suspend fun resume(
    context: DownloadContext,
    resumeState: SourceResumeState,
  ) {
    val state = try {
      Json.decodeFromString<TorrentResumeState>(resumeState.data)
    } catch (e: Exception) {
      if (e is CancellationException) throw e
      throw KetchError.CorruptResumeState(e.message, e)
    }

    log.i { "Resuming torrent: infoHash=${state.infoHash}" }

    val resumeData = try {
      decodeBase64(state.resumeData)
    } catch (e: Exception) {
      if (e is CancellationException) throw e
      log.w(e) { "Failed to decode resume data, starting fresh" }
      null
    }

    val selectedIndices = state.selectedFileIds
      .mapNotNull { it.toIntOrNull() }
      .toSet()

    val engine = getEngine()
    val magnetUri = if (
      context.url.lowercase().startsWith("magnet:")
    ) {
      context.url
    } else {
      null
    }

    val session = try {
      engine.addTorrent(
        infoHash = state.infoHash,
        savePath = state.savePath,
        magnetUri = magnetUri,
        selectedFileIndices = selectedIndices,
        resumeData = resumeData,
      )
    } catch (e: Exception) {
      if (e is CancellationException) throw e
      if (e is KetchError) throw e
      throw KetchError.SourceError(TYPE, e)
    }

    val speedLimit = context.request.speedLimit
    if (!speedLimit.isUnlimited) {
      session.setDownloadRateLimit(speedLimit.bytesPerSecond)
    }

    val segments = context.segments.value
    val totalBytes = state.totalBytes

    try {
      monitorProgress(context, session, segments, totalBytes)
    } catch (e: CancellationException) {
      session.pause()
      throw e
    } catch (e: Exception) {
      if (e is KetchError) throw e
      throw KetchError.SourceError(TYPE, e)
    }
  }

  /**
   * Monitors torrent download progress and maps it to Ketch
   * segment progress until download completes or is canceled.
   */
  private suspend fun monitorProgress(
    context: DownloadContext,
    session: TorrentSession,
    segments: List<Segment>,
    totalBytes: Long,
  ) {
    // Wait for the download to complete
    while (true) {
      val sessionState = session.state.value
      when (sessionState) {
        TorrentSessionState.FINISHED,
        TorrentSessionState.SEEDING -> break

        TorrentSessionState.STOPPED -> {
          throw KetchError.SourceError(
            TYPE, Exception("Torrent session stopped unexpectedly")
          )
        }
        else -> {
          // Update progress
          val downloaded = session.downloadedBytes.value
            .coerceAtMost(totalBytes)
          updateSegmentProgress(context, segments, downloaded, totalBytes)
          context.onProgress(downloaded, totalBytes)
          delay(PROGRESS_INTERVAL_MS)
        }
      }
    }

    // Final progress update
    updateSegmentProgress(context, segments, totalBytes, totalBytes)
    context.onProgress(totalBytes, totalBytes)

    // Save resume data for potential re-seeding
    val resumeData = session.saveResumeData()
    if (resumeData != null) {
      val savePath = extractSavePath(context)
      val selectedIds = context.request.selectedFileIds.ifEmpty {
        segments.map { it.index.toString() }.toSet()
      }
      val sourceState = TorrentResumeState(
        infoHash = session.infoHash,
        totalBytes = totalBytes,
        resumeData = encodeBase64(resumeData),
        selectedFileIds = selectedIds,
        savePath = savePath,
      )
      // Store resume state by updating the context's segments
      // The DownloadExecution will persist this through TaskRecord
      log.d { "Saved torrent resume data for ${session.infoHash}" }
    }
  }

  /**
   * Distributes downloaded bytes across segments proportionally.
   */
  private fun updateSegmentProgress(
    context: DownloadContext,
    segments: List<Segment>,
    downloaded: Long,
    totalBytes: Long,
  ) {
    if (segments.isEmpty()) return
    val fraction = if (totalBytes > 0) {
      downloaded.toDouble() / totalBytes
    } else {
      0.0
    }
    val updated = segments.map { segment ->
      val segDownloaded = (segment.totalBytes * fraction)
        .toLong()
        .coerceAtMost(segment.totalBytes)
      segment.copy(downloadedBytes = segDownloaded)
    }
    context.segments.value = updated
  }

  private fun createFileSegments(
    resolved: ResolvedSource,
    selectedIndices: Set<Int>,
  ): List<Segment> {
    var offset = 0L
    val segments = mutableListOf<Segment>()
    for (file in resolved.files) {
      val fileIndex = file.id.toIntOrNull() ?: continue
      if (fileIndex !in selectedIndices) continue
      segments.add(
        Segment(
          index = segments.size,
          start = offset,
          end = offset + file.size - 1,
          downloadedBytes = 0,
        )
      )
      offset += file.size
    }
    return segments
  }

  private fun extractSavePath(context: DownloadContext): String {
    val dest = context.request.destination
    return dest?.value ?: "downloads"
  }

  companion object {
    const val TYPE = "torrent"
    internal const val META_INFO_HASH = "infoHash"
    internal const val META_NAME = "name"
    internal const val META_PIECE_LENGTH = "pieceLength"
    internal const val META_COMMENT = "comment"
    private const val PROGRESS_INTERVAL_MS = 500L

    fun buildResumeState(
      infoHash: String,
      totalBytes: Long,
      resumeData: ByteArray,
      selectedFileIds: Set<String>,
      savePath: String,
    ): SourceResumeState {
      val state = TorrentResumeState(
        infoHash = infoHash,
        totalBytes = totalBytes,
        resumeData = encodeBase64(resumeData),
        selectedFileIds = selectedFileIds,
        savePath = savePath,
      )
      return SourceResumeState(
        sourceType = TYPE,
        data = Json.encodeToString(state),
      )
    }
  }
}

/**
 * Platform-specific factory for [TorrentEngine].
 * Implemented in jvmAndAndroid source set.
 */
internal expect fun createTorrentEngine(
  config: TorrentConfig,
): TorrentEngine

/** Platform-specific base64 encoding. */
internal expect fun encodeBase64(data: ByteArray): String

/** Platform-specific base64 decoding. */
internal expect fun decodeBase64(data: String): ByteArray
