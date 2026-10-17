package com.linroid.ketch.torrent.engine

import com.linroid.ketch.core.engine.HttpEngine
import com.linroid.ketch.core.engine.ServerInfo
import com.linroid.ketch.torrent.TorrentConfig
import com.linroid.ketch.torrent.crypto.sha1Digest
import com.linroid.ketch.torrent.crypto.torrentRandomBytes
import com.linroid.ketch.torrent.metainfo.Bencode
import com.linroid.ketch.torrent.metainfo.InfoHash
import com.linroid.ketch.torrent.metainfo.TorrentMetadata
import com.linroid.ketch.torrent.net.TorrentHttp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import okio.FileSystem
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

class TorrentEngineStopTest {
  @Test
  fun stop_givesEveryTorrentsStoppedAnnouncesOneWindow() = runTest {
    withContext(Dispatchers.Default) {
      withTimeout(20_000) {
        val requests = MutableStateFlow<List<String>>(emptyList())
        val http = TorrentHttp(object : HttpEngine {
          override suspend fun head(url: String, headers: Map<String, String>): ServerInfo =
            error("Unused")

          override suspend fun download(
            url: String,
            range: LongRange?,
            headers: Map<String, String>,
            onData: suspend (ByteArray) -> Unit,
          ) {
            requests.update { it + url }
            // Trackers that never answer a `stopped` announce hold its whole window.
            if ("event=stopped" in url) awaitCancellation()
            onData(Bencode.encode(mapOf("interval" to 3600L, "peers" to ByteArray(0))))
          }

          override fun close() = Unit
        })
        val root = FileSystem.SYSTEM_TEMPORARY_DIRECTORY /
          "ketch-engine-stop-${InfoHash.fromBytes(torrentRandomBytes(20)).hex}"
        val trackers = (1..3).map { "http://tracker$it/announce" }
        val engine = KotlinTorrentEngine(TorrentConfig(dhtEnabled = false), http = http)
        try {
          engine.start()
          for ((index, tracker) in trackers.withIndex()) {
            val bytes = byteArrayOf(index.toByte(), 2, 3, 4)
            val metadata = TorrentMetadata.fromBencode(Bencode.encode(mapOf(
              "announce" to tracker, "info" to mapOf("name" to "payload", "length" to 4L,
                "piece length" to 4L, "pieces" to sha1Digest(bytes))
            )))
            engine.addTask(TorrentTaskSpec("stop-$index", metadata,
              (root / "$index").toString(), emptySet())).resume()
          }
          requests.first { urls ->
            trackers.all { tracker -> urls.any { it.startsWith(tracker) } }
          }
          val stopping = TimeSource.Monotonic.markNow()
          engine.stop()
          val took = stopping.elapsedNow()
          for (tracker in trackers) {
            val heard = requests.value.filter { it.startsWith(tracker) }
            assertEquals(1, heard.count { "event=stopped" in it }, "$tracker heard $heard")
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
}
