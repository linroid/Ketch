package com.linroid.ketch.torrent.control

import com.linroid.ketch.api.DownloadConfig
import com.linroid.ketch.api.FileSelectionMode
import com.linroid.ketch.api.ResolvedSource
import com.linroid.ketch.api.Segment
import com.linroid.ketch.api.SourceFile
import com.linroid.ketch.core.engine.DownloadContext
import com.linroid.ketch.core.engine.DownloadSource
import com.linroid.ketch.core.engine.HttpEngine
import com.linroid.ketch.core.engine.SelectionPlan
import com.linroid.ketch.core.engine.SelectionRequest
import com.linroid.ketch.core.engine.SelectionUpdate
import com.linroid.ketch.core.engine.ServerInfo
import com.linroid.ketch.core.engine.SourceResumeState
import com.linroid.ketch.core.task.TaskRecord
import com.linroid.ketch.core.task.TaskStore
import com.linroid.ketch.torrent.MemoryTaskStore
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import okio.IOException

/**
 * A copy of core's test source of the same name. A source with several files of [sizes] (IDs "0",
 * "1", …) that writes its own files and follows selections live, like a torrent. Each download or
 * resume is a [Run] that lasts until the test completes its [Run.finish]. With
 * [FileSelectionMode.SINGLE] the files are variants, one of which downloads, "0" when none is
 * chosen.
 */
internal class SelectableSource(
  private val sizes: List<Long> = listOf(100, 200, 300),
  private val mode: FileSelectionMode = FileSelectionMode.MULTIPLE,
) : DownloadSource {
  override val type = TYPE
  override val managesOwnFileIo = true

  /** Whether runs follow delivered selections; when false they never see them. */
  var collects = true

  /** Whether runs acknowledge the selections they apply. */
  var live = true

  /** Whether the source reports progress when it applies a selection, as a busy transfer does. */
  var reportsOnChange = true

  /** Holds metadata lookups until completed. */
  var resolveGate: CompletableDeferred<Unit>? = null

  /** Holds every resume state update, the periodic and the final save's, until completed. */
  var saveGate: CompletableDeferred<Unit>? = null

  var resolves = 0
  var storedResolves = 0

  val runs = MutableStateFlow<List<Run>>(emptyList())

  val ids: Set<String> = sizes.indices.mapTo(linkedSetOf()) { it.toString() }

  /** The files a task with no selection downloads. */
  private val defaults: Set<String> = if (mode == FileSelectionMode.SINGLE) setOf("0") else ids

  /** The [SelectionRequest.fileIds] of every [planSelection] call, in order. */
  val planned = mutableListOf<Set<String>?>()

  class Run(val context: DownloadContext, val resumed: Boolean, val initial: SelectionUpdate) {
    val finish = CompletableDeferred<Unit>()
    val applied = mutableListOf<SelectionUpdate>()
  }

  fun resolved(url: String, marker: String, fileSize: (Int) -> Long = { sizes[it] }) =
    ResolvedSource(
      url = url, sourceType = type, totalBytes = sizes.indices.sumOf(fileSize),
      supportsResume = true, suggestedFileName = "pick", maxSegments = sizes.size,
      metadata = mapOf(ResolvedSource.METAINFO_KEY to "metainfo", MARKER to marker),
      files = sizes.indices.map { SourceFile(it.toString(), "file$it", fileSize(it)) },
      selectionMode = mode,
    )

  override fun canHandle(url: String) = url.startsWith("pick:")

  override suspend fun resolve(url: String, headers: Map<String, String>): ResolvedSource {
    resolves++
    resolveGate?.await()
    return resolved(url, "network")
  }

  override suspend fun resolveForDownload(
    url: String,
    headers: Map<String, String>,
    config: DownloadConfig,
  ): ResolvedSource = resolve(url, headers)

  override fun buildResumeState(resolved: ResolvedSource, totalBytes: Long) =
    SourceResumeState(type, "$STORED${resolved.url}")

  override suspend fun resolveStored(resumeState: SourceResumeState): ResolvedSource? {
    if (!resumeState.data.startsWith(STORED)) return null
    storedResolves++
    return resolved(resumeState.data.removePrefix(STORED), "stored")
  }

  override suspend fun planSelection(request: SelectionRequest): SelectionPlan {
    val known = request.resumeState?.data?.startsWith(STORED) == true || request.resolved != null
    check(known) { "The file list is not known yet" }
    planned += request.fileIds
    val chosen = request.fileIds ?: ids
    require(chosen.isNotEmpty() && chosen.all { it in ids }) { "Unknown file id" }
    if (mode == FileSelectionMode.SINGLE) check(chosen.size == 1) { "Several variants planned" }
    val effective = request.current.ifEmpty { defaults }
    val old = request.segments.orEmpty().associateBy { it.index }
    return SelectionPlan(
      fileIds = chosen,
      totalBytes = totalOf(chosen),
      changed = chosen != effective,
      expands = (chosen - effective).isNotEmpty(),
      segments = layout(chosen) { index ->
        when {
          request.completed && index.toString() in effective -> sizes[index]
          else -> old[index]?.downloadedBytes?.coerceAtMost(sizes[index]) ?: 0
        }
      },
    )
  }

  override suspend fun updateResumeState(context: DownloadContext): SourceResumeState? {
    saveGate?.await()
    return null
  }

  override suspend fun download(context: DownloadContext) = execute(context, resumed = false)

  override suspend fun resume(context: DownloadContext, resumeState: SourceResumeState) =
    execute(context, resumed = true)

  private suspend fun execute(context: DownloadContext, resumed: Boolean): Unit = coroutineScope {
    val initial = context.selection.value
    val run = Run(context, resumed, initial)
    runs.update { it + run }
    context.acknowledgeSelection(initial.revision)
    publish(context, initial.fileIds)
    val collector = if (collects) {
      launch {
        context.selection.collect { update ->
          if (update.revision <= initial.revision) return@collect
          run.applied += update
          if (reportsOnChange) publish(context, update.fileIds)
          if (live) context.acknowledgeSelection(update.revision)
        }
      }
    } else {
      null
    }
    run.finish.await()
    collector?.cancel()
  }

  private suspend fun publish(context: DownloadContext, fileIds: Set<String>) {
    val chosen = fileIds.ifEmpty { defaults }
    context.segments.value = layout(chosen) { 0 }
    context.onProgress(0, totalOf(chosen))
  }

  fun totalOf(fileIds: Set<String>): Long = fileIds.sumOf { sizes[it.toInt()] }

  private fun layout(fileIds: Set<String>, downloaded: (Int) -> Long): List<Segment> {
    var offset = 0L
    return fileIds.map { it.toInt() }.sorted().map { index ->
      val start = offset
      offset += sizes[index]
      Segment(index, start, offset - 1, downloaded(index))
    }
  }

  companion object {
    const val TYPE = "pick"
    const val MARKER = "marker"
    const val STORED = "stored:"
  }
}

/** An in-memory store that keeps every record it saves, in order. */
internal class RecordingTaskStore : TaskStore {
  private val inner = MemoryTaskStore()
  val saves = mutableListOf<TaskRecord>()

  /** Makes every save fail, as a full disk would. */
  var failSaves = false

  override suspend fun save(record: TaskRecord) {
    if (failSaves) throw IOException("No space left")
    saves += record
    inner.save(record)
  }

  override suspend fun load(taskId: String): TaskRecord? = inner.load(taskId)

  override suspend fun loadAll(): List<TaskRecord> = inner.loadAll()

  override suspend fun remove(taskId: String) = inner.remove(taskId)

  /** The last record saved of [taskId], or `null` when none was. */
  fun latest(taskId: String): TaskRecord? = saves.lastOrNull { it.taskId == taskId }
}

/** An HTTP engine for Ketch instances whose tests download nothing over HTTP. */
internal class UnusedHttpEngine : HttpEngine {
  override suspend fun head(url: String, headers: Map<String, String>): ServerInfo =
    throw UnsupportedOperationException("No HTTP in this test")

  override suspend fun download(
    url: String,
    range: LongRange?,
    headers: Map<String, String>,
    onData: suspend (ByteArray) -> Unit,
  ): Unit = throw UnsupportedOperationException("No HTTP in this test")

  override fun close() {}
}
