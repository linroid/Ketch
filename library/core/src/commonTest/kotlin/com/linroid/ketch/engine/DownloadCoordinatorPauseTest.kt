package com.linroid.ketch.engine

import com.linroid.ketch.api.Destination
import com.linroid.ketch.api.DownloadConfig
import com.linroid.ketch.api.DownloadProgress
import com.linroid.ketch.api.DownloadRequest
import com.linroid.ketch.api.DownloadState
import com.linroid.ketch.api.KetchError
import com.linroid.ketch.api.PauseReason
import com.linroid.ketch.api.ResolveInput
import com.linroid.ketch.api.ResolvedSource
import com.linroid.ketch.api.Segment
import com.linroid.ketch.core.KetchDispatchers
import com.linroid.ketch.core.engine.DownloadContext
import com.linroid.ketch.core.engine.DownloadCoordinator
import com.linroid.ketch.core.engine.DownloadSource
import com.linroid.ketch.core.engine.HttpDownloadSource
import com.linroid.ketch.core.engine.SourceResolver
import com.linroid.ketch.core.file.DefaultFileNameResolver
import com.linroid.ketch.core.file.FileAccessor
import com.linroid.ketch.core.file.NoOpFileAccessor
import com.linroid.ketch.core.file.createFileAccessor
import com.linroid.ketch.core.file.platformFileSystem
import com.linroid.ketch.core.task.AtomicSaver
import com.linroid.ketch.core.task.TaskHandle
import com.linroid.ketch.core.task.TaskRecord
import com.linroid.ketch.core.task.TaskState
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.serializer
import okio.FileSystem

/**
 * Verifies that [DownloadCoordinator.pause] keeps the segments the execution saves while
 * stopping. With an unconfined network dispatcher, the `job.cancel()` inside `pause()` runs
 * the execution's final checkpoint before `pause()` persists anything, which forces the
 * interleaving that once made a restarted task resume from stale offsets. Also verifies that the
 * run flushes the file a pause or a cancel keeping it leaves, that a close or a failure closes it
 * without waiting for the disk, and that a pause claiming a verified run's end stops it before
 * its file takes the final name.
 */
class DownloadCoordinatorPauseTest {
  @Test
  fun pause_sourcePublishesProgressWhileStopping_keepsFinalSegments() = runTest {
    val final = listOf(Segment(0, 0, 99, 60))
    val record = pauseWhileSourceStops(published = listOf(Segment(0, 0, 99, 20)), final = final)
    assertEquals(TaskState.PAUSED, record.state)
    assertEquals(final, record.segments)
  }

  @Test
  fun pause_sourceResetsSegmentsWhileStopping_keepsResetSegments() = runTest {
    // Less progress is legitimate, e.g. after a failed local-file check; keeping the larger
    // snapshot would resume past bytes that are not on disk.
    val final = listOf(Segment(0, 0, 99, 0))
    val record = pauseWhileSourceStops(published = listOf(Segment(0, 0, 99, 60)), final = final)
    assertEquals(TaskState.PAUSED, record.state)
    assertEquals(final, record.segments)
  }

  @Test
  fun pause_preempted_writesQueuedRecordBeforeJoining() = runTest {
    val checkpoint = CompletableDeferred<Unit>()
    val saved = mutableListOf<TaskState>()
    val handle = handle { saved += it.state }
    val coordinator = coordinator(StoppingSource(checkpoint))
    try {
      coordinator.start(handle)
      runCurrent()
      saved.clear()

      val pause = launch { coordinator.pause(handle.taskId, PauseReason.Preempted("urgent")) }
      runCurrent()

      // The source is still saving its checkpoint, so pause() has not returned yet.
      assertFalse(pause.isCompleted)
      assertEquals(TaskState.QUEUED, handle.record.value.state)
      assertEquals(
        DownloadState.Paused(DownloadProgress(20, 100), PauseReason.Preempted("urgent")),
        handle.mutableState.value,
      )
      checkpoint.complete(Unit)
      runCurrent()
      assertTrue(pause.isCompleted)
      assertTrue(TaskState.PAUSED !in saved, "Saved states: $saved")
      assertEquals(TaskState.QUEUED, handle.record.value.state)
    } finally {
      coordinator.close()
    }
  }

  @Test
  fun pause_afterCompletion_keepsCompletedStateAndRecord() = runTest {
    val handle = handle()
    val coordinator = coordinator(StoppingSource(CompletableDeferred(Unit)))
    try {
      coordinator.start(handle)
      runCurrent()
      // The execution finished; its finally has not yet taken it out of the coordinator.
      val completed = DownloadState.Completed("/tmp/ketch-pause-checkpoint", 100)
      handle.record.update { it.copy(state = TaskState.COMPLETED) }
      handle.mutableState.value = completed

      coordinator.pause(handle.taskId, PauseReason.Preempted("urgent"))
      runCurrent()

      assertEquals(completed, handle.mutableState.value)
      assertEquals(TaskState.COMPLETED, handle.record.value.state)
    } finally {
      coordinator.close()
    }
  }

  @Test
  fun pause_flushesTheKeptFileBeforeTheRunClosesIt() = runTest {
    // A disk slow to flush, so a flush the pause left running would land once the file closed.
    val disk = CompletableDeferred<Unit>()
    val file = RecordingFile(disk)
    val handle = handle()
    val coordinator = coordinator(WritingSource(), openFile = { _, _ -> file })
    try {
      coordinator.start(handle)
      runCurrent()
      assertEquals(listOf("write"), file.ops)

      val pause = launch { coordinator.pause(handle.taskId) }
      runCurrent()
      disk.complete(Unit)
      runCurrent()

      assertTrue(pause.isCompleted)
      assertEquals(listOf("write", "flush", "close"), file.ops)
      assertEquals(TaskState.PAUSED, handle.record.value.state)
    } finally {
      coordinator.close()
    }
  }

  @Test
  fun pause_cancellingTheLastFlush_flushesTheFileBeforeTheRunClosesIt() = runTest {
    // The transfer is done and its last flush waits for a busy disk: the pause cancels it first.
    val disk = CompletableDeferred<Unit>()
    val file = RecordingFile(disk)
    val handle = handle()
    val source = WritingSource(written = 100, end = {})
    val coordinator = coordinator(source, openFile = { _, _ -> file })
    try {
      coordinator.start(handle)
      runCurrent()
      assertEquals(listOf("write"), file.ops)

      val pause = launch { coordinator.pause(handle.taskId) }
      runCurrent()
      disk.complete(Unit)
      runCurrent()

      // The saved segments count every byte, so they must be on disk.
      assertTrue(pause.isCompleted)
      assertEquals(listOf("write", "flush", "close"), file.ops)
      assertIs<DownloadState.Paused>(handle.mutableState.value)
      assertEquals(TaskState.PAUSED, handle.record.value.state)
      assertEquals(listOf(Segment(0, 0, 99, 100)), handle.record.value.segments)
    } finally {
      coordinator.close()
    }
  }

  @Test
  fun pause_claimingTheRunBeforeItCompletes_keepsItsVerifiedPartialFileForTheResume() = runTest {
    val folder = FileSystem.SYSTEM_TEMPORARY_DIRECTORY / "ketch-pause-${Random.nextLong()}"
    platformFileSystem.createDirectories(folder)
    val engine = FakeHttpEngine()
    val handle = handle(
      DownloadRequest(
        "https://example.com/file.bin",
        destination = Destination("$folder/"),
        connections = 1,
      ),
    )
    lateinit var coordinator: DownloadCoordinator
    var pause: Job? = null
    coordinator = coordinator(
      HttpDownloadSource(engine),
      openFile = { path, disk ->
        val file = createFileAccessor(path, disk)
        object : FileAccessor by file {
          override suspend fun flush() {
            file.flush()
            // The run's last flush, as the source flushes none. The pause claims the run's end
            // before the run suspends again, so before the run claims its completion.
            if (pause == null) {
              pause = launch(start = CoroutineStart.UNDISPATCHED) {
                coordinator.pause(handle.taskId)
              }
            }
          }
        }
      },
    )
    val partial = folder / "file.bin.ketch"
    try {
      coordinator.start(handle)
      runCurrent()

      // Stopped before the move, the whole file waits under its partial name.
      assertTrue(pause?.isCompleted == true)
      assertIs<DownloadState.Paused>(handle.mutableState.value)
      assertEquals(TaskState.PAUSED, handle.record.value.state)
      assertEquals(partial.toString(), handle.record.value.partialPath)
      assertContentEquals(engine.content, platformFileSystem.read(partial) { readByteArray() })
      assertFalse(platformFileSystem.exists(folder / "file.bin"))

      val downloads = engine.downloadCallCount
      assertTrue(coordinator.resume(handle))
      runCurrent()

      assertIs<DownloadState.Completed>(handle.mutableState.value)
      assertEquals(downloads, engine.downloadCallCount)
      val output = platformFileSystem.read(folder / "file.bin") { readByteArray() }
      assertContentEquals(engine.content, output)
      assertFalse(platformFileSystem.exists(partial))
    } finally {
      coordinator.close()
      runCurrent()
      platformFileSystem.deleteRecursively(folder)
    }
  }

  @Test
  fun cancel_keepingTheFile_closesItWithoutWaitingForTheDisk() = runTest {
    // As remove(deleteFiles = false) does: its record goes, so nothing resumes from the file.
    val file = RecordingFile(CompletableDeferred())
    val handle = handle()
    val coordinator = coordinator(WritingSource(), openFile = { _, _ -> file })
    try {
      coordinator.start(handle)
      runCurrent()

      val cancel = launch { coordinator.cancel(handle, deletePartialFile = false) }
      runCurrent()

      assertTrue(cancel.isCompleted)
      assertEquals(listOf("write", "close"), file.ops)
      assertEquals(DownloadState.Canceled, handle.mutableState.value)
    } finally {
      coordinator.close()
    }
  }

  @Test
  fun close_closesTheKeptFileWithoutWaitingForTheDisk() = runTest {
    // A disk that never finishes a flush: closing must not wait for one.
    val file = RecordingFile(CompletableDeferred())
    val handle = handle()
    val coordinator = coordinator(WritingSource(), openFile = { _, _ -> file })
    coordinator.start(handle)
    runCurrent()

    val closed = coordinator.close()
    runCurrent()

    assertTrue(closed.isCompleted)
    assertEquals(listOf("write", "close"), file.ops)
  }

  @Test
  fun failure_closesTheKeptFileWithoutWaitingForTheDisk() = runTest {
    val file = RecordingFile(CompletableDeferred())
    val handle = handle()
    val source = WritingSource(end = { throw KetchError.Http(404) })
    val coordinator = coordinator(source, openFile = { _, _ -> file })
    try {
      coordinator.start(handle)
      runCurrent()

      assertIs<DownloadState.Failed>(handle.mutableState.value)
      assertEquals(listOf("write", "close"), file.ops)
    } finally {
      coordinator.close()
    }
  }

  /**
   * Records what a run does to its file. A flush waits for [disk], which by default is ready, and
   * a flush that lands once the file closed is recorded as such.
   */
  private class RecordingFile(
    private val disk: CompletableDeferred<Unit> = CompletableDeferred(Unit),
  ) : FileAccessor by NoOpFileAccessor {
    val ops = mutableListOf<String>()
    private var closed = false

    override suspend fun writeAt(offset: Long, data: ByteArray) { ops += "write" }

    override suspend fun flush() {
      disk.await()
      ops += if (closed) "flush after close" else "flush"
    }

    override fun close() {
      closed = true
      ops += "close"
    }
  }

  /**
   * Writes [written] of its 100 bytes to the file Ketch opened for it, then runs [end], which by
   * default waits to be stopped.
   */
  private class WritingSource(
    private val written: Int = 20,
    private val end: suspend () -> Unit = { awaitCancellation() },
  ) : DownloadSource<Unit> {
    override val stateSerializer = Unit.serializer()
    override val type = "fixture"
    override fun canHandle(input: ResolveInput): Boolean = input is ResolveInput.Url
    override suspend fun resolve(input: ResolveInput, config: DownloadConfig): ResolvedSource {
      val url = (input as ResolveInput.Url).url
      return ResolvedSource(
        url = url, sourceType = type, totalBytes = 100, supportsResume = true,
        suggestedFileName = "fixture.bin", maxSegments = 1,
      )
    }
    override suspend fun download(context: DownloadContext, state: Unit?) {
      context.fileAccessor.writeAt(0, ByteArray(written))
      context.segments.value = listOf(Segment(0, 0, 99, written.toLong()))
      end()
    }
  }

  /** Publishes some progress, then waits for [checkpoint] while it stops. */
  private class StoppingSource(
    private val checkpoint: CompletableDeferred<Unit>,
  ) : DownloadSource<Unit> {
    override val stateSerializer = Unit.serializer()
    override val type = "fixture"
    override val managesOwnFileIo = true
    override fun canHandle(input: ResolveInput): Boolean = input is ResolveInput.Url
    override suspend fun resolve(input: ResolveInput, config: DownloadConfig): ResolvedSource {
      val url = (input as ResolveInput.Url).url
      return ResolvedSource(
        url = url, sourceType = type, totalBytes = 100, supportsResume = true,
        suggestedFileName = "fixture", maxSegments = 1,
      )
    }
    override suspend fun download(context: DownloadContext, state: Unit?) {
      context.segments.value = listOf(Segment(0, 0, 99, 20))
      try {
        awaitCancellation()
      } finally {
        withContext(NonCancellable) { checkpoint.await() }
      }
    }
  }

  private fun handle(
    request: DownloadRequest = DownloadRequest("fixture:input",
      destination = Destination("/tmp/ketch-pause-checkpoint")),
    onSave: (TaskRecord) -> Unit = {},
  ): TaskHandle {
    val now = Clock.System.now()
    return object : TaskHandle {
      override val taskId = "pause-checkpoint"
      override val request = request
      override val createdAt = now
      override val mutableState = MutableStateFlow<DownloadState>(DownloadState.Queued)
      override val mutableSegments = MutableStateFlow<List<Segment>>(emptyList())
      override val mutableQueuePosition = MutableStateFlow<Int?>(null)
      override val controlLock = Mutex()
      override val record = AtomicSaver(TaskRecord(taskId, request, state = TaskState.QUEUED,
        createdAt = now, updatedAt = now)) { onSave(it) }
    }
  }

  private fun TestScope.coordinator(
    source: DownloadSource<*>,
    openFile: (String, CoroutineDispatcher) -> FileAccessor = { _, _ -> NoOpFileAccessor },
  ): DownloadCoordinator {
    val dispatcher = StandardTestDispatcher(testScheduler)
    return DownloadCoordinator(SourceResolver(listOf(source)),
      { DownloadConfig(saveIntervalMs = 60_000) }, DefaultFileNameResolver(),
      dispatchers = KetchDispatchers(dispatcher, dispatcher, dispatcher), openFile = openFile)
  }

  private suspend fun pauseWhileSourceStops(
    published: List<Segment>,
    final: List<Segment>,
  ): TaskRecord {
    val source = object : DownloadSource<Unit> {
      override val stateSerializer = Unit.serializer()
      override val type = "fixture"
      override val managesOwnFileIo = true
      override fun canHandle(input: ResolveInput): Boolean = input is ResolveInput.Url
      override suspend fun resolve(input: ResolveInput, config: DownloadConfig): ResolvedSource {
        val url = (input as ResolveInput.Url).url
        return ResolvedSource(
          url = url, sourceType = type, totalBytes = 100, supportsResume = true,
          suggestedFileName = "fixture", maxSegments = 1,
        )
      }
      override suspend fun download(context: DownloadContext, state: Unit?) {
        context.segments.value = published
        // Like SegmentedDownloadHelper, which publishes throttled progress when a batch stops.
        try { awaitCancellation() } finally { context.segments.value = final }
      }
    }
    val now = Clock.System.now()
    val request = DownloadRequest("fixture:input",
      destination = Destination("/tmp/ketch-pause-checkpoint"))
    val handle = object : TaskHandle {
      override val taskId = "pause-checkpoint"
      override val request = request
      override val createdAt = now
      override val mutableState = MutableStateFlow<DownloadState>(DownloadState.Queued)
      override val mutableSegments = MutableStateFlow<List<Segment>>(emptyList())
      override val mutableQueuePosition = MutableStateFlow<Int?>(null)
      override val controlLock = Mutex()
      override val record = AtomicSaver(TaskRecord(taskId, request, state = TaskState.QUEUED,
        createdAt = now, updatedAt = now)) {}
    }
    val unconfined = Dispatchers.Unconfined
    val coordinator = DownloadCoordinator(SourceResolver(listOf(source)),
      { DownloadConfig(saveIntervalMs = 60_000) }, DefaultFileNameResolver(),
      dispatchers = KetchDispatchers(unconfined, unconfined, unconfined))
    try {
      // Unconfined, start() returns once the source is waiting to be cancelled.
      coordinator.start(handle)
      assertEquals(published, handle.mutableSegments.value)
      coordinator.pause(handle.taskId)
    } finally {
      coordinator.close()
    }
    return handle.record.value
  }
}
