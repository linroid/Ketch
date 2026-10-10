package com.linroid.ketch.torrent

import com.linroid.ketch.api.Destination
import com.linroid.ketch.api.DownloadRequest
import com.linroid.ketch.api.DownloadState
import com.linroid.ketch.api.DownloadTask
import com.linroid.ketch.api.ResolvedSource
import com.linroid.ketch.api.Segment
import com.linroid.ketch.core.Ketch
import com.linroid.ketch.core.engine.SourceResumeState
import com.linroid.ketch.core.task.TaskRecord
import com.linroid.ketch.core.task.TaskState
import com.linroid.ketch.core.task.TaskStore
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import okio.FileSystem
import okio.Path
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.time.Clock

/**
 * Ketch and the torrent source change a v1 task's files in every state: a running torrent
 * follows the change on its connection, and paused, completed and seeding ones pick it up.
 * Files of 100,000, 70,000 and 86,144 bytes in 16 KiB pieces, so pieces span files.
 */
@OptIn(ExperimentalAtomicApi::class)
class TorrentDownloadSourceSelectionTest {
  private inner class Harness(val seeder: GatedSeeder, val root: Path) {
    val torrent: MultiFileTorrent get() = seeder.torrent
    val engines = mutableListOf<KotlinTorrentEngine>()
    val networks = mutableListOf<CountingTorrentNetwork>()
    private val instances = mutableListOf<Ketch>()

    /** A source whose engine dials through a counting network; [networks] keeps it. */
    fun source(policy: TorrentUploadPolicy = TorrentUploadPolicy.DISABLED): TorrentDownloadSource {
      val network = CountingTorrentNetwork().also { networks += it }
      return TorrentDownloadSource(TorrentConfig(dhtEnabled = false, uploadPolicy = policy),
        seeder.http).also { source ->
        source.engineFactory = { config ->
          KotlinTorrentEngine(config, network, TorrentHttp(seeder.http),
            listenHost = "127.0.0.1").also { engines += it }
        }
      }
    }

    fun ketch(source: TorrentDownloadSource, store: TaskStore = MemoryTaskStore()): Ketch =
      Ketch(seeder.http, taskStore = store, additionalSources = listOf(source))
        .also { instances += it }

    fun request(vararg files: Int) = DownloadRequest(GatedSeeder.URL,
      destination = Destination(output.toString()),
      selectedFileIds = files.map { it.toString() }.toSet())

    val output: Path get() = root / "out"

    fun assertFile(index: Int) = assertContentEquals(torrent.payloads[index],
      torrentFileSystem.read(output / "f$index") { readByteArray() }, "f$index")

    fun assertNoFile(index: Int) =
      assertFalse(torrentFileSystem.exists(output / "f$index"), "f$index exists")

    fun close() = instances.forEach { it.close() }
  }

  private fun harness(block: suspend Harness.() -> Unit) = runTest {
    withContext(Dispatchers.Default) {
      withTimeout(45_000) {
        coroutineScope {
          val seeder = GatedSeeder()
          seeder.start(this)
          val root = FileSystem.SYSTEM_TEMPORARY_DIRECTORY /
            "ketch-source-selection-${InfoHash.fromBytes(torrentRandomBytes(20)).hex}"
          torrentFileSystem.createDirectories(root)
          val harness = Harness(seeder, root)
          try {
            harness.block()
          } finally {
            harness.close()
            harness.engines.forEach { it.stop() }
            seeder.close()
            torrentFileSystem.deleteRecursively(root, mustExist = false)
          }
          harness.engines.forEach { it.assertNoLeaks() }
        }
      }
    }
  }

  private suspend fun DownloadTask.awaitVerified(bytes: Long) = awaitStage(
    "$bytes verified bytes", this) {
    segments.first { segments -> segments.sumOf { it.downloadedBytes } >= bytes }
  }

  private suspend fun DownloadTask.awaitCompleted(): DownloadState.Completed {
    awaitStage("completion", this) { await().getOrThrow() }
    return assertIs<DownloadState.Completed>(state.value)
  }

  @Test
  fun selectFiles_running_expandsWithoutANewConnection() = harness {
    val source = source()
    val ketch = ketch(source)
    ketch.start()
    seeder.open(0..2)
    val task = ketch.download(request(0))
    task.awaitVerified(3 * 16_384L)
    task.selectFiles(setOf("0", "1"))
    assertEquals(setOf("0", "1"), task.request.selectedFileIds)
    seeder.openAll()
    assertEquals(torrent.sizeOf(0, 1), task.awaitCompleted().totalBytes)
    assertFile(0)
    assertFile(1)
    assertNoFile(2)
    assertEquals(1, networks.single().connects.load())
    assertEquals(1, seeder.accepted.load())
    source.assertNoLeaks()
  }

  @Test
  fun selectFiles_shrinkToVerified_completesWithTheNewTotal() = harness {
    val source = source()
    val ketch = ketch(source)
    ketch.start()
    // Every piece of f0; f1 waits.
    seeder.open(torrent.piecesOf(0))
    val task = ketch.download(request(0, 1))
    awaitStage("f0 to verify", task) {
      task.segments.first { segments -> segments.firstOrNull()?.isComplete == true }
    }
    task.selectFiles(setOf("0"))
    assertEquals(torrent.sizeOf(0), task.awaitCompleted().totalBytes)
    assertFile(0)
    assertEquals(1, networks.single().connects.load())
    source.assertNoLeaks()
    seeder.openAll()
  }

  @Test
  fun selectFiles_paused_resumesWithTheNewSelection() = harness {
    val source = source()
    val ketch = ketch(source)
    ketch.start()
    seeder.open(0..1)
    val task = ketch.download(request(0))
    task.awaitVerified(2 * 16_384L)
    task.pause()
    assertIs<DownloadState.Paused>(task.state.value)
    task.selectFiles(setOf("0", "2"))
    val paused = assertIs<DownloadState.Paused>(task.state.value)
    assertEquals(torrent.sizeOf(0, 2), paused.progress.totalBytes)
    seeder.openAll()
    task.resume()
    assertEquals(torrent.sizeOf(0, 2), task.awaitCompleted().totalBytes)
    assertFile(0)
    assertFile(2)
    assertNoFile(1)
    source.assertNoLeaks()
  }

  @Test
  fun selectFiles_completedExpand_reopensAndDownloadsTheNewFile() = harness {
    val source = source()
    val ketch = ketch(source)
    ketch.start()
    seeder.openAll()
    val task = ketch.download(request(0))
    val first = task.awaitCompleted()
    assertNoFile(1)
    task.selectFiles(setOf("0", "1"))
    val reopened = task.awaitCompleted()
    assertEquals(first.outputPath, reopened.outputPath)
    assertEquals(torrent.sizeOf(0, 1), reopened.totalBytes)
    assertFile(0)
    assertFile(1)
    source.assertNoLeaks()
  }

  @Test
  fun selectFiles_completedSeedingExpand_adoptsTheSeedingSession() = harness {
    val source = source(TorrentUploadPolicy.SEED_AFTER_COMPLETION)
    val ketch = ketch(source)
    ketch.start()
    seeder.openAll()
    val task = ketch.download(request(0))
    task.awaitCompleted()
    val seeding = assertNotNull(source.sessionOf(task.taskId))
    assertEquals(TorrentSessionState.SEEDING, seeding.state.value)
    task.selectFiles(setOf("0", "1"))
    assertEquals(torrent.sizeOf(0, 1), task.awaitCompleted().totalBytes)
    assertFile(1)
    // The same session downloaded the new file on its connection, and seeds again.
    assertSame(seeding, source.sessionOf(task.taskId))
    eventually("the session to seed again") {
      seeding.state.value == TorrentSessionState.SEEDING
    }
    assertEquals(1, networks.single().connects.load())
    source.stopSeeding(task.taskId)
    source.assertNoLeaks()
  }

  @Test
  fun selectFiles_racingFinish_rerunsAndDownloadsTheNewFile() = harness {
    val source = source()
    val ketch = ketch(source)
    ketch.start()
    val started = CompletableDeferred<DownloadTask>()
    var raced = false
    // The run already stopped following selections when this one arrives.
    source.onTransferEnd = {
      if (!raced) {
        raced = true
        started.await().selectFiles(setOf("0", "1"))
      }
    }
    seeder.openAll()
    val task = ketch.download(request(0))
    started.complete(task)
    assertEquals(torrent.sizeOf(0, 1), task.awaitCompleted().totalBytes)
    assertTrue(raced)
    assertFile(0)
    assertFile(1)
    source.assertNoLeaks()
  }

  @Test
  fun selectFiles_afterKetchRecreation_keepsTheSelection() = harness {
    val store = FileTaskStore(root / "tasks.json")
    val firstSource = source()
    val first = ketch(firstSource, store)
    first.start()
    seeder.open(0..2)
    val task = first.download(request(0))
    task.awaitVerified(16_384L)
    task.selectFiles(setOf("0", "2"))
    task.pause()
    first.close()
    firstSource.assertNoLeaks()
    val secondSource = source()
    val second = ketch(secondSource, FileTaskStore(root / "tasks.json"))
    second.start()
    val restored = second.tasks.value.single()
    assertEquals(task.taskId, restored.taskId)
    assertEquals(setOf("0", "2"), restored.request.selectedFileIds)
    assertIs<DownloadState.Paused>(restored.state.value)
    seeder.openAll()
    restored.resume()
    assertEquals(torrent.sizeOf(0, 2), restored.awaitCompleted().totalBytes)
    assertFile(0)
    assertFile(2)
    assertNoFile(1)
    secondSource.assertNoLeaks()
  }

  @Test
  fun resolvedSource_addedWithoutPreview_namesItsFiles() = harness {
    val source = source()
    val ketch = ketch(source)
    ketch.start()
    seeder.openAll()
    val task = ketch.download(request(1))
    assertNull(task.request.resolvedSource)
    task.awaitCompleted()
    val resolved = assertNotNull(task.request.resolvedSource)
    assertEquals(listOf("pack/f0", "pack/f1", "pack/f2"), resolved.files.map { it.name })
    // The saved request keeps the file list without the bulky metainfo.
    assertNull(resolved.metadata[ResolvedSource.METAINFO_KEY])
    source.assertNoLeaks()
  }

  @Test
  fun resolvedSource_olderRecordResumed_namesItsFiles() = harness {
    val store = MemoryTaskStore()
    val now = Clock.System.now()
    // A record saved before the request kept the file list: only the source state has it.
    val state = TorrentResumeState(torrent.metadata.infoHash.hex, torrent.sizeOf(0), "",
      setOf("0"), output.toString(), encodeBase64(torrent.metainfo), version = 2,
      privacy = TorrentDiscoveryPrivacy.PUBLIC)
    store.save(TaskRecord(taskId = "older", request = request(0), outputPath = output.toString(),
      state = TaskState.PAUSED, totalBytes = torrent.sizeOf(0),
      segments = listOf(Segment(0, 0, torrent.sizeOf(0) - 1)),
      sourceType = TorrentDownloadSource.TYPE,
      sourceResumeState = SourceResumeState(TorrentDownloadSource.TYPE,
        Json.encodeToString(state)),
      createdAt = now, updatedAt = now))
    val source = source()
    val ketch = ketch(source, store)
    ketch.start()
    val task = ketch.tasks.value.single()
    assertNull(task.request.resolvedSource)
    seeder.openAll()
    task.resume()
    task.awaitCompleted()
    assertFile(0)
    assertEquals(listOf("pack/f0", "pack/f1", "pack/f2"),
      assertNotNull(task.request.resolvedSource).files.map { it.name })
    source.assertNoLeaks()
  }
}
