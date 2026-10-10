package com.linroid.ketch.core.torrent

import com.linroid.ketch.api.DownloadState
import com.linroid.ketch.api.DownloadTask
import com.linroid.ketch.api.KetchError
import com.linroid.ketch.api.PauseReason
import com.linroid.ketch.api.ResolvedSource
import com.linroid.ketch.api.Segment
import com.linroid.ketch.api.SourceFile
import com.linroid.ketch.api.log.KetchLogger
import com.linroid.ketch.api.torrent.TorrentActivity
import com.linroid.ketch.api.torrent.TorrentCapabilities
import com.linroid.ketch.api.torrent.TorrentCapability
import com.linroid.ketch.api.torrent.TorrentCommandContext
import com.linroid.ketch.api.torrent.TorrentCommandError
import com.linroid.ketch.api.torrent.TorrentCommandException
import com.linroid.ketch.api.torrent.TorrentCommandResult
import com.linroid.ketch.api.torrent.TorrentController
import com.linroid.ketch.api.torrent.TorrentCounters
import com.linroid.ketch.api.torrent.TorrentFileEntry
import com.linroid.ketch.api.torrent.TorrentFileOrder
import com.linroid.ketch.api.torrent.TorrentFilePage
import com.linroid.ketch.api.torrent.TorrentPageRequest
import com.linroid.ketch.api.torrent.TorrentSnapshot
import com.linroid.ketch.api.torrent.sortedByFileOrder
import com.linroid.ketch.core.engine.LiveTorrent
import com.linroid.ketch.core.engine.SeedingOutcome
import com.linroid.ketch.core.engine.SourceResumeState
import com.linroid.ketch.core.engine.TorrentControlSource
import com.linroid.ketch.core.task.ControlCommand
import com.linroid.ketch.core.task.ControlOutcome
import com.linroid.ketch.core.task.RealDownloadTask
import com.linroid.ketch.core.task.TaskController
import com.linroid.ketch.core.task.TaskControl
import com.linroid.ketch.core.task.TaskHandle
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import okio.ByteString.Companion.decodeBase64
import okio.ByteString.Companion.encodeUtf8
import okio.IOException
import kotlin.time.Duration.Companion.seconds

/**
 * Ketch's [TorrentController] over a [TorrentControlSource]: snapshots, file pages and
 * subscriptions from the task records Ketch owns and the source's live sessions, and selection
 * and seeding commands through [controller], which keeps their retry ledger in the task record.
 *
 * @param sourceType the [com.linroid.ketch.core.engine.DownloadSource.type] of [source]
 * @param handlesUrl whether [source] downloads a URL, for tasks that have not resolved yet
 * @param seed starts seeding a completed task in the background
 */
internal class KetchTorrentController(
  private val source: TorrentControlSource,
  private val sourceType: String,
  private val tasks: StateFlow<List<DownloadTask>>,
  private val handlesUrl: (String) -> Boolean,
  private val controller: TaskController,
  private val seed: (TaskHandle) -> Unit,
  private val revisions: TorrentRevisions = TorrentRevisions(),
) : TorrentController {
  private val log = KetchLogger("TorrentController")
  private val subscriptions = Semaphore(MAX_SUBSCRIPTIONS)
  private val cacheMutex = Mutex()
  // Access order: the least recently used file list is dropped first.
  private val fileCache = LinkedHashMap<String, CachedFiles>()

  private class CachedFiles(
    val resumeState: SourceResumeState?,
    val resolved: ResolvedSource?,
    val files: List<SourceFile>,
  )

  /** A snapshot without its revision; equal contents publish the same revision. */
  private data class Content(
    val activity: TorrentActivity,
    val generation: Long,
    val selectionComplete: Boolean,
    val counters: TorrentCounters?,
    val seedingIntent: Boolean,
  ) {
    val control get() = TorrentRevisions.Control(generation, seedingIntent)
  }

  override suspend fun capabilities(): TorrentCapabilities = TorrentCapabilities(
    protocolMajor = 1,
    names = setOf(TorrentCapability.INSPECT.wireName) + source.torrentCapabilities,
    maxPageSize = MAX_PAGE_SIZE,
    maxSubscriptions = MAX_SUBSCRIPTIONS,
    backgroundTransfers = false,
  )

  override suspend fun snapshot(taskId: String): TorrentSnapshot? {
    val task = torrentTask(taskId) ?: return null
    return snapshotOf(task)
  }

  override fun observe(taskId: String): Flow<TorrentSnapshot?> = channelFlow {
    if (!subscriptions.tryAcquire()) {
      throw TorrentCommandException(
        TorrentCommandError.RESOURCE_EXHAUSTED,
        "Too many torrent subscriptions",
      )
    }
    try {
      val task = torrentTask(taskId)
      if (task == null) {
        send(null)
        return@channelFlow
      }
      coroutineScope {
        val changes = Channel<Unit>(Channel.CONFLATED)
        changes.trySend(Unit)
        launch {
          merge(
            task.state.map {}, task.requestState.map {}, task.segments.map {},
            source.seedingTaskIds.map {}, tasks.map {},
          ).collect { changes.trySend(Unit) }
        }
        // Counters move without a state change while a session transfers.
        launch {
          while (true) {
            delay(1.seconds)
            val state = task.state.value
            if (state is DownloadState.Downloading ||
              state is DownloadState.Completed && state.seeding
            ) {
              changes.trySend(Unit)
            }
          }
        }
        for (change in changes) {
          if (tasks.value.none { it.taskId == taskId }) {
            forget(taskId)
            send(null)
            break
          }
          send(snapshotOf(task))
        }
        coroutineContext.cancelChildren()
      }
    } finally {
      subscriptions.release()
    }
  }.distinctUntilChanged().conflate()

  override suspend fun files(
    taskId: String,
    page: TorrentPageRequest,
    order: TorrentFileOrder,
    descending: Boolean,
  ): TorrentFilePage? {
    val task = torrentTask(taskId) ?: return null
    val files = filesOf(task) ?: throw TorrentCommandException(
      TorrentCommandError.METADATA_UNAVAILABLE,
      "The file list is not known yet",
    )
    val record = task.record.value
    val generation = record.control?.selectionGeneration ?: 0
    val revision = revisions.publish(taskId, contentOf(task), currentControl(task))
    val offset = page.cursor?.let {
      val cursor = PageCursor.decode(it)
      if (cursor.taskId != taskId || cursor.epoch != revisions.epoch ||
        cursor.generation != generation || cursor.order != order.wireName ||
        cursor.descending != descending
      ) {
        throw TorrentCommandException(
          TorrentCommandError.STALE_CURSOR,
          "The page cursor belongs to an older file list or another order",
        )
      }
      if (cursor.offset !in 0..files.size) {
        throw TorrentCommandException(TorrentCommandError.INVALID_INPUT, "Invalid page cursor")
      }
      cursor.offset
    } ?: 0
    val selected = selectedIds(task, files)
    val sorted = files.sortedByFileOrder(
      order = order,
      descending = descending,
      path = { it.name },
      size = { it.size },
      selected = { it.id in selected },
    )
    val end = minOf(offset + page.limit, sorted.size)
    val entries = sorted.subList(offset, end).map {
      TorrentFileEntry(it.id, it.name, it.size.coerceAtLeast(0), it.id in selected)
    }
    val next = if (end < sorted.size) {
      PageCursor(taskId, revisions.epoch, generation, end, order.wireName, descending).encode()
    } else {
      null
    }
    return TorrentFilePage(taskId, revision, generation, files.size, entries, next)
  }

  override suspend fun select(
    taskId: String,
    fileIds: Set<String>,
    context: TorrentCommandContext,
  ): TorrentCommandResult {
    val task = torrentTask(taskId) ?: throw notFound()
    requireCapability(TorrentCapability.FILE_SELECTION, TorrentCommandError.UNSUPPORTED)
    if (fileIds.isEmpty() || fileIds.size > DownloadTask.MAX_SELECTED_FILES ||
      fileIds.any { it.length !in 1..DownloadTask.MAX_FILE_ID_LENGTH }
    ) {
      throw TorrentCommandException(
        TorrentCommandError.INVALID_INPUT,
        "Choose between 1 and ${DownloadTask.MAX_SELECTED_FILES} files, each with an ID of 1 " +
          "to ${DownloadTask.MAX_FILE_ID_LENGTH} characters",
      )
    }
    if (filesOf(task) == null) {
      throw TorrentCommandException(
        TorrentCommandError.METADATA_UNAVAILABLE,
        "The file list is not known yet",
      )
    }
    val digest = sha256Hex("select\n" + fileIds.sorted().joinToString("\n"))
    val outcome = mapErrors {
      controller.selectFiles(task, fileIds, command(task, context, digest) {})
    }
    return result(taskId, outcome, seeding = null)
  }

  override suspend fun setSeeding(
    taskId: String,
    seeding: Boolean,
    context: TorrentCommandContext,
  ): TorrentCommandResult {
    val task = torrentTask(taskId) ?: throw notFound()
    var alreadyActive = false
    val digest = sha256Hex("seeding\n$seeding")
    val outcome = mapErrors {
      controller.setSeeding(
        task,
        seeding,
        command(task, context, digest) {
          if (task.state.value !is DownloadState.Completed) {
            throw TorrentCommandException(
              TorrentCommandError.INVALID_STATE,
              "Only completed downloads can seed",
            )
          }
          if (seeding) {
            requireCapability(TorrentCapability.SEEDING, TorrentCommandError.POLICY_DENIED)
            when (source.seedingAvailability(taskId)) {
              SeedingOutcome.POLICY_OFF, SeedingOutcome.UNSUPPORTED ->
                throw TorrentCommandException(
                  TorrentCommandError.POLICY_DENIED,
                  "Seeding is switched off",
                )
              SeedingOutcome.NO_SLOT -> throw TorrentCommandException(
                TorrentCommandError.RESOURCE_EXHAUSTED,
                "Every torrent slot is taken",
              )
              SeedingOutcome.ALREADY_ACTIVE -> alreadyActive = true
              else -> {}
            }
          }
        },
      )
    }
    if (!outcome.replayed) {
      if (!seeding) {
        mapErrors { source.stopSeeding(taskId) }
      } else if (!alreadyActive) {
        seed(task)
      }
    }
    return result(taskId, outcome, outcome.seeding)
  }

  /** The command [context] asks for, which checks the revision and then [check]. */
  private fun command(
    task: RealDownloadTask,
    context: TorrentCommandContext,
    digest: String,
    check: suspend () -> Unit,
  ) = ControlCommand(
    key = context.idempotencyKey,
    digest = digest,
    precondition = {
      revisions.publish(task.taskId, contentOf(task), currentControl(task))
      revisions.requireCurrent(task.taskId, context.expectedRevision)
      check()
    },
    reserve = { generation, seeding ->
      revisions.markControl(task.taskId, TorrentRevisions.Control(generation, seeding))
    },
  )

  private fun result(taskId: String, outcome: ControlOutcome, seeding: Boolean?) =
    TorrentCommandResult(
      taskId = taskId,
      revision = outcome.revision ?: throw IllegalStateException("Unrevised command outcome"),
      selectionGeneration = outcome.generation,
      seeding = seeding,
    )

  private fun requireCapability(capability: TorrentCapability, error: TorrentCommandError) {
    if (capability.wireName !in source.torrentCapabilities) {
      throw TorrentCommandException(error, "This download cannot do that now")
    }
  }

  /** Maps the exceptions of task operations to [TorrentCommandException]s. */
  private suspend fun <T> mapErrors(block: suspend () -> T): T = try {
    block()
  } catch (e: CancellationException) {
    throw e
  } catch (e: TorrentCommandException) {
    throw e
  } catch (e: IllegalArgumentException) {
    throw TorrentCommandException(TorrentCommandError.INVALID_INPUT, e.safeMessage(), cause = e)
  } catch (e: IllegalStateException) {
    throw TorrentCommandException(TorrentCommandError.INVALID_STATE, e.safeMessage(), cause = e)
  } catch (e: UnsupportedOperationException) {
    throw TorrentCommandException(TorrentCommandError.UNSUPPORTED, e.safeMessage(), cause = e)
  } catch (e: KetchError.Disk) {
    throw TorrentCommandException(
      TorrentCommandError.STORAGE_FAILURE,
      "Saving the change failed",
      cause = e,
    )
  } catch (e: IOException) {
    throw TorrentCommandException(
      TorrentCommandError.STORAGE_FAILURE,
      "Saving the change failed",
      cause = e,
    )
  }

  // Core's own messages name no paths; a missing one gets a generic text.
  private fun Exception.safeMessage(): String = message ?: "The command was refused"

  private fun notFound() =
    TorrentCommandException(TorrentCommandError.NOT_FOUND, "No such torrent download")

  /** [taskId]'s task when it is a torrent of [source]; forgets removed tasks. */
  private suspend fun torrentTask(taskId: String): RealDownloadTask? {
    val task = tasks.value.firstOrNull { it.taskId == taskId } as? RealDownloadTask
    if (task == null) {
      forget(taskId)
      return null
    }
    val record = task.record.value
    val type = record.sourceType
      ?: record.request.resolvedSource?.sourceType
      ?: if (handlesUrl(record.request.url)) sourceType else null
    return task.takeIf { type == sourceType }
  }

  private suspend fun forget(taskId: String) {
    revisions.drop(taskId)
    cacheMutex.withLock { fileCache.remove(taskId) }
  }

  /** Every content file of [task] in metainfo order, or `null` while unknown. */
  private suspend fun filesOf(task: RealDownloadTask): List<SourceFile>? {
    val record = task.record.value
    val resumeState = record.sourceResumeState
    val resolved = record.request.resolvedSource
    cacheMutex.withLock {
      val cached = fileCache.remove(task.taskId)
      if (cached != null && cached.matches(resumeState, resolved)) {
        fileCache[task.taskId] = cached
        return cached.files
      }
    }
    val files = source.torrentFiles(resumeState, resolved) ?: return null
    cacheMutex.withLock {
      fileCache[task.taskId] = CachedFiles(resumeState, resolved, files)
      while (fileCache.size > MAX_CACHED_FILE_LISTS) {
        fileCache.remove(fileCache.keys.first())
      }
    }
    return files
  }

  private fun CachedFiles.matches(
    resumeState: SourceResumeState?,
    resolved: ResolvedSource?,
  ): Boolean = if (resumeState != null || this.resumeState != null) {
    this.resumeState === resumeState || this.resumeState == resumeState
  } else {
    this.resolved === resolved || this.resolved == resolved
  }

  /** The files [task] downloads: none while it waits for a choice, every file for legacy tasks. */
  private fun selectedIds(task: RealDownloadTask, files: List<SourceFile>): Set<String> {
    val state = task.state.value
    if (state is DownloadState.Paused && state.reason == PauseReason.AwaitingFileSelection) {
      return emptySet()
    }
    return task.record.value.request.selectedFileIds.ifEmpty { files.mapTo(HashSet()) { it.id } }
  }

  private fun currentControl(task: RealDownloadTask): TorrentRevisions.Control {
    val control = task.record.value.control ?: TaskControl()
    return TorrentRevisions.Control(control.selectionGeneration, control.seeding)
  }

  private suspend fun snapshotOf(task: RealDownloadTask): TorrentSnapshot {
    val content = contentOf(task)
    val revision = revisions.publish(task.taskId, content, content.control)
    return TorrentSnapshot(
      taskId = task.taskId,
      revision = revision,
      activity = content.activity,
      selectionGeneration = content.generation,
      selectionComplete = content.selectionComplete,
      counters = content.counters,
    )
  }

  private suspend fun contentOf(task: RealDownloadTask): Content {
    val record = task.record.value
    val state = task.state.value
    val files = filesOf(task)
    val live = source.liveTorrent(task.taskId)
    val counters = files?.let { countersOf(task, it, state, task.segments.value, live) }
    val control = record.control ?: TaskControl()
    return Content(
      activity = activityOf(state, task.queuePosition.value, files != null, live),
      generation = control.selectionGeneration,
      selectionComplete = state is DownloadState.Completed && counters != null,
      counters = counters,
      seedingIntent = control.seeding,
    )
  }

  private fun countersOf(
    task: RealDownloadTask,
    files: List<SourceFile>,
    state: DownloadState,
    segments: List<Segment>,
    live: LiveTorrent?,
  ): TorrentCounters {
    val selected = selectedIds(task, files)
    val total = files.sumOf { it.size.coerceAtLeast(0) }
    val wanted = files.filter { it.id in selected }.sumOf { it.size.coerceAtLeast(0) }
    val verified = if (state is DownloadState.Completed) {
      wanted
    } else {
      segments.sumOf { it.downloadedBytes }.coerceIn(0, wanted)
    }
    return TorrentCounters(
      totalPayloadBytes = total,
      wantedBytes = wanted,
      selectedVerifiedBytes = verified,
      receivedPayloadBytes = live?.receivedPayloadBytes,
      uploadedPayloadBytes = live?.uploadedPayloadBytes,
      downloadBytesPerSecond = (state as? DownloadState.Downloading)?.progress?.bytesPerSecond,
      uploadBytesPerSecond = live?.uploadBytesPerSecond,
    )
  }

  private fun activityOf(
    state: DownloadState,
    queuePosition: Int?,
    filesKnown: Boolean,
    live: LiveTorrent?,
  ): TorrentActivity = when (state) {
    is DownloadState.Scheduled -> TorrentActivity.QUEUED
    DownloadState.Queued -> when {
      queuePosition != null -> TorrentActivity.QUEUED
      filesKnown -> TorrentActivity.QUEUED
      else -> TorrentActivity.RESOLVING
    }
    is DownloadState.Downloading ->
      live?.activity?.takeUnless { it == TorrentActivity.STOPPED } ?: TorrentActivity.DOWNLOADING
    is DownloadState.Paused ->
      if (state.reason is PauseReason.Preempted) TorrentActivity.QUEUED else TorrentActivity.PAUSED
    is DownloadState.Completed -> live?.activity ?: TorrentActivity.STOPPED
    is DownloadState.Failed -> TorrentActivity.FAILED
    DownloadState.Canceled -> TorrentActivity.STOPPED
  }

  /**
   * Where a file page continues: `c1.` and the base64url of its JSON. It binds the task, the
   * epoch, the selection generation and the order.
   */
  @Serializable
  private data class PageCursor(
    val taskId: String,
    val epoch: String,
    val generation: Long,
    val offset: Int,
    val order: String,
    val descending: Boolean,
  ) {
    fun encode(): String = PREFIX + cursorJson.encodeToString(serializer(), this)
      .encodeUtf8().base64Url().trimEnd('=')

    companion object {
      private const val PREFIX = "c1."

      fun decode(cursor: String): PageCursor {
        val invalid = TorrentCommandException(
          TorrentCommandError.INVALID_INPUT,
          "Invalid page cursor",
        )
        if (!cursor.startsWith(PREFIX)) throw invalid
        val bytes = cursor.removePrefix(PREFIX).decodeBase64() ?: throw invalid
        return try {
          cursorJson.decodeFromString(serializer(), bytes.utf8())
        } catch (_: SerializationException) {
          throw invalid
        } catch (_: IllegalArgumentException) {
          throw invalid
        }
      }
    }
  }

  companion object {
    const val MAX_PAGE_SIZE: Int = 1000
    const val MAX_SUBSCRIPTIONS: Int = 16
    private const val MAX_CACHED_FILE_LISTS = 16
    private val cursorJson = Json { ignoreUnknownKeys = false }

    private fun sha256Hex(text: String): String = text.encodeUtf8().sha256().hex()
  }
}
