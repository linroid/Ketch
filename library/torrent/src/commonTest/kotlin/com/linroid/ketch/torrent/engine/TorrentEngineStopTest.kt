package com.linroid.ketch.torrent.engine

import com.linroid.ketch.core.engine.HttpEngine
import com.linroid.ketch.core.engine.ServerInfo
import com.linroid.ketch.torrent.TorrentConfig
import com.linroid.ketch.torrent.TorrentDiscoveryPrivacy
import com.linroid.ketch.torrent.TorrentV2Fixture
import com.linroid.ketch.torrent.crypto.sha1Digest
import com.linroid.ketch.torrent.crypto.torrentRandomBytes
import com.linroid.ketch.torrent.metainfo.Bencode
import com.linroid.ketch.torrent.metainfo.InfoHash
import com.linroid.ketch.torrent.metainfo.TorrentMetadata
import com.linroid.ketch.torrent.net.TorrentHttp
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.getAndUpdate
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import okio.FileSystem
import okio.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

class TorrentEngineStopTest {
  /**
   * Trackers that never answer a `stopped` announce, so each holds its whole window, recording
   * when it began and ended after the engine began to stop ([stopping]). A [late] tracker's first
   * announce is still on its way as the stop begins, deaf to cancellation, until [lateBy] into
   * it: its torrent only leaves, and says `stopped`, then.
   */
  private class SilentTrackers(
    private val late: Set<String> = emptySet(),
    private val lateBy: Duration = Duration.ZERO,
  ) : HttpEngine {
    val stopping = CompletableDeferred<TimeSource.Monotonic.ValueTimeMark>()
    val requests = MutableStateFlow<List<String>>(emptyList())
    /** Per tracker: when its `stopped` announce began and ended, from the start of the stop. */
    val stopped = MutableStateFlow<Map<String, Pair<Duration, Duration>>>(emptyMap())
    private val held = MutableStateFlow<Set<String>>(emptySet())

    override suspend fun head(url: String, headers: Map<String, String>): ServerInfo =
      error("Unused")

    override suspend fun download(
      url: String,
      range: LongRange?,
      headers: Map<String, String>,
      onData: suspend (ByteArray) -> Unit,
    ) {
      val tracker = url.substringBefore('?')
      requests.update { it + url }
      if ("event=stopped" in url) {
        val since = stopping.await()
        val began = since.elapsedNow()
        try { awaitCancellation() } finally {
          stopped.update { it + (tracker to (began to since.elapsedNow())) }
        }
      }
      if (tracker in late && tracker !in held.getAndUpdate { it + tracker }) {
        withContext(NonCancellable) {
          stopping.await()
          delay(lateBy)
        }
      }
      onData(Bencode.encode(mapOf("interval" to 3600L, "peers" to ByteArray(0))))
    }

    override fun close() = Unit

    fun stoppedAnnounces(tracker: String): Int =
      requests.value.count { it.startsWith(tracker) && "event=stopped" in it }
  }

  /** A new folder: a v2 owner's output must be in one that exists. */
  private fun root(): Path = (FileSystem.SYSTEM_TEMPORARY_DIRECTORY /
    "ketch-engine-stop-${InfoHash.fromBytes(torrentRandomBytes(20)).hex}")
    .also { FileSystem.SYSTEM.createDirectories(it) }

  /** A one-piece v1 task announcing to [tracker], started. */
  private suspend fun KotlinTorrentEngine.addV1(index: Int, tracker: String, root: Path) {
    val bytes = byteArrayOf(index.toByte(), 2, 3, 4)
    val metadata = TorrentMetadata.fromBencode(Bencode.encode(mapOf(
      "announce" to tracker, "info" to mapOf("name" to "payload", "length" to 4L,
        "piece length" to 4L, "pieces" to sha1Digest(bytes))
    )))
    addTask(TorrentTaskSpec("stop-$index", metadata, (root / "$index").toString(), emptySet()))
      .resume()
  }

  @Test
  fun stop_givesEveryTorrentsStoppedAnnouncesOneWindow() = runTest {
    withContext(Dispatchers.Default) {
      withTimeout(20_000) {
        val trackers = SilentTrackers()
        val root = root()
        val urls = (1..3).map { "http://tracker$it/announce" }
        val engine = KotlinTorrentEngine(TorrentConfig(dhtEnabled = false),
          http = TorrentHttp(trackers))
        try {
          engine.start()
          for ((index, url) in urls.withIndex()) engine.addV1(index, url, root)
          trackers.requests.first { requests ->
            urls.all { url -> requests.any { it.startsWith(url) } }
          }
          val stopping = TimeSource.Monotonic.markNow()
          trackers.stopping.complete(stopping)
          engine.stop()
          val took = stopping.elapsedNow()
          for (url in urls) {
            assertEquals(1, trackers.stoppedAnnounces(url), "$url heard ${trackers.requests.value}")
          }
          // One torrent after another, they would take a window each.
          assertTrue(took < 4.seconds, "Stopping took $took")
        } finally {
          engine.stop()
          FileSystem.SYSTEM.deleteRecursively(root, mustExist = false)
        }
      }
    }
  }

  @Test
  fun stop_givesStoppedAnnouncesThatStartLateWhatIsLeftOfTheWindow() = runTest {
    withContext(Dispatchers.Default) {
      withTimeout(20_000) {
        val prompt = "http://prompt/announce"
        val lateV1 = "http://late-v1/announce"
        val lateV2 = "http://late-v2/announce"
        val lateBy = 1_200.milliseconds
        val trackers = SilentTrackers(late = setOf(lateV1, lateV2), lateBy = lateBy)
        val root = root()
        val engine = KotlinTorrentEngine(TorrentConfig(dhtEnabled = false),
          http = TorrentHttp(trackers))
        try {
          engine.start()
          engine.addV1(0, prompt, root)
          engine.addV1(1, lateV1, root)
          val fixture = TorrentV2Fixture.build(listOf("a" to 5))
          engine.addV2Task(TorrentV2TaskSpec("stop-v2", fixture.document,
            (root / "v2").toString(), trackerTiers = listOf(listOf(lateV2)),
            privacy = TorrentDiscoveryPrivacy.PUBLIC)).resume()
          val urls = listOf(prompt, lateV1, lateV2)
          trackers.requests.first { requests ->
            urls.all { url -> requests.any { it.startsWith(url) } }
          }
          val stopping = TimeSource.Monotonic.markNow()
          trackers.stopping.complete(stopping)
          engine.stop()
          val took = stopping.elapsedNow()
          for (url in urls) {
            assertEquals(1, trackers.stoppedAnnounces(url), "$url heard ${trackers.requests.value}")
          }
          val windows = trackers.stopped.value
          // A torrent that leaves at once has the whole window.
          assertTrue(windows.getValue(prompt).second >= 1_500.milliseconds, "$windows")
          for (url in listOf(lateV1, lateV2)) {
            val (began, ended) = windows.getValue(url)
            assertTrue(began >= lateBy, "$url began at $began")
            // Only what was left of the window, not a window of its own.
            assertTrue(ended < 2_600.milliseconds, "$url ended at $ended")
          }
          assertTrue(took < 2_800.milliseconds, "Stopping took $took")
        } finally {
          engine.stop()
          FileSystem.SYSTEM.deleteRecursively(root, mustExist = false)
        }
      }
    }
  }
}
