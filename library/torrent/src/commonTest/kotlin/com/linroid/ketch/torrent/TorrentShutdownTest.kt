package com.linroid.ketch.torrent

import com.linroid.ketch.api.Destination
import com.linroid.ketch.api.DownloadRequest
import com.linroid.ketch.core.Ketch
import com.linroid.ketch.core.engine.HttpEngine
import com.linroid.ketch.core.engine.ServerInfo
import com.linroid.ketch.core.engine.SourceState
import com.linroid.ketch.core.engine.decodeState
import com.linroid.ketch.core.task.TaskControl
import com.linroid.ketch.core.task.TaskRecord
import com.linroid.ketch.core.task.TaskState
import com.linroid.ketch.torrent.control.SeedingOutcome
import com.linroid.ketch.torrent.control.SeedingTask
import com.linroid.ketch.torrent.crypto.sha1Digest
import com.linroid.ketch.torrent.crypto.torrentRandomBytes
import com.linroid.ketch.torrent.engine.KotlinTorrentEngine
import com.linroid.ketch.torrent.engine.TorrentEngine
import com.linroid.ketch.torrent.metainfo.Bencode
import com.linroid.ketch.torrent.metainfo.InfoHash
import com.linroid.ketch.torrent.metainfo.TorrentMetadata
import com.linroid.ketch.torrent.net.LocalIpv4Network
import com.linroid.ketch.torrent.net.PeerEndpoint
import com.linroid.ketch.torrent.net.TorrentHttp
import com.linroid.ketch.torrent.net.createTorrentNetwork
import com.linroid.ketch.torrent.portmap.NatPmpClient
import com.linroid.ketch.torrent.portmap.PortMapGateways
import com.linroid.ketch.torrent.portmap.UpnpClient
import com.linroid.ketch.torrent.portmap.natPmpGateway
import com.linroid.ketch.torrent.storage.torrentFileSystem
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import okio.FileSystem
import okio.Path
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant
import kotlin.time.TimeSource

/** Ketch shutting down with torrents downloading and seeding, against trackers that hang. */
class TorrentShutdownTest {
  /**
   * Trackers that answer every announce but `stopped`, which they never answer, and serve
   * [metainfo] at any other URL. Records when each tracker's `stopped` announce ended, from the
   * start of the shutdown ([stopping]).
   */
  private class SilentTrackers(private val metainfo: ByteArray) : HttpEngine {
    val stopping = CompletableDeferred<TimeSource.Monotonic.ValueTimeMark>()
    val announces = MutableStateFlow<List<String>>(emptyList())
    val stopped = MutableStateFlow<Map<String, Duration>>(emptyMap())

    override suspend fun head(url: String, headers: Map<String, String>): ServerInfo =
      error("Unexpected HEAD")

    override suspend fun download(
      url: String,
      range: LongRange?,
      headers: Map<String, String>,
      onData: suspend (ByteArray) -> Unit,
    ) {
      if ("/announce" !in url) return onData(metainfo)
      val tracker = url.substringBefore('?')
      announces.update { it + url }
      if ("event=stopped" in url) {
        val since = stopping.await()
        try { awaitCancellation() } finally {
          stopped.update { it + (tracker to since.elapsedNow()) }
        }
      }
      onData(Bencode.encode(mapOf("interval" to 3600L, "peers" to ByteArray(0))))
    }

    override fun close() = Unit

    fun heard(tracker: String) = announces.value.any { it.startsWith(tracker) }

    fun stoppedAnnounces(tracker: String): Int =
      announces.value.count { it.startsWith(tracker) && "event=stopped" in it }
  }

  /** A completed task of [torrent], on disk at [output], that seeded when Ketch last stopped. */
  private fun seedingRecord(taskId: String, torrent: MultiFileTorrent, output: Path): TaskRecord {
    torrent.writeTo(output)
    val ids = torrent.sizes.indices.mapTo(LinkedHashSet()) { it.toString() }
    val total = torrent.sizes.sum().toLong()
    val state = TorrentSourceState(torrent.metadata.infoHash.hex, total, "", ids,
      output.toString(), encodeBase64(torrent.metainfo), version = 2,
      privacy = TorrentDiscoveryPrivacy.PUBLIC)
    val at = Instant.fromEpochMilliseconds(1_000)
    return TaskRecord(taskId = taskId,
      request = DownloadRequest("http://fixture/$taskId.torrent",
        destination = Destination(output.toString()), selectedFileIds = ids),
      outputPath = output.toString(), state = TaskState.COMPLETED, totalBytes = total,
      sourceType = TorrentDownloadSource.TYPE,
      sourceState = SourceState(TorrentDownloadSource.TYPE, Json.encodeToString(state)),
      createdAt = at, updatedAt = at, completedAt = at, control = TaskControl(sharing = true))
  }

  /** What [shutdownTest]'s scenario left to check once Ketch shut down. */
  private class Shutdown(
    val trackers: SilentTrackers,
    val store: MemoryTaskStore,
    val source: TorrentDownloadSource,
    /** The downloading task, when the scenario had one. */
    val downloadId: String?,
    val took: Duration,
    /** When the router heard the first mapping removed, from the start of the shutdown. */
    val removalStarted: Duration?,
  ) {
    /** Asserts that [tracker] heard one `stopped` announce, within the one window. */
    fun assertStoppedInWindow(tracker: String) {
      assertEquals(1, trackers.stoppedAnnounces(tracker), "$tracker: ${trackers.announces.value}")
      // One window from the start of the shutdown, not one after another.
      val ended = assertNotNull(trackers.stopped.value[tracker], tracker)
      assertTrue(ended < 2_600.milliseconds, "$tracker said goodbye until $ended")
    }
  }

  /**
   * Runs Ketch with a completed task seeding and, with [download], a torrent no peer serves,
   * against [SilentTrackers] and a router that maps the listen port but never answers its
   * removal, as port mapping is on by default; shuts it down once every torrent announced and the
   * port is mapped, right after [beforeShutdown], waiting as long as hosts do (the default 5 s)
   * before their process exits, and hands [check] what happened by then.
   */
  private fun shutdownTest(
    download: Boolean,
    beforeShutdown: (TorrentDownloadSource) -> Unit = {},
    check: suspend Shutdown.() -> Unit,
  ) = runTest {
    withContext(Dispatchers.Default) {
      withTimeout(30_000) {
        val root = FileSystem.SYSTEM_TEMPORARY_DIRECTORY /
          "ketch-shutdown-${InfoHash.fromBytes(torrentRandomBytes(20)).hex}"
        val downloading = MultiFileTorrent(announce = DOWNLOAD_TRACKER)
        val seeding = MultiFileTorrent(sizes = listOf(40_000, 25_000), announce = SEED_TRACKER)
        val trackers = SilentTrackers(downloading.metainfo)
        val store = MemoryTaskStore()
        store.save(seedingRecord("seeder", seeding, root / "seeded"))
        val network = createTorrentNetwork()
        val routerScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        // The lifetime of each mapping the router was asked for; 0 removes one.
        val mappings = MutableStateFlow(emptyList<Pair<Int, Int>>())
        val router = natPmpGateway(network, routerScope, silentRemovals = true,
          requests = mappings)
        val source = TorrentDownloadSource(TorrentConfig(dhtEnabled = false,
          localDiscovery = false, uploadPolicy = TorrentUploadPolicy.SEED_AFTER_COMPLETION),
          trackers).also { source ->
          source.engineFactory = { config ->
            KotlinTorrentEngine(config, http = TorrentHttp(trackers), listenHost = "127.0.0.1",
              portMapping = true, portMapGateways = PortMapGateways(NatPmpClient(network, router),
                UpnpClient(network, ssdp = PeerEndpoint("127.0.0.1", 9), searchMs = 100),
                pcp = null,
                // The gateway guesses for 127.0.0.2 include 127.0.0.1, where the router answers.
                networks = { listOf(LocalIpv4Network(byteArrayOf(127, 0, 0, 2), 8)) }))
          }
        }
        val ketch = Ketch(trackers, taskStore = store, additionalSources = listOf(source))
        try {
          ketch.start()
          // No peer has the download's pieces: it downloads until Ketch stops.
          val task = if (!download) null else {
            ketch.download(DownloadRequest("http://fixture/downloading.torrent",
              destination = Destination((root / "downloading").toString())))
          }
          awaitStage("every torrent announced", task) {
            trackers.announces.first {
              (task == null || trackers.heard(DOWNLOAD_TRACKER)) && trackers.heard(SEED_TRACKER)
            }
          }
          eventually("the seeder to seed") { source.seedingTaskIds.value == setOf("seeder") }
          awaitStage("the router to map the port") {
            mappings.first { asked -> asked.any { it.second > 0 } }
          }
          assertTrue(mappings.value.none { it.second == 0 }, "Removed early: ${mappings.value}")
          val removal = routerScope.async {
            mappings.first { asked -> asked.any { it.second == 0 } }
            TimeSource.Monotonic.markNow()
          }

          beforeShutdown(source)
          val stopping = TimeSource.Monotonic.markNow()
          trackers.stopping.complete(stopping)
          ketch.shutdown()
          val took = stopping.elapsedNow()
          val removalStarted = if (removal.isCompleted) removal.await() - stopping else null
          Shutdown(trackers, store, source, task?.taskId, took, removalStarted).check()
        } finally {
          // What goes on in the background stops before the router and the network do.
          withContext(NonCancellable) {
            ketch.shutdown(10.seconds)
            routerScope.coroutineContext.job.cancelAndJoin()
          }
          network.close()
          torrentFileSystem.deleteRecursively(root, mustExist = false)
        }
      }
    }
  }

  @Test
  fun shutdown_downloadAndSeeder_shareOneStoppedAnnounceWindow() =
    shutdownTest(download = true) {
      assertStoppedInWindow(DOWNLOAD_TRACKER)
      assertStoppedInWindow(SEED_TRACKER)
      // The router forgets the port meanwhile, however long it takes to answer.
      val removal = assertNotNull(removalStarted, "No mapping removed")
      assertTrue(removal < 1.seconds, "Removing the mapping began after $removal")
      assertTrue(took < 3.seconds, "Shutting down took $took")
      // The download is paused to resume, and the seeder saved its checkpoint.
      val download = assertNotNull(store.load(assertNotNull(downloadId)))
      assertEquals(TaskState.DOWNLOADING, download.state)
      assertNotNull(download.sourceState)
      val seeder = assertNotNull(store.load("seeder")?.sourceState)
      assertTrue(source.decodeState(seeder).resumeData.isNotEmpty())
    }

  @Test
  fun shutdown_seederOnly_removesThePortMappingWithinTheWindow() =
    shutdownTest(download = false) {
      assertStoppedInWindow(SEED_TRACKER)
      val removal = assertNotNull(removalStarted, "No mapping removed")
      assertTrue(removal < 1.seconds, "Removing the mapping began after $removal")
      assertTrue(took < 3.seconds, "Shutting down took $took")
      val seeder = assertNotNull(store.load("seeder")?.sourceState)
      assertTrue(source.decodeState(seeder).resumeData.isNotEmpty())
    }

  @Test
  fun shutdown_seederSavingSlowly_announcesAndSavesBeforeTheHostStopsWaiting() {
    val saved = CompletableDeferred<Pair<String, SourceState>>()
    shutdownTest(download = false, beforeShutdown = { source ->
      // A busy disk: each save of the seeder's state takes longer than the whole window, and
      // longer than the host's 5 s leave after it.
      source.saveSessionStates { taskId, state ->
        delay(3_500)
        saved.complete(taskId to state)
      }
    }) {
      assertStoppedInWindow(SEED_TRACKER)
      // Saved while the trackers heard it, before the host stopped waiting and its process exits.
      assertTrue(saved.isCompleted, "The seeder's state was not saved within the shutdown")
      val (taskId, state) = saved.await()
      assertEquals("seeder", taskId)
      assertTrue(source.decodeState(state).resumeData.isNotEmpty())
    }
  }

  /** A completed one-file task of a torrent named [name], to seed, and its info hash. */
  private fun seedingTask(name: String): Pair<SeedingTask, String> {
    val metainfo = Bencode.encode(mapOf("info" to mapOf("name" to name, "length" to 4L,
      "piece length" to 4L, "pieces" to sha1Digest(byteArrayOf(1, 2, 3, 4)))))
    val hash = TorrentMetadata.fromBencode(metainfo).infoHash.hex
    val ids = TorrentFileTable.parse(metainfo, TorrentConfig()).ids
    val state = TorrentSourceState(hash, 4, "", ids, "unused-$name", encodeBase64(metainfo),
      version = 2, privacy = TorrentDiscoveryPrivacy.PUBLIC)
    return SeedingTask(name, "http://fixture/$name.torrent", state.saved(), null,
      "unused-$name", ids) to hash
  }

  /** Seeds a completed task named [name] through [source] on [engine]; its resume data is `1`. */
  private suspend fun seed(
    source: TorrentDownloadSource,
    engine: FakeTorrentEngine,
    name: String,
  ): FakeTorrentSession = coroutineScope {
    val (task, hash) = seedingTask(name)
    val session = FakeTorrentSession(hash).apply { savedResumeData = byteArrayOf(1) }
    engine.addTorrentResult = session
    val seeds = launch {
      eventually("$name to check its files") { session.resumed }
      session.seed()
    }
    assertEquals(SeedingOutcome.SEEDING, source.startSeeding(task))
    seeds.join()
    session
  }

  @Test
  fun stopping_stopsTheSeedersAtOnce_andStopCatchesLaterOnes() = runTest {
    withContext(Dispatchers.Default) {
      withTimeout(10_000) {
        val fake = FakeTorrentEngine()
        var windowOpened = false
        val engine = object : TorrentEngine by fake {
          override fun openStoppedWindow() {
            windowOpened = true
          }
        }
        val source = TorrentDownloadSource(TorrentConfig(dhtEnabled = false,
          uploadPolicy = TorrentUploadPolicy.SEED_AFTER_COMPLETION))
          .also { it.engineFactory = { engine } }
        // Each save, and whether the engine had stopped by then.
        val saves = Channel<Pair<String, Boolean>>(Channel.UNLIMITED)
        source.saveSessionStates { taskId, _ -> saves.send(taskId to fake.stopped) }
        suspend fun seed(name: String) = seed(source, fake, name)
        try {
          val early = seed("early")

          source.stopping()
          // It stops while Ketch pauses the downloads, in the window it opened for them all.
          assertTrue(windowOpened)
          eventually("the seeder to stop") { early.paused }
          assertEquals("early" to false, saves.receive())
          assertEquals(emptySet(), source.seedingTaskIds.value)

          // One that begins to seed meanwhile: stop() stops it, before the engine.
          val late = seed("late")
          source.stop()
          assertTrue(late.paused)
          assertEquals("late" to false, saves.receive())
          assertTrue(fake.stopped)
        } finally { source.close() }
      }
    }
  }

  @Test
  fun stopping_savesTheSeederWhileItPauses_thenAsThePauseLeftIt() = runTest {
    withContext(Dispatchers.Default) {
      withTimeout(10_000) {
        val fake = FakeTorrentEngine()
        val source = TorrentDownloadSource(TorrentConfig(dhtEnabled = false,
          uploadPolicy = TorrentUploadPolicy.SEED_AFTER_COMPLETION))
          .also { it.engineFactory = { fake } }
        // The resume data of each save.
        val saves = Channel<ByteArray>(Channel.UNLIMITED)
        source.saveSessionStates { _, state ->
          saves.send(decodeBase64(source.decodeState(state).resumeData))
        }
        try {
          val seeder = seed(source, fake, "seeder")
          // Its trackers take their time to hear that it stopped.
          val goodbye = CompletableDeferred<Unit>()
          seeder.pauseGate = goodbye

          source.stopping()
          // Saved as it is meanwhile, not after: a host may exit before the pause ends.
          assertContentEquals(byteArrayOf(1), saves.receive())
          assertFalse(seeder.paused)
          // Then as its pause left it, the final counters.
          seeder.savedResumeData = byteArrayOf(2)
          goodbye.complete(Unit)
          assertContentEquals(byteArrayOf(2), saves.receive())
          source.stop()
          assertTrue(saves.tryReceive().isFailure)
        } finally { source.close() }
      }
    }
  }

  private companion object {
    const val DOWNLOAD_TRACKER = "http://download-tracker/announce"
    const val SEED_TRACKER = "http://seed-tracker/announce"
  }
}
