package com.linroid.ketch.torrent

import com.linroid.ketch.core.engine.HttpEngine
import com.linroid.ketch.core.engine.ServerInfo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import okio.FileSystem
import okio.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AdditionalTrackersTest {
  private val bytes = byteArrayOf(1, 2, 3, 4)

  private fun metadata(private: Boolean) = TorrentMetadata.fromBencode(Bencode.encode(mapOf(
    "announce" to OWN,
    "info" to mapOf("name" to "payload", "private" to if (private) 1L else 0L, "length" to 4L,
      "piece length" to 4L, "pieces" to sha1Digest(bytes))
  )))

  @Test
  fun publicTorrent_findsPeersThroughExtraTrackerWhenItsOwnTrackerFails() = runTest {
    val requests = download(metadata(private = false), seederTracker = EXTRA) { engine ->
      engine.setAdditionalTrackers(listOf("not a tracker", EXTRA))
    }
    assertTrue(requests.any { it.startsWith(OWN) }, "own tracker was not contacted")
    assertTrue(requests.any { it.startsWith(EXTRA) }, "extra tracker was not contacted")
  }

  @Test
  fun privateTorrent_neverContactsExtraTrackers() = runTest {
    val requests = download(metadata(private = true), seederTracker = OWN)
    assertTrue(requests.none { it.startsWith(EXTRA) }, "private torrent announced to $requests")
  }

  @Test
  fun trackerOnlyDiscovery_neverContactsExtraTrackers() = runTest {
    val requests = download(metadata(private = false), seederTracker = OWN,
      privacy = TorrentDiscoveryPrivacy.TRACKER_ONLY)
    assertTrue(requests.none { it.startsWith(EXTRA) }, "tracker-only task announced to $requests")
  }

  /**
   * Downloads [metadata] from a local seeder that only [seederTracker] returns; the other tracker
   * fails. Returns every tracker URL requested.
   */
  private suspend fun download(
    metadata: TorrentMetadata,
    seederTracker: String,
    privacy: TorrentDiscoveryPrivacy = TorrentDiscoveryPrivacy.PUBLIC,
    configure: (KotlinTorrentEngine) -> Unit = {},
  ): List<String> = withContext(Dispatchers.Default) {
    withTimeout(15_000) {
      coroutineScope {
        val root = FileSystem.SYSTEM_TEMPORARY_DIRECTORY /
          "ketch-extra-trackers-${InfoHash.fromBytes(torrentRandomBytes(20)).hex}"
        // The engine closes its own network on stop, so the seeder listens on a separate one.
        val seederNetwork = createTorrentNetwork()
        val listener = seederNetwork.listen(PeerEndpoint("127.0.0.1", 0))
        val requests = MutableStateFlow<List<String>>(emptyList())
        val http = object : HttpEngine {
          override suspend fun head(url: String, headers: Map<String, String>): ServerInfo =
            error("unused")
          override suspend fun download(url: String, range: LongRange?,
            headers: Map<String, String>, onData: suspend (ByteArray) -> Unit) {
            requests.update { it + url }
            if (!url.startsWith(seederTracker)) throw IOException("Tracker unavailable")
            onData(Bencode.encode(mapOf("interval" to 1800L, "peers" to listOf(
              mapOf("ip" to listener.local.host, "port" to listener.local.port.toLong())
            ))))
          }
          override fun close() = Unit
        }
        val seeder = seed(listener, metadata)
        val engine = KotlinTorrentEngine(
          TorrentConfig(dhtEnabled = false, additionalTrackers = listOf(EXTRA)),
          createTorrentNetwork(), TorrentHttp(http),
        )
        // Private and tracker-only runs also need the extra configured to prove it is skipped.
        configure(engine)
        try {
          engine.start()
          val session = engine.addTask(TorrentTaskSpec("task", metadata,
            (root / "output").toString(), emptySet(), privacy = privacy))
          session.resume()
          assertEquals(TorrentSessionState.FINISHED, session.state.first {
            it == TorrentSessionState.FINISHED || it == TorrentSessionState.STOPPED
          }, session.failure.value?.stackTraceToString())
          requests.value
        } finally {
          seeder.cancelAndJoin()
          engine.stop()
          seederNetwork.close()
          torrentFileSystem.deleteRecursively(root, mustExist = false)
        }
      }
    }
  }

  private fun CoroutineScope.seed(listener: TorrentListener, metadata: TorrentMetadata) = launch {
    while (true) {
      val connection = listener.accept()
      launch {
        try {
          val wire = PeerWire(connection, metadata)
          wire.handshake(PeerHandshake(metadata.infoHash, torrentRandomBytes(20), true, false))
          wire.send(PeerMessage.Bitfield(byteArrayOf(128.toByte())))
          wire.send(PeerMessage.Control(PeerMessage.Signal.UNCHOKE))
          while (true) {
            if (wire.read() is PeerMessage.Request) wire.send(PeerMessage.Piece(0, 0, bytes))
          }
        } catch (_: IOException) {
          // The downloader closes the connection once the payload is verified.
        } finally { connection.close() }
      }
    }
  }

  private companion object {
    const val OWN = "http://own/announce"
    const val EXTRA = "http://extra/announce"
  }
}
