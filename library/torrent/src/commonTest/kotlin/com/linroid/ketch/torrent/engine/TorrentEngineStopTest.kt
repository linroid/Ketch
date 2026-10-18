package com.linroid.ketch.torrent.engine

import com.linroid.ketch.core.engine.HttpEngine
import com.linroid.ketch.core.engine.ServerInfo
import com.linroid.ketch.torrent.TorrentConfig
import com.linroid.ketch.torrent.TorrentDiscoveryPrivacy
import com.linroid.ketch.torrent.TorrentV2Fixture
import com.linroid.ketch.torrent.crypto.sha1Digest
import com.linroid.ketch.torrent.crypto.torrentRandomBytes
import com.linroid.ketch.torrent.magnet.ABANDONED_LOOKUP_GRACE
import com.linroid.ketch.torrent.metainfo.Bencode
import com.linroid.ketch.torrent.metainfo.InfoHash
import com.linroid.ketch.torrent.metainfo.TorrentMetadata
import com.linroid.ketch.torrent.net.PeerEndpoint
import com.linroid.ketch.torrent.net.TorrentConnection
import com.linroid.ketch.torrent.net.TorrentHttp
import com.linroid.ketch.torrent.net.createTorrentNetwork
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.getAndUpdate
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import okio.FileSystem
import okio.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
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

  /**
   * A tracker that takes [busy] to apply a `stopped` announce, as loaded ones do, or never answers
   * one, recording each announce's event as it arrives (`none` for a regular one) and `stopped
   * applied` once it is. Its answers name [peer], if any.
   */
  private class BusyTracker(
    private val busy: Duration = Duration.INFINITE,
    private val peer: PeerEndpoint? = null,
  ) : HttpEngine {
    val events = MutableStateFlow<List<String>>(emptyList())

    override suspend fun head(url: String, headers: Map<String, String>): ServerInfo =
      error("Unused")

    override suspend fun download(
      url: String,
      range: LongRange?,
      headers: Map<String, String>,
      onData: suspend (ByteArray) -> Unit,
    ) {
      if ("/announce" in url) {
        val event = url.substringAfter("&event=", "").substringBefore('&').ifEmpty { "none" }
        events.update { it + event }
        if (event == "stopped") {
          delay(busy)
          events.update { it + "stopped applied" }
        }
      }
      val peers = peer?.let { listOf(mapOf("ip" to it.host, "port" to it.port.toLong())) }
      onData(Bencode.encode(mapOf("interval" to 3600L, "peers" to (peers ?: ByteArray(0)))))
    }

    override fun close() = Unit

    /**
     * Waits for the torrent to start again after its first `stopped`, and tells whether this
     * tracker had applied that `stopped` by then.
     */
    suspend fun restartedOnceStoppedApplied(): Boolean {
      val heard = events.first { heard ->
        val stopped = heard.indexOf("stopped")
        stopped >= 0 && "started" in heard.subList(stopped, heard.size)
      }
      val stopped = heard.indexOf("stopped")
      val restarted = stopped + heard.subList(stopped, heard.size).indexOf("started")
      return heard.indexOf("stopped applied") in stopped..<restarted
    }
  }

  /**
   * Pauses and resumes a torrent [add] adds at once, once its tracker heard it start, and checks
   * that both returned at once, and that the tracker applied its `stopped` before it heard it
   * start again.
   */
  private fun pauseAndResume_startsAgainOnceStoppedIsApplied(
    add: suspend (KotlinTorrentEngine, String, Path) -> TorrentSession,
  ) = runTest {
    withContext(Dispatchers.Default) {
      withTimeout(20_000) {
        val tracker = BusyTracker(1.seconds)
        val root = root()
        val engine = KotlinTorrentEngine(TorrentConfig(dhtEnabled = false),
          http = TorrentHttp(tracker))
        try {
          engine.start()
          val session = add(engine, "http://busy/announce", root)
          tracker.events.first { "started" in it }
          val stopping = TimeSource.Monotonic.markNow()
          session.pause()
          session.resume()
          val took = stopping.elapsedNow()
          assertTrue(took < 1.seconds, "Pausing and resuming took $took")
          assertTrue(tracker.restartedOnceStoppedApplied(), "Heard ${tracker.events.value}")
        } finally {
          engine.stop()
          FileSystem.SYSTEM.deleteRecursively(root, mustExist = false)
        }
      }
    }
  }

  @Test
  fun pauseAndResume_v1_startsAgainOnceTheTrackerAppliedStopped() =
    pauseAndResume_startsAgainOnceStoppedIsApplied { engine, tracker, root ->
      engine.addV1(0, tracker, root)
    }

  @Test
  fun pauseAndResume_v2_startsAgainOnceTheTrackerAppliedStopped() =
    pauseAndResume_startsAgainOnceStoppedIsApplied { engine, tracker, root ->
      val fixture = TorrentV2Fixture.build(listOf("a" to 5))
      engine.addV2Task(TorrentV2TaskSpec("busy-v2", fixture.document, (root / "v2").toString(),
        trackerTiers = listOf(listOf(tracker)), privacy = TorrentDiscoveryPrivacy.PUBLIC))
        .also { it.resume() }
    }

  /**
   * Runs [body] with a started engine whose tracker-only lookups of [magnet], a v2 one unless
   * given, ask [tracker] at `http://busy/announce`, going on for [grace] once nothing waits for
   * them, and get from it a peer that takes their connection and never answers: [body] waits for
   * one with `dialed`.
   */
  private fun trackerOnlyLookup(
    busy: Duration,
    magnet: String = "magnet:?xt=urn:btmh:1220${"ab".repeat(32)}" +
      "&tr=http%3A%2F%2Fbusy%2Fannounce",
    grace: Duration = ABANDONED_LOOKUP_GRACE,
    body: suspend CoroutineScope.(
      engine: KotlinTorrentEngine,
      tracker: BusyTracker,
      magnet: String,
      dialed: suspend () -> Unit,
    ) -> Unit,
  ) = runTest {
    withContext(Dispatchers.Default) {
      withTimeout(20_000) {
        val network = createTorrentNetwork()
        val listener = network.listen(PeerEndpoint("127.0.0.1", 0))
        val tracker = BusyTracker(busy, peer = listener.local)
        val engine = KotlinTorrentEngine(TorrentConfig(dhtEnabled = false),
          http = TorrentHttp(tracker), magnetLookupGrace = grace)
        val accepted = MutableStateFlow<List<TorrentConnection>>(emptyList())
        try {
          engine.start()
          coroutineScope {
            val peer = launch {
              while (true) {
                val connection = listener.accept()
                accepted.update { it + connection }
              }
            }
            var dials = 0
            body(engine, tracker, magnet) {
              dials++
              accepted.first { it.size >= dials }
            }
            peer.cancel()
          }
        } finally {
          engine.stop()
          accepted.value.forEach { it.close() }
          listener.close()
          network.close()
        }
      }
    }
  }

  @Test
  fun v2TrackerOnlyLookup_canceled_returnsAtOnce() =
    trackerOnlyLookup(busy = Duration.INFINITE) { engine, tracker, magnet, dialed ->
      val lookup = async { engine.fetchV2Metadata(magnet, TorrentDiscoveryPrivacy.TRACKER_ONLY) }
      // The lookup asks the peer its tracker named.
      dialed()
      val canceling = TimeSource.Monotonic.markNow()
      lookup.cancelAndJoin()
      val took = canceling.elapsedNow()
      // It did not wait for its tracker to answer `stopped`...
      assertTrue(took < 1.seconds, "Canceling took $took")
      // ...which still heard it.
      tracker.events.first { "stopped" in it }
    }

  @Test
  fun v2TrackerOnlyLookup_askedAgainAtOnce_startsOnceTheTrackerAppliedStopped() =
    trackerOnlyLookup(busy = 1.seconds) { engine, tracker, magnet, dialed ->
      val first = async { engine.fetchV2Metadata(magnet, TorrentDiscoveryPrivacy.TRACKER_ONLY) }
      dialed()
      first.cancelAndJoin()
      val second = async { engine.fetchV2Metadata(magnet, TorrentDiscoveryPrivacy.TRACKER_ONLY) }
      assertTrue(tracker.restartedOnceStoppedApplied(), "Heard ${tracker.events.value}")
      second.cancelAndJoin()
    }

  @Test
  fun stop_trackerOnlyLookupInItsGrace_saysStoppedWithinTheWindow() =
    trackerOnlyLookup(busy = Duration.INFINITE, magnet = "magnet:?xt=urn:btih:${"ab".repeat(20)}" +
      "&tr=http%3A%2F%2Fbusy%2Fannounce", grace = 1.minutes) { engine, tracker, magnet, dialed ->
      val lookup = async { engine.fetchMetadata(magnet, TorrentDiscoveryPrivacy.TRACKER_ONLY) }
      dialed()
      lookup.cancelAndJoin()
      // Nothing waits for it, so it goes on for its grace, still started at its tracker...
      assertEquals(1, engine.pendingMetadataLookups)
      assertTrue("stopped" !in tracker.events.value, "Heard ${tracker.events.value}")
      // ...until the engine stops, which ends it while its `stopped` can still go out.
      engine.stop()
      assertTrue("stopped" in tracker.events.value, "Heard ${tracker.events.value}")
    }

  @Test
  fun stop_v2TrackerOnlyLookupAwaited_saysStoppedWithinTheWindow() =
    trackerOnlyLookup(busy = Duration.INFINITE) { engine, tracker, magnet, dialed ->
      val lookup = async {
        runCatching { engine.fetchV2Metadata(magnet, TorrentDiscoveryPrivacy.TRACKER_ONLY) }
      }
      dialed()
      // A preview still waits for it as the engine stops, which ends it with its `stopped`.
      engine.stop()
      assertTrue("stopped" in tracker.events.value, "Heard ${tracker.events.value}")
      assertTrue(lookup.await().isFailure)
    }

  /** A new folder: a v2 owner's output must be in one that exists. */
  private fun root(): Path = (FileSystem.SYSTEM_TEMPORARY_DIRECTORY /
    "ketch-engine-stop-${InfoHash.fromBytes(torrentRandomBytes(20)).hex}")
    .also { FileSystem.SYSTEM.createDirectories(it) }

  /** A one-piece v1 task announcing to [tracker], started. */
  private suspend fun KotlinTorrentEngine.addV1(
    index: Int,
    tracker: String,
    root: Path,
  ): KotlinTorrentSession {
    val bytes = byteArrayOf(index.toByte(), 2, 3, 4)
    val metadata = TorrentMetadata.fromBencode(Bencode.encode(mapOf(
      "announce" to tracker, "info" to mapOf("name" to "payload", "length" to 4L,
        "piece length" to 4L, "pieces" to sha1Digest(bytes))
    )))
    return addTask(TorrentTaskSpec("stop-$index", metadata, (root / "$index").toString(),
      emptySet())).also { it.resume() }
  }

  @Test
  fun removeAndPause_returnAtOnce_whileTheTrackersNeverAnswerStopped() = runTest {
    withContext(Dispatchers.Default) {
      withTimeout(20_000) {
        val trackers = SilentTrackers()
        val root = root()
        val removed = "http://removed/announce"
        val paused = "http://paused/announce"
        val removedV2 = "http://removed-v2/announce"
        val urls = listOf(removed, paused, removedV2)
        val engine = KotlinTorrentEngine(TorrentConfig(dhtEnabled = false),
          http = TorrentHttp(trackers))
        try {
          engine.start()
          val first = engine.addV1(0, removed, root)
          val second = engine.addV1(1, paused, root)
          val fixture = TorrentV2Fixture.build(listOf("a" to 5))
          val third = engine.addV2Task(TorrentV2TaskSpec("stop-v2", fixture.document,
            (root / "v2").toString(), trackerTiers = listOf(listOf(removedV2)),
            privacy = TorrentDiscoveryPrivacy.PUBLIC)).also { it.resume() }
          trackers.requests.first { requests ->
            urls.all { url -> requests.any { it.startsWith(url) } }
          }
          trackers.stopping.complete(TimeSource.Monotonic.markNow())
          val took = listOf(
            suspend { engine.removeTorrent(first.infoHash, deleteFiles = false) },
            suspend { second.pause() },
            suspend { engine.removeTorrent(third.infoHash, deleteFiles = false) }
          ).map { stop ->
            val stopping = TimeSource.Monotonic.markNow()
            stop()
            stopping.elapsedNow()
          }
          // None waited for its tracker to answer `stopped`...
          assertTrue(took.all { it < 1.seconds }, "Removing, pausing and removing took $took")
          // ...which still heard it, for as long as it would have waited.
          val windows = trackers.stopped.first { it.keys.containsAll(urls) }
          for (url in urls) {
            assertEquals(1, trackers.stoppedAnnounces(url), "$url heard ${trackers.requests.value}")
            val (began, ended) = windows.getValue(url)
            assertTrue(ended - began >= 1_500.milliseconds, "$url said goodbye $began to $ended")
          }
        } finally {
          engine.stop()
          FileSystem.SYSTEM.deleteRecursively(root, mustExist = false)
        }
      }
    }
  }

  @Test
  fun stop_waitsForTheStoppedAnnouncesOfTorrentsRemovedBefore() = runTest {
    withContext(Dispatchers.Default) {
      withTimeout(20_000) {
        val trackers = SilentTrackers()
        val root = root()
        val removed = "http://removed/announce"
        val engine = KotlinTorrentEngine(TorrentConfig(dhtEnabled = false),
          http = TorrentHttp(trackers))
        try {
          engine.start()
          val session = engine.addV1(0, removed, root)
          trackers.requests.first { requests -> requests.any { it.startsWith(removed) } }
          val stopping = TimeSource.Monotonic.markNow()
          trackers.stopping.complete(stopping)
          engine.removeTorrent(session.infoHash, deleteFiles = false)
          engine.stop()
          val took = stopping.elapsedNow()
          // The engine waited for the announce still on its way, until its window ended.
          val (_, ended) = assertNotNull(trackers.stopped.value[removed], "No goodbye ended")
          assertTrue(ended >= 1_500.milliseconds, "Said goodbye until $ended")
          assertTrue(took < 2_600.milliseconds, "Stopping took $took")
        } finally {
          engine.stop()
          FileSystem.SYSTEM.deleteRecursively(root, mustExist = false)
        }
      }
    }
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
