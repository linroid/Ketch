package com.linroid.ketch.metalink

import com.linroid.ketch.api.DownloadConfig
import com.linroid.ketch.api.FileSelectionMode
import com.linroid.ketch.api.KetchError
import com.linroid.ketch.api.KetchFeatures
import com.linroid.ketch.api.Proxy
import com.linroid.ketch.api.ResolveInput
import com.linroid.ketch.api.ResolvedSource
import com.linroid.ketch.api.SourceFile
import com.linroid.ketch.api.log.KetchLogger
import com.linroid.ketch.api.log.describeCauses
import com.linroid.ketch.core.digest.sha256Digest
import com.linroid.ketch.core.engine.DownloadContext
import com.linroid.ketch.core.engine.DownloadSource
import com.linroid.ketch.core.engine.HttpEngine
import com.linroid.ketch.core.engine.SelectionPlan
import com.linroid.ketch.core.engine.SelectionRequest
import com.linroid.ketch.core.engine.TransferWatchdog
import com.linroid.ketch.core.mirror.MirrorDownloadHelper
import com.linroid.ketch.core.mirror.mirrorHeaders
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlin.time.TimeSource

/**
 * Downloads files a Metalink describes (RFC 5854 `.meta4`, or Metalink 3 `.metalink`) from all
 * their HTTP(S) mirrors at once, through [MirrorDownloadHelper]: each segment comes from the
 * least busy mirror, moves to another when its mirror fails, and pieces that fail their hashes
 * are fetched again from other mirrors. Ketch then verifies the whole file against the
 * strongest hash the Metalink gives ([ResolvedSource.checksum]).
 *
 * It takes `http(s)` URLs ending in `.meta4` or `.metalink`, and such documents as content
 * ([ResolveInput.Content]), whose tasks download from `metalink:` URLs. The document is kept
 * with the task, so a resume never fetches it again. A Metalink listing several files offers
 * them as a single choice ([FileSelectionMode.SINGLE]): a task downloads one, saved under its own
 * name, chosen before the task starts.
 *
 * Register through `Ketch(additionalSources = listOf(MetalinkDownloadSource(httpEngine)))`.
 * Transfers share the supplied engine; closing this source does not close it.
 */
class MetalinkDownloadSource internal constructor(
  private val httpEngine: HttpEngine,
  timeSource: TimeSource,
) : DownloadSource<MetalinkSourceState> {
  constructor(httpEngine: HttpEngine) : this(httpEngine, TimeSource.Monotonic)

  private val log = KetchLogger("MetalinkSource")
  private val helper = MirrorDownloadHelper(httpEngine, TYPE, timeSource)

  override val type: String = TYPE

  override val features: Set<String> = setOf(
    KetchFeatures.METALINK, KetchFeatures.FILE_SELECTION, KetchFeatures.AWAIT_FILE_SELECTION,
  )

  override val stateSerializer: KSerializer<MetalinkSourceState> =
    MetalinkSourceState.serializer()

  override fun canHandle(input: ResolveInput): Boolean = when (input) {
    is ResolveInput.Url -> {
      val lower = input.url.lowercase()
      val path = lower.substringBefore('#').substringBefore('?')
      lower.startsWith(SCHEME) ||
        (lower.startsWith("http://") || lower.startsWith("https://")) &&
        EXTENSIONS.any { path.endsWith(it) }
    }
    is ResolveInput.Content ->
      EXTENSIONS.any { input.fileName?.lowercase()?.endsWith(it) == true } ||
        input.bytes.copyOf(minOf(input.bytes.size, SNIFF_BYTES)).decodeToString()
          .let { it.trimStart('﻿', ' ', '\t', '\r', '\n').startsWith("<") && "<metalink" in it }
  }

  override suspend fun resolve(input: ResolveInput, config: DownloadConfig): ResolvedSource =
    when (input) {
      is ResolveInput.Url -> {
        if (input.url.startsWith(SCHEME, ignoreCase = true)) {
          throw KetchError.SourceError(TYPE, detail = "Open the Metalink file again to download it")
        }
        val watchdog = TransferWatchdog.of(config)
        val (text, secure) = fetch(input.url, input.headers, config.proxy, watchdog)
        resolved(input.url, parse(text, secure), input.headers, config)
      }
      is ResolveInput.Content -> {
        if (input.bytes.size > MAX_DOCUMENT_BYTES) {
          throw KetchError.SourceError(TYPE, detail = "The Metalink is larger than 8 MiB")
        }
        // Picked by the user, not fetched, so plain HTTP mirrors are theirs to trust.
        val metalink = parse(input.bytes.decodeToString(), secure = false)
        val id = sha256Digest(input.bytes).take(16).joinToString("") {
          (it.toInt() and 0xff).toString(16).padStart(2, '0')
        }
        resolved("$SCHEME$id", metalink, emptyMap(), config)
      }
    }

  override suspend fun planSelection(
    request: SelectionRequest<MetalinkSourceState>,
  ): SelectionPlan {
    val metalink = metalinkOf(request.resolved)
      ?: throw IllegalStateException("The files of this Metalink are not known yet")
    val ids = request.fileIds
    require(ids == null || ids.size == 1) { "Choose one file of this Metalink" }
    val id = ids?.single() ?: DEFAULT_FILE
    val file = fileOf(metalink, id) ?: throw IllegalArgumentException("Unknown file")
    val current = request.current.singleOrNull() ?: DEFAULT_FILE
    val changed = id != current
    // A task saves one file under the name it started with; another file is another task.
    check(!changed || request.outputPath == null && !request.completed) {
      "This download already saves another file; add the Metalink again for this one"
    }
    // Sizes are asked of the mirrors while resolving, with the request's headers and proxy.
    val size = file.size
      ?: throw IllegalStateException("No mirror reported the size of this file")
    return SelectionPlan(setOf(id), size, changed, expands = changed, segments = null)
  }

  override suspend fun download(context: DownloadContext, state: MetalinkSourceState?) {
    val metalink = metalinkOf(context.preResolved)
      ?: metalinkOf(resolve(ResolveInput.Url(context.url, context.headers), context.config))
      ?: throw KetchError.SourceError(TYPE, detail = "The Metalink of this download is gone")
    val selection = context.selection.value
    val id = selection.fileIds.singleOrNull() ?: DEFAULT_FILE
    val file = fileOf(metalink, id)
      ?: throw KetchError.SourceError(TYPE, detail = "The Metalink has no such file")
    val size = file.size
      ?: probeSize(file, context.url, context.headers, context.config.proxy, context.watchdog)
      ?: throw KetchError.SourceError(TYPE, detail = "No mirror reports the size of the file")
    if (state != null && state.fileId != id) {
      log.w { "taskId=${context.taskId} saved another file of its Metalink; starting over" }
      context.segments.value = emptyList()
    }
    context.publishedChecksum = file.checksum
    context.acknowledgeSelection(selection.revision)
    helper.download(context, file.mirrors(), size, file.pieceHashes())
  }

  /** The file the run downloads, so a resume can tell its bytes are of that file. */
  override suspend fun store(context: DownloadContext): MetalinkSourceState =
    MetalinkSourceState(context.selection.value.fileIds.singleOrNull() ?: DEFAULT_FILE)

  private suspend fun resolved(
    url: String,
    parsed: Metalink,
    headers: Map<String, String>,
    config: DownloadConfig,
  ): ResolvedSource {
    val metalink = withProbedSizes(parsed, url, headers, config.proxy, TransferWatchdog.of(config))
    val default = metalink.files.first()
    if (default.size == null) {
      throw KetchError.SourceError(
        TYPE,
        detail = "The Metalink gives no size and no mirror reports one",
      )
    }
    val several = metalink.files.size > 1
    log.i {
      "Resolved Metalink: files=${metalink.files.size}, mirrors=${default.urls.size}, " +
        "pieces=${default.pieces?.hashes?.size ?: 0}, checksum=${default.checksum?.algorithm}"
    }
    return ResolvedSource(
      url = url,
      sourceType = TYPE,
      totalBytes = default.size ?: -1,
      supportsResume = true,
      suggestedFileName = default.name,
      maxSegments = config.maxConnectionsPerDownload,
      metadata = mapOf(
        ResolvedSource.METAINFO_KEY to json.encodeToString(Metalink.serializer(), metalink),
        META_MIRRORS to default.urls.size.toString(),
      ),
      files = if (several) {
        metalink.files.mapIndexed { index, file ->
          SourceFile(
            id = index.toString(),
            name = file.name,
            size = file.size ?: -1,
            metadata = buildMap {
              put(SourceFile.FILE_NAME_KEY, file.name)
              put(META_MIRRORS, file.urls.size.toString())
              file.checksum?.let { put(META_CHECKSUM, it.toString()) }
            },
          )
        }
      } else {
        emptyList()
      },
      selectionMode = if (several) FileSelectionMode.SINGLE else FileSelectionMode.MULTIPLE,
      checksum = default.checksum,
    )
  }

  /**
   * [metalink] with the sizes it lacks, of its first [PROBED_FILES] files, asked of their mirrors
   * with the request's [headers] and [proxy] under [watchdog], so a file chosen later is sized as
   * it is fetched.
   */
  private suspend fun withProbedSizes(
    metalink: Metalink,
    from: String,
    headers: Map<String, String>,
    proxy: Proxy,
    watchdog: TransferWatchdog,
  ): Metalink {
    val missing = metalink.files.withIndex().filter { it.value.size == null }.take(PROBED_FILES)
    if (missing.isEmpty()) return metalink
    val sizes = coroutineScope {
      missing.map { (index, file) ->
        async { index to probeSize(file, from, headers, proxy, watchdog) }
      }.awaitAll()
    }.toMap()
    return metalink.copy(
      files = metalink.files.mapIndexed { index, file ->
        sizes[index]?.let { file.copy(size = it) } ?: file
      },
    )
  }

  /**
   * Fetches the document at [url], at most [MAX_DOCUMENT_BYTES], under [watchdog], and whether it
   * came over TLS.
   */
  private suspend fun fetch(
    url: String,
    headers: Map<String, String>,
    proxy: Proxy,
    watchdog: TransferWatchdog,
  ): Pair<String, Boolean> {
    val chunks = mutableListOf<ByteArray>()
    var size = 0
    val final = watchdog.watch { monitor ->
      engine(proxy).downloadResource(url, headers) { bytes ->
        monitor.received(bytes.size)
        if (bytes.size > MAX_DOCUMENT_BYTES - size) {
          throw KetchError.SourceError(TYPE, detail = "The Metalink is larger than 8 MiB")
        }
        size += bytes.size
        chunks += bytes
      }
    }
    val bytes = ByteArray(size)
    var offset = 0
    for (chunk in chunks) {
      chunk.copyInto(bytes, offset)
      offset += chunk.size
    }
    return bytes.decodeToString() to final.startsWith("https://", ignoreCase = true)
  }

  /**
   * The size the first mirrors of [file] that answer `HEAD` report, or `null`; a mirror that does
   * not answer within [watchdog]'s limits is passed over.
   */
  private suspend fun probeSize(
    file: MetalinkFile,
    from: String,
    headers: Map<String, String>,
    proxy: Proxy,
    watchdog: TransferWatchdog,
  ): Long? {
    val engine = engine(proxy)
    for ((index, mirror) in file.urls.take(PROBED_MIRRORS).withIndex()) {
      try {
        val info = watchdog.watch {
          engine.head(mirror.url, mirrorHeaders(from, mirror.url, headers))
        }
        info.contentLength?.takeIf { it > 0 }?.let { return it }
      } catch (e: CancellationException) {
        throw e
      } catch (e: Exception) {
        log.d { "Mirror #$index gave no size: ${e.describeCauses()}" }
      }
    }
    return null
  }

  private fun engine(proxy: Proxy): HttpEngine =
    if (proxy == Proxy.System) httpEngine else httpEngine.withProxy(proxy)

  private fun parse(text: String, secure: Boolean): Metalink = try {
    parseMetalink(text, secure)
  } catch (e: MetalinkException) {
    throw KetchError.SourceError(TYPE, e, e.message)
  }

  private fun metalinkOf(resolved: ResolvedSource?): Metalink? {
    val saved = resolved?.metadata?.get(ResolvedSource.METAINFO_KEY) ?: return null
    return try {
      json.decodeFromString(Metalink.serializer(), saved)
    } catch (e: SerializationException) {
      log.w { "Saved Metalink is unreadable: ${e.describeCauses()}" }
      null
    } catch (e: IllegalArgumentException) {
      log.w { "Saved Metalink is unreadable: ${e.describeCauses()}" }
      null
    }
  }

  private fun fileOf(metalink: Metalink, id: String): MetalinkFile? =
    id.toIntOrNull()?.let { metalink.files.getOrNull(it) }

  companion object {
    const val TYPE: String = "metalink"

    /** [ResolvedSource.metadata] and [SourceFile.metadata] key of the number of mirrors. */
    const val META_MIRRORS: String = "metalink.mirrors"

    /** [SourceFile.metadata] key of a file's strongest checksum, as `<algorithm>:<hex>`. */
    const val META_CHECKSUM: String = "metalink.checksum"

    /** The largest Metalink document read. */
    const val MAX_DOCUMENT_BYTES: Int = 8 * 1024 * 1024

    private const val SCHEME = "metalink:"
    private const val DEFAULT_FILE = "0"
    private const val SNIFF_BYTES = 1024
    private const val PROBED_MIRRORS = 3
    private const val PROBED_FILES = 32
    private val EXTENSIONS = listOf(".meta4", ".metalink")
    private val json = Json { ignoreUnknownKeys = true }
  }
}
