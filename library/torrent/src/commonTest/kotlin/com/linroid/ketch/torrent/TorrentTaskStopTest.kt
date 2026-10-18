package com.linroid.ketch.torrent

import com.linroid.ketch.api.Destination
import com.linroid.ketch.api.DownloadRequest
import com.linroid.ketch.api.DownloadState
import com.linroid.ketch.api.DownloadTask
import com.linroid.ketch.core.Ketch
import com.linroid.ketch.core.engine.HttpEngine
import com.linroid.ketch.core.engine.ServerInfo
import com.linroid.ketch.torrent.crypto.torrentRandomBytes
import com.linroid.ketch.torrent.engine.KotlinTorrentEngine
import com.linroid.ketch.torrent.metainfo.Bencode
import com.linroid.ketch.torrent.metainfo.InfoHash
import com.linroid.ketch.torrent.net.TorrentHttp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import okio.FileSystem
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

/** Stopping a downloading torrent never waits for its trackers to answer `stopped`. */
class TorrentTaskStopTest {
  /** A tracker that answers every announce but `stopped`, and serves [metainfo] elsewhere. */
  private class DeafTracker(private val metainfo: ByteArray) : HttpEngine {
    val announces = MutableStateFlow<List<String>>(emptyList())
    /** Whether a `stopped` announce gave up: the window the engine gives it ended. */
    val stoppedGaveUp = MutableStateFlow(false)

    override suspend fun head(url: String, headers: Map<String, String>): ServerInfo =
      error("Unexpected HEAD")

    override suspend fun download(
      url: String,
      range: LongRange?,
      headers: Map<String, String>,
      onData: suspend (ByteArray) -> Unit,
    ) {
      if ("/announce" !in url) return onData(metainfo)
      announces.update { it + url }
      if ("event=stopped" in url) {
        try {
          awaitCancellation()
        } finally {
          stoppedGaveUp.value = true
        }
      }
      onData(Bencode.encode(mapOf("interval" to 3600L, "peers" to ByteArray(0))))
    }

    override fun close() = Unit

    fun heard(event: String): Boolean = announces.value.any { "event=$event" in it }
  }

  /**
   * Starts a torrent no peer serves, stops it with [stop] once its tracker heard it start, and
   * checks that [stop] returned while the tracker's `stopped` announce was still waiting, with the
   * task in the state [stopped] expects, and that the tracker still hears it stopped. A stop that
   * waited for its trackers would return only once that announce's 2 s window ended.
   */
  private fun stoppingTheTask_returnsAtOnce(
    stop: suspend (Ketch, DownloadTask) -> Unit,
    stopped: suspend (Ketch, DownloadTask) -> Boolean,
  ) = runTest {
    withContext(Dispatchers.Default) {
      withTimeout(30_000) {
        val root = FileSystem.SYSTEM_TEMPORARY_DIRECTORY /
          "ketch-task-stop-${InfoHash.fromBytes(torrentRandomBytes(20)).hex}"
        val tracker = DeafTracker(MultiFileTorrent(announce = "http://tracker/announce").metainfo)
        val source = TorrentDownloadSource(TorrentConfig(dhtEnabled = false, portMapping = false,
          localDiscovery = false), tracker).also { source ->
          source.engineFactory = { config ->
            KotlinTorrentEngine(config, http = TorrentHttp(tracker), listenHost = "127.0.0.1")
          }
        }
        val ketch = Ketch(tracker, additionalSources = listOf(source))
        try {
          ketch.start()
          val task = ketch.download(DownloadRequest("http://fixture/pack.torrent",
            destination = Destination(root.toString())))
          eventually("the tracker to hear it start") { tracker.heard("started") }
          val stopping = TimeSource.Monotonic.markNow()
          stop(ketch, task)
          val took = stopping.elapsedNow()
          assertFalse(tracker.stoppedGaveUp.value, "Stopping waited for the tracker: $took")
          assertTrue(stopped(ketch, task), "Left ${task.state.value}")
          eventually("the tracker to hear it stop") { tracker.heard("stopped") }
        } finally {
          withContext(NonCancellable) { ketch.shutdown(10.seconds) }
          FileSystem.SYSTEM.deleteRecursively(root, mustExist = false)
        }
      }
    }
  }

  @Test
  fun cancel_returnsAtOnce() = stoppingTheTask_returnsAtOnce(
    stop = { _, task -> task.cancel() },
    stopped = { _, task -> task.state.value is DownloadState.Canceled },
  )

  @Test
  fun pause_returnsAtOnce() = stoppingTheTask_returnsAtOnce(
    stop = { _, task -> task.pause() },
    stopped = { _, task -> task.state.value is DownloadState.Paused },
  )

  @Test
  fun remove_returnsAtOnce() = stoppingTheTask_returnsAtOnce(
    stop = { _, task -> task.remove(deleteFiles = true) },
    stopped = { ketch, task -> ketch.tasks.value.none { it.taskId == task.taskId } },
  )
}
