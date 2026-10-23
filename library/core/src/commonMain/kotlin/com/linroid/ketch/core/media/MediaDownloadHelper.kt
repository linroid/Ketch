package com.linroid.ketch.core.media

import com.linroid.ketch.api.FileSelectionMode
import com.linroid.ketch.api.KetchError
import com.linroid.ketch.api.Proxy
import com.linroid.ketch.api.ResolvedSource
import com.linroid.ketch.api.SourceFile
import com.linroid.ketch.api.isFile
import com.linroid.ketch.core.engine.ConnectionHandle
import com.linroid.ketch.core.engine.DownloadContext
import com.linroid.ketch.core.engine.HttpEngine
import com.linroid.ketch.core.engine.SelectionPlan
import com.linroid.ketch.core.engine.SelectionRequest
import com.linroid.ketch.core.engine.TransferWatchdog
import com.linroid.ketch.core.engine.through
import com.linroid.ketch.core.file.DefaultFileNameResolver
import com.linroid.ketch.core.engine.httpConnectionSpec
import com.linroid.ketch.core.engine.observeExchanges
import com.linroid.ketch.core.file.sanitizeFileName
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import okio.Buffer

/**
 * Shared bounded manifest fetching and sequential transfer for finite media sources: variant
 * choice, AES-128 decryption of encrypted parts and merging of separate fragmented MP4 tracks.
 * Requests go through the proxy the task downloads with ([DownloadContext.config]).
 */
class MediaDownloadHelper(private val http: HttpEngine, private val type: String) {
  companion object {
    /** [ResolvedSource.metadata] key of the output's extension, without a dot. */
    const val EXTENSION_KEY: String = "media.extension"

    /** [ResolvedSource.metadata] key of the variant the resolution was planned for. */
    const val VARIANT_KEY: String = "media.variant"

    /** [ResolvedSource.metadata] key of how many tracks are merged, when more than one. */
    const val TRACKS_KEY: String = "media.tracks"

    /** [com.linroid.ketch.api.SourceFile.metadata] key of a variant's declared bandwidth. */
    const val BANDWIDTH_KEY: String = "bandwidth"

    private const val MAX_INIT_BYTES = 4L * 1024 * 1024
    private val MEDIA_EXTENSIONS = setOf("ts", "aac", "mp3", "mp4")
  }

  /**
   * Builds metadata for an already validated [plan], the one for variant [chosen] of
   * [variants] when the stream offers several, which become a single choice of
   * [ResolvedSource.files], best first.
   */
  fun resolve(
    url: String,
    plan: MediaPlan,
    variants: List<MediaVariant> = emptyList(),
    chosen: String? = null,
  ): ResolvedSource {
    val name = mediaName(url)
    val choices = variants.takeIf { it.size > 1 }.orEmpty()
    return ResolvedSource(
      url = url,
      sourceType = type,
      totalBytes = -1,
      supportsResume = false,
      suggestedFileName = sanitizeFileName("$name.${plan.extension}"),
      maxSegments = 1,
      metadata = buildMap {
        put(EXTENSION_KEY, plan.extension)
        if (choices.isNotEmpty() && chosen != null) put(VARIANT_KEY, chosen)
        if (plan.tracks > 1) put(TRACKS_KEY, plan.tracks.toString())
      },
      files = choices.map { variant ->
        SourceFile(
          id = variant.id,
          name = variant.name,
          metadata = variant.metadata + (BANDWIDTH_KEY to variant.bandwidth.toString()),
        )
      },
      selectionMode = FileSelectionMode.SINGLE,
    )
  }

  /**
   * Checks a variant selection against the variants a resolution listed ([resolved]), without
   * fetching anything: one known ID, sized as unknown, as media sizes are.
   */
  fun planSelection(request: SelectionRequest<*>): SelectionPlan {
    val files = request.resolved?.files.orEmpty()
    check(files.isNotEmpty()) { "The variants of this stream are not known yet" }
    val ids = request.fileIds ?: setOf(files.first().id)
    require(ids.size == 1) { "Choose exactly one variant of this stream" }
    require(ids.single() in files.map { it.id }) { "Unknown variant" }
    val current = request.current.ifEmpty { setOf(files.first().id) }
    val changed = ids != current
    return SelectionPlan(ids, totalBytes = -1, changed = changed, expands = changed,
      segments = null)
  }

  /**
   * The variant a run downloads: the one selected, else the first (the best). Checks that the
   * manifest still lists what the task chose from, as variant IDs are positions.
   */
  fun chosenVariant(context: DownloadContext, selected: Set<String>, variants: List<MediaVariant>):
    MediaVariant? {
    if (variants.size <= 1) {
      mediaRequire(selected.isEmpty(), "This stream has no variants to choose")
      return variants.firstOrNull()
    }
    val id = selected.singleOrNull() ?: variants.first().id.also {
      mediaRequire(selected.isEmpty(), "Choose exactly one variant of this stream")
    }
    val variant = variants.firstOrNull { it.id == id }
    mediaRequire(variant != null, "The chosen variant is gone; add the stream again")
    val listed = context.preResolved?.files?.firstOrNull { it.id == id }
    mediaRequire(listed == null ||
      listed.metadata[BANDWIDTH_KEY] == variant!!.bandwidth.toString(),
      "The stream's variants changed; add it again")
    return variant
  }

  /**
   * Downloads [plan] for [context], restarting with the new variant whenever the task's
   * selection changes while it runs. [plan] gives the plan of the selected IDs.
   */
  suspend fun downloadFollowingSelection(
    context: DownloadContext,
    plan: suspend (selected: Set<String>) -> MediaPlan,
  ) {
    while (true) {
      val update = context.selection.value
      val next = plan(update.fileIds)
      context.acknowledgeSelection(update.revision)
      val restarted = coroutineScope {
        val transfer = async { download(context, next) }
        val changed = async {
          context.selection.first { it.revision > update.revision }
        }
        select {
          transfer.onAwait { false }
          changed.onAwait { true }
        }.also {
          transfer.cancel()
          changed.cancel()
        }
      }
      if (!restarted) return
    }
  }

  /** Writes a validated plan in order, truncating partial output and applying task throttling. */
  suspend fun download(context: DownloadContext, plan: MediaPlan) {
    val expected = context.preResolved?.metadata?.get(EXTENSION_KEY)
    val variant = context.preResolved?.metadata?.get(VARIANT_KEY)
    val selected = context.selection.value.fileIds.singleOrNull()
    mediaRequire(expected == null || expected == plan.extension ||
      (variant != null && selected != null && selected != variant),
      "Media format changed; add it again")
    // A name Ketch chose ends with the container of the variant it was resolved for, so another
    // variant in another container must not be written under it. A file the user named is theirs.
    val named = context.request.destination?.isFile() != true
    val chosenExtension = context.outputPath?.substringAfterLast('/')?.substringAfterLast('\\')
      ?.substringAfterLast('.', "")?.lowercase()
    mediaRequire(
      !named || chosenExtension !in MEDIA_EXTENSIONS || chosenExtension == plan.extension,
      "This quality is a .${plan.extension} stream, not .$chosenExtension; add it again for it",
    )
    mediaRequire(plan.tracks in 1..2, "Unsupported media tracks")
    val engine = http.through(context.config.proxy)
    context.segments.value = emptyList()
    disk { context.fileAccessor.preallocate(0) }
    var written = 0L
    context.onProgress(0, 0)
    val keys = mutableMapOf<String, ByteArray>()
    suspend fun keyOf(key: MediaKey): ByteArray = keys[key.url] ?: run {
      val bytes = Buffer()
      val headers = mediaHeaders(context.url, key.url, context.headers)
      context.watchdog.watch { monitor ->
        engine.download(key.url, null, headers) { data ->
          monitor.received(data.size)
          mediaRequire(bytes.size + data.size <= 16, "Invalid AES-128 key")
          bytes.write(data)
        }
      }
      mediaRequire(bytes.size == 16L, "Invalid AES-128 key")
      bytes.readByteArray().also { keys[key.url] = it }
    }
    suspend fun write(data: ByteArray) {
      if (data.isEmpty()) return
      mediaRequire(data.size <= Long.MAX_VALUE - written, "Media output is too large")
      disk { context.fileAccessor.writeAt(written, data) }
      written += data.size
      context.onProgress(written, 0)
    }
    // The parts are fetched one after another, so the task has one connection whose
    // endpoint follows the part being fetched.
    var handle: ConnectionHandle? = null
    suspend fun transfer(part: MediaPart, sink: suspend (ByteArray) -> Unit) {
      currentCoroutineContext().ensureActive()
      val decryptor = part.key?.let { key -> media { Aes128CbcDecryptor(keyOf(key), key.iv) } }
      val spec = httpConnectionSpec(type, part.url, context.config.proxy)
      val connection = handle?.also { it.describe(spec) }
        ?: context.connections.open(spec).also { handle = it }
      var received = 0L
      val expectedBytes = part.range?.let { it.last - it.first + 1 }
      val headers = mediaHeaders(context.url, part.url, context.headers)
      observeExchanges(connection, spec) {
        context.watchdog.watch { monitor ->
          engine.download(part.url, part.range, headers) { data ->
            currentCoroutineContext().ensureActive()
            monitor.received(data.size)
            mediaRequire(expectedBytes == null || data.size <= expectedBytes - received,
              "Media server returned a different byte range")
            monitor.throttled { context.throttle(data.size) }
            received += data.size
            connection.received(data.size)
            sink(if (decryptor == null) data else media { decryptor.update(data) })
          }
        }
      }
      mediaRequire(received > 0 && (expectedBytes == null || received == expectedBytes),
        "Media segment is empty or incomplete")
      if (decryptor != null) sink(media { decryptor.finish() })
    }
    try {
      if (plan.tracks == 1) {
        for (part in plan.parts) transfer(part, ::write)
      } else {
        val inits = plan.parts.filter { it.init }
        mediaRequire(inits.map { it.track } == listOf(0, 1), "Invalid media tracks")
        val segments = inits.map { part ->
          val bytes = Buffer()
          transfer(part) { data ->
            mediaRequire(bytes.size + data.size <= MAX_INIT_BYTES,
              "Media initialization segment is too large")
            bytes.write(data)
          }
          bytes.readByteArray()
        }
        write(media { mergeInitSegments(segments[0], segments[1]) })
        var sequence = 0L
        for (part in plan.parts) {
          if (part.init) continue
          // An encrypted part's offsets refer to the plaintext, which starts at zero.
          val rewriter = FragmentRewriter(
            trackId = part.track + 1L,
            nextSequence = { ++sequence },
            sourceStart = if (part.key == null) part.range?.first ?: 0 else 0,
            outputStart = written,
          )
          transfer(part) { data -> write(media { rewriter.update(data) }) }
          media { rewriter.finish() }
        }
      }
    } finally {
      handle?.close()
    }
    context.onProgress(written, written)
  }

  /**
   * Fetches at most 1 MiB through [proxy] under [watchdog], as transfers run, and retains the
   * final URL and headers safe for that origin.
   */
  suspend fun fetchManifest(
    url: String,
    headers: Map<String, String>,
    proxy: Proxy = Proxy.System,
    watchdog: TransferWatchdog = TransferWatchdog.Off,
  ): MediaManifest {
    val checked = mediaUrl(type, url, url)
    val buffer = Buffer()
    val effective = watchdog.watch { monitor ->
      http.through(proxy).downloadResource(checked, headers) { bytes ->
        monitor.received(bytes.size)
        mediaRequire(buffer.size + bytes.size <= 1024 * 1024, "Media manifest exceeds 1 MiB")
        buffer.write(bytes)
      }
    }
    val base = mediaUrl(type, checked, effective)
    return MediaManifest(buffer.readUtf8(), base, mediaHeaders(checked, base, headers))
  }

  /**
   * Fetches [range] of [url], at most [maxBytes], through [proxy] under [watchdog] with what
   * [headers] of the stream at [streamUrl] [url]'s origin may receive: a part a plan needs before
   * it is complete, such as a DASH representation's segment index.
   */
  suspend fun fetchRange(
    streamUrl: String,
    url: String,
    range: LongRange,
    headers: Map<String, String>,
    proxy: Proxy = Proxy.System,
    maxBytes: Long = 1024 * 1024,
    watchdog: TransferWatchdog = TransferWatchdog.Off,
  ): ByteArray {
    val size = range.last - range.first + 1
    mediaRequire(size in 1..maxBytes, "Media index is too large")
    val buffer = Buffer()
    watchdog.watch { monitor ->
      http.through(proxy).download(url, range, mediaHeaders(streamUrl, url, headers)) { data ->
        monitor.received(data.size)
        mediaRequire(
          buffer.size + data.size <= size, "Media server returned a different byte range",
        )
        buffer.write(data)
      }
    }
    mediaRequire(buffer.size == size, "Media index is incomplete")
    return buffer.readByteArray()
  }

  /** Runs a media transformation, reporting what it cannot read as this source's error. */
  private inline fun <T> media(action: () -> T): T = try {
    action()
  } catch (e: IllegalArgumentException) {
    throw KetchError.SourceError(type, detail = e.message ?: "Invalid media")
  }

  private fun mediaRequire(value: Boolean, message: String) {
    if (!value) throw KetchError.SourceError(type, detail = message)
  }

  private suspend fun disk(action: suspend () -> Unit) {
    try {
      action()
    } catch (e: CancellationException) {
      throw e
    } catch (e: KetchError) {
      throw e
    } catch (e: Exception) {
      throw KetchError.Disk(e)
    }
  }
}

/**
 * What the stream at [url] is called, without an extension: its playlist's name, unless that
 * only says it is a playlist (`master.m3u8`, `index.m3u8`, `dash.mpd`) or there is none
 * (`…/tears-of-steel.ism/.m3u8`). Then the nearest folder of the two above it that names
 * something, as `img_bipbop_adv_example_fmp4` in `…/img_bipbop_adv_example_fmp4/master.m3u8`,
 * without a Smooth Streaming `.ism`; never a signed path's token. Else the playlist's name, or
 * `media`.
 */
internal fun mediaName(url: String): String {
  val path = url.substringBefore('?').substringBefore('#').substringAfter("://", url)
    .substringAfter('/', "")
  val segments = path.split('/').map(DefaultFileNameResolver::percentDecode)
  val file = segments.lastOrNull().orEmpty().substringBeforeLast('.')
  if (file.isNotBlank() && file.lowercase() !in GENERIC_MEDIA_NAMES) return file
  val folder = segments.dropLast(1).takeLast(2).asReversed()
    .map { it.replace(SMOOTH_STREAMING_SUFFIX, "") }
    .firstOrNull { folder ->
      folder.isNotBlank() && folder.lowercase() !in GENERIC_MEDIA_NAMES && folder.length <= 64 &&
        folder.any(Char::isLetter) && folder.none { it in "=~," }
    }
  return folder ?: file.ifBlank { "media" }
}

/** A Smooth Streaming server manifest's folder suffix, `.ism` or `.isml`, in any case. */
private val SMOOTH_STREAMING_SUFFIX = Regex("\\.isml?$", RegexOption.IGNORE_CASE)

/** Playlist and folder names that say only that they hold a stream. */
private val GENERIC_MEDIA_NAMES = setOf(
  "master", "index", "playlist", "manifest", "main", "media", "stream", "streams", "prog_index",
  "chunklist", "dash", "hls", "video", "videos", "audio", "output", "out", "default",
)

