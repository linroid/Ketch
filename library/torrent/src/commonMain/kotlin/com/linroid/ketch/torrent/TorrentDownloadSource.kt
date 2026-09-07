package com.linroid.ketch.torrent

import com.linroid.ketch.api.FileSelectionMode
import com.linroid.ketch.api.KetchError
import com.linroid.ketch.api.ResolvedSource
import com.linroid.ketch.api.Segment
import com.linroid.ketch.api.SourceFile
import com.linroid.ketch.core.engine.DownloadContext
import com.linroid.ketch.core.engine.DownloadSource
import com.linroid.ketch.core.engine.HttpEngine
import com.linroid.ketch.core.engine.SourceResumeState
import io.ktor.http.Url
import io.ktor.http.decodeURLPart
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okio.FileSystem
import okio.Path.Companion.toPath
import kotlin.concurrent.atomics.AtomicBoolean
import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.io.encoding.Base64

/**
 * Pure Kotlin BitTorrent v1 source for JVM, Android, and iOS.
 * Supports HTTP(S) metainfo, local paths/file URLs, metainfo bytes, and btih magnets.
 * The optional HTTP engine remains owned by its caller. Output must be a filesystem path.
 */
@OptIn(ExperimentalAtomicApi::class)
class TorrentDownloadSource(
  private val config: TorrentConfig = TorrentConfig(),
  httpEngine: HttpEngine? = null,
) : DownloadSource {
  private val httpDelegate = lazy { httpEngine?.let { TorrentHttp(it) } ?: TorrentHttp.default() }
  private val http by httpDelegate
  internal var engineFactory: () -> TorrentEngine = { KotlinTorrentEngine(config, http = http) }
  private val engine = AtomicReference<TorrentEngine?>(null)
  private val closed = AtomicBoolean(false)
  private val engineMutex = Mutex()
  private val tasks = TorrentSessionRegistry()
  private val stateMutex = Mutex()
  private val states = linkedMapOf<String, TorrentResumeState>()
  override val type: String = TYPE
  override val managesOwnFileIo: Boolean = true

  private suspend fun getEngine(): TorrentEngine = engineMutex.withLock {
    check(!closed.load()) { "Torrent source is closed" }
    engine.load()?.let { return@withLock it }
    val created = engineFactory()
    try {
      created.start()
      engine.store(created)
      if (closed.load()) { engine.exchange(null)?.close(); error("Torrent source is closed") }
      created
    } catch (e: Throwable) { created.close(); throw e }
  }

  override fun close() {
    if (closed.compareAndSet(false, true)) {
      engine.exchange(null)?.close()
      if (httpDelegate.isInitialized()) http.close()
    }
  }

  /** Change the shared upload rate immediately; zero means unlimited. */
  suspend fun setUploadRateLimit(bytesPerSecond: Long) {
    require(bytesPerSecond >= 0)
    getEngine().setUploadRateLimit(bytesPerSecond)
  }

  /** Change upload rate for an active task, including a completed task that is seeding. */
  suspend fun setTaskUploadRateLimit(taskId: String, bytesPerSecond: Long) {
    require(bytesPerSecond >= 0)
    checkNotNull(tasks.session(taskId) as? KotlinTorrentSession) { "Torrent task is not active" }
      .setUploadRateLimit(bytesPerSecond)
  }

  /** Change the shared TCP connection bound. Existing excess connections are closed. */
  suspend fun setConnectionLimit(connections: Int) {
    require(connections in 1..4096)
    (getEngine() as KotlinTorrentEngine).setConnections(connections)
  }

  override fun canHandle(url: String): Boolean {
    val lower = url.lowercase()
    return lower.startsWith("magnet:") || lower.startsWith("torrent:") ||
      lower.substringBefore('?').substringBefore('#').endsWith(".torrent")
  }

  /** Resolve metainfo supplied by a file picker or SDK caller without making a network request. */
  fun resolveMetainfo(bytes: ByteArray): ResolvedSource {
    check(!closed.load()) { "Torrent source is closed" }
    val metadata = TorrentMetadata.fromBencode(bytes, config.maxMetadataBytes)
    return resolved("torrent:${metadata.infoHash.hex}", metadata)
  }

  override suspend fun resolve(url: String, properties: Map<String, String>): ResolvedSource {
    check(!closed.load()) { "Torrent source is closed" }
    try {
      val metadata = if (url.startsWith("magnet:", true)) {
        getEngine().fetchMetadata(url) ?: throw KetchError.Network(
          Exception("Torrent metadata resolution timed out"))
      } else {
        val bytes = if (url.startsWith("https://", true) || url.startsWith("http://", true)) {
          http.fetch(url, config.maxMetadataBytes, headers = properties)
        } else {
          val path = if (url.startsWith("file:", true)) {
            val parsed = Url(url)
            require(parsed.host.isEmpty() || parsed.host == "localhost")
            parsed.encodedPath.decodeURLPart()
          } else {
            require("://" !in url && !url.startsWith("torrent:")) {
              "Metainfo bytes are required for this input"
            }
            url
          }
          withContext(Dispatchers.IO) {
            FileSystem.SYSTEM.read(path.toPath()) {
              val result = readByteArray(minOf(config.maxMetadataBytes.toLong(),
                FileSystem.SYSTEM.metadata(path.toPath()).size ?: 0L))
              require(exhausted()) { "Metainfo exceeds limit" }
              result
            }
          }
        }
        TorrentMetadata.fromBencode(bytes, config.maxMetadataBytes)
      }
      return resolved(url, metadata)
    } catch (e: TimeoutCancellationException) {
      currentCoroutineContext().ensureActive()
      throw KetchError.Network(Exception("Torrent operation timed out"))
    } catch (e: CancellationException) { throw e
    } catch (e: Exception) {
      if (e is KetchError) throw e
      throw KetchError.SourceError(TYPE, e)
    }
  }

  private fun resolved(url: String, metadata: TorrentMetadata): ResolvedSource = ResolvedSource(
    url = url, sourceType = TYPE, totalBytes = metadata.totalBytes, supportsResume = true,
    suggestedFileName = metadata.name, maxSegments = metadata.files.size,
    metadata = buildMap {
      put(META_INFO_HASH, metadata.infoHash.hex)
      put(META_NAME, metadata.name)
      put(META_PIECE_LENGTH, metadata.pieceLength.toString())
      put(META_METAINFO, encodeBase64(metadata.metainfoBytes))
      metadata.comment?.let { put(META_COMMENT, it) }
    },
    files = metadata.files.map { SourceFile(it.index.toString(), it.path, it.size,
      metadata = mapOf("path" to it.path)) },
    selectionMode = FileSelectionMode.MULTIPLE,
  )

  override fun buildResumeState(resolved: ResolvedSource, totalBytes: Long): SourceResumeState =
    encode(TorrentResumeState(resolved.metadata[META_INFO_HASH] ?: "", totalBytes, "",
      resolved.files.map { it.id }.toSet(), "", resolved.metadata[META_METAINFO] ?: ""))

  override suspend fun updateResumeState(context: DownloadContext): SourceResumeState? {
    val owner = tasks.session(context.taskId)
    val saved = owner?.saveResumeData()
    return stateMutex.withLock {
      val state = states[context.taskId] ?: return@withLock null
      val updated = if (saved != null) state.copy(resumeData = encodeBase64(saved)) else state
      if (owner == null) states.remove(context.taskId) else states[context.taskId] = updated
      encode(updated)
    }
  }

  override suspend fun download(context: DownloadContext) {
    val resolved = context.preResolved ?: resolve(context.url, context.headers)
    execute(context, resolved, null)
  }

  override suspend fun resume(context: DownloadContext, resumeState: SourceResumeState) {
    val state = try { Json.decodeFromString<TorrentResumeState>(resumeState.data)
    } catch (e: Exception) { throw KetchError.CorruptResumeState(e.message, e) }
    val checkpoint = if (state.resumeData.isEmpty()) null else
      TorrentCheckpoint.decode(decodeBase64(state.resumeData))
    val resolved = when {
      state.metainfo.isNotEmpty() -> resolveMetainfo(decodeBase64(state.metainfo))
      checkpoint != null -> resolved(context.url, checkpoint.metadata)
      else -> resolve(context.url, context.headers)
    }
    require(resolved.metadata[META_INFO_HASH] == state.infoHash) { "Resume torrent changed" }
    execute(context, resolved, state)
  }

  private suspend fun execute(
    context: DownloadContext,
    resolved: ResolvedSource,
    previous: TorrentResumeState?,
  ) {
    val hash = requireNotNull(resolved.metadata[META_INFO_HASH])
    val bytes = decodeBase64(requireNotNull(resolved.metadata[META_METAINFO]))
    val metadata = TorrentMetadata.fromBencode(bytes, config.maxMetadataBytes)
    require(metadata.infoHash.hex == hash) { "Resolved torrent hash mismatch" }
    val selectedIds = context.request.selectedFileIds.ifEmpty {
      previous?.selectedFileIds ?: emptySet()
    }
    val selected = if (selectedIds.isEmpty()) metadata.files.indices.toSet() else {
      selectedIds.map { id ->
        requireNotNull(id.toIntOrNull()).also { require(it in metadata.files.indices) }
      }.toSet()
    }
    val output = context.outputPath ?: previous?.savePath?.takeIf { it.isNotEmpty() }
      ?: error("Torrent output path has not been resolved")
    require(!output.contains("://")) { "Torrent output requires a filesystem path" }
    val total = metadata.files.filter { it.index in selected }.sumOf { it.size }
    val state = TorrentResumeState(hash, total, previous?.resumeData ?: "",
      selected.map { it.toString() }.toSet(), output, encodeBase64(bytes))
    tasks.reserve(context.taskId, hash)
    var session: TorrentSession? = null
    var keepSeeding = false
    try {
      stateMutex.withLock {
        // Finished snapshots are bounded; persisted task state and the ownership journal recover
        // tasks after eviction. Active entries must remain until the final core snapshot.
        while (states.size >= 128 && context.taskId !in states) {
          val finished = states.keys.firstOrNull { !tasks.isReserved(it) }
          check(finished != null) { "Too many pending torrent tasks" }
          states.remove(finished)
        }
        states[context.taskId] = state
      }
      val runtime = getEngine()
      session = runtime.addTask(TorrentTaskSpec(context.taskId, metadata, output, selected,
        context.url.takeIf { it.startsWith("magnet:", true) },
        previous?.resumeData?.takeIf { it.isNotEmpty() }?.let(::decodeBase64), context.throttle))
      tasks.attach(context.taskId, session)
      coroutineScope {
        val connections = launch {
          context.maxConnections.collect { value ->
            (session as? KotlinTorrentSession)?.setConnections(
              if (value > 0) value.coerceAtMost(512) else config.connectionsPerTorrent)
          }
        }
        try {
          session.resume()
          while (true) {
            val currentState = session.state.value
            val progress = (session as? KotlinTorrentSession)?.fileProgress()
              ?: LongArray(metadata.files.size)
            var offset = 0L
            context.segments.value = metadata.files.filter { it.index in selected }.map { file ->
              Segment(file.index, offset, offset + file.size - 1,
                progress[file.index]).also { offset += file.size }
            }
            context.reportedSpeed.value = session.downloadSpeed
            context.onProgress(context.segments.value.sumOf { it.downloadedBytes }, total)
            when (currentState) {
              TorrentSessionState.FINISHED -> break
              TorrentSessionState.SEEDING -> { keepSeeding = true; break }
              TorrentSessionState.STOPPED -> throw ((session as? KotlinTorrentSession)
                ?.failure?.value ?: error("Torrent session stopped"))
              else -> delay(200)
            }
          }
        } finally { connections.cancel() }
      }
    } catch (e: CancellationException) { throw e
    } catch (e: Exception) { throw KetchError.SourceError(TYPE, e)
    } finally {
      withContext(NonCancellable) {
        try {
          if (!keepSeeding) session?.pause()
          updateResumeState(context)
        } finally {
          if (!keepSeeding) {
            engine.load()?.removeTorrent(hash)
            tasks.release(context.taskId)
          }
        }
      }
    }
  }

  override suspend fun cleanup(context: DownloadContext, resumeState: SourceResumeState?) {
    if (resumeState == null) return
    try {
      val state = Json.decodeFromString<TorrentResumeState>(resumeState.data)
      if (tasks.session(context.taskId) != null) {
        engine.load()?.removeTorrent(state.infoHash, deleteFiles = true)
      }
      tasks.release(context.taskId)
      val checkpoint = state.resumeData.takeIf { it.isNotEmpty() }
        ?.let { TorrentCheckpoint.decode(decodeBase64(it)) }
      val metadata = checkpoint?.metadata ?: state.metainfo.takeIf { it.isNotEmpty() }
        ?.let { TorrentMetadata.fromBencode(decodeBase64(it)) } ?: return
      val output = context.outputPath ?: checkpoint?.output ?: state.savePath
      if (output.isEmpty()) return
      val store = TorrentPieceStore(metadata, output.toPath(),
        state.selectedFileIds.map { it.toInt() }.toSet(), context.taskId)
      checkpoint?.let { store.restore(it) }
      store.recoverOwnership()
      store.cleanup()
      stateMutex.withLock { states.remove(context.taskId) }
    } catch (e: CancellationException) { throw e
    } catch (_: Exception) {
      // Unknown or legacy ownership is conservatively preserved.
    }
  }

  private fun encode(state: TorrentResumeState): SourceResumeState =
    SourceResumeState(TYPE, Json.encodeToString(state))

  companion object {
    const val TYPE = "torrent"
    internal const val META_INFO_HASH = "infoHash"
    internal const val META_NAME = "name"
    internal const val META_PIECE_LENGTH = "pieceLength"
    internal const val META_COMMENT = "comment"
    internal const val META_METAINFO = "metainfo"

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
internal fun encodeBase64(data: ByteArray): String = Base64.Default.encode(data)

/** Platform-specific base64 decoding. */
internal fun decodeBase64(data: String): ByteArray = Base64.Default.decode(data)
