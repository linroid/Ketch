package com.linroid.ketch.engine

import com.linroid.ketch.api.Destination
import com.linroid.ketch.api.DownloadConfig
import com.linroid.ketch.api.DownloadRequest
import com.linroid.ketch.api.DownloadState
import com.linroid.ketch.core.Ketch
import com.linroid.ketch.core.KetchDispatchers
import com.linroid.ketch.core.file.platformFileSystem
import com.linroid.ketch.core.task.InMemoryTaskStore
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
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Verifies that a download writes under its partial name (`name.ext.ketch`) until it completes,
 * then takes its final name without replacing a file that appeared there meanwhile, and that
 * tasks saved before partial files existed keep writing to their final name.
 */
class PartialFileTest {
  @Test
  fun download_unfinished_writesUnderPartialName() = runTest {
    withKetch { ketch, engine, store, folder ->
      engine.stallAfterBytes = 500
      val task = ketch.download(request(folder))
      runCurrent()

      assertIs<DownloadState.Downloading>(task.state.value)
      assertEquals((folder / "file.bin").toString(), task.outputPath)
      assertEquals((folder / "file.bin.ketch").toString(), store.load(task.taskId)?.partialPath)
      assertTrue(platformFileSystem.exists(folder / "file.bin.ketch"))
      assertFalse(platformFileSystem.exists(folder / "file.bin"))
    }
  }

  @Test
  fun download_completes_movesPartialToFinalName() = runTest {
    withKetch { ketch, engine, store, folder ->
      val task = ketch.download(request(folder))
      runCurrent()

      val completed = assertIs<DownloadState.Completed>(task.state.value)
      assertEquals((folder / "file.bin").toString(), completed.outputPath)
      assertContentEquals(engine.content, read(folder / "file.bin"))
      assertFalse(platformFileSystem.exists(folder / "file.bin.ketch"))
      assertNull(store.load(task.taskId)?.partialPath)
    }
  }

  @Test
  fun pauseAndResume_keepsWritingThePartialFile() = runTest {
    withKetch { ketch, engine, _, folder ->
      engine.stallAfterBytes = 500
      val task = ketch.download(request(folder))
      runCurrent()
      task.pause()
      runCurrent()

      assertTrue(platformFileSystem.exists(folder / "file.bin.ketch"))
      assertFalse(platformFileSystem.exists(folder / "file.bin"))

      engine.stallAfterBytes = -1
      task.resume()
      runCurrent()

      assertIs<DownloadState.Completed>(task.state.value)
      assertEquals(500L..999L, engine.lastDownloadRange)
      assertContentEquals(engine.content, read(folder / "file.bin"))
    }
  }

  @Test
  fun complete_fileAppearedAtFinalName_savedUnderFreeNameWithoutReplacingIt() = runTest {
    withKetch { ketch, engine, store, folder ->
      engine.stallAfterBytes = 500
      val task = ketch.download(request(folder))
      runCurrent()
      task.pause()
      runCurrent()
      val other = "someone else's".encodeToByteArray()
      platformFileSystem.write(folder / "file.bin") { write(other) }

      engine.stallAfterBytes = -1
      task.resume()
      runCurrent()

      val completed = assertIs<DownloadState.Completed>(task.state.value)
      val saved = (folder / "file (1).bin").toString()
      assertEquals(saved, completed.outputPath)
      assertEquals(saved, task.outputPath)
      assertEquals(saved, store.load(task.taskId)?.outputPath)
      assertContentEquals(engine.content, read(folder / "file (1).bin"))
      assertContentEquals(other, read(folder / "file.bin"))
    }
  }

  @Test
  fun complete_fileDestinationExists_isReplaced() = runTest {
    withKetch { ketch, engine, _, folder ->
      val output = folder / "named.bin"
      platformFileSystem.write(output) { write("old".encodeToByteArray()) }

      val task = ketch.download(
        DownloadRequest(url = URL, destination = Destination(output.toString()), connections = 1)
      )
      runCurrent()

      val completed = assertIs<DownloadState.Completed>(task.state.value)
      assertEquals(output.toString(), completed.outputPath)
      assertContentEquals(engine.content, read(output))
      assertFalse(platformFileSystem.exists(folder / "named.bin.ketch"))
    }
  }

  @Test
  fun download_unknownSize_movedOnceStreamed() = runTest {
    withKetch { ketch, engine, _, folder ->
      engine.serverInfo = engine.serverInfo.copy(contentLength = null, acceptRanges = false)
      val task = ketch.download(request(folder))
      runCurrent()

      val completed = assertIs<DownloadState.Completed>(task.state.value)
      assertEquals(1000L, completed.totalBytes)
      assertContentEquals(engine.content, read(folder / "file.bin"))
      assertFalse(platformFileSystem.exists(folder / "file.bin.ketch"))
    }
  }

  @Test
  fun remove_deletingFilesOfPausedTask_deletesOnlyItsPartialFile() = runTest {
    withKetch { ketch, engine, _, folder ->
      engine.stallAfterBytes = 500
      val task = ketch.download(request(folder))
      runCurrent()
      task.pause()
      runCurrent()
      // Not the task's: it never wrote under its final name.
      platformFileSystem.write(folder / "file.bin") { write("mine".encodeToByteArray()) }

      task.remove(deleteFiles = true)
      runCurrent()

      assertFalse(platformFileSystem.exists(folder / "file.bin.ketch"))
      assertTrue(platformFileSystem.exists(folder / "file.bin"))
    }
  }

  @Test
  fun resume_recordSavedBeforePartialFiles_keepsWritingToFinalName() = runTest {
    withKetch { ketch, engine, store, folder ->
      engine.stallAfterBytes = 500
      val task = ketch.download(request(folder))
      runCurrent()
      task.pause()
      runCurrent()
      ketch.close()
      runCurrent()
      // What an older version left: the partial bytes at the final name, and no partial path.
      platformFileSystem.atomicMove(folder / "file.bin.ketch", folder / "file.bin")
      val record = checkNotNull(store.load(task.taskId))
      store.save(record.copy(partialPath = null))

      engine.stallAfterBytes = -1
      val restarted = newKetch(engine, store)
      try {
        restarted.start()
        runCurrent()
        val restored = restarted.tasks.value.single()
        restored.resume()
        runCurrent()

        val completed = assertIs<DownloadState.Completed>(restored.state.value)
        assertEquals((folder / "file.bin").toString(), completed.outputPath)
        assertEquals(500L..999L, engine.lastDownloadRange)
        assertContentEquals(engine.content, read(folder / "file.bin"))
        assertFalse(platformFileSystem.exists(folder / "file.bin.ketch"))
      } finally {
        restarted.close()
        runCurrent()
      }
    }
  }

  @Test
  fun download_partialFileOfPausedTaskExists_newDownloadGetsFreeName() = runTest {
    withKetch { ketch, engine, _, folder ->
      engine.stallAfterBytes = 500
      val first = ketch.download(request(folder))
      runCurrent()
      first.pause()
      runCurrent()

      engine.stallAfterBytes = -1
      val second = ketch.download(request(folder))
      runCurrent()

      val completed = assertIs<DownloadState.Completed>(second.state.value)
      assertEquals((folder / "file (1).bin").toString(), completed.outputPath)
      assertTrue(platformFileSystem.exists(folder / "file.bin.ketch"))
    }
  }

  private fun read(path: Path): ByteArray = platformFileSystem.read(path) { readByteArray() }

  private suspend fun TestScope.withKetch(
    block: suspend (Ketch, FakeHttpEngine, InMemoryTaskStore, Path) -> Unit,
  ) {
    val folder = FileSystem.SYSTEM_TEMPORARY_DIRECTORY / "ketch-partial-${Random.nextLong()}"
    platformFileSystem.createDirectories(folder)
    val engine = FakeHttpEngine()
    val store = InMemoryTaskStore()
    val ketch = newKetch(engine, store)
    try {
      block(ketch, engine, store, folder)
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

  private fun request(folder: Path) = DownloadRequest(
    url = URL,
    destination = Destination("$folder/"),
    connections = 1,
  )

  private companion object {
    const val URL = "https://example.com/file.bin"
  }
}
