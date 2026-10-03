package com.linroid.ketch.engine

import com.linroid.ketch.api.Destination
import com.linroid.ketch.api.DownloadConfig
import com.linroid.ketch.api.DownloadRequest
import com.linroid.ketch.api.DownloadState
import com.linroid.ketch.core.Ketch
import com.linroid.ketch.core.KetchDispatchers
import com.linroid.ketch.core.engine.HttpDownloadSource
import com.linroid.ketch.core.file.platformFileSystem
import com.linroid.ketch.core.task.InMemoryTaskStore
import com.linroid.ketch.core.task.TaskRecord
import com.linroid.ketch.core.task.TaskState
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
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.time.Clock

/**
 * Verifies that an empty segment list with a known size counts as no progress: resuming such a
 * record downloads the file instead of completing a zero-filled one. Content of unknown size has
 * no segments by design and resumes into the same file.
 */
class DownloadCoordinatorResumeTest {
  @Test
  fun resume_emptySegmentsWithKnownSize_restartsFromZero() = runTest {
    withKetch { ketch, engine, store, output ->
      // Saved by an older version paused before its first segments, beside a preallocated file.
      platformFileSystem.write(output) { write(ByteArray(1000)) }
      val now = Clock.System.now()
      store.save(
        TaskRecord(
          taskId = "legacy",
          request = request(output),
          outputPath = output.toString(),
          state = TaskState.PAUSED,
          totalBytes = 1000,
          segments = emptyList(),
          sourceType = HttpDownloadSource.TYPE,
          sourceResumeState = HttpDownloadSource.buildResumeState(
            etag = null,
            lastModified = null,
            totalBytes = 1000,
          ),
          createdAt = now,
          updatedAt = now,
        ),
      )
      ketch.start()
      val task = ketch.tasks.value.single()

      task.resume()
      runCurrent()

      assertIs<DownloadState.Completed>(task.state.value)
      assertEquals(0L..999L, engine.lastDownloadRange)
      assertContentEquals(engine.content, platformFileSystem.read(output) { readByteArray() })
    }
  }

  @Test
  fun pause_beforeFirstSegments_savesNoSegmentsAndResumeDownloadsFile() = runTest {
    withKetch { ketch, engine, store, output ->
      // The source waits out the rate limit before it publishes any segments.
      val serverInfo = engine.serverInfo
      engine.serverInfo = serverInfo.copy(rateLimitRemaining = 0, rateLimitReset = 60)
      val task = ketch.download(request(output))
      runCurrent()

      task.pause()

      val record = store.load(task.taskId)
      assertEquals(TaskState.PAUSED, record?.state)
      assertEquals(1000L, record?.totalBytes)
      assertNull(record?.segments)

      engine.serverInfo = serverInfo
      task.resume()
      runCurrent()

      assertIs<DownloadState.Completed>(task.state.value)
      assertContentEquals(engine.content, platformFileSystem.read(output) { readByteArray() })
    }
  }

  @Test
  fun pause_unknownSizeStream_resumesIntoSameFile() = runTest {
    withKetch { ketch, engine, store, output ->
      engine.serverInfo = engine.serverInfo.copy(contentLength = null, acceptRanges = false)
      engine.stallAfterBytes = 500
      // A folder destination: starting over would pick "file (1).bin" beside the partial file.
      val folder = checkNotNull(output.parent)
      val task = ketch.download(request(output).copy(destination = Destination("$folder/")))
      runCurrent()

      task.pause()

      assertEquals(emptyList(), store.load(task.taskId)?.segments)
      engine.stallAfterBytes = -1
      task.resume()
      runCurrent()

      val completed = assertIs<DownloadState.Completed>(task.state.value)
      assertEquals(output.toString(), completed.outputPath)
      assertContentEquals(engine.content, platformFileSystem.read(output) { readByteArray() })
    }
  }

  private suspend fun TestScope.withKetch(
    block: suspend (Ketch, FakeHttpEngine, InMemoryTaskStore, Path) -> Unit,
  ) {
    val folder = FileSystem.SYSTEM_TEMPORARY_DIRECTORY / "ketch-resume-${Random.nextLong()}"
    platformFileSystem.createDirectories(folder)
    val engine = FakeHttpEngine()
    val store = InMemoryTaskStore()
    val dispatcher = StandardTestDispatcher(testScheduler)
    val ketch = Ketch(
      httpEngine = engine,
      taskStore = store,
      config = DownloadConfig(retryCount = 0),
      dispatchers = KetchDispatchers(dispatcher, dispatcher, dispatcher),
    )
    try {
      block(ketch, engine, store, folder / "file.bin")
    } finally {
      ketch.close()
      runCurrent()
      platformFileSystem.deleteRecursively(folder)
    }
  }

  private fun request(output: Path) = DownloadRequest(
    url = "https://example.com/file.bin",
    destination = Destination(output.toString()),
    connections = 1,
  )
}
