package com.linroid.ketch.webdav

import com.linroid.ketch.api.DownloadConfig
import com.linroid.ketch.api.KetchError
import com.linroid.ketch.api.Proxy
import com.linroid.ketch.api.ResolveInput
import com.linroid.ketch.api.ResolvedSource
import com.linroid.ketch.api.Segment
import com.linroid.ketch.api.log.KetchLogger
import com.linroid.ketch.core.engine.DownloadContext
import com.linroid.ketch.core.engine.DownloadSource
import com.linroid.ketch.core.engine.HttpEngine
import com.linroid.ketch.core.engine.httpConnectionSpec
import com.linroid.ketch.core.engine.observeExchanges
import com.linroid.ketch.core.file.sanitizeFileName
import com.linroid.ketch.core.segment.SegmentCalculator
import com.linroid.ketch.core.segment.SegmentLane
import com.linroid.ketch.core.segment.SegmentedDownloadHelper
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.KSerializer
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.TimeSource

/**
 * Downloads files from WebDAV servers (RFC 4918), such as Nextcloud, ownCloud, a NAS or Apache
 * `mod_dav`, given `webdav://` or `dav://` links (HTTP) and `webdavs://` or `davs://` links
 * (HTTPS). Register with
 * `Ketch(additionalSources = listOf(WebDavDownloadSource(httpEngine)))`; requests share the
 * engine, its proxies and networks, and closing this source does not close it.
 *
 * A `PROPFIND` of the file reads its size, entity tag and modification time, and refuses a
 * folder; a ranged `GET` of its first byte tells whether the server sends byte ranges. Files
 * then download in segments like HTTP ones, over [DownloadContext.effectiveConnections], and a
 * pause, retry or restart continues from the bytes written once the server still reports the
 * same file ([KetchError.FileChanged] otherwise). Servers that answer the file but not
 * `PROPFIND` are read from their headers instead.
 *
 * The link's user information (`davs://user:password@host/path`), or an `Authorization: Basic`
 * header among the request's headers, signs in when the server asks: with Digest (RFC 7616) when
 * it offers it, else Basic. Refused or missing credentials fail with
 * [KetchError.AuthenticationFailed].
 */
class WebDavDownloadSource(private val httpEngine: HttpEngine) :
  DownloadSource<WebDavSourceState> {
  private val log = KetchLogger("WebDavSource")

  // The identity each running task found as its run started, saved by store().
  private val observedLock = Mutex()
  private val observed = mutableMapOf<String, WebDavSourceState>()

  override val type: String = TYPE

  override val stateSerializer: KSerializer<WebDavSourceState> = WebDavSourceState.serializer()

  override fun canHandle(input: ResolveInput): Boolean =
    input is ResolveInput.Url && WebDavUrl.isWebDav(input.url)

  override suspend fun resolve(input: ResolveInput, config: DownloadConfig): ResolvedSource {
    val (link, headers) = input as ResolveInput.Url
    val url = parse(link)
    return inspect(WebDavClient(engineFor(config.proxy), url, headers), link, url, config).resolved
  }

  /**
   * The identity the task's run found, else the one its resolution reported, so a resume knows
   * whether the file changed.
   */
  override suspend fun store(context: DownloadContext): WebDavSourceState? =
    observedLock.withLock { observed[context.taskId] }
      ?: context.preResolved?.let(::stateOf)

  override suspend fun download(context: DownloadContext, state: WebDavSourceState?) {
    val url = parse(context.url)
    val client = WebDavClient(engineFor(context.config.proxy), url, context.headers)
    // Asked on every run: it answers the server's challenge and tells whether the file changed.
    val inspection = inspect(client, context.url, url, context.config)
    val resolved = inspection.resolved
    val current = stateOf(resolved)
    // Bytes written before this run are only kept when a strong validator ties them to the file.
    var continuable = true
    if (state != null) {
      log.i { "Resuming download for taskId=${context.taskId}" }
      requireUnchanged(context.taskId, state, current)
      context.publishedChecksum = resolved.checksum
      continuable = hasValidator(state)
      if (!continuable) {
        log.w { "No strong ETag or Last-Modified for taskId=${context.taskId}; starting over" }
      }
    }
    observedLock.withLock { observed[context.taskId] = state ?: current }

    val totalBytes = resolved.totalBytes
    if (totalBytes < 0) {
      downloadUnknownSize(context, client, url)
      return
    }
    val ranges = resolved.supportsResume
    // The segments are sized from this value; downloadAll resegments for any later change.
    val requested = context.maxConnections.value
    val connections = if (ranges) context.effectiveConnections(requested) else 1
    val segments = plan(context, totalBytes, ranges && continuable, connections)
    context.segments.value = segments

    SegmentedDownloadHelper(
      progressIntervalMs = context.config.progressIntervalMs,
      tag = "WebDavSource",
    ).downloadAll(context, segments, totalBytes, ranges, requested) { lane ->
      downloadLane(context, client, url, inspection.conditions, lane)
    }
  }

  override suspend fun cleanup(
    context: DownloadContext,
    state: WebDavSourceState?,
    deleteFiles: Boolean,
  ) {
    observedLock.withLock { observed.remove(context.taskId) }
    super.cleanup(context, state, deleteFiles)
  }

  /**
   * What the server said about a file: its resolution, and the conditions every ranged `GET` of
   * it carries so that each answers from the same version.
   */
  private class Inspection(val resolved: ResolvedSource, val conditions: Map<String, String>)

  /**
   * Reads what the server says about the file at [url] into a resolution of [link]. Its entity
   * tag and modification time are the `GET` probe's where it sends them, as those are what the
   * ranged `GET`s carry and their conditions compare, else the `PROPFIND`'s.
   */
  private suspend fun inspect(
    client: WebDavClient,
    link: String,
    url: WebDavUrl,
    config: DownloadConfig,
  ): Inspection {
    val properties = client.propfind()
    if (properties?.collection == true) {
      throw KetchError.SourceError(TYPE, detail = "This WebDAV link is a folder; link to a file")
    }
    val info = client.probe()
    val totalBytes = info.contentLength ?: properties?.contentLength ?: -1
    val ranges = info.acceptRanges && totalBytes > 0
    val etag = info.etag ?: properties?.etag
    log.d {
      "WebDAV file: dav=${properties != null}, totalBytes=$totalBytes, ranges=$ranges, " +
        "etag=$etag"
    }
    val resolved = ResolvedSource(
      url = link,
      sourceType = TYPE,
      totalBytes = totalBytes,
      supportsResume = ranges,
      suggestedFileName = url.fileName?.let(::sanitizeFileName)
        ?: properties?.displayName?.let(::sanitizeFileName),
      maxSegments = if (ranges) config.maxConnectionsPerDownload else 1,
      metadata = buildMap {
        etag?.let { put(META_ETAG, it) }
        (info.lastModified ?: properties?.lastModified)?.let { put(META_LAST_MODIFIED, it) }
        if (properties != null) put(META_DAV, "true")
      },
      contentType = properties?.contentType ?: info.contentType,
      checksum = info.checksum,
    )
    // Only validators the GET itself sent: a server may not compare one only PROPFIND reports.
    val getEtag = info.etag
    val getLastModified = info.lastModified
    val conditions = when {
      getEtag != null && isStrong(getEtag) -> mapOf(IF_MATCH to getEtag)
      getLastModified != null -> mapOf(IF_UNMODIFIED_SINCE to getLastModified)
      else -> emptyMap()
    }
    return Inspection(resolved, conditions)
  }

  /**
   * Fails with [KetchError.FileChanged] when [current] is not the file [saved] describes, by its
   * strong entity tag, else its modification time (a weak tag only says the content is
   * equivalent, not the same bytes), and its size.
   */
  private fun requireUnchanged(
    taskId: String,
    saved: WebDavSourceState,
    current: WebDavSourceState,
  ) {
    val savedTag = saved.etag?.takeIf(::isStrong)?.let(::strongTag)
    val currentTag = current.etag?.takeIf(::isStrong)?.let(::strongTag)
    val reason = when {
      savedTag != null && savedTag != currentTag -> "ETag"
      savedTag == null && saved.lastModified != null &&
        saved.lastModified != current.lastModified -> "Last-Modified"
      saved.totalBytes >= 0 && current.totalBytes >= 0 &&
        saved.totalBytes != current.totalBytes -> "Size"
      else -> return
    }
    log.w { "$reason mismatch for taskId=$taskId - file has changed on server" }
    throw KetchError.FileChanged("$reason mismatch - file has changed on server")
  }

  /**
   * The segments to download [totalBytes] with: the saved ones when their progress can be
   * continued, resegmented to [connections], else new ones over an emptied file.
   */
  private suspend fun plan(
    context: DownloadContext,
    totalBytes: Long,
    ranges: Boolean,
    connections: Int,
  ): List<Segment> {
    val existing = context.segments.value
    fun fresh(): List<Segment> = if (connections > 1) {
      SegmentCalculator.calculateSegments(totalBytes, connections)
    } else {
      SegmentCalculator.singleSegment(totalBytes)
    }
    val reusable = existing.isNotEmpty() && ranges &&
      existing.sumOf { it.totalBytes } == totalBytes &&
      existing.any { it.downloadedBytes > 0 } &&
      localFileHolds(context, existing, totalBytes)
    if (!reusable) {
      if (existing.any { it.downloadedBytes > 0 }) {
        log.w { "Restarting taskId=${context.taskId} from zero" }
      }
      log.i { "Using $connections connections for taskId=${context.taskId}, total=$totalBytes" }
      disk { context.fileAccessor.preallocate(totalBytes) }
      return fresh()
    }
    val incomplete = existing.count { !it.isComplete }
    if (incomplete > 0 && incomplete != connections) {
      log.i {
        "Resegmenting for taskId=${context.taskId}: $incomplete -> $connections connections"
      }
      return SegmentCalculator.resegment(existing, connections)
    }
    return existing
  }

  /** Whether the file on disk is at least as long as [segments] claim, so their bytes are there. */
  private suspend fun localFileHolds(
    context: DownloadContext,
    segments: List<Segment>,
    totalBytes: Long,
  ): Boolean {
    val size = try {
      context.fileAccessor.size()
    } catch (e: Exception) {
      if (e is CancellationException) throw e
      0L
    }
    val claimed = segments.sumOf { it.downloadedBytes }
    val holds = size >= claimed && size >= totalBytes
    if (!holds) {
      log.w {
        "Local file integrity check failed for taskId=${context.taskId}: fileSize=$size, " +
          "claimedProgress=$claimed, totalBytes=$totalBytes"
      }
    }
    return holds
  }

  /**
   * Downloads the bytes [lane] still needs with one ranged `GET` carrying [conditions], writing
   * what [SegmentLane.admit] allows, as another connection may take over its tail meanwhile. A
   * `412` means the file changed since the run inspected it, and fails with
   * [KetchError.FileChanged] rather than mix two versions.
   */
  private suspend fun downloadLane(
    context: DownloadContext,
    client: WebDavClient,
    url: WebDavUrl,
    conditions: Map<String, String>,
    lane: SegmentLane,
  ) {
    if (lane.isComplete) return
    val range = lane.offset..lane.end
    var writeOffset = range.first
    val spec = httpConnectionSpec(TYPE, url.httpUrl, context.config.proxy)
    val handle = context.connections.open(spec)
    try {
      observeExchanges(handle, spec) {
        context.watchdog.watch { monitor ->
          client.download(range, conditions) { data ->
            currentCoroutineContext().ensureActive()
            monitor.received(data.size)
            val allowed = lane.admit(data.size)
            if (allowed > 0) {
              monitor.throttled { context.throttle(allowed) }
              val chunk = if (allowed == data.size) data else data.copyOf(allowed)
              disk { context.fileAccessor.writeAt(writeOffset, chunk) }
              writeOffset += allowed
              handle.received(allowed)
              // Ends the response when another connection took the rest of the range.
              lane.commit(allowed)
            }
            if (allowed < data.size) {
              throw KetchError.Unsupported(IllegalStateException("Response exceeds segment"))
            }
          }
        }
      }
    } catch (e: KetchError.Http) {
      if (e.code != 412) throw e
      log.w { "Precondition failed for taskId=${context.taskId} - file has changed on server" }
      throw KetchError.FileChanged("File changed on server during the download")
    } finally {
      handle.close()
    }
    if (!lane.isComplete) {
      throw KetchError.Network(
        IllegalStateException(
          "Connection closed prematurely: received ${writeOffset - range.first} of " +
            "${lane.end - range.first + 1} bytes",
        ),
      )
    }
  }

  /**
   * Streams a file whose size the server does not report over one connection, from byte zero:
   * such a transfer can neither be split nor continued, so every attempt empties the file first.
   */
  private suspend fun downloadUnknownSize(
    context: DownloadContext,
    client: WebDavClient,
    url: WebDavUrl,
  ) {
    log.i { "Unknown size, streaming taskId=${context.taskId} over one connection" }
    context.segments.value = emptyList()
    disk { context.fileAccessor.preallocate(0) }
    val progressInterval = context.config.progressIntervalMs.milliseconds
    var lastProgress = TimeSource.Monotonic.markNow()
    var downloaded = 0L
    context.onProgress(0, 0)
    val spec = httpConnectionSpec(TYPE, url.httpUrl, context.config.proxy)
    val handle = context.connections.open(spec)
    try {
      observeExchanges(handle, spec) {
        context.watchdog.watch { monitor ->
          client.download(null) { data ->
            currentCoroutineContext().ensureActive()
            monitor.received(data.size)
            monitor.throttled { context.throttle(data.size) }
            disk { context.fileAccessor.writeAt(downloaded, data) }
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

  private fun engineFor(proxy: Proxy): HttpEngine =
    if (proxy == Proxy.System) httpEngine else httpEngine.withProxy(proxy)

  companion object {
    /** The [DownloadSource.type] of WebDAV downloads. */
    const val TYPE = "webdav"
    internal const val META_ETAG = "etag"
    internal const val META_LAST_MODIFIED = "lastModified"
    internal const val META_DAV = "dav"

    private fun parse(link: String): WebDavUrl = try {
      WebDavUrl.parse(link)
    } catch (e: IllegalArgumentException) {
      throw KetchError.SourceError(TYPE, e, "Invalid WebDAV link")
    }

    private fun stateOf(resolved: ResolvedSource) = WebDavSourceState(
      etag = resolved.metadata[META_ETAG],
      lastModified = resolved.metadata[META_LAST_MODIFIED],
      totalBytes = resolved.totalBytes,
    )

    private const val IF_MATCH = "If-Match"
    private const val IF_UNMODIFIED_SINCE = "If-Unmodified-Since"

    /** Whether [etag] is a strong entity tag, one that names the exact bytes. */
    private fun isStrong(etag: String): Boolean = !etag.trim().startsWith("W/")

    /** A strong [etag] without its quotes, which `getetag` and `ETag` may write differently. */
    private fun strongTag(etag: String): String = etag.trim().removeSurrounding("\"")

    /** Whether [state] ties the bytes written to one version: a strong tag or a modification time. */
    private fun hasValidator(state: WebDavSourceState): Boolean =
      state.etag?.let(::isStrong) == true || state.lastModified != null

    /** Runs a file operation, reporting its failures as [KetchError.Disk]. */
    private inline fun <T> disk(block: () -> T): T = try {
      block()
    } catch (e: CancellationException) {
      throw e
    } catch (e: KetchError) {
      throw e
    } catch (e: Exception) {
      throw KetchError.Disk(e)
    }
  }
}
