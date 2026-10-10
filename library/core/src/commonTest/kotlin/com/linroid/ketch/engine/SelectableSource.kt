package com.linroid.ketch.engine

import com.linroid.ketch.api.DownloadConfig
import com.linroid.ketch.api.FileSelectionMode
import com.linroid.ketch.api.ResolvedSource
import com.linroid.ketch.api.Segment
import com.linroid.ketch.api.SourceFile
import com.linroid.ketch.core.engine.DownloadContext
import com.linroid.ketch.core.engine.DownloadSource
import com.linroid.ketch.core.engine.SelectionPlan
import com.linroid.ketch.core.engine.SelectionRequest
import com.linroid.ketch.core.engine.SelectionUpdate
import com.linroid.ketch.core.engine.SourceResumeState
import com.linroid.ketch.core.task.InMemoryTaskStore
import com.linroid.ketch.core.task.TaskRecord
import com.linroid.ketch.core.task.TaskStore
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * A source with several files of [sizes] (IDs "0", "1", …) that writes its own files and follows
 * selections live, like a torrent. Each download or resume is a [Run] that lasts until the test
 * completes its [Run.finish].
 */
internal class SelectableSource(
  private val sizes: List<Long> = listOf(100, 200, 300),
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
      selectionMode = FileSelectionMode.MULTIPLE,
    )

  override fun canHandle(url: String) = url.startsWith("pick:")

  override suspend fun resolve(url: String, properties: Map<String, String>): ResolvedSource {
    resolves++
    resolveGate?.await()
    return resolved(url, "network")
  }

  override suspend fun resolveForDownload(
    url: String,
    properties: Map<String, String>,
    config: DownloadConfig,
  ): ResolvedSource = resolve(url, properties)

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
    val chosen = request.fileIds ?: ids
    require(chosen.isNotEmpty() && chosen.all { it in ids }) { "Unknown file id" }
    val effective = request.current.ifEmpty { ids }
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
    val chosen = fileIds.ifEmpty { ids }
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
  private val inner = InMemoryTaskStore()
  val saves = mutableListOf<TaskRecord>()

  override suspend fun save(record: TaskRecord) {
    saves += record
    inner.save(record)
  }

  override suspend fun load(taskId: String): TaskRecord? = inner.load(taskId)

  override suspend fun loadAll(): List<TaskRecord> = inner.loadAll()

  override suspend fun remove(taskId: String) = inner.remove(taskId)
}
