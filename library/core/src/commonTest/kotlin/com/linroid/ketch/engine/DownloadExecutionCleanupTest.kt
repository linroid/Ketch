package com.linroid.ketch.engine

import com.linroid.ketch.api.Destination
import com.linroid.ketch.api.DownloadConfig
import com.linroid.ketch.api.DownloadRequest
import com.linroid.ketch.api.DownloadState
import com.linroid.ketch.api.KetchError
import com.linroid.ketch.api.ResolvedSource
import com.linroid.ketch.api.Segment
import com.linroid.ketch.core.Ketch
import com.linroid.ketch.core.KetchDispatchers
import com.linroid.ketch.core.engine.DownloadContext
import com.linroid.ketch.core.engine.DownloadExecution
import com.linroid.ketch.core.engine.DownloadSource
import com.linroid.ketch.core.engine.SourceResolver
import com.linroid.ketch.core.engine.SourceResumeState
import com.linroid.ketch.core.engine.SpeedLimiter
import com.linroid.ketch.core.file.DefaultFileNameResolver
import com.linroid.ketch.core.file.FileAccessor
import com.linroid.ketch.core.file.NoOpFileAccessor
import com.linroid.ketch.core.file.platformFileSystem
import com.linroid.ketch.core.task.AtomicSaver
import com.linroid.ketch.core.task.InMemoryTaskStore
import com.linroid.ketch.core.task.TaskHandle
import com.linroid.ketch.core.task.TaskRecord
import com.linroid.ketch.core.task.TaskState
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import okio.FileSystem
import okio.Path
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Clock

/**
 * Verifies that a download only deletes its partial file when the caller discards it (an explicit
 * cancel), and keeps it, with the segments that describe it, after a failure, `Ketch.close()` or
 * `remove(deleteFiles = false)`. A failed final flush keeps the file but not its progress.
 */
class DownloadExecutionCleanupTest {
  @Test
  fun failure_keepsPartialFileAndResumeContinues() = runTest {
    withKetch { ketch, engine, store, output ->
      engine.failAfterBytes = 500
      val task = ketch.download(request(output))
      runCurrent()

      assertIs<DownloadState.Failed>(task.state.value)
      assertWritten(engine, output, 500)
      val record = store.load(task.taskId)
      assertEquals(TaskState.FAILED, record?.state)
      assertEquals(500L, record?.segments?.sumOf { it.downloadedBytes })

      engine.failAfterBytes = -1
      task.resume()
      runCurrent()

      assertIs<DownloadState.Completed>(task.state.value)
      assertEquals(500L..999L, engine.lastDownloadRange)
      assertContentEquals(engine.content, platformFileSystem.read(output) { readByteArray() })
    }
  }

  @Test
  fun close_midDownload_keepsPartialFileAndRestartResumes() = runTest {
    withKetch { ketch, engine, store, output ->
      engine.stallAfterBytes = 500
      val task = ketch.download(request(output))
      runCurrent()
      assertIs<DownloadState.Downloading>(task.state.value)

      ketch.close()
      runCurrent()

      assertWritten(engine, output, 500)
      assertEquals(TaskState.DOWNLOADING, store.load(task.taskId)?.state)
      engine.stallAfterBytes = -1
      val restarted = newKetch(engine, store)
      try {
        restarted.start()
        runCurrent()

        assertIs<DownloadState.Completed>(restarted.tasks.value.single().state.value)
        assertEquals(500L..999L, engine.lastDownloadRange)
        assertContentEquals(engine.content, platformFileSystem.read(output) { readByteArray() })
      } finally {
        restarted.close()
        runCurrent()
      }
    }
  }

  @Test
  fun remove_keepingFilesOfActiveTask_keepsPartialFile() = runTest {
    withKetch { ketch, engine, _, output ->
      engine.stallAfterBytes = 500
      val task = ketch.download(request(output))
      runCurrent()

      task.remove(deleteFiles = false)

      assertTrue(ketch.tasks.value.isEmpty())
      assertWritten(engine, output, 500)
    }
  }

  @Test
  fun cancel_activeTask_deletesPartialFile() = runTest {
    withKetch { ketch, engine, _, output ->
      engine.stallAfterBytes = 500
      val task = ketch.download(request(output))
      runCurrent()

      task.cancel()

      assertEquals(DownloadState.Canceled, task.state.value)
      assertFalse(platformFileSystem.exists(output))
    }
  }

  @Test
  fun flushFailure_keepsFileButResetsProgress() = runTest {
    val request = DownloadRequest("fixture:flush", destination = Destination("/tmp/ketch-flush"))
    val now = Clock.System.now()
    val handle = object : TaskHandle {
      override val taskId = "flush"
      override val request = request
      override val createdAt = now
      override val mutableState = MutableStateFlow<DownloadState>(DownloadState.Queued)
      override val mutableSegments = MutableStateFlow<List<Segment>>(emptyList())
      override val mutableQueuePosition = MutableStateFlow<Int?>(null)
      override val controlLock = Mutex()
      override val record = AtomicSaver(
        TaskRecord(taskId, request, state = TaskState.QUEUED, createdAt = now, updatedAt = now),
      ) {}
    }
    var deleted = false
    val file = object : FileAccessor by NoOpFileAccessor {
      override suspend fun flush() = throw IllegalStateException("Simulated flush failure")
      override suspend fun delete() { deleted = true }
    }
    // Every byte is reported written, but the final flush fails.
    val source = object : DownloadSource {
      override val type = "fixture"
      override fun canHandle(url: String) = true
      override suspend fun resolve(url: String, properties: Map<String, String>) = ResolvedSource(
        url = url, sourceType = type, totalBytes = 100, supportsResume = true,
        suggestedFileName = "fixture.bin", maxSegments = 1,
      )
      override fun buildResumeState(resolved: ResolvedSource, totalBytes: Long) =
        SourceResumeState(type, "")
      override suspend fun download(context: DownloadContext) {
        context.segments.value = listOf(Segment(0, 0, 99, 100))
      }
      override suspend fun resume(context: DownloadContext, resumeState: SourceResumeState) = Unit
    }
    val dispatcher = StandardTestDispatcher(testScheduler)
    val execution = DownloadExecution(
      handle = handle,
      sourceResolver = SourceResolver(listOf(source)),
      fileNameResolver = DefaultFileNameResolver(),
      config = DownloadConfig(retryCount = 0, saveIntervalMs = 60_000),
      globalLimiter = SpeedLimiter.Unlimited,
      dispatchers = KetchDispatchers(dispatcher, dispatcher, dispatcher),
      openFile = { _, _ -> file },
    )

    assertFailsWith<KetchError.Disk> { execution.execute() }

    // The bytes may not be on disk, so a resume downloads all of them again into the same file.
    assertEquals(listOf(Segment(0, 0, 99, 0)), handle.record.value.segments)
    assertFalse(deleted)
  }

  /** Asserts that [output] exists with its first [bytes] bytes downloaded from [engine]. */
  private fun assertWritten(engine: FakeHttpEngine, output: Path, bytes: Int) {
    val written = platformFileSystem.read(output) { readByteArray() }
    assertContentEquals(engine.content.copyOf(bytes), written.copyOf(bytes))
  }

  private suspend fun TestScope.withKetch(
    block: suspend (Ketch, FakeHttpEngine, InMemoryTaskStore, Path) -> Unit,
  ) {
    val folder = FileSystem.SYSTEM_TEMPORARY_DIRECTORY / "ketch-cleanup-${Random.nextLong()}"
    platformFileSystem.createDirectories(folder)
    val engine = FakeHttpEngine()
    val store = InMemoryTaskStore()
    val ketch = newKetch(engine, store)
    try {
      block(ketch, engine, store, folder / "file.bin")
    } finally {
      ketch.close()
      runCurrent()
      platformFileSystem.deleteRecursively(folder)
    }
  }

  private fun TestScope.newKetch(engine: FakeHttpEngine, store: InMemoryTaskStore): Ketch {
    val dispatcher = StandardTestDispatcher(testScheduler)
    return Ketch(
      httpEngine = engine,
      taskStore = store,
      config = DownloadConfig(retryCount = 0),
      dispatchers = KetchDispatchers(dispatcher, dispatcher, dispatcher),
    )
  }

  private fun request(output: Path) = DownloadRequest(
    url = "https://example.com/file.bin",
    destination = Destination(output.toString()),
    connections = 1,
  )
}
