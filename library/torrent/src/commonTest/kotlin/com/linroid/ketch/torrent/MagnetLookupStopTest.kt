package com.linroid.ketch.torrent

import com.linroid.ketch.api.Destination
import com.linroid.ketch.api.DownloadRequest
import com.linroid.ketch.api.DownloadTask
import com.linroid.ketch.core.Ketch
import com.linroid.ketch.core.engine.HttpEngine
import com.linroid.ketch.core.engine.ServerInfo
import com.linroid.ketch.core.engine.resolve
import com.linroid.ketch.torrent.crypto.torrentRandomBytes
import com.linroid.ketch.torrent.engine.KotlinTorrentEngine
import com.linroid.ketch.torrent.magnet.MAX_PENDING_METADATA
import com.linroid.ketch.torrent.metainfo.InfoHash
import com.linroid.ketch.torrent.net.TorrentHttp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import okio.FileSystem
import okio.Path
import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * A magnet's metadata lookup runs only while a task or a preview waits for it, and for a short
 * grace after: stopping the task stops the lookup then, resuming it meanwhile carries on with it,
 * and lookups given up never keep new magnets from being looked up.
 */
@OptIn(ExperimentalAtomicApi::class)
class MagnetLookupStopTest {
  /**
   * Trackers that never answer an announce, counting those still waiting, and every announce,
   * by tracker host.
   */
  private class HangingTrackers : HttpEngine {
    val waiting = MutableStateFlow<Map<String, Int>>(emptyMap())
    val announces = MutableStateFlow<Map<String, Int>>(emptyMap())

    override suspend fun head(url: String, headers: Map<String, String>): ServerInfo =
      error("Unexpected HEAD")

    override suspend fun download(
      url: String,
      range: LongRange?,
      headers: Map<String, String>,
      onData: suspend (ByteArray) -> Unit,
    ) {
      val host = url.substringAfter("://").substringBefore('/')
      announces.update { it + (host to (it[host] ?: 0) + 1) }
      waiting.update { it + (host to (it[host] ?: 0) + 1) }
      try { awaitCancellation() } finally {
        waiting.update { it + (host to it.getValue(host) - 1) }
      }
    }

    override fun close() = Unit

    fun asked(host: String): Boolean = (waiting.value[host] ?: 0) > 0
  }

  /** A magnet of its own hash, announced only to `tracker<index>.test`. */
  private fun magnet(index: Int): String =
    "magnet:?xt=urn:btih:${InfoHash.fromBytes(torrentRandomBytes(20)).hex}" +
      "&tr=http%3A%2F%2Ftracker$index.test%2Fannounce"

  /**
   * A source whose engine, without DHT or LAN discovery, reaches trackers through [http], and
   * keeps a lookup nothing waits for going for [GRACE].
   */
  private fun source(http: HangingTrackers, engine: AtomicReference<KotlinTorrentEngine?>) =
    TorrentDownloadSource(TorrentConfig(dhtEnabled = false, portMapping = false,
      localDiscovery = false), http).also { source ->
      source.engineFactory = { config ->
        KotlinTorrentEngine(config, http = TorrentHttp(http), listenHost = "127.0.0.1",
          magnetLookupGrace = GRACE).also { engine.store(it) }
      }
    }

  /** Runs [body] with Ketch over a [source], the folder it downloads to and its trackers. */
  private fun withKetch(
    body: suspend (Ketch, Path, HangingTrackers, AtomicReference<KotlinTorrentEngine?>) -> Unit,
  ) = runTest {
    withContext(Dispatchers.Default) {
      withTimeout(30_000) {
        val trackers = HangingTrackers()
        val engine = AtomicReference<KotlinTorrentEngine?>(null)
        val source = source(trackers, engine)
        val root = FileSystem.SYSTEM_TEMPORARY_DIRECTORY /
          "ketch-magnet-stop-${InfoHash.fromBytes(torrentRandomBytes(20)).hex}"
        val ketch = Ketch(trackers, additionalSources = listOf(source))
        try {
          ketch.start()
          body(ketch, root, trackers, engine)
        } finally {
          withContext(NonCancellable) { ketch.shutdown(10.seconds) }
          FileSystem.SYSTEM.deleteRecursively(root, mustExist = false)
        }
      }
    }
  }

  /** Starts a magnet task, stops it with [stop], and checks that its lookup stopped. */
  private fun stoppingTheTask_stopsItsLookup(stop: suspend (DownloadTask) -> Unit) =
    withKetch { ketch, root, trackers, engine ->
      val task = ketch.download(DownloadRequest(magnet(0),
        destination = Destination(root.toString())))
      eventually("the lookup to announce") { trackers.asked("tracker0.test") }
      stop(task)
      // Without anything waiting for it, the lookup stops once its grace ends, not when its two
      // minutes do.
      eventually("the lookup to stop") {
        !trackers.asked("tracker0.test") && engine.load()?.pendingMetadataLookups == 0
      }
    }

  @Test
  fun pause_stopsTheLookup() = stoppingTheTask_stopsItsLookup { it.pause() }

  @Test
  fun cancel_stopsTheLookup() = stoppingTheTask_stopsItsLookup { it.cancel() }

  @Test
  fun remove_stopsTheLookup() = stoppingTheTask_stopsItsLookup { it.remove() }

  @Test
  fun pauseAndResume_withinTheGrace_carriesOnWithTheLookup() =
    withKetch { ketch, root, trackers, engine ->
      val task = ketch.download(DownloadRequest(magnet(0),
        destination = Destination(root.toString())))
      eventually("the lookup to announce") { trackers.asked("tracker0.test") }
      task.pause()
      task.resume()
      // Past the grace the lookup had when it was left: it still asks its tracker, with the
      // announce it began before the pause.
      delay(GRACE + 500.milliseconds)
      assertTrue(trackers.asked("tracker0.test"))
      assertEquals(1, trackers.announces.value["tracker0.test"])
      assertEquals(1, engine.load()?.pendingMetadataLookups)
      // Paused for longer, it stops once its grace ends.
      task.pause()
      eventually("the lookup to stop") {
        !trackers.asked("tracker0.test") && engine.load()?.pendingMetadataLookups == 0
      }
    }

  @Test
  fun linkNamingAnotherTracker_withinTheGrace_isLookedUpThere() = runTest {
    withContext(Dispatchers.Default) {
      withTimeout(30_000) {
        val trackers = HangingTrackers()
        val engine = AtomicReference<KotlinTorrentEngine?>(null)
        val source = source(trackers, engine)
        val hash = InfoHash.fromBytes(torrentRandomBytes(20)).hex
        fun link(tracker: Int) =
          "magnet:?xt=urn:btih:$hash&tr=http%3A%2F%2Ftracker$tracker.test%2Fannounce"
        try {
          coroutineScope {
            val first = async { source.resolve(link(0)) }
            eventually("the lookup to announce") { trackers.asked("tracker0.test") }
            first.cancelAndJoin()
            // Within the grace of the lookup it left, the same torrent's link with another
            // tracker is looked up there, by a lookup that takes the left one's place.
            val second = async { source.resolve(link(1)) }
            eventually("the new link's tracker to be asked") { trackers.asked("tracker1.test") }
            assertTrue(second.isActive)
            assertEquals(1, engine.load()?.pendingMetadataLookups)
            second.cancelAndJoin()
          }
        } finally {
          withContext(NonCancellable) {
            source.stop()
            source.close()
          }
        }
      }
    }
  }

  @Test
  fun previewsGivenUp_leaveNewMagnetsTheirLookups() = runTest {
    withContext(Dispatchers.Default) {
      withTimeout(30_000) {
        val trackers = HangingTrackers()
        val engine = AtomicReference<KotlinTorrentEngine?>(null)
        val source = source(trackers, engine)
        try {
          coroutineScope {
            // One more than run at once: the last waits for a lookup to end, its grace included.
            val previews = (0..MAX_PENDING_METADATA).map { index ->
              async { source.resolve(magnet(index)) }
            }
            eventually("every lookup that may run to announce") {
              trackers.waiting.value.count { it.value > 0 } == MAX_PENDING_METADATA
            }
            previews.forEach { it.cancelAndJoin() }
            eventually("the lookups to stop") {
              trackers.waiting.value.values.all { it == 0 } &&
                engine.load()?.pendingMetadataLookups == 0
            }
            // A new magnet is looked up rather than refused for too many lookups.
            val next = async { source.resolve(magnet(100)) }
            eventually("the new lookup to announce") { trackers.asked("tracker100.test") }
            assertTrue(next.isActive)
            assertEquals(1, engine.load()?.pendingMetadataLookups)
            next.cancelAndJoin()
          }
        } finally {
          withContext(NonCancellable) {
            source.stop()
            source.close()
          }
        }
      }
    }
  }

  private companion object {
    /** How long a lookup nothing waits for goes on, shorter than the engine's. */
    val GRACE = 1.seconds
  }
}
