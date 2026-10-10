package com.linroid.ketch.engine

import com.linroid.ketch.api.Destination
import com.linroid.ketch.api.DownloadConfig
import com.linroid.ketch.api.DownloadRequest
import com.linroid.ketch.api.DownloadState
import com.linroid.ketch.api.DownloadTask
import com.linroid.ketch.api.ResolvedSource
import com.linroid.ketch.api.Segment
import com.linroid.ketch.api.SourceFile
import com.linroid.ketch.api.torrent.TorrentActivity
import com.linroid.ketch.api.torrent.TorrentCapability
import com.linroid.ketch.api.torrent.TorrentCommandContext
import com.linroid.ketch.api.torrent.TorrentCommandError
import com.linroid.ketch.api.torrent.TorrentCommandException
import com.linroid.ketch.api.torrent.TorrentController
import com.linroid.ketch.api.torrent.TorrentFileOrder
import com.linroid.ketch.api.torrent.TorrentPageRequest
import com.linroid.ketch.api.torrent.TorrentRevision
import com.linroid.ketch.api.torrent.TorrentSnapshot
import com.linroid.ketch.core.Ketch
import com.linroid.ketch.core.KetchDispatchers
import com.linroid.ketch.core.engine.DownloadSource
import com.linroid.ketch.core.engine.LiveTorrent
import com.linroid.ketch.core.engine.SeedingOutcome
import com.linroid.ketch.core.engine.SeedingTask
import com.linroid.ketch.core.engine.SourceResumeState
import com.linroid.ketch.core.engine.TorrentControlSource
import com.linroid.ketch.core.task.RealDownloadTask
import com.linroid.ketch.core.task.TaskCommandRecord
import com.linroid.ketch.core.task.TaskControl
import com.linroid.ketch.core.task.TaskStore
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Duration.Companion.minutes

/**
 * Ketch's torrent controller: revisions that never conflict with progress, the retry ledger in
 * the task record, file pages with cursors, snapshots, subscriptions and seeding commands.
 */
class KetchTorrentControllerTest {
  private val folder = "/tmp/ketch-torrent-controller/"

  /** [SelectableSource] with the torrent controls of a fake engine. */
  private class ControlledSource(
    val inner: SelectableSource = SelectableSource(),
  ) : DownloadSource by inner, TorrentControlSource {
    var capabilities = mutableSetOf(TorrentCapability.FILE_SELECTION.wireName)
    override val torrentCapabilities: Set<String> get() = capabilities
    val seeding = MutableStateFlow<Set<String>>(emptySet())
    override val seedingTaskIds: StateFlow<Set<String>> = seeding
    override val restoresSeeding: Boolean = false
    val live = mutableMapOf<String, LiveTorrent>()
    var availability: SeedingOutcome? = null
    val started = mutableListOf<String>()
    val stopped = mutableListOf<String>()

    override suspend fun torrentFiles(
      resumeState: SourceResumeState?,
      resolved: ResolvedSource?,
    ): List<SourceFile>? {
      val known = resumeState?.data?.startsWith(SelectableSource.STORED) == true ||
        resolved != null
      return if (known) inner.resolved("pick:files", "files").files else null
    }

    override suspend fun liveTorrent(taskId: String): LiveTorrent? = live[taskId]

    override suspend fun seedingAvailability(taskId: String): SeedingOutcome? = availability

    override suspend fun startSeeding(task: SeedingTask): SeedingOutcome {
      started += task.taskId
      seeding.update { it + task.taskId }
      return SeedingOutcome.SEEDING
    }

    override suspend fun stopSeeding(taskId: String) {
      stopped += taskId
      seeding.update { it - taskId }
    }
  }

  private fun TestScope.ketch(
    source: ControlledSource,
    store: TaskStore = RecordingTaskStore(),
  ): Ketch {
    val dispatcher = StandardTestDispatcher(testScheduler)
    return Ketch(
      httpEngine = FakeHttpEngine(),
      taskStore = store,
      config = DownloadConfig(retryCount = 0, saveIntervalMs = 60_000),
      additionalSources = listOf(source),
      dispatchers = KetchDispatchers(dispatcher, dispatcher, dispatcher),
    )
  }

  private fun request(vararg ids: String) = DownloadRequest(
    url = "pick:files",
    destination = Destination(folder),
    selectedFileIds = ids.toSet(),
  )

  private fun context(key: String, revision: TorrentRevision) =
    TorrentCommandContext(key, revision)

  private val Ketch.controller: TorrentController get() = assertNotNull(torrents)

  /** A paused task that downloads file 0, with its file list known. */
  private suspend fun TestScope.pausedTask(ketch: Ketch): DownloadTask {
    val task = ketch.download(request("0"))
    runCurrent()
    assertIs<DownloadState.Downloading>(task.state.value)
    task.pause()
    runCurrent()
    assertIs<DownloadState.Paused>(task.state.value)
    return task
  }

  private suspend fun TestScope.completedTask(
    ketch: Ketch,
    source: ControlledSource,
  ): DownloadTask {
    val task = ketch.download(request("0"))
    runCurrent()
    source.inner.runs.value.last().finish.complete(Unit)
    runCurrent()
    assertIs<DownloadState.Completed>(task.state.value)
    return task
  }

  private suspend fun TorrentController.revisionOf(taskId: String): TorrentRevision =
    assertNotNull(snapshot(taskId)).revision

  private suspend inline fun failure(
    error: TorrentCommandError,
    block: () -> Unit,
  ): TorrentCommandException {
    val e = assertFailsWith<TorrentCommandException> { block() }
    assertEquals(error, e.error)
    return e
  }

  private val DownloadTask.saved get() = (this as RealDownloadTask).record.value

  @Test
  fun select_exactRetryAfterNewerChange_returnsOriginalResult() = runTest {
    val source = ControlledSource()
    val ketch = ketch(source)
    try {
      val task = pausedTask(ketch)
      val controller = ketch.controller
      val initial = controller.revisionOf(task.taskId)
      val first = controller.select(task.taskId, setOf("0", "1"), context("a", initial))
      val second = controller.select(
        task.taskId, setOf("0", "1", "2"), context("b", controller.revisionOf(task.taskId)),
      )

      val retry = controller.select(task.taskId, setOf("1", "0"), context("a", initial))

      assertEquals(first, retry)
      assertEquals(1, first.selectionGeneration)
      assertEquals(2, second.selectionGeneration)
      assertEquals(setOf("0", "1", "2"), task.request.selectedFileIds)
      assertEquals(2, task.saved.control?.commands?.size)
    } finally {
      ketch.close()
    }
  }

  @Test
  fun select_keyReusedWithOtherFiles_failsWithKeyReused() = runTest {
    val source = ControlledSource()
    val ketch = ketch(source)
    try {
      val task = pausedTask(ketch)
      val controller = ketch.controller
      val revision = controller.revisionOf(task.taskId)
      controller.select(task.taskId, setOf("0", "1"), context("a", revision))

      failure(TorrentCommandError.KEY_REUSED) {
        controller.select(task.taskId, setOf("2"), context("a", revision))
      }
      assertEquals(setOf("0", "1"), task.request.selectedFileIds)
    } finally {
      ketch.close()
    }
  }

  @Test
  fun select_staleRevision_conflictsWithoutChange() = runTest {
    val source = ControlledSource()
    val store = RecordingTaskStore()
    val ketch = ketch(source, store)
    try {
      val task = pausedTask(ketch)
      val controller = ketch.controller
      val stale = controller.revisionOf(task.taskId)
      val accepted = controller.select(task.taskId, setOf("0", "1"), context("a", stale))
      val writes = store.saves.size

      val conflict = failure(TorrentCommandError.CONFLICT) {
        controller.select(task.taskId, setOf("2"), context("b", stale))
      }

      assertEquals(accepted.revision.epoch, conflict.currentRevision?.epoch)
      assertTrue(assertNotNull(conflict.currentRevision).sequence >= accepted.revision.sequence)
      assertEquals(writes, store.saves.size)
      assertEquals(setOf("0", "1"), task.request.selectedFileIds)
      // A revision from another epoch conflicts too.
      failure(TorrentCommandError.CONFLICT) {
        controller.select(task.taskId, setOf("2"), context("c", TorrentRevision("old", 0)))
      }
    } finally {
      ketch.close()
    }
  }

  @Test
  fun select_revisionAfterProgressTicks_isAccepted() = runTest {
    val source = ControlledSource()
    val ketch = ketch(source)
    try {
      val task = ketch.download(request("0", "1"))
      runCurrent()
      val controller = ketch.controller
      val before = controller.revisionOf(task.taskId)
      val run = source.inner.runs.value.single()
      run.context.segments.value = listOf(Segment(0, 0, 99, 40), Segment(1, 100, 299, 0))
      run.context.onProgress(40, 300)
      runCurrent()
      val after = controller.revisionOf(task.taskId)
      assertTrue(after.sequence > before.sequence)

      val result = controller.select(task.taskId, setOf("0"), context("a", before))

      assertEquals(1, result.selectionGeneration)
      assertEquals(setOf("0"), task.request.selectedFileIds)
    } finally {
      ketch.close()
    }
  }

  @Test
  fun select_concurrentDuplicates_runOnce() = runTest {
    val source = ControlledSource()
    val store = RecordingTaskStore()
    val ketch = ketch(source, store)
    try {
      val task = pausedTask(ketch)
      val controller = ketch.controller
      val revision = controller.revisionOf(task.taskId)
      val writes = store.saves.size

      val results = List(2) {
        async { controller.select(task.taskId, setOf("1", "2"), context("same", revision)) }
      }.awaitAll()

      assertEquals(results[0], results[1])
      assertEquals(1, results[0].selectionGeneration)
      assertEquals(writes + 1, store.saves.size)
      assertEquals(1, task.saved.control?.commands?.size)
    } finally {
      ketch.close()
    }
  }

  @Test
  fun select_fullLedgerInsideWindow_isExhausted() = runTest {
    val source = ControlledSource()
    val ketch = ketch(source)
    try {
      val task = pausedTask(ketch)
      val controller = ketch.controller
      val selections = listOf(setOf("0", "1"), setOf("0"))
      repeat(TaskControl.MAX_COMMANDS) { i ->
        controller.select(
          task.taskId, selections[i % 2], context("key$i", controller.revisionOf(task.taskId)),
        )
      }
      val generation = task.saved.control?.selectionGeneration

      failure(TorrentCommandError.RESOURCE_EXHAUSTED) {
        controller.select(
          task.taskId, setOf("2"), context("more", controller.revisionOf(task.taskId)),
        )
      }
      assertEquals(generation, task.saved.control?.selectionGeneration)
      // Exact retries of entries in the window still answer.
      val replay =
        controller.select(task.taskId, selections[0], context("key0", TorrentRevision("x", 0)))
      assertEquals(1, replay.selectionGeneration)
    } finally {
      ketch.close()
    }
  }

  @Test
  fun select_expiredEntries_arePruned() = runTest {
    val source = ControlledSource()
    val ketch = ketch(source)
    try {
      val task = pausedTask(ketch)
      val expiredAt = (Clock.System.now() - 16.minutes).toEpochMilliseconds()
      val digest = "0".repeat(64)
      (task as RealDownloadTask).record.update { record ->
        record.copy(
          control = TaskControl(
            commands = List(TaskControl.MAX_COMMANDS) { i ->
              TaskCommandRecord("old$i", digest, "epoch", 1, 0, null, expiredAt)
            },
          ),
        )
      }
      val controller = ketch.controller

      controller.select(
        task.taskId, setOf("1"), context("old0", controller.revisionOf(task.taskId)),
      )

      val commands = task.saved.control?.commands.orEmpty()
      assertEquals(listOf("old0"), commands.map { it.key })
      assertNotEquals(digest, commands.single().digest)
    } finally {
      ketch.close()
    }
  }

  @Test
  fun select_ledgerSurvivesKetchRecreation() = runTest {
    val store = RecordingTaskStore()
    val firstSource = ControlledSource()
    val first = ketch(firstSource, store)
    val taskId: String
    val result = try {
      val task = pausedTask(first)
      taskId = task.taskId
      first.controller.select(
        taskId, setOf("0", "2"), context("kept", first.controller.revisionOf(taskId)),
      )
    } finally {
      first.close()
    }
    val next = ketch(ControlledSource(), store)
    try {
      next.start()
      runCurrent()
      val nextRevision = next.controller.revisionOf(taskId)
      assertNotEquals(result.revision.epoch, nextRevision.epoch)

      val replay = next.controller.select(taskId, setOf("2", "0"), context("kept", result.revision))

      assertEquals(result, replay)
      assertEquals(1, next.tasks.value.single().saved.control?.selectionGeneration)
    } finally {
      next.close()
    }
  }

  @Test
  fun select_filesUnknown_isMetadataUnavailable() = runTest {
    val source = ControlledSource()
    source.inner.resolveGate = CompletableDeferred()
    val ketch = ketch(source)
    try {
      val task = ketch.download(request())
      runCurrent()
      assertEquals(DownloadState.Queued, task.state.value)
      val controller = ketch.controller
      val snapshot = assertNotNull(controller.snapshot(task.taskId))
      assertEquals(TorrentActivity.RESOLVING, snapshot.activity)
      assertNull(snapshot.counters)

      failure(TorrentCommandError.METADATA_UNAVAILABLE) {
        controller.select(task.taskId, setOf("0"), context("a", snapshot.revision))
      }
      failure(TorrentCommandError.METADATA_UNAVAILABLE) { controller.files(task.taskId) }
    } finally {
      ketch.close()
    }
  }

  @Test
  fun select_invalidInputAndUnknownTasks_areRefused() = runTest {
    val source = ControlledSource()
    val ketch = ketch(source)
    try {
      val task = pausedTask(ketch)
      val controller = ketch.controller
      val revision = controller.revisionOf(task.taskId)
      failure(TorrentCommandError.INVALID_INPUT) {
        controller.select(task.taskId, emptySet(), context("a", revision))
      }
      failure(TorrentCommandError.INVALID_INPUT) {
        controller.select(task.taskId, setOf("9"), context("b", revision))
      }
      failure(TorrentCommandError.NOT_FOUND) {
        controller.select("missing", setOf("0"), context("c", revision))
      }
      assertNull(controller.snapshot("missing"))
      assertNull(controller.files("missing"))
      source.capabilities.clear()
      failure(TorrentCommandError.UNSUPPORTED) {
        controller.select(task.taskId, setOf("1"), context("d", revision))
      }
    } finally {
      ketch.close()
    }
  }

  @Test
  fun files_pages_coverEveryFileOnce() = runTest {
    val source = ControlledSource(SelectableSource(listOf(5, 1, 4, 2, 3)))
    val ketch = ketch(source)
    try {
      val task = pausedTask(ketch)
      val controller = ketch.controller
      val seen = mutableListOf<String>()
      var cursor: String? = null
      var pages = 0
      do {
        val page = assertNotNull(
          controller.files(task.taskId, TorrentPageRequest(limit = 2, cursor = cursor))
        )
        assertEquals(5, page.totalFiles)
        assertEquals(0, page.selectionGeneration)
        seen += page.files.map { it.id }
        cursor = page.nextCursor
        pages++
      } while (cursor != null)

      assertEquals(listOf("0", "1", "2", "3", "4"), seen)
      assertEquals(3, pages)
      val first = assertNotNull(controller.files(task.taskId)).files
      assertEquals(listOf(true, false, false, false, false), first.map { it.selected })
      assertEquals("file0", first.first().path)
    } finally {
      ketch.close()
    }
  }

  @Test
  fun files_sortedPages_followTheOrder() = runTest {
    val source = ControlledSource(SelectableSource(listOf(5, 1, 4, 2, 3)))
    val ketch = ketch(source)
    try {
      val task = pausedTask(ketch)
      val controller = ketch.controller
      val seen = mutableListOf<String>()
      var cursor: String? = null
      do {
        val page = assertNotNull(
          controller.files(
            task.taskId, TorrentPageRequest(limit = 2, cursor = cursor), TorrentFileOrder.SIZE,
            descending = true,
          )
        )
        seen += page.files.map { it.id }
        cursor = page.nextCursor
      } while (cursor != null)

      assertEquals(listOf("0", "2", "4", "3", "1"), seen)
      val selectedFirst = assertNotNull(
        controller.files(task.taskId, order = TorrentFileOrder.SELECTED, descending = true)
      )
      assertEquals("0", selectedFirst.files.last().id)
    } finally {
      ketch.close()
    }
  }

  @Test
  fun files_cursorWithAnotherOrder_isStale() = runTest {
    val source = ControlledSource()
    val ketch = ketch(source)
    try {
      val task = pausedTask(ketch)
      val controller = ketch.controller
      val cursor = assertNotNull(
        controller.files(task.taskId, TorrentPageRequest(limit = 1), TorrentFileOrder.NAME)
          ?.nextCursor
      )
      assertNotNull(
        controller.files(task.taskId, TorrentPageRequest(1, cursor), TorrentFileOrder.NAME)
      )

      failure(TorrentCommandError.STALE_CURSOR) {
        controller.files(task.taskId, TorrentPageRequest(1, cursor), TorrentFileOrder.SIZE)
      }
      failure(TorrentCommandError.STALE_CURSOR) {
        controller.files(task.taskId, TorrentPageRequest(1, cursor), TorrentFileOrder.NAME, true)
      }
    } finally {
      ketch.close()
    }
  }

  @Test
  fun files_cursorAfterSelectionChange_isStale() = runTest {
    val source = ControlledSource()
    val ketch = ketch(source)
    try {
      val task = pausedTask(ketch)
      val controller = ketch.controller
      val cursor = assertNotNull(
        controller.files(task.taskId, TorrentPageRequest(limit = 1))?.nextCursor
      )
      task.selectFiles(setOf("0", "1"))

      failure(TorrentCommandError.STALE_CURSOR) {
        controller.files(task.taskId, TorrentPageRequest(limit = 1, cursor = cursor))
      }
      val fresh = assertNotNull(controller.files(task.taskId))
      assertEquals(1, fresh.selectionGeneration)
      assertEquals(listOf(true, true, false), fresh.files.map { it.selected })
    } finally {
      ketch.close()
    }
  }

  @Test
  fun files_foreignCursor_isInvalid() = runTest {
    val source = ControlledSource()
    val ketch = ketch(source)
    try {
      val task = pausedTask(ketch)
      val other = pausedTask(ketch)
      val controller = ketch.controller
      failure(TorrentCommandError.INVALID_INPUT) {
        controller.files(task.taskId, TorrentPageRequest(cursor = "c1.bm90IGpzb24"))
      }
      failure(TorrentCommandError.INVALID_INPUT) {
        controller.files(task.taskId, TorrentPageRequest(cursor = "elsewhere"))
      }
      val otherCursor = assertNotNull(
        controller.files(other.taskId, TorrentPageRequest(limit = 1))?.nextCursor
      )
      failure(TorrentCommandError.STALE_CURSOR) {
        controller.files(task.taskId, TorrentPageRequest(limit = 1, cursor = otherCursor))
      }
    } finally {
      ketch.close()
    }
  }

  @Test
  fun snapshot_untrackedCounters_areNull() = runTest {
    val source = ControlledSource()
    val ketch = ketch(source)
    try {
      val task = pausedTask(ketch)
      val snapshot = assertNotNull(ketch.controller.snapshot(task.taskId))

      assertEquals(TorrentActivity.PAUSED, snapshot.activity)
      assertFalse(snapshot.selectionComplete)
      val counters = assertNotNull(snapshot.counters)
      assertEquals(600, counters.totalPayloadBytes)
      assertEquals(100, counters.wantedBytes)
      assertNull(counters.receivedPayloadBytes)
      assertNull(counters.uploadedPayloadBytes)
      assertNull(counters.discardedPayloadBytes)
      assertNull(counters.protocolBytes)
      assertNull(counters.downloadBytesPerSecond)
      assertNull(counters.uploadBytesPerSecond)
      assertNull(counters.seedSeconds)
      // An unchanged task keeps its revision.
      assertEquals(snapshot, ketch.controller.snapshot(task.taskId))
    } finally {
      ketch.close()
    }
  }

  @Test
  fun snapshot_completedSeeding_reportsSeeding() = runTest {
    val source = ControlledSource()
    val ketch = ketch(source)
    try {
      val task = completedTask(ketch, source)
      source.live[task.taskId] = LiveTorrent(TorrentActivity.SEEDING, 100, 2048, 512)

      val snapshot = assertNotNull(ketch.controller.snapshot(task.taskId))

      assertEquals(TorrentActivity.SEEDING, snapshot.activity)
      assertTrue(snapshot.selectionComplete)
      val counters = assertNotNull(snapshot.counters)
      assertEquals(100, counters.selectedVerifiedBytes)
      assertEquals(100, counters.receivedPayloadBytes)
      assertEquals(2048, counters.uploadedPayloadBytes)
      assertEquals(512, counters.uploadBytesPerSecond)
      source.live.remove(task.taskId)
      assertEquals(TorrentActivity.STOPPED, ketch.controller.snapshot(task.taskId)?.activity)
    } finally {
      ketch.close()
    }
  }

  @Test
  fun observe_removedTask_emitsTombstone() = runTest {
    val source = ControlledSource()
    val ketch = ketch(source)
    try {
      val task = pausedTask(ketch)
      val received = mutableListOf<TorrentSnapshot?>()
      val done = CompletableDeferred<Unit>()
      launch {
        ketch.controller.observe(task.taskId).collect { received += it }
        done.complete(Unit)
      }
      runCurrent()
      assertEquals(task.taskId, received.single()?.taskId)

      task.remove()
      runCurrent()

      assertTrue(done.isCompleted)
      assertNull(received.last())
      assertEquals(listOf<TorrentSnapshot?>(null), ketch.controller.observe("missing").toList())
    } finally {
      ketch.close()
    }
  }

  @Test
  fun observe_selectionChange_emitsNewGeneration() = runTest {
    val source = ControlledSource()
    val ketch = ketch(source)
    try {
      val task = pausedTask(ketch)
      val received = mutableListOf<TorrentSnapshot?>()
      val job = launch { ketch.controller.observe(task.taskId).collect { received += it } }
      runCurrent()

      task.selectFiles(setOf("1"))
      runCurrent()

      val last = assertNotNull(received.last())
      assertEquals(1, last.selectionGeneration)
      assertTrue(last.revision.sequence > assertNotNull(received.first()).revision.sequence)
      job.cancel()
    } finally {
      ketch.close()
    }
  }

  @Test
  fun observe_seventeenthSubscriber_isExhausted() = runTest {
    val source = ControlledSource()
    val ketch = ketch(source)
    try {
      val task = pausedTask(ketch)
      val subscribers = List(16) {
        launch { ketch.controller.observe(task.taskId).collect() }
      }
      runCurrent()

      failure(TorrentCommandError.RESOURCE_EXHAUSTED) {
        ketch.controller.observe(task.taskId).first()
      }

      subscribers.first().cancel()
      runCurrent()
      assertEquals(task.taskId, ketch.controller.observe(task.taskId).first()?.taskId)
      subscribers.forEach { it.cancel() }
    } finally {
      ketch.close()
    }
  }

  @Test
  fun setSeeding_runningTask_isInvalidState() = runTest {
    val source = ControlledSource()
    source.capabilities += TorrentCapability.SEEDING.wireName
    val ketch = ketch(source)
    try {
      val task = ketch.download(request("0"))
      runCurrent()
      val controller = ketch.controller

      failure(TorrentCommandError.INVALID_STATE) {
        controller.setSeeding(task.taskId, true, context("a", controller.revisionOf(task.taskId)))
      }
      assertTrue(source.started.isEmpty())
      assertNull(task.saved.control)
    } finally {
      ketch.close()
    }
  }

  @Test
  fun setSeeding_policyOff_isDeniedBeforeSideEffects() = runTest {
    val source = ControlledSource()
    val store = RecordingTaskStore()
    val ketch = ketch(source, store)
    try {
      val task = completedTask(ketch, source)
      val controller = ketch.controller
      val writes = store.saves.size

      failure(TorrentCommandError.POLICY_DENIED) {
        controller.setSeeding(task.taskId, true, context("a", controller.revisionOf(task.taskId)))
      }
      source.capabilities += TorrentCapability.SEEDING.wireName
      source.availability = SeedingOutcome.POLICY_OFF
      failure(TorrentCommandError.POLICY_DENIED) {
        controller.setSeeding(task.taskId, true, context("b", controller.revisionOf(task.taskId)))
      }
      source.availability = SeedingOutcome.NO_SLOT
      failure(TorrentCommandError.RESOURCE_EXHAUSTED) {
        controller.setSeeding(task.taskId, true, context("c", controller.revisionOf(task.taskId)))
      }
      runCurrent()

      assertTrue(source.started.isEmpty())
      assertEquals(writes, store.saves.size)
      assertNull(task.saved.control)
    } finally {
      ketch.close()
    }
  }

  @Test
  fun setSeeding_onAndOff_savesIntentAndActs() = runTest {
    val source = ControlledSource()
    source.capabilities += TorrentCapability.SEEDING.wireName
    val ketch = ketch(source)
    try {
      ketch.start()
      val task = completedTask(ketch, source)
      val controller = ketch.controller

      val on = controller.setSeeding(
        task.taskId, true, context("on", controller.revisionOf(task.taskId)),
      )
      runCurrent()

      assertEquals(true, on.seeding)
      assertEquals(listOf(task.taskId), source.started)
      assertEquals(true, task.saved.control?.seeding)
      assertEquals(true, (task.state.value as DownloadState.Completed).seeding)

      val off = controller.setSeeding(task.taskId, false, context("off", on.revision))
      runCurrent()

      assertEquals(false, off.seeding)
      assertEquals(listOf(task.taskId), source.stopped)
      assertEquals(false, task.saved.control?.seeding)
      assertTrue(off.revision.sequence > on.revision.sequence)
      // An exact retry changes nothing and starts nothing.
      assertEquals(on, controller.setSeeding(task.taskId, true, context("on", on.revision)))
      runCurrent()
      assertEquals(1, source.started.size)
    } finally {
      ketch.close()
    }
  }

  @Test
  fun capabilities_listOnlyExecutableNames() = runTest {
    val source = ControlledSource()
    val ketch = ketch(source)
    try {
      val capabilities = ketch.controller.capabilities()
      assertEquals(setOf("inspect", "file-selection"), capabilities.names)
      assertEquals(1000, capabilities.maxPageSize)
      assertEquals(16, capabilities.maxSubscriptions)
      assertFalse(capabilities.backgroundTransfers)

      source.capabilities += TorrentCapability.SEEDING.wireName
      assertTrue(ketch.controller.capabilities().supports(TorrentCapability.SEEDING))
    } finally {
      ketch.close()
    }
  }

  @Test
  fun torrents_withoutControlSource_isNull() = runTest {
    val dispatcher = StandardTestDispatcher(testScheduler)
    val ketch = Ketch(
      httpEngine = FakeHttpEngine(),
      additionalSources = listOf(SelectableSource()),
      dispatchers = KetchDispatchers(dispatcher, dispatcher, dispatcher),
    )
    try {
      assertNull(ketch.torrents)
    } finally {
      ketch.close()
    }
  }
}
