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
import kotlin.concurrent.atomics.AtomicBoolean
import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.io.encoding.Base64
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okio.Path.Companion.toPath

/**
 * Pure Kotlin BitTorrent v1, v2, and hybrid download source for JVM, Android, and iOS.
 * Supports HTTP(S) metainfo, local paths/file URLs, metainfo bytes, and btih/btmh magnets.
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

  /** Accepts `.torrent` file names, or content that starts like a bencoded dictionary. */
  override fun canHandleContent(content: ByteArray, fileName: String?): Boolean {
    if (fileName?.endsWith(".torrent", ignoreCase = true) == true) return true
    // Metainfo is a dictionary whose first key is length-prefixed, e.g. "d8:announce".
    return content.size >= 2 && content[0] == 'd'.code.toByte() &&
      content[1] in '1'.code.toByte()..'9'.code.toByte()
  }

  /** Resolves dropped or picked `.torrent` bytes via [resolveMetainfo], off the caller thread. */
  override suspend fun resolveContent(content: ByteArray, fileName: String?): ResolvedSource =
    withContext(Dispatchers.Default) {
      try {
        resolveMetainfo(content)
      } catch (e: CancellationException) { throw e
      } catch (e: Exception) {
        if (e is KetchError) throw e
        throw KetchError.SourceError(TYPE, e)
      }
    }

  /** Resolve metainfo supplied by a file picker or SDK caller without making a network request. */
  fun resolveMetainfo(
    bytes: ByteArray,
    privacy: TorrentDiscoveryPrivacy = config.discoveryPrivacy,
  ): ResolvedSource {
    check(!closed.load()) { "Torrent source is closed" }
    resolveV2Metainfo(null, bytes, config, privacy)?.let { return it }
    val metadata = TorrentMetadata.fromBencode(bytes, config.maxMetadataBytes)
    return resolved("torrent:${metadata.infoHash.hex}", metadata, privacy)
  }

  override suspend fun resolve(url: String, properties: Map<String, String>): ResolvedSource =
    resolve(url, config.discoveryPrivacy, properties)

  /** Resolve with an explicit privacy choice before any discovery or metadata request starts. */
  suspend fun resolve(
    url: String,
    privacy: TorrentDiscoveryPrivacy,
    properties: Map<String, String> = emptyMap(),
  ): ResolvedSource {
    check(!closed.load()) { "Torrent source is closed" }
    try {
      val metadata = if (url.startsWith("magnet:", true)) {
        if (MagnetUri.parse(url).identity.v2 != null) {
          val bytes = (getEngine() as KotlinTorrentEngine).fetchV2Metadata(url, privacy)
          return requireNotNull(resolveV2Metainfo(url, bytes, config, privacy))
        }
        val fetched = getEngine().fetchMetadata(url, privacy) ?: throw KetchError.Network(
          Exception("Torrent metadata resolution timed out"))
        if (!isHybridInfo(fetched.infoBytes)) fetched else {
          // A btih-only magnet found a hybrid torrent. Its v2 identity keys the task, so fetch
          // the piece layers under that topic instead of mixing v1 and v2 hashes.
          val v2Hash = V2InfoHash.fromBytes(sha256Digest(fetched.infoBytes))
          val bytes = (getEngine() as KotlinTorrentEngine)
            .fetchV2Metadata("$url&xt=urn:btmh:${v2Hash.multihash()}", privacy)
          return requireNotNull(resolveV2Metainfo(url, bytes, config, privacy))
        }
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
            torrentSystemFileSystem.read(path.toPath()) {
              val result = readByteArray(minOf(config.maxMetadataBytes.toLong(),
                torrentSystemFileSystem.metadata(path.toPath()).size ?: 0L))
              require(exhausted()) { "Metainfo exceeds limit" }
              result
            }
          }
        }
        resolveV2Metainfo(url, bytes, config, privacy)?.let { return it }
        TorrentMetadata.fromBencode(bytes, config.maxMetadataBytes)
      }
      return resolved(url, metadata, privacy)
    } catch (e: TimeoutCancellationException) {
      currentCoroutineContext().ensureActive()
      throw KetchError.Network(Exception("Torrent operation timed out"))
    } catch (e: CancellationException) { throw e
    } catch (e: Exception) {
      if (e is KetchError) throw e
      throw KetchError.SourceError(TYPE, e)
    }
  }

  private fun resolved(
    url: String,
    metadata: TorrentMetadata,
    privacy: TorrentDiscoveryPrivacy,
  ): ResolvedSource = ResolvedSource(
    url = url, sourceType = TYPE, totalBytes = metadata.totalBytes, supportsResume = true,
    suggestedFileName = metadata.name, maxSegments = metadata.files.size,
    metadata = buildMap {
      put(META_INFO_HASH, metadata.infoHash.hex)
      put(META_NAME, metadata.name)
      put(META_PIECE_LENGTH, metadata.pieceLength.toString())
      put(META_METAINFO, encodeBase64(metadata.metainfoBytes))
      put(META_PRIVACY, privacy.name)
      metadata.comment?.let { put(META_COMMENT, it) }
    },
    files = metadata.files.map { SourceFile(it.index.toString(), it.path, it.size,
      metadata = mapOf("path" to it.path)) },
    selectionMode = FileSelectionMode.MULTIPLE,
  )

  override fun buildResumeState(resolved: ResolvedSource, totalBytes: Long): SourceResumeState =
    encode(TorrentResumeState(resolved.metadata[META_INFO_HASH] ?: "", totalBytes, "",
      resolved.files.map { it.id }.toSet(), "", resolved.metadata[META_METAINFO] ?: "",
      version = if (resolved.metadata["format"] == "v2") 3 else 2,
      privacy = resolvedPrivacy(resolved)))

  private fun resolvedPrivacy(resolved: ResolvedSource): TorrentDiscoveryPrivacy =
    resolved.metadata[META_PRIVACY]?.let(TorrentDiscoveryPrivacy::valueOf)
      ?: config.discoveryPrivacy

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
    val checkpoint = if (state.version == 3 || state.resumeData.isEmpty()) null else
      TorrentCheckpoint.decode(decodeBase64(state.resumeData))
    val privacy = state.privacy ?: config.discoveryPrivacy
    val resolved = when {
      state.metainfo.isNotEmpty() -> resolveMetainfo(decodeBase64(state.metainfo), privacy)
      checkpoint != null -> resolved(context.url, checkpoint.metadata, privacy)
      else -> resolve(context.url, privacy, context.headers)
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
    val v2 = resolveV2Metainfo(resolved.url, bytes, config, resolvedPrivacy(resolved))
    if (v2 != null) {
      require(v2.metadata[META_INFO_HASH] == hash) { "Resolved torrent hash mismatch" }
      executeV2(context, v2, previous)
      return
    }
    require(previous?.version != 3) { "Cannot downgrade a v2 task" }
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
    val privacy = previous?.privacy ?: resolvedPrivacy(resolved)
    val state = TorrentResumeState(hash, total, previous?.resumeData ?: "",
      selected.map { it.toString() }.toSet(), output, encodeBase64(bytes),
      version = 2, privacy = privacy)
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
      makeRoomForDownload(runtime)
      session = runtime.addTask(TorrentTaskSpec(context.taskId, metadata, output, selected,
        context.url.takeIf { it.startsWith("magnet:", true) },
        previous?.resumeData?.takeIf { it.isNotEmpty() }?.let(::decodeBase64), context.throttle,
        privacy = privacy))
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
          } else {
            tasks.markSeeding(context.taskId)
          }
        }
      }
    }
  }

  private suspend fun executeV2(
    context: DownloadContext,
    resolved: ResolvedSource,
    previous: TorrentResumeState?,
  ) {
    require(previous == null || previous.version == 3) { "Invalid v2 resume state" }
    val bytes = decodeBase64(resolved.metadata.getValue(META_METAINFO))
    val document = TorrentV2Document.parse(bytes, maxDocumentBytes = config.maxMetadataBytes,
      maxFiles = config.maxFilesPerTorrent)
    val selected = context.request.selectedFileIds.ifEmpty {
      previous?.selectedFileIds.orEmpty()
    }.ifEmpty { resolved.files.map { it.id }.toSet() }
    require(selected.all { id -> resolved.files.any { it.id == id } }) { "Invalid file selection" }
    require(previous == null || previous.savePath.isEmpty() ||
      previous.selectedFileIds == selected) {
      "Resume selection changed"
    }
    val output = context.outputPath ?: previous?.savePath?.takeIf { it.isNotEmpty() }
      ?: error("Torrent output path has not been resolved")
    require("://" !in output) { "Torrent output requires a filesystem path" }
    val privacy = previous?.privacy ?: resolvedPrivacy(resolved)
    val hash = document.info.hash.hex
    val files = resolved.files.filter { it.id in selected }
    val total = files.sumOf { it.size }
    val state = TorrentResumeState(hash, total, previous?.resumeData.orEmpty(), selected, output,
      encodeBase64(bytes), version = 3, privacy = privacy)
    tasks.reserve(context.taskId, hash)
    try {
      stateMutex.withLock {
        while (states.size >= 128 && context.taskId !in states) {
          val finished = checkNotNull(states.keys.firstOrNull { !tasks.isReserved(it) }) {
            "Too many pending torrent tasks"
          }
          states.remove(finished)
        }
        states[context.taskId] = state
      }
      val runtime = getEngine() as KotlinTorrentEngine
      makeRoomForDownload(runtime)
      runtime.withV2Download(context.taskId, document, output, selected,
        checkpointEncoded = previous?.resumeData?.takeIf { it.isNotEmpty() },
        trackerTiers = sourceTrackerTiers(bytes, config.maxMetadataBytes),
        magnetUri = context.url.takeIf { it.startsWith("magnet:", true) },
        privacy = privacy, throttle = context.throttle, recoverCreations = true,
      ) { session ->
        coroutineScope {
          tasks.attach(context.taskId, session)
          val connections = launch {
            context.maxConnections.collect { value ->
              session.setConnections(if (value > 0) minOf(value, 500)
                else minOf(config.connectionsPerTorrent, 500))
            }
          }
          val clock = monotonicClock()
          var lastTime = clock()
          var lastReceived = session.receivedBytes()
          try {
            session.resume()
            while (true) {
              val status = session.state.value
              val progress = session.fileProgress()
              var offset = 0L
              context.segments.value = files.map { file ->
                Segment(file.id.toInt(), offset, offset + file.size - 1,
                  progress[file.id] ?: 0).also { offset += file.size }
              }
              val now = clock()
              val received = session.receivedBytes()
              context.reportedSpeed.value = if (now > lastTime) {
                (received - lastReceived) * 1000 / (now - lastTime)
              } else 0
              lastReceived = received
              lastTime = now
              context.onProgress(context.segments.value.sumOf { it.downloadedBytes }, total)
              when (status) {
                TorrentSessionState.FINISHED -> break
                TorrentSessionState.STOPPED -> throw (session.failure.value
                  ?: IllegalStateException("Torrent session stopped"))
                else -> delay(200)
              }
            }
          } finally {
            withContext(NonCancellable) {
              connections.cancel()
              session.pause()
              updateResumeState(context)
            }
          }
        }
      }
    } catch (error: CancellationException) { throw error
    } catch (error: Exception) { throw KetchError.SourceError(TYPE, error)
    } finally { tasks.release(context.taskId) }
  }

  /**
   * Seeding is optional background work, so a new download takes the slot of the oldest seeder.
   * Only tasks whose download already returned are evicted; a live download never sees its
   * session stop underneath it.
   */
  private suspend fun makeRoomForDownload(runtime: TorrentEngine) {
    val kotlin = runtime as? KotlinTorrentEngine ?: return
    while (!kotlin.hasFreeSlot()) {
      release(tasks.oldestSeeding() ?: return, null)
    }
  }

  /** Stops a session that outlived its download, such as one still seeding. */
  override suspend fun release(taskId: String, resumeState: SourceResumeState?) {
    val session = tasks.session(taskId) ?: return
    try {
      engine.load()?.removeTorrent(session.infoHash)
    } finally {
      tasks.release(taskId)
    }
  }

  override suspend fun cleanup(context: DownloadContext, resumeState: SourceResumeState?) {
    if (resumeState == null) return
    try {
      val state = Json.decodeFromString<TorrentResumeState>(resumeState.data)
      if (state.version == 3) {
        cleanupV2(context, state)
        return
      }
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

  private suspend fun cleanupV2(context: DownloadContext, state: TorrentResumeState) {
    check(!tasks.isReserved(context.taskId)) { "Join the torrent download before cleanup" }
    val document = TorrentV2Document.parse(decodeBase64(state.metainfo),
      maxDocumentBytes = config.maxMetadataBytes, maxFiles = config.maxFilesPerTorrent)
    require(document.info.hash.hex == state.infoHash) { "Resume torrent changed" }
    val output = (context.outputPath ?: state.savePath).toPath()
    val absolute = torrentSystemFileSystem.canonicalize(".".toPath()).resolve(output).normalized()
    val selected = if (state.resumeData.isEmpty() && state.savePath.isEmpty()) {
      context.request.selectedFileIds.ifEmpty { state.selectedFileIds }
    } else state.selectedFileIds
    val store = TorrentV2PieceStore(document, absolute, selected, context.taskId,
      TorrentBufferBudget(config.maxBufferedBytes),
      kotlinx.coroutines.sync.Semaphore(config.maxOpenPayloadFiles),
      creationLogPath = v2CreationLog(absolute, context.taskId))
    try {
      state.resumeData.takeIf { it.isNotEmpty() }?.let {
        store.restore(requireNotNull(TorrentV2Checkpoint.decode(decodeBase64(it))))
      }
      store.recoverOwnership()
      store.cleanup()
      stateMutex.withLock { states.remove(context.taskId) }
    } finally { store.close() }
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
    internal const val META_PRIVACY = "discoveryPrivacy"

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

private fun isHybridInfo(infoBytes: ByteArray): Boolean = infoBytes.isNotEmpty() &&
  Bencode.parse(infoBytes, infoBytes.size)["meta version"]?.integer == 2L
