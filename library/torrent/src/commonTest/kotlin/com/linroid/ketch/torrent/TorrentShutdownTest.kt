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
import com.linroid.ketch.torrent.net.TorrentHttp
import com.linroid.ketch.torrent.storage.torrentFileSystem
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import okio.FileSystem
import okio.Path
import kotlin.test.Test
import kotlin.test.assertEquals
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

  @Test
  fun shutdown_downloadAndSeeder_shareOneStoppedAnnounceWindow() = runTest {
    withContext(Dispatchers.Default) {
      withTimeout(30_000) {
        val root = FileSystem.SYSTEM_TEMPORARY_DIRECTORY /
          "ketch-shutdown-${InfoHash.fromBytes(torrentRandomBytes(20)).hex}"
        val downloading = MultiFileTorrent(announce = DOWNLOAD_TRACKER)
        val seeding = MultiFileTorrent(sizes = listOf(40_000, 25_000), announce = SEED_TRACKER)
        val trackers = SilentTrackers(downloading.metainfo)
        val store = MemoryTaskStore()
        store.save(seedingRecord("seeder", seeding, root / "seeded"))
        val source = TorrentDownloadSource(TorrentConfig(dhtEnabled = false, portMapping = false,
          localDiscovery = false, uploadPolicy = TorrentUploadPolicy.SEED_AFTER_COMPLETION),
          trackers).also { source ->
          source.engineFactory = { config ->
            KotlinTorrentEngine(config, http = TorrentHttp(trackers), listenHost = "127.0.0.1")
          }
        }
        val ketch = Ketch(trackers, taskStore = store, additionalSources = listOf(source))
        try {
          ketch.start()
          // No peer has the download's pieces: it downloads until Ketch stops.
          val task = ketch.download(DownloadRequest("http://fixture/downloading.torrent",
            destination = Destination((root / "downloading").toString())))
          awaitStage("both torrents announced", task) {
            trackers.announces.first {
              trackers.heard(DOWNLOAD_TRACKER) && trackers.heard(SEED_TRACKER)
            }
          }
          eventually("the seeder to seed") { source.seedingTaskIds.value == setOf("seeder") }

          val stopping = TimeSource.Monotonic.markNow()
          trackers.stopping.complete(stopping)
          ketch.shutdown(10.seconds)
          val took = stopping.elapsedNow()

          for (tracker in listOf(DOWNLOAD_TRACKER, SEED_TRACKER)) {
            val heard = trackers.announces.value
            assertEquals(1, trackers.stoppedAnnounces(tracker), "$tracker: $heard")
            // One window from the start of the shutdown, not one after another.
            val ended = assertNotNull(trackers.stopped.value[tracker], tracker)
            assertTrue(ended < 2_600.milliseconds, "$tracker said goodbye until $ended")
          }
          assertTrue(took < 3.seconds, "Shutting down took $took")
          // The download is paused to resume, and the seeder saved its checkpoint.
          val download = assertNotNull(store.load(task.taskId))
          assertEquals(TaskState.DOWNLOADING, download.state)
          assertNotNull(download.sourceState)
          val seeder = assertNotNull(store.load("seeder")?.sourceState)
          assertTrue(source.decodeState(seeder).resumeData.isNotEmpty())
        } finally {
          ketch.close()
          torrentFileSystem.deleteRecursively(root, mustExist = false)
        }
      }
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
        suspend fun seed(name: String): FakeTorrentSession {
          val (task, hash) = seedingTask(name)
          val session = FakeTorrentSession(hash).apply { savedResumeData = byteArrayOf(1) }
          fake.addTorrentResult = session
          val seeds = launch {
            eventually("$name to check its files") { session.resumed }
            session.seed()
          }
          assertEquals(SeedingOutcome.SEEDING, source.startSeeding(task))
          seeds.join()
          return session
        }
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

  private companion object {
    const val DOWNLOAD_TRACKER = "http://download-tracker/announce"
    const val SEED_TRACKER = "http://seed-tracker/announce"
  }
}
