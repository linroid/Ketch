package com.linroid.ketch.app.state

import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import okio.FileSystem
import okio.ForwardingFileSystem
import okio.Path
import okio.Path.Companion.toOkioPath
import okio.Path.Companion.toPath
import okio.Sink
import okio.Source
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.concurrent.thread
import kotlin.test.assertTrue
import kotlin.time.Instant

class FileDiscoverHistoryStoreTest {
  private val fileSystem = FileSystem.SYSTEM
  private val directory = Files.createTempDirectory("ketch-discover").toOkioPath()
  private val path = directory / "discover-history.json"
  private val tmp = "$path.tmp".toPath()
  private val backup = "$path.bak".toPath()

  @AfterTest
  fun deleteDirectory() {
    fileSystem.deleteRecursively(directory)
  }

  private fun TestScope.store() =
    FileDiscoverHistoryStore(fileSystem, path, StandardTestDispatcher(testScheduler))

  private fun session(title: String): DiscoverSession {
    val at = Instant.fromEpochMilliseconds(1_790_000_000_000)
    return DiscoverSession(
      id = title,
      title = title,
      createdAt = at,
      updatedAt = at,
      turns = listOf(
        DiscoverTurn(
          id = "$title-turn",
          message = title,
          sites = emptyList(),
          startedAt = at,
          status = TurnStatus.Done,
        ),
      ),
    )
  }

  private fun write(target: Path, text: String) = fileSystem.write(target) { writeUtf8(text) }

  private fun read(target: Path): String = fileSystem.read(target) { readUtf8() }

  @Test
  fun save_thenAnotherStoreLoads_readsTheSessions() = runTest {
    val first = store()
    first.save(listOf(session("blender")))
    first.close()

    assertEquals(listOf(session("blender")), store().load())
    assertFalse(fileSystem.exists(tmp))
  }

  @Test
  fun load_afterTheFirst_answersFromMemory() = runTest {
    write(path, DiscoverHistoryCodec.encode(listOf(session("blender"))))
    val store = store()
    store.load()

    write(path, DiscoverHistoryCodec.encode(listOf(session("ubuntu"))))
    store.save(listOf(session("kernel")))

    assertEquals(listOf(session("kernel")), store.load())
    store.close()
  }

  @Test
  fun load_whileAThreadOfItsOwnReads_waitsForThatReadInsteadOfReadingAgain() = runTest {
    // The hosts read the history ahead, so the main thread finds it in memory.
    write(path, DiscoverHistoryCodec.encode(listOf(session("blender"))))
    val reading = CountDownLatch(1)
    val release = CountDownLatch(1)
    val reads = AtomicInteger()
    val slow = object : ForwardingFileSystem(fileSystem) {
      override fun source(file: Path): Source {
        reads.incrementAndGet()
        reading.countDown()
        release.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)
        return super.source(file)
      }
    }
    val store = FileDiscoverHistoryStore(slow, path, StandardTestDispatcher(testScheduler))
    val ahead = thread { store.load() }
    assertTrue(reading.await(TIMEOUT_SECONDS, TimeUnit.SECONDS))
    var loaded: List<DiscoverSession>? = null
    val main = thread { loaded = store.load() }

    release.countDown()
    ahead.join(TIMEOUT_SECONDS * 1000)
    main.join(TIMEOUT_SECONDS * 1000)

    assertEquals(listOf(session("blender")), loaded)
    assertEquals(1, reads.get())
    store.close()
  }

  @Test
  fun close_afterSavesInARow_writesTheLastOne() = runTest {
    val store = store()

    store.save(listOf(session("blender")))
    store.save(listOf(session("blender"), session("ubuntu")))
    store.close()

    val saved = DiscoverHistoryCodec.decode(read(path))
    assertEquals(listOf(session("blender"), session("ubuntu")), saved)
  }

  @Test
  fun flush_afterASave_waitsUntilItIsWritten() = runTest {
    val store = store()
    store.save(listOf(session("blender")))

    store.flush()

    assertEquals(listOf(session("blender")), DiscoverHistoryCodec.decode(read(path)))
    store.close()
  }

  @Test
  fun load_interruptedWrite_usesTheLeftoverFile() = runTest {
    write(tmp, DiscoverHistoryCodec.encode(listOf(session("blender"))))

    assertEquals(listOf(session("blender")), store().load())
    assertTrue(fileSystem.exists(path))
    assertFalse(fileSystem.exists(tmp))
  }

  @Test
  fun load_staleLeftoverBesideTheFile_deletesIt() = runTest {
    write(path, DiscoverHistoryCodec.encode(listOf(session("blender"))))
    write(tmp, DiscoverHistoryCodec.encode(listOf(session("stale"))))

    assertEquals(listOf(session("blender")), store().load())
    assertFalse(fileSystem.exists(tmp))
  }

  @Test
  fun save_afterAWriteFailed_writesTheNextOne() = runTest {
    var failing = true
    val flaky = object : ForwardingFileSystem(fileSystem) {
      override fun sink(file: Path, mustCreate: Boolean): Sink {
        check(!failing) { "The disk went away" }
        return super.sink(file, mustCreate)
      }
    }
    val store = FileDiscoverHistoryStore(flaky, path, StandardTestDispatcher(testScheduler))
    store.save(listOf(session("blender")))
    store.flush()
    assertFalse(fileSystem.exists(path))

    failing = false
    store.save(listOf(session("ubuntu")))
    store.close()

    assertEquals(listOf(session("ubuntu")), DiscoverHistoryCodec.decode(read(path)))
  }

  @Test
  fun load_historyOfANewerVersion_movesItAsideAndStartsEmpty() = runTest {
    val newer = """{"version": ${DiscoverHistoryCodec.VERSION + 1}, "sessions": []}"""
    write(path, newer)

    assertEquals(emptyList(), store().load())
    assertEquals(newer, read(backup))
    assertFalse(fileSystem.exists(path))
  }

  @Test
  fun load_unreadableFile_movesItAsideAndStartsEmpty() = runTest {
    write(backup, "an older copy")
    write(path, "not json")

    assertEquals(emptyList(), store().load())
    assertEquals("not json", read(backup))
    assertFalse(fileSystem.exists(path))
  }

  private companion object {
    const val TIMEOUT_SECONDS = 10L
  }
}
