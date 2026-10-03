package com.linroid.ketch.engine

import com.linroid.ketch.api.Destination
import com.linroid.ketch.api.DownloadConfig
import com.linroid.ketch.api.DownloadRequest
import com.linroid.ketch.api.DownloadState
import com.linroid.ketch.core.Ketch
import com.linroid.ketch.core.KetchDispatchers
import com.linroid.ketch.core.file.platformFileSystem
import com.linroid.ketch.core.task.InMemoryTaskStore
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
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Verifies that a download only deletes its partial file when the caller discards it (an explicit
 * cancel), and keeps it, with the segments that describe it, after a failure, `Ketch.close()` or
 * `remove(deleteFiles = false)`.
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
