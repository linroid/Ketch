package com.linroid.ketch.torrent

import com.linroid.ketch.api.Destination
import com.linroid.ketch.api.DownloadConfig
import com.linroid.ketch.api.DownloadRequest
import com.linroid.ketch.api.DownloadState
import com.linroid.ketch.api.DownloadTask
import com.linroid.ketch.api.PauseReason
import com.linroid.ketch.core.Ketch
import com.linroid.ketch.core.engine.HttpEngine
import com.linroid.ketch.core.engine.ServerInfo
import com.linroid.ketch.core.task.TaskStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
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

/**
 * A magnet that asks to wait for a file selection learns its files from a seeding engine over
 * `ut_metadata`, then stops without writing anything or holding a slot until files are chosen.
 * The seeding engine shares under [TorrentUploadPolicy.SEED_AFTER_COMPLETION]: metadata is not
 * served to peers when uploads are off.
 */
@OptIn(ExperimentalAtomicApi::class)
class TorrentAwaitSelectionTest {
  private val torrent = MultiFileTorrent()

  private inner class Harness(val root: Path, val seeder: KotlinTorrentEngine) {
    val engines = mutableListOf<KotlinTorrentEngine>()
    val networks = mutableListOf<CountingTorrentNetwork>()
    private val instances = mutableListOf<Ketch>()
    val output: Path get() = root / "out"
    val magnet: String get() = MagnetUri(torrent.metadata.infoHash,
      explicitPeers = listOf("127.0.0.1:${seeder.listenPort}")).toUri()

    fun source(): TorrentDownloadSource {
      val network = CountingTorrentNetwork().also { networks += it }
      return TorrentDownloadSource(TorrentConfig(dhtEnabled = false)).also { source ->
        source.engineFactory = { config ->
          KotlinTorrentEngine(config, network, listenHost = "127.0.0.1").also { engines += it }
        }
      }
    }

    fun ketch(
      source: TorrentDownloadSource,
      store: TaskStore = MemoryTaskStore(),
      config: DownloadConfig = DownloadConfig.Default,
    ): Ketch = Ketch(PlainHttp, taskStore = store, config = config,
      additionalSources = listOf(source)).also { instances += it }

    fun waitingRequest() = DownloadRequest(magnet, destination = Destination(output.toString()),
      awaitFileSelection = true)

    fun assertFile(index: Int) = assertContentEquals(torrent.payloads[index],
      torrentFileSystem.read(output / "f$index") { readByteArray() }, "f$index")

    fun close() = instances.forEach { it.close() }
  }

  private fun harness(block: suspend Harness.() -> Unit) = runTest {
    withContext(Dispatchers.Default) {
      withTimeout(45_000) {
        val root = FileSystem.SYSTEM_TEMPORARY_DIRECTORY /
          "ketch-await-selection-${InfoHash.fromBytes(torrentRandomBytes(20)).hex}"
        torrent.writeTo(root / "seed")
        val seeder = KotlinTorrentEngine(TorrentConfig(dhtEnabled = false,
          uploadPolicy = TorrentUploadPolicy.SEED_AFTER_COMPLETION), listenHost = "127.0.0.1")
        val harness = Harness(root, seeder)
        try {
          seeder.start()
          val seed = seeder.addTask(TorrentTaskSpec("seed", torrent.metadata,
            (root / "seed").toString(), emptySet()))
          seed.resume()
          assertEquals(TorrentSessionState.SEEDING, awaitStage("the seeder to seed") {
            seed.state.first {
              it == TorrentSessionState.SEEDING || it == TorrentSessionState.STOPPED
            }
          })
          harness.block()
        } finally {
          harness.close()
          harness.engines.forEach { it.stop() }
          seeder.stop()
          torrentFileSystem.deleteRecursively(root, mustExist = false)
        }
        harness.engines.forEach { it.assertNoLeaks() }
      }
    }
  }

  private suspend fun DownloadTask.awaitWaiting(): DownloadState.Paused =
    awaitStage("the task to wait for files", this) {
      state.first {
        it is DownloadState.Paused && it.reason == PauseReason.AwaitingFileSelection ||
          it.isTerminal
      }
    }.let { assertIs<DownloadState.Paused>(it) }

  @Test
  fun awaitFileSelection_magnet_parksWithoutWritingAndFreesTheSlot() = harness {
    val source = source()
    val ketch = ketch(source, config = DownloadConfig(maxConcurrentDownloads = 1))
    ketch.start()
    val task = ketch.download(waitingRequest())
    val waiting = task.awaitWaiting()
    assertEquals(torrent.sizeOf(0, 1, 2), waiting.progress.totalBytes)
    assertFalse(torrentFileSystem.exists(output))
    assertNull(task.outputPath)
    assertEquals(listOf("pack/f0", "pack/f1", "pack/f2"),
      assertNotNull(task.request.resolvedSource).files.map { it.name })
    source.assertNoLeaks()
    // The only download slot is free: another download runs to completion.
    val other = ketch.download(DownloadRequest("http://fixture/other",
      destination = Destination((root / "other").toString())))
    awaitStage("the other download", other) { other.await().getOrThrow() }
    assertIs<DownloadState.Paused>(task.state.value)
  }

  @Test
  fun selectFiles_parkedMagnet_downloadsOnlyChosenFiles() = harness {
    val source = source()
    val ketch = ketch(source)
    ketch.start()
    val task = ketch.download(waitingRequest())
    task.awaitWaiting()
    task.selectFiles(setOf("1"))
    awaitStage("completion", task) { task.await().getOrThrow() }
    assertEquals(torrent.sizeOf(1),
      assertIs<DownloadState.Completed>(task.state.value).totalBytes)
    assertFile(1)
    assertFalse(torrentFileSystem.exists(output / "f0"))
    assertFalse(torrentFileSystem.exists(output / "f2"))
    source.assertNoLeaks()
  }

  @Test
  fun restart_parkedMagnet_staysWaiting() = harness {
    val firstSource = source()
    val first = ketch(firstSource, FileTaskStore(root / "tasks.json"))
    first.start()
    val task = first.download(waitingRequest())
    task.awaitWaiting()
    first.close()
    val secondSource = source()
    val second = ketch(secondSource, FileTaskStore(root / "tasks.json"))
    second.start()
    val restored = second.tasks.value.single()
    assertEquals(PauseReason.AwaitingFileSelection,
      assertIs<DownloadState.Paused>(restored.state.value).reason)
    restored.selectFiles(setOf("0", "2"))
    awaitStage("completion", restored) { restored.await().getOrThrow() }
    assertFile(0)
    assertFile(2)
    assertFalse(torrentFileSystem.exists(output / "f1"))
    // Started from the saved metadata: the only connection downloaded the files.
    assertEquals(1, networks.last().connects.load())
    secondSource.assertNoLeaks()
  }

  /** Serves a small payload to the ordinary download that checks the freed slot. */
  private object PlainHttp : HttpEngine {
    private val payload = ByteArray(1024) { it.toByte() }

    override suspend fun head(url: String, headers: Map<String, String>): ServerInfo =
      ServerInfo(payload.size.toLong(), true, null, null)

    override suspend fun download(
      url: String,
      range: LongRange?,
      headers: Map<String, String>,
      onData: suspend (ByteArray) -> Unit,
    ) {
      val begin = range?.first?.toInt() ?: 0
      val end = range?.last?.toInt()?.plus(1) ?: payload.size
      onData(payload.copyOfRange(begin, end))
    }

    override fun close() = Unit
  }
}
