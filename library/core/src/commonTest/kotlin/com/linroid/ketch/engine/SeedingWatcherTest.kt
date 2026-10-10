package com.linroid.ketch.engine

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
import com.linroid.ketch.core.engine.LiveTorrent
import com.linroid.ketch.core.engine.SeedingOutcome
import com.linroid.ketch.core.engine.SeedingTask
import com.linroid.ketch.core.engine.SourceResumeState
import com.linroid.ketch.core.engine.TorrentControlSource
import com.linroid.ketch.core.task.TaskControl
import com.linroid.ketch.core.task.TaskRecord
import com.linroid.ketch.core.task.TaskState
import com.linroid.ketch.core.task.TaskStore
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Instant

/**
 * Ketch shows on completed tasks whether their source seeds them, saves the intent to seed them
 * again, and restores seeding at start, oldest completion first, into free slots only.
 */
class SeedingWatcherTest {
  private val folder = "/tmp/ketch-seeding-watcher/"

  /** A source that completes each download once its gate opens and seeds on request. */
  private class SeedingSource : DownloadSource, TorrentControlSource {
    override val type = TYPE
    override val managesOwnFileIo = true

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

    override suspend fun resolve(url: String, properties: Map<String, String>) = ResolvedSource(
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

    override suspend fun stopSeeding(taskId: String) = ids.update { it - taskId }

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
      httpEngine = FakeHttpEngine(),
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
      control = seeding?.let { TaskControl(seeding = it) },
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
      assertFalse(assertIs<DownloadState.Completed>(task.state.value).seeding)

      source.ids.value = setOf("done")
      runCurrent()

      assertTrue(assertIs<DownloadState.Completed>(task.state.value).seeding)
      assertEquals(true, store.load("done")?.control?.seeding)

      // A seeder a download stopped, or one that stopped with Ketch, keeps its intent.
      source.ids.value = emptySet()
      runCurrent()

      assertFalse(assertIs<DownloadState.Completed>(task.state.value).seeding)
      assertEquals(true, store.load("done")?.control?.seeding)
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

      assertTrue(assertIs<DownloadState.Completed>(first.state.value).seeding)
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
      assertTrue(assertIs<DownloadState.Completed>(first.state.value).seeding)
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
    assertTrue(assertIs<DownloadState.Completed>(ketch.tasks.value.single().state.value).seeding)

    ketch.close()
    source.ids.value = emptySet()
    runCurrent()

    assertEquals(true, store.load("done")?.control?.seeding)
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
        task.taskId to (task.state.value as? DownloadState.Completed)?.seeding
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
      assertEquals(true, store.load("done")?.control?.seeding)
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
      assertEquals(false, store.load("changed")?.control?.seeding)
      assertEquals(true, store.load("broken")?.control?.seeding)
      assertEquals(true, store.load("intact")?.control?.seeding)
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
      assertEquals(true, store.load("second")?.control?.seeding)
      assertEquals(true, store.load("third")?.control?.seeding)
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
      assertEquals(true, store.load("first")?.control?.seeding)
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
