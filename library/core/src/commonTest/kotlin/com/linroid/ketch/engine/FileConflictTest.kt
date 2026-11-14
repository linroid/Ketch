package com.linroid.ketch.engine

import com.linroid.ketch.api.Checksum
import com.linroid.ketch.api.ChecksumAlgorithm
import com.linroid.ketch.api.Destination
import com.linroid.ketch.api.DownloadConfig
import com.linroid.ketch.api.DownloadRequest
import com.linroid.ketch.api.DownloadState
import com.linroid.ketch.api.FileConflict
import com.linroid.ketch.api.KetchError
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
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Verifies what a download does when a file already has the name Ketch chose for it
 * ([FileConflict]): saved beside it by default, or replacing it once the download completes,
 * and never before, nor when the name is another unfinished download's.
 */
class FileConflictTest {
  @Test
  fun download_renameByDefault_keepsExistingFileAndSavesBesideIt() = runTest {
    withKetch { ketch, engine, _, folder ->
      writeOld(folder / "file.bin")

      val task = ketch.download(request(folder))
      runCurrent()

      val completed = assertIs<DownloadState.Completed>(task.state.value)
      assertEquals((folder / "file (1).bin").toString(), completed.outputPath)
      assertContentEquals(OLD, read(folder / "file.bin"))
      assertContentEquals(engine.content, read(folder / "file (1).bin"))
    }
  }

  @Test
  fun download_overwrite_keepsExistingFileUntilItCompletes() = runTest {
    withKetch(OVERWRITE) { ketch, engine, store, folder ->
      writeOld(folder / "file.bin")
      engine.stallAfterBytes = 500

      val task = ketch.download(request(folder))
      runCurrent()

      assertIs<DownloadState.Downloading>(task.state.value)
      assertEquals((folder / "file.bin").toString(), task.outputPath)
      assertEquals((folder / "file.bin.ketch").toString(), store.load(task.taskId)?.partialPath)
      assertContentEquals(OLD, read(folder / "file.bin"))
    }
  }

  @Test
  fun download_overwrite_replacesExistingFileOnceComplete() = runTest {
    withKetch(OVERWRITE) { ketch, engine, _, folder ->
      writeOld(folder / "file.bin")

      val task = ketch.download(request(folder))
      runCurrent()

      val completed = assertIs<DownloadState.Completed>(task.state.value)
      assertEquals((folder / "file.bin").toString(), completed.outputPath)
      assertContentEquals(engine.content, read(folder / "file.bin"))
      assertFalse(platformFileSystem.exists(folder / "file.bin.ketch"))
      assertFalse(platformFileSystem.exists(folder / "file (1).bin"))
    }
  }

  @Test
  fun download_overwriteNoExistingFile_savesUnderTheName() = runTest {
    withKetch(OVERWRITE) { ketch, engine, _, folder ->
      val task = ketch.download(request(folder))
      runCurrent()

      val completed = assertIs<DownloadState.Completed>(task.state.value)
      assertEquals((folder / "file.bin").toString(), completed.outputPath)
      assertContentEquals(engine.content, read(folder / "file.bin"))
    }
  }

  @Test
  fun pauseAndResume_overwrite_replacesExistingFileOnceComplete() = runTest {
    withKetch(OVERWRITE) { ketch, engine, _, folder ->
      writeOld(folder / "file.bin")
      engine.stallAfterBytes = 500
      val task = ketch.download(request(folder))
      runCurrent()
      task.pause()
      runCurrent()
      assertContentEquals(OLD, read(folder / "file.bin"))

      engine.stallAfterBytes = -1
      task.resume()
      runCurrent()

      val completed = assertIs<DownloadState.Completed>(task.state.value)
      assertEquals((folder / "file.bin").toString(), completed.outputPath)
      assertContentEquals(engine.content, read(folder / "file.bin"))
    }
  }

  @Test
  fun download_overwriteFails_keepsExistingFile() = runTest {
    withKetch(OVERWRITE) { ketch, engine, _, folder ->
      writeOld(folder / "file.bin")
      engine.failAfterBytes = 500

      val task = ketch.download(request(folder))
      runCurrent()

      assertIs<DownloadState.Failed>(task.state.value)
      assertContentEquals(OLD, read(folder / "file.bin"))
    }
  }

  @Test
  fun download_overwriteChecksumMismatch_keepsExistingFile() = runTest {
    withKetch(OVERWRITE) { ketch, _, _, folder ->
      writeOld(folder / "file.bin")
      val wrong = Checksum(ChecksumAlgorithm.SHA_256, "0".repeat(64))

      val task = ketch.download(request(folder).copy(checksum = wrong))
      runCurrent()

      val failed = assertIs<DownloadState.Failed>(task.state.value)
      assertIs<KetchError.ChecksumMismatch>(failed.error)
      assertContentEquals(OLD, read(folder / "file.bin"))
      assertFalse(platformFileSystem.exists(folder / "file.bin.ketch"))
    }
  }

  @Test
  fun cancel_overwrite_deletesOnlyThePartialFile() = runTest {
    withKetch(OVERWRITE) { ketch, engine, _, folder ->
      writeOld(folder / "file.bin")
      engine.stallAfterBytes = 500
      val task = ketch.download(request(folder))
      runCurrent()

      task.cancel()
      runCurrent()

      assertIs<DownloadState.Canceled>(task.state.value)
      assertContentEquals(OLD, read(folder / "file.bin"))
      assertFalse(platformFileSystem.exists(folder / "file.bin.ketch"))
    }
  }

  @Test
  fun remove_deletingFilesOfPausedOverwrite_keepsExistingFile() = runTest {
    withKetch(OVERWRITE) { ketch, engine, _, folder ->
      writeOld(folder / "file.bin")
      engine.stallAfterBytes = 500
      val task = ketch.download(request(folder))
      runCurrent()
      task.pause()
      runCurrent()

      task.remove(deleteFiles = true)
      runCurrent()

      assertContentEquals(OLD, read(folder / "file.bin"))
      assertFalse(platformFileSystem.exists(folder / "file.bin.ketch"))
    }
  }

  @Test
  fun download_overwriteNameOfRunningDownload_getsFreeName() = runTest {
    withKetch(OVERWRITE) { ketch, engine, _, folder ->
      engine.stallAfterBytes = 500
      val first = ketch.download(request(folder))
      runCurrent()

      val second = ketch.download(request(folder))
      runCurrent()

      assertEquals((folder / "file.bin").toString(), first.outputPath)
      assertEquals((folder / "file (1).bin").toString(), second.outputPath)
    }
  }

  @Test
  fun download_overwriteNameOfPausedDownload_getsFreeName() = runTest {
    withKetch(OVERWRITE) { ketch, engine, _, folder ->
      writeOld(folder / "file.bin")
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
      assertContentEquals(OLD, read(folder / "file.bin"))
      assertTrue(platformFileSystem.exists(folder / "file.bin.ketch"))
    }
  }

  @Test
  fun download_overwriteFileOfUnfinishedTaskWithoutPartialFile_getsFreeName() = runTest {
    withKetch { ketch, engine, store, folder ->
      engine.stallAfterBytes = 500
      val task = ketch.download(request(folder))
      runCurrent()
      task.pause()
      runCurrent()
      ketch.close()
      runCurrent()
      // A paused task that writes under its final name, as those saved before partial files.
      platformFileSystem.atomicMove(folder / "file.bin.ketch", folder / "file.bin")
      val record = checkNotNull(store.load(task.taskId))
      store.save(record.copy(partialPath = null))
      val half = read(folder / "file.bin")

      engine.stallAfterBytes = -1
      val restarted = newKetch(engine, store, OVERWRITE)
      try {
        restarted.start()
        runCurrent()
        val second = restarted.download(request(folder))
        runCurrent()

        val completed = assertIs<DownloadState.Completed>(second.state.value)
        assertEquals((folder / "file (1).bin").toString(), completed.outputPath)
        assertContentEquals(half, read(folder / "file.bin"))
      } finally {
        restarted.close()
        runCurrent()
      }
    }
  }

  @Test
  fun download_overwriteNameIsAFolder_getsFreeName() = runTest {
    withKetch(OVERWRITE) { ketch, engine, _, folder ->
      platformFileSystem.createDirectories(folder / "file.bin")

      val task = ketch.download(request(folder))
      runCurrent()

      val completed = assertIs<DownloadState.Completed>(task.state.value)
      assertEquals((folder / "file (1).bin").toString(), completed.outputPath)
      assertContentEquals(engine.content, read(folder / "file (1).bin"))
      assertTrue(platformFileSystem.metadata(folder / "file.bin").isDirectory)
    }
  }

  @Test
  fun download_overwriteNameTooLongForPartialFile_getsFreeName() = runTest {
    withKetch(OVERWRITE) { ketch, _, _, folder ->
      // 252 bytes: no room for the partial suffix, so the download would write over the file.
      val name = "a".repeat(248) + ".bin"
      writeOld(folder / name)

      val task = ketch.download(
        DownloadRequest(url = "https://example.com/$name", destination = Destination("$folder/")),
      )
      runCurrent()

      val completed = assertIs<DownloadState.Completed>(task.state.value)
      assertTrue(completed.outputPath != (folder / name).toString())
      assertContentEquals(OLD, read(folder / name))
    }
  }

  @Test
  fun download_requestRenames_winsOverConfigOverwrite() = runTest {
    withKetch(OVERWRITE) { ketch, _, _, folder ->
      writeOld(folder / "file.bin")

      val task = ketch.download(request(folder).copy(fileConflict = FileConflict.RENAME))
      runCurrent()

      val completed = assertIs<DownloadState.Completed>(task.state.value)
      assertEquals((folder / "file (1).bin").toString(), completed.outputPath)
      assertContentEquals(OLD, read(folder / "file.bin"))
    }
  }

  @Test
  fun download_requestOverwrites_winsOverConfigRename() = runTest {
    withKetch { ketch, engine, _, folder ->
      writeOld(folder / "file.bin")

      val task = ketch.download(request(folder).copy(fileConflict = FileConflict.OVERWRITE))
      runCurrent()

      val completed = assertIs<DownloadState.Completed>(task.state.value)
      assertEquals((folder / "file.bin").toString(), completed.outputPath)
      assertContentEquals(engine.content, read(folder / "file.bin"))
    }
  }

  @Test
  fun download_sameRequestIdWithAnotherFileConflict_returnsTheTaskUnchanged() = runTest {
    withKetch { ketch, engine, _, folder ->
      engine.stallAfterBytes = 500
      val id = "123e4567-e89b-12d3-a456-426614174000"
      val first = ketch.download(request(folder).copy(requestId = id))
      runCurrent()

      val again = ketch.download(
        request(folder).copy(requestId = id, fileConflict = FileConflict.OVERWRITE),
      )
      runCurrent()

      assertSame(first, again)
      assertEquals(null, again.request.fileConflict)
      assertEquals(1, ketch.tasks.value.size)
    }
  }

  private fun writeOld(path: Path) = platformFileSystem.write(path) { write(OLD) }

  private fun read(path: Path): ByteArray = platformFileSystem.read(path) { readByteArray() }

  private suspend fun TestScope.withKetch(
    config: DownloadConfig = DownloadConfig(retryCount = 0),
    block: suspend (Ketch, FakeHttpEngine, InMemoryTaskStore, Path) -> Unit,
  ) {
    val folder = FileSystem.SYSTEM_TEMPORARY_DIRECTORY / "ketch-conflict-${Random.nextLong()}"
    platformFileSystem.createDirectories(folder)
    val engine = FakeHttpEngine()
    val store = InMemoryTaskStore()
    val ketch = newKetch(engine, store, config)
    try {
      block(ketch, engine, store, folder)
    } finally {
      ketch.close()
      runCurrent()
      platformFileSystem.deleteRecursively(folder)
    }
  }

  private fun TestScope.newKetch(
    engine: FakeHttpEngine,
    store: InMemoryTaskStore,
    config: DownloadConfig,
  ): Ketch {
    val dispatcher = StandardTestDispatcher(testScheduler)
    return Ketch(
      httpEngine = engine,
      taskStore = store,
      config = config,
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
    val OLD = "the file that was there".encodeToByteArray()
    val OVERWRITE = DownloadConfig(retryCount = 0, fileConflict = FileConflict.OVERWRITE)
  }
}
