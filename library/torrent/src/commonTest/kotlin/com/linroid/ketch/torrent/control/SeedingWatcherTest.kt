package com.linroid.ketch.torrent.control

import com.linroid.ketch.api.Destination
import com.linroid.ketch.api.DownloadConfig
import com.linroid.ketch.api.DownloadRequest
import com.linroid.ketch.api.DownloadState
import com.linroid.ketch.api.ResolvedSource
import com.linroid.ketch.api.Segment
import com.linroid.ketch.api.SourceFile
import com.linroid.ketch.core.Ketch
import com.linroid.ketch.core.KetchDispatchers
import com.linroid.ketch.core.engine.DownloadContext
import com.linroid.ketch.core.engine.DownloadSource
import com.linroid.ketch.core.engine.SourceExtension
import com.linroid.ketch.core.engine.SourceHost
import com.linroid.ketch.core.engine.SourceResumeState
import com.linroid.ketch.core.task.TaskControl
import com.linroid.ketch.core.task.TaskRecord
import com.linroid.ketch.core.task.TaskState
import com.linroid.ketch.core.task.TaskStore
import com.linroid.ketch.torrent.api.TorrentCommandContext
import com.linroid.ketch.torrent.api.TorrentCommandError
import com.linroid.ketch.torrent.api.TorrentCommandException
import com.linroid.ketch.torrent.api.TorrentController
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Instant

/**
 * [TorrentControls] shows on completed tasks whether their source seeds them, saves the intent to
 * seed them again, and restores seeding at start, oldest completion first, into free slots only.
 */
class SeedingWatcherTest {
  private val folder = "/tmp/ketch-seeding-watcher/"

  /** A source that completes each download once its gate opens and seeds on request. */
  private class SeedingSource : DownloadSource, TorrentControlSource {
    override val type = TYPE
    override val managesOwnFileIo = true

    override fun attach(host: SourceHost): SourceExtension =
      TorrentControls(this, type, ::canHandle, host)

    val ids = MutableStateFlow<Set<String>>(emptySet())
    override val seedingTaskIds: StateFlow<Set<String>> = ids
    var restores = true
    override val restoresSeeding: Boolean get() = restores
    override val torrentCapabilities: Set<String> = emptySet()

    /** Outcomes of [startSeeding] by task; seeding by default. */
    val outcomes = mutableMapOf<String, SeedingOutcome>()
    val started = mutableListOf<String>()

    /** Download gates by URL; open by default. */
    val gates = mutableMapOf<String, CompletableDeferred<Unit>>()

    /** Runs as a download finishes, before it returns. */
    var onFinish: (taskId: String) -> Unit = {}

    override fun canHandle(url: String) = url.startsWith("seed:")

    override suspend fun resolve(url: String, headers: Map<String, String>) = ResolvedSource(
      url = url, sourceType = TYPE, totalBytes = 100, supportsResume = true,
      suggestedFileName = "seed", maxSegments = 1, files = listOf(SourceFile("0", "seed", 100)),
    )

    override fun buildResumeState(resolved: ResolvedSource, totalBytes: Long) =
      SourceResumeState(TYPE, resolved.url)

    override suspend fun download(context: DownloadContext) {
      context.onProgress(0, 100)
      gates[context.url]?.await()
      context.segments.value = listOf(Segment(0, 0, 99, 100))
      context.onProgress(100, 100)
      onFinish(context.taskId)
    }

    override suspend fun resume(context: DownloadContext, resumeState: SourceResumeState) =
      download(context)

    override suspend fun torrentFiles(
      resumeState: SourceResumeState?,
      resolved: ResolvedSource?,
    ): List<SourceFile>? = null

    override suspend fun liveTorrent(taskId: String): LiveTorrent? = null

    override suspend fun seedingAvailability(taskId: String): SeedingOutcome? = null

    override suspend fun startSeeding(task: SeedingTask): SeedingOutcome {
      started += task.taskId
      val outcome = outcomes[task.taskId] ?: SeedingOutcome.SEEDING
      if (outcome == SeedingOutcome.SEEDING) ids.update { it + task.taskId }
      return outcome
    }

    /** Holds [stopSeeding] until it completes. */
    var stopGate: CompletableDeferred<Unit>? = null

    override suspend fun stopSeeding(taskId: String) {
      stopGate?.await()
      ids.update { it - taskId }
    }

    companion object {
      const val TYPE = "seed"
    }
  }

  private fun TestScope.ketch(
    source: SeedingSource,
    store: TaskStore = RecordingTaskStore(),
    config: DownloadConfig = DownloadConfig(retryCount = 0, saveIntervalMs = 60_000),
  ): Ketch {
    val dispatcher = StandardTestDispatcher(testScheduler)
    return Ketch(
      httpEngine = UnusedHttpEngine(),
      taskStore = store,
      config = config,
      additionalSources = listOf(source),
      dispatchers = KetchDispatchers(dispatcher, dispatcher, dispatcher),
    )
  }

  private fun request(url: String) = DownloadRequest(url, destination = Destination(folder))

  /** A completed task of [SeedingSource], completed at [completedAtMs]. */
  private fun completed(
    taskId: String,
    completedAtMs: Long,
    seeding: Boolean? = true,
    state: TaskState = TaskState.COMPLETED,
  ): TaskRecord {
    val at = Instant.fromEpochMilliseconds(completedAtMs)
    return TaskRecord(
      taskId = taskId,
      request = request("seed:$taskId"),
      outputPath = "$folder$taskId",
      state = state,
      totalBytes = 100,
      sourceType = SeedingSource.TYPE,
      sourceResumeState = SourceResumeState(SeedingSource.TYPE, "seed:$taskId"),
      createdAt = at,
      updatedAt = at,
      completedAt = at,
      control = seeding?.let { TaskControl(sharing = it) },
    )
  }

  @Test
  fun seedingSet_completedTask_publishesSeedingAndPersistsIntent() = runTest {
    val source = SeedingSource().apply { restores = false }
    val store = RecordingTaskStore()
    store.save(completed("done", 1_000, seeding = null))
    val ketch = ketch(source, store)
    try {
      ketch.start()
      runCurrent()
      val task = ketch.tasks.value.single()
      assertFalse(assertIs<DownloadState.Completed>(task.state.value).sharing)

      source.ids.value = setOf("done")
      runCurrent()

      assertTrue(assertIs<DownloadState.Completed>(task.state.value).sharing)
      assertEquals(true, store.load("done")?.control?.sharing)

      // A seeder a download stopped, or one that stopped with Ketch, keeps its intent.
      source.ids.value = emptySet()
      runCurrent()

      assertFalse(assertIs<DownloadState.Completed>(task.state.value).sharing)
      assertEquals(true, store.load("done")?.control?.sharing)
    } finally {
      ketch.close()
    }
  }

  @Test
  fun stopCommand_watcherSaveQueuedBehindIt_keepsTheStop() = runTest {
    val source = SeedingSource().apply { restores = false }
    val store = RecordingTaskStore()
    store.save(completed("done", 1_000, seeding = null))
    val ketch = ketch(source, store)
    try {
      ketch.start()
      runCurrent()
      val task = ketch.tasks.value.single()
      val controller = assertNotNull(ketch.controller(TorrentController))
      val revision = assertNotNull(controller.snapshot("done")).revision
      val gate = CompletableDeferred<Unit>()
      source.stopGate = gate
      val stop = async {
        controller.setSeeding("done", false, TorrentCommandContext("stop", revision))
      }
      runCurrent()
      // Seeding starts while the stop, its intent saved, still holds the task's control lock:
      // the watcher's save of the intent queues behind it.
      source.ids.value = setOf("done")
      runCurrent()
      assertEquals(false, store.load("done")?.control?.sharing)

      gate.complete(Unit)
      runCurrent()
      val stopped = stop.await()

      assertEquals(false, stopped.seeding)
      assertEquals(emptySet(), source.ids.value)
      assertFalse(assertIs<DownloadState.Completed>(task.state.value).sharing)
      assertEquals(false, store.load("done")?.control?.sharing)
      // The stop's revision stays current: nothing changed the intent after it.
      val again = controller.setSeeding("done", false,
        TorrentCommandContext("again", stopped.revision))
      assertEquals(false, again.seeding)
    } finally {
      ketch.close()
    }
  }

  @Test
  fun stopCommand_revisionFromBeforeTheIntentWasSaved_isCurrent() = runTest {
    val source = SeedingSource().apply { restores = false }
    val store = RecordingTaskStore()
    store.save(completed("done", 1_000, seeding = null))
    val ketch = ketch(source, store)
    try {
      ketch.start()
      runCurrent()
      val controller = assertNotNull(ketch.controller(TorrentController))
      // A client sees the task seeding before the watcher saved the intent to seed it again.
      source.ids.value = setOf("done")
      val revision = assertNotNull(controller.snapshot("done")).revision
      runCurrent()
      assertEquals(true, store.load("done")?.control?.sharing)

      val stopped = controller.setSeeding("done", false, TorrentCommandContext("stop", revision))

      assertEquals(false, stopped.seeding)
      assertEquals(false, store.load("done")?.control?.sharing)
    } finally {
      ketch.close()
    }
  }

  @Test
  fun stopCommand_saveFails_keepsSeeding() = runTest {
    val source = SeedingSource().apply { restores = false }
    val store = RecordingTaskStore()
    store.save(completed("done", 1_000, seeding = true))
    val ketch = ketch(source, store)
    try {
      ketch.start()
      runCurrent()
      source.ids.value = setOf("done")
      runCurrent()
      val task = ketch.tasks.value.single()
      val controller = assertNotNull(ketch.controller(TorrentController))
      val revision = assertNotNull(controller.snapshot("done")).revision
      store.failSaves = true

      val error = assertFailsWith<TorrentCommandException> {
        controller.setSeeding("done", false, TorrentCommandContext("stop", revision))
      }

      // Nothing changed: the task seeds, and seeds again after a restart, as it says.
      assertEquals(TorrentCommandError.STORAGE_FAILURE, error.error)
      assertEquals(setOf("done"), source.ids.value)
      assertTrue(assertIs<DownloadState.Completed>(task.state.value).sharing)
      store.failSaves = false
      assertEquals(true, store.load("done")?.control?.sharing)
    } finally {
      ketch.close()
    }
  }

  @Test
  fun seedingFlagChange_releasesTheSlotOnce() = runTest {
    val source = SeedingSource().apply { restores = false }
    val ketch = ketch(source, config = DownloadConfig(maxConcurrentDownloads = 1, retryCount = 0,
      saveIntervalMs = 60_000))
    try {
      ketch.start()
      var firstId: String? = null
      // The first task starts seeding as its download finishes, like a torrent lending its slot.
      source.onFinish = { taskId -> if (taskId == firstId) source.ids.update { it + taskId } }
      source.gates["seed:second"] = CompletableDeferred()
      val first = ketch.download(request("seed:first"))
      firstId = first.taskId
      val second = ketch.download(request("seed:second"))
      val third = ketch.download(request("seed:third"))
      runCurrent()

      assertTrue(assertIs<DownloadState.Completed>(first.state.value).sharing)
      assertIs<DownloadState.Downloading>(second.state.value)
      assertEquals(DownloadState.Queued, third.state.value)
      assertEquals(1, third.queuePosition.value)

      // Every copy of the completed state leaves the one slot with the second task.
      repeat(3) {
        source.ids.update { it - first.taskId }
        runCurrent()
        source.ids.update { it + first.taskId }
        runCurrent()
      }
      assertTrue(assertIs<DownloadState.Completed>(first.state.value).sharing)
      assertIs<DownloadState.Downloading>(second.state.value)
      assertEquals(DownloadState.Queued, third.state.value)
      assertEquals(1, third.queuePosition.value)

      checkNotNull(source.gates["seed:second"]).complete(Unit)
      runCurrent()

      assertIs<DownloadState.Completed>(second.state.value)
      assertIs<DownloadState.Completed>(third.state.value)
    } finally {
      ketch.close()
    }
  }

  @Test
  fun close_whileSeeding_keepsIntent() = runTest {
    val source = SeedingSource()
    val store = RecordingTaskStore()
    store.save(completed("done", 1_000))
    val ketch = ketch(source, store)
    ketch.start()
    runCurrent()
    assertTrue(assertIs<DownloadState.Completed>(ketch.tasks.value.single().state.value).sharing)

    ketch.close()
    source.ids.value = emptySet()
    runCurrent()

    assertEquals(true, store.load("done")?.control?.sharing)
  }

  @Test
  fun restore_intentSet_startsSeedingInCompletionOrder() = runTest {
    val source = SeedingSource()
    val store = RecordingTaskStore()
    store.save(completed("late", 3_000))
    store.save(completed("early", 1_000))
    store.save(completed("plain", 2_000, seeding = false))
    store.save(completed("failed", 500, state = TaskState.FAILED))
    val ketch = ketch(source, store)
    try {
      ketch.start()
      runCurrent()

      assertEquals(listOf("early", "late"), source.started)
      val seeding = ketch.tasks.value.associate { task ->
        task.taskId to (task.state.value as? DownloadState.Completed)?.sharing
      }
      assertEquals(true, seeding["early"])
      assertEquals(true, seeding["late"])
      assertEquals(false, seeding["plain"])
    } finally {
      ketch.close()
    }
  }

  @Test
  fun restore_sourceDoesNotRestore_startsNothing() = runTest {
    val source = SeedingSource().apply { restores = false }
    val store = RecordingTaskStore()
    store.save(completed("done", 1_000))
    val ketch = ketch(source, store)
    try {
      ketch.start()
      runCurrent()

      assertEquals(emptyList(), source.started)
      assertEquals(true, store.load("done")?.control?.sharing)
    } finally {
      ketch.close()
    }
  }

  @Test
  fun restore_changedOnDisk_clearsIntent() = runTest {
    val source = SeedingSource()
    source.outcomes["changed"] = SeedingOutcome.CHANGED_ON_DISK
    source.outcomes["broken"] = SeedingOutcome.FAILED
    val store = RecordingTaskStore()
    store.save(completed("changed", 1_000))
    store.save(completed("broken", 2_000))
    store.save(completed("intact", 3_000))
    val ketch = ketch(source, store)
    try {
      ketch.start()
      runCurrent()

      // Neither a change on disk nor a failure stops the walk.
      assertEquals(listOf("changed", "broken", "intact"), source.started)
      assertEquals(false, store.load("changed")?.control?.sharing)
      assertEquals(true, store.load("broken")?.control?.sharing)
      assertEquals(true, store.load("intact")?.control?.sharing)
    } finally {
      ketch.close()
    }
  }

  @Test
  fun restore_noSlot_stopsTheWalk() = runTest {
    val source = SeedingSource()
    source.outcomes["second"] = SeedingOutcome.NO_SLOT
    val store = RecordingTaskStore()
    store.save(completed("first", 1_000))
    store.save(completed("second", 2_000))
    store.save(completed("third", 3_000))
    val ketch = ketch(source, store)
    try {
      ketch.start()
      runCurrent()

      assertEquals(listOf("first", "second"), source.started)
      // Tasks that found no slot keep their intent for the next start.
      assertEquals(true, store.load("second")?.control?.sharing)
      assertEquals(true, store.load("third")?.control?.sharing)
    } finally {
      ketch.close()
    }
  }

  @Test
  fun restore_policyOff_stopsTheWalk() = runTest {
    val source = SeedingSource()
    source.outcomes["first"] = SeedingOutcome.POLICY_OFF
    val store = RecordingTaskStore()
    store.save(completed("first", 1_000))
    store.save(completed("second", 2_000))
    val ketch = ketch(source, store)
    try {
      ketch.start()
      runCurrent()

      assertEquals(listOf("first"), source.started)
      assertEquals(true, store.load("first")?.control?.sharing)
    } finally {
      ketch.close()
    }
  }

  @Test
  fun start_twice_restoresOnce() = runTest {
    val source = SeedingSource()
    val store = RecordingTaskStore()
    store.save(completed("done", 1_000))
    val ketch = ketch(source, store)
    try {
      ketch.start()
      runCurrent()
      source.ids.value = emptySet()
      ketch.start()
      runCurrent()

      assertEquals(listOf("done"), source.started)
    } finally {
      ketch.close()
    }
  }
}
