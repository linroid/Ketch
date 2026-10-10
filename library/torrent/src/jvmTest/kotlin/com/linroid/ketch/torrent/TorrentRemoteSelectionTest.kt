package com.linroid.ketch.torrent

import com.linroid.ketch.api.Destination
import com.linroid.ketch.api.DownloadConfig
import com.linroid.ketch.api.DownloadRequest
import com.linroid.ketch.api.DownloadState
import com.linroid.ketch.api.DownloadTask
import com.linroid.ketch.api.PauseReason
import com.linroid.ketch.api.ResolvedSource
import com.linroid.ketch.api.torrent.TorrentCommandContext
import com.linroid.ketch.api.torrent.TorrentCommandError
import com.linroid.ketch.api.torrent.TorrentCommandException
import com.linroid.ketch.api.torrent.TorrentController
import com.linroid.ketch.api.torrent.TorrentFileOrder
import com.linroid.ketch.core.Ketch
import com.linroid.ketch.core.engine.HttpEngine
import com.linroid.ketch.core.engine.ServerInfo
import com.linroid.ketch.remote.RemoteKetch
import com.linroid.ketch.server.KetchServer
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.request.prepareGet
import io.ktor.client.statement.bodyAsChannel
import io.ktor.utils.io.readUTF8Line
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import okio.FileSystem
import okio.Path
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * File selection and the torrent controller end to end: a daemon ([KetchServer]) runs the real
 * torrent source, and a [RemoteKetch] chooses files, changes them live and reads the torrent
 * controller over REST and SSE.
 */
@OptIn(ExperimentalAtomicApi::class)
class TorrentRemoteSelectionTest {
  private inner class Daemon(val root: Path, val http: HttpEngine, scope: CoroutineScope) {
    val engines = mutableListOf<KotlinTorrentEngine>()
    val source = TorrentDownloadSource(TorrentConfig(dhtEnabled = false), http).also { source ->
      source.engineFactory = { config ->
        KotlinTorrentEngine(config, createTorrentNetwork(), TorrentHttp(http),
          listenHost = "127.0.0.1").also { engines += it }
      }
    }
    val ketch = Ketch(http, config = DownloadConfig(maxConcurrentDownloads = 1),
      additionalSources = listOf(source))

    // Without a token the server keeps clients to its folders, so the test's root is added.
    val server = KetchServer(ketch, host = "127.0.0.1", port = 0,
      allowedDirectories = listOf(root.toString()), mdnsEnabled = false)
    lateinit var remote: RemoteKetch
    val events = StringBuilder()
    private val rawClient = HttpClient(CIO)
    private var capture: Job? = null
    private val captureScope = scope

    val output: Path get() = root / "out"

    suspend fun start() {
      ketch.start()
      server.start(wait = false)
      remote = RemoteKetch("127.0.0.1", server.port())
      remote.start()
      remote.connectionState.first { it is com.linroid.ketch.remote.ConnectionState.Connected }
      // Everything the server sends on the shared event stream, as a client receives it.
      val started = CompletableDeferred<Unit>()
      capture = captureScope.launch {
        rawClient.prepareGet("http://127.0.0.1:${server.port()}/api/events").execute {
          val channel = it.bodyAsChannel()
          started.complete(Unit)
          while (true) {
            val line = channel.readUTF8Line() ?: break
            synchronized(events) { events.append(line).append('\n') }
          }
        }
      }
      started.await()
    }

    val torrents: TorrentController get() = remote.torrents

    fun capturedEvents(): String = synchronized(events) { events.toString() }

    suspend fun close() {
      capture?.cancelAndJoin()
      rawClient.close()
      if (::remote.isInitialized) remote.close()
      server.stop()
      ketch.close()
      engines.forEach { it.stop() }
    }
  }

  private fun test(block: suspend CoroutineScope.(Path) -> Unit) = runTest {
    withContext(Dispatchers.Default) {
      withTimeout(60_000) {
        coroutineScope {
          val root = FileSystem.SYSTEM_TEMPORARY_DIRECTORY /
            "ketch-remote-selection-${InfoHash.fromBytes(torrentRandomBytes(20)).hex}"
          torrentFileSystem.createDirectories(root)
          try {
            block(root)
          } finally {
            torrentFileSystem.deleteRecursively(root, mustExist = false)
          }
        }
      }
    }
  }

  private fun assertFile(output: Path, torrent: MultiFileTorrent, index: Int) =
    assertContentEquals(torrent.payloads[index],
      torrentFileSystem.read(output / "f$index") { readByteArray() }, "f$index")

  private suspend fun DownloadTask.awaitCompleted(): DownloadState.Completed =
    awaitStage("completion", this, timeoutMs = 30_000) {
      state.first { it is DownloadState.Completed || it is DownloadState.Failed }
    }.let { assertIs<DownloadState.Completed>(it, "$it") }

  private fun DownloadTask.downloaded(): Long = segments.value.sumOf { it.downloadedBytes }

  private suspend fun TorrentController.context(taskId: String, key: String) =
    TorrentCommandContext(key, assertNotNull(snapshot(taskId)).revision)

  @Test
  fun remoteSelection_waitingMagnet_selectsThroughTheDaemon() = test { root ->
    val torrent = MultiFileTorrent()
    torrent.writeTo(root / "seed")
    // Metadata is only served to peers by an engine that shares.
    val seeder = KotlinTorrentEngine(TorrentConfig(dhtEnabled = false,
      uploadPolicy = TorrentUploadPolicy.SEED_AFTER_COMPLETION), listenHost = "127.0.0.1")
    val daemon = Daemon(root, PlainHttp, this)
    try {
      seeder.start()
      val seed = seeder.addTask(TorrentTaskSpec("seed", torrent.metadata,
        (root / "seed").toString(), emptySet()))
      seed.resume()
      awaitStage("the seeder to seed") { seed.state.first { it == TorrentSessionState.SEEDING } }
      daemon.start()
      val magnet = MagnetUri(torrent.metadata.infoHash,
        explicitPeers = listOf("127.0.0.1:${seeder.listenPort}")).toUri()

      val task = daemon.remote.download(DownloadRequest(magnet,
        destination = Destination(daemon.output.toString()), awaitFileSelection = true))
      val waiting = awaitStage("the task to wait for files", task) {
        task.state.first {
          it is DownloadState.Paused && it.reason == PauseReason.AwaitingFileSelection ||
            it.isTerminal
        }
      }
      assertIs<DownloadState.Paused>(waiting)
      // The only download slot is free while it waits.
      val other = daemon.remote.download(DownloadRequest("http://fixture/other",
        destination = Destination((root / "other").toString())))
      val finished = awaitStage("the other download", other) {
        other.state.first { it is DownloadState.Completed || it.isTerminal }
      }
      assertIs<DownloadState.Completed>(finished)

      val page = assertNotNull(daemon.torrents.files(task.taskId))
      assertEquals(listOf("pack/f0", "pack/f1", "pack/f2"), page.files.map { it.path })
      assertTrue(page.files.none { it.selected })
      val bySize = assertNotNull(
        daemon.torrents.files(task.taskId, order = TorrentFileOrder.SIZE, descending = true)
      )
      assertEquals(listOf("0", "2", "1"), bySize.files.map { it.id })

      task.selectFiles(setOf("1"))
      val completed = task.awaitCompleted()

      assertEquals(torrent.sizeOf(1), completed.totalBytes)
      assertFile(daemon.output, torrent, 1)
      assertFalse(torrentFileSystem.exists(daemon.output / "f0"))
      assertFalse(torrentFileSystem.exists(daemon.output / "f2"))
      assertEquals(setOf("1"), task.request.selectedFileIds)
      daemon.source.assertNoLeaks()
    } finally {
      daemon.close()
      seeder.stop()
    }
    daemon.engines.forEach { it.assertNoLeaks() }
  }

  @Test
  fun remoteSelection_liveExpandPauseShrinkAndReopen() = test { root ->
    val seeder = GatedSeeder()
    seeder.start(this)
    val torrent = seeder.torrent
    val daemon = Daemon(root, seeder.http, this)
    try {
      daemon.start()
      val controller = daemon.torrents
      val resolved: ResolvedSource = daemon.remote.resolve(GatedSeeder.URL)
      assertTrue(ResolvedSource.METAINFO_KEY in resolved.metadata)
      // File 0 can only finish its first piece, so the download is still running.
      seeder.open(0..0)
      val task = daemon.remote.download(DownloadRequest(GatedSeeder.URL,
        destination = Destination(daemon.output.toString()), selectedFileIds = setOf("0"),
        resolvedSource = resolved))
      eventually("progress") { task.downloaded() > 0 }
      val connections = seeder.accepted.load()
      val before = controller.context(task.taskId, "expand")

      val expanded = controller.select(task.taskId, setOf("0", "1"), before)

      assertEquals(1, expanded.selectionGeneration)
      // The same key returns the same outcome, whatever revision it carries.
      assertEquals(expanded, controller.select(task.taskId, setOf("1", "0"), before))
      val conflict = assertFailsWith<TorrentCommandException> {
        controller.select(task.taskId, setOf("0"), TorrentCommandContext("stale",
          before.expectedRevision))
      }
      assertEquals(TorrentCommandError.CONFLICT, conflict.error)
      assertNotNull(conflict.currentRevision)
      eventually("the task to download both files") {
        task.request.selectedFileIds == setOf("0", "1") &&
          (task.state.value as? DownloadState.Downloading)?.progress?.totalBytes ==
          torrent.sizeOf(0, 1)
      }
      // File 0 and the start of file 1 arrive over the connection the download already had.
      // The seeder serves requests in order, so file 1's last pieces stay out of reach.
      seeder.open(1..8)
      eventually("file 0 and part of file 1") {
        task.downloaded() > torrent.sizeOf(0)
      }
      assertEquals(connections, seeder.accepted.load())

      task.pause()
      awaitStage("pause", task) { task.state.first { it is DownloadState.Paused } }
      val shrunk = controller.select(task.taskId, setOf("1"),
        controller.context(task.taskId, "shrink"))
      assertEquals(2, shrunk.selectionGeneration)
      seeder.openAll()
      task.resume()
      assertEquals(torrent.sizeOf(1), task.awaitCompleted().totalBytes)
      assertFile(daemon.output, torrent, 1)

      val reopened = controller.select(task.taskId, setOf("1", "2"),
        controller.context(task.taskId, "reopen"))
      assertEquals(3, reopened.selectionGeneration)
      awaitStage("the reopened task to finish", task, timeoutMs = 30_000) {
        task.state.first {
          it is DownloadState.Completed && it.totalBytes == torrent.sizeOf(1, 2)
        }
      }
      assertFile(daemon.output, torrent, 1)
      assertFile(daemon.output, torrent, 2)

      val final = awaitStage("the final snapshot") {
        controller.observe(task.taskId).first {
          it != null && it.selectionComplete && it.selectionGeneration == 3L
        }.let(::assertNotNull)
      }
      assertEquals(torrent.sizeOf(1, 2), assertNotNull(final.counters).wantedBytes)
      val files = assertNotNull(controller.files(task.taskId))
      assertEquals(listOf(false, true, true), files.files.map { it.selected })
      daemon.source.assertNoLeaks()

      val captured = daemon.capturedEvents()
      assertTrue("state_changed" in captured, captured.take(500))
      assertFalse("metainfo" in captured, "the event stream carried a torrent's metainfo")
    } finally {
      daemon.close()
      seeder.close()
    }
    daemon.engines.forEach { it.assertNoLeaks() }
  }

  /** Serves a small payload to an ordinary download. */
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
