package com.linroid.ketch.torrent.engine

import com.linroid.ketch.core.engine.HttpEngine
import com.linroid.ketch.core.engine.ServerInfo
import com.linroid.ketch.torrent.TorrentClient
import com.linroid.ketch.torrent.TorrentConfig
import com.linroid.ketch.torrent.TorrentDiscoveryPrivacy
import com.linroid.ketch.torrent.TorrentEncryption
import com.linroid.ketch.torrent.TorrentUploadPolicy
import com.linroid.ketch.torrent.crypto.torrentRandomBytes
import com.linroid.ketch.torrent.magnet.PrivateTorrentMagnetException
import com.linroid.ketch.torrent.magnet.TorrentMetadataExchange
import com.linroid.ketch.torrent.metainfo.Bencode
import com.linroid.ketch.torrent.metainfo.InfoHash
import com.linroid.ketch.torrent.metainfo.TorrentMetadata
import com.linroid.ketch.torrent.net.PeerEndpoint
import com.linroid.ketch.torrent.net.TorrentConnection
import com.linroid.ketch.torrent.net.TorrentDatagramSocket
import com.linroid.ketch.torrent.net.TorrentHttp
import com.linroid.ketch.torrent.net.TorrentListener
import com.linroid.ketch.torrent.net.TorrentNetwork
import com.linroid.ketch.torrent.net.createTorrentNetwork
import com.linroid.ketch.torrent.peer.PeerExtensions
import com.linroid.ketch.torrent.peer.PeerHandshake
import com.linroid.ketch.torrent.peer.PeerMessage
import com.linroid.ketch.torrent.peer.PeerWire
import com.linroid.ketch.torrent.storage.torrentFileSystem
import com.linroid.ketch.torrent.tracker.TrackerStatus
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import okio.FileSystem
import okio.IOException
import kotlin.concurrent.atomics.AtomicInt
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

@OptIn(ExperimentalAtomicApi::class)
class TrackerOnlyMagnetTest {
  private fun metadata(private: Boolean) = TorrentMetadata.fromBencode(Bencode.encode(mapOf(
    "announce" to "https://trusted/announce", "info" to mapOf("name" to "empty", "length" to 0L,
      "piece length" to 1L, "pieces" to ByteArray(0), "private" to if (private) 1L else 0L)
  )))

  private class Network : TorrentNetwork {
    private val delegate = createTorrentNetwork()
    val connections = MutableStateFlow<List<PeerEndpoint>>(emptyList())
    val udp = AtomicInt(0)
    override suspend fun connect(remote: PeerEndpoint): TorrentConnection {
      connections.value += remote
      require(remote.host == "127.0.0.1") { "Unexpected peer destination" }
      return delegate.connect(remote)
    }
    override suspend fun listen(local: PeerEndpoint): TorrentListener = delegate.listen(local)
    /** uTP may bind the port; [udp] counts DHT or UDP tracker datagrams sent. */
    override suspend fun bindUdp(local: PeerEndpoint): TorrentDatagramSocket =
      delegate.bindUdp(local).countingPublicDiscovery(udp)
    override fun close() = delegate.close()
  }

  private fun http(reply: suspend (String) -> ByteArray) = TorrentHttp(object : HttpEngine {
    override suspend fun head(url: String, headers: Map<String, String>): ServerInfo = error("Unused")
    override suspend fun download(
      url: String,
      range: LongRange?,
      headers: Map<String, String>,
      onData: suspend (ByteArray) -> Unit,
    ) = onData(reply(url))
    override fun close() = Unit
  })

  @Test
  fun trackerOnlyPrivateResolutionUsesTrackerPeersAndPublicCacheRetryStillRejectsIt() = runTest {
    resolution("failure")
  }

  @Test
  fun trackerOnlyFallsBackAfterAnEmptyPeerResponse() = runTest { resolution("empty") }

  @Test
  fun trackerOnlyFallsBackAfterMetadataPeersFail() = runTest { resolution("bad-peer") }

  private suspend fun resolution(firstResult: String) = withContext(Dispatchers.Default) {
    withTimeout(15_000) {
      val metadata = metadata(true)
      val network = Network()
      val listener = network.listen(PeerEndpoint("127.0.0.1", 0))
      val requests = MutableStateFlow<List<String>>(emptyList())
      val engine = KotlinTorrentEngine(TorrentConfig(encryption = TorrentEncryption.DISABLED),
        rawNetwork = network,
        http = http { url ->
          requests.value += url
          if (url.startsWith("https://unavailable/")) {
            if (firstResult == "failure") throw IOException("Tracker unavailable")
            val peers = if (firstResult == "empty") ByteArray(0)
              else byteArrayOf(127, 0, 0, 2, 0, 9)
            return@http Bencode.encode(mapOf("interval" to 3600L, "peers" to peers))
          }
          assertTrue(url.startsWith("https://trusted/"))
          Bencode.encode(mapOf("interval" to 3600L, "peers" to byteArrayOf(127, 0, 0, 1,
            (listener.local.port ushr 8).toByte(), listener.local.port.toByte())))
        })
      val server = async {
        val connection = listener.accept()
        try {
          val wire = PeerWire(connection)
          val ours = wire.handshake(PeerHandshake(metadata.infoHash, torrentRandomBytes(20), true,
            false))
          val hello = Bencode.parse((wire.read() as PeerMessage.Extended).payload)
          assertEquals(null, hello["m"]?.get("ut_pex"))
          // The lookup names our client and tells the peer where it is seen (BEP 10).
          assertEquals(TorrentClient.name, hello["v"]?.text())
          assertContentEquals(byteArrayOf(127, 0, 0, 1), hello["yourip"]?.bytes)
          wire.send(PeerMessage.Extended(0, Bencode.encode(mapOf(
            "m" to mapOf("ut_metadata" to 7L), "metadata_size" to metadata.infoBytes.size.toLong()
          ))))
          val request = wire.read() as PeerMessage.Extended
          assertEquals(7, request.id)
          wire.send(TorrentMetadataExchange.response(1, 0, metadata))
          ours.peerId
        } finally { connection.close() }
      }
      try {
        engine.start()
        val magnet = "magnet:?xt=urn:btih:${metadata.infoHash.hex}" +
          "&tr=https%3A%2F%2Funavailable%2Fannounce&tr=https%3A%2F%2Ftrusted%2Fannounce" +
          "&x.pe=127.0.0.2:9"
        val resolved = assertNotNull(engine.fetchMetadata(magnet,
          TorrentDiscoveryPrivacy.TRACKER_ONLY))
        assertTrue(resolved.isPrivate)
        assertContentEquals(metadata.infoBytes, resolved.infoBytes)
        // Trackers and metadata peers see the engine's one peer ID, which names Ketch (BEP 20).
        val peerId = server.await()
        assertTrue(peerId.decodeToString().startsWith(TorrentClient.peerIdPrefix))
        val query = "&peer_id=" + peerId.joinToString("") {
          "%" + (it.toInt() and 255).toString(16).padStart(2, '0')
        } + "&"
        val announces = requests.value.filter { "/announce?" in it }
        assertTrue(announces.isNotEmpty() && announces.all { query in it })
        val expected = if (firstResult == "bad-peer") {
          listOf(PeerEndpoint("127.0.0.2", 9), listener.local)
        } else listOf(listener.local)
        assertEquals(expected, network.connections.value)
        assertEquals(0, network.udp.load())
        // Said from the engine once the lookup returned.
        requests.first { sent ->
          sent.any { it.startsWith("https://trusted/") && "event=stopped" in it }
        }
        if (firstResult != "failure") {
          val stopped = requests.value.indexOfFirst {
            it.startsWith("https://unavailable/") && "event=stopped" in it
          }
          val fallback = requests.value.indexOfFirst { it.startsWith("https://trusted/") }
          assertTrue(stopped >= 0 && stopped < fallback)
        }
        val previousRequests = requests.value
        assertFailsWith<PrivateTorrentMagnetException> { engine.fetchMetadata(magnet) }
        assertEquals(previousRequests, requests.value)
        assertEquals(expected, network.connections.value)
        assertEquals(0, network.udp.load())
      } finally {
        engine.stop()
        server.cancelAndJoin()
        listener.close()
        network.close()
      }
    }
  }

  @Test
  fun trackerOnlyLookup_found_returnsBeforeItsTrackerAnswersStopped() = runTest {
    withContext(Dispatchers.Default) {
      withTimeout(15_000) {
        val metadata = metadata(true)
        val network = Network()
        val listener = network.listen(PeerEndpoint("127.0.0.1", 0))
        val requests = MutableStateFlow<List<String>>(emptyList())
        val engine = KotlinTorrentEngine(TorrentConfig(encryption = TorrentEncryption.DISABLED),
          rawNetwork = network,
          http = http { url ->
            requests.value += url
            // A tracker that never answers `stopped`.
            if ("event=stopped" in url) awaitCancellation()
            Bencode.encode(mapOf("interval" to 3600L, "peers" to byteArrayOf(127, 0, 0, 1,
              (listener.local.port ushr 8).toByte(), listener.local.port.toByte())))
          })
        val server = async {
          val connection = listener.accept()
          try {
            val wire = PeerWire(connection)
            wire.handshake(PeerHandshake(metadata.infoHash, torrentRandomBytes(20), true, false))
            wire.read()
            wire.send(PeerMessage.Extended(0, Bencode.encode(mapOf(
              "m" to mapOf("ut_metadata" to 7L), "metadata_size" to metadata.infoBytes.size.toLong()
            ))))
            wire.read()
            wire.send(TorrentMetadataExchange.response(1, 0, metadata))
          } finally { connection.close() }
        }
        try {
          engine.start()
          val looking = TimeSource.Monotonic.markNow()
          val resolved = assertNotNull(engine.fetchMetadata("magnet:?xt=urn:btih:" +
            "${metadata.infoHash.hex}&tr=https%3A%2F%2Ftrusted%2Fannounce",
            TorrentDiscoveryPrivacy.TRACKER_ONLY))
          val took = looking.elapsedNow()
          assertContentEquals(metadata.infoBytes, resolved.infoBytes)
          // It did not wait for its tracker to answer `stopped`, which still heard it.
          assertTrue(took < 1.5.seconds, "Looking up took $took")
          requests.first { sent -> sent.any { "event=stopped" in it } }
        } finally {
          engine.stop()
          server.cancelAndJoin()
          listener.close()
          network.close()
        }
      }
    }
  }

  @Test
  fun missingTrackersRejectBeforeExplicitPeerOrDhtDiscovery() = runTest {
    val network = Network()
    val engine = KotlinTorrentEngine(TorrentConfig(encryption = TorrentEncryption.DISABLED),
      rawNetwork = network,
      http = http { error("Unexpected tracker") })
    try {
      engine.start()
      for (tracker in listOf("", "&tr=file%3A%2F%2F%2Fprivate")) {
        assertFailsWith<IllegalArgumentException> {
          engine.fetchMetadata("magnet:?xt=urn:btih:${metadata(false).infoHash.hex}" +
            "$tracker&x.pe=should-not-resolve.invalid:9", TorrentDiscoveryPrivacy.TRACKER_ONLY)
        }
      }
      assertTrue(network.connections.value.isEmpty())
      assertEquals(0, network.udp.load())
    } finally { engine.stop() }
  }

  @Test
  fun waitingForTrackerPeersDoesNotFallBackToPublicDiscovery() = runTest {
    withContext(Dispatchers.Default) {
      withTimeout(10_000) {
        val network = Network()
        val announced = CompletableDeferred<Unit>()
        val engine = KotlinTorrentEngine(TorrentConfig(encryption = TorrentEncryption.DISABLED),
          rawNetwork = network,
          http = http {
            announced.complete(Unit)
            Bencode.encode(mapOf("interval" to 3600L, "peers" to ByteArray(0)))
          })
        engine.start()
        val pending = async {
          engine.fetchMetadata("magnet:?xt=urn:btih:${metadata(false).infoHash.hex}" +
            "&tr=https%3A%2F%2Ftrusted%2Fannounce&x.pe=127.0.0.2:9",
            TorrentDiscoveryPrivacy.TRACKER_ONLY)
        }
        try {
          announced.await()
          pending.cancelAndJoin()
          assertTrue(network.connections.value.isEmpty())
          assertEquals(0, network.udp.load())
        } finally {
          pending.cancelAndJoin()
          engine.stop()
        }
      }
    }
  }

  @Test
  fun publicTrackerOnlyTaskRejectsUnsolicitedPeersAndPeerExchange() = runTest {
    withContext(Dispatchers.Default) {
      withTimeout(15_000) {
        val network = Network()
        val listener = network.listen(PeerEndpoint("127.0.0.1", 0))
        val metadata = metadata(false)
        val engine = KotlinTorrentEngine(TorrentConfig(
          encryption = TorrentEncryption.DISABLED,
          uploadPolicy = TorrentUploadPolicy.SEED_AFTER_COMPLETION), rawNetwork = network,
          http = http {
            Bencode.encode(mapOf("interval" to 3600L, "peers" to byteArrayOf(127, 0, 0, 1,
              (listener.local.port ushr 8).toByte(), listener.local.port.toByte())))
          })
        val root = FileSystem.SYSTEM_TEMPORARY_DIRECTORY /
          "ketch-tracker-only-pex-${InfoHash.fromBytes(torrentRandomBytes(20)).hex}"
        val server = async {
          val connection = listener.accept()
          try {
            val wire = PeerWire(connection)
            wire.handshake(PeerHandshake(metadata.infoHash, torrentRandomBytes(20), true, false))
            val hello = wire.read() as PeerMessage.Extended
            assertEquals(null, Bencode.parse(hello.payload)["m"]?.get("ut_pex"))
            wire.send(PeerExtensions.handshake(metadata, pex = true))
            wire.send(PeerMessage.Extended(PeerExtensions.PEX,
              Bencode.encode(mapOf("added" to byteArrayOf(8, 8, 8, 8, 0, 9)))))
            assertFailsWith<IOException> {
              while (true) wire.read()
            }
          } finally { connection.close() }
        }
        try {
          engine.start()
          val session = engine.addTask(TorrentTaskSpec("restricted", metadata,
            (root / "empty").toString(), emptySet(),
            privacy = TorrentDiscoveryPrivacy.TRACKER_ONLY,
          ))
          session.resume()
          server.await()
          session.state.first { it == TorrentSessionState.SEEDING }
          val unsolicited = object : TorrentConnection {
            override val remote = PeerEndpoint("127.0.0.2", 9)
            override suspend fun readExactly(size: Int): ByteArray = error("Unused")
            override suspend fun write(bytes: ByteArray) = error("Unused")
            override fun close() = Unit
          }
          assertEquals(false, session.accept(unsolicited))
          session.pause()
          assertTrue(network.connections.value.all { it == listener.local })
          assertEquals(0, network.udp.load())
        } finally {
          engine.stop()
          server.cancelAndJoin()
          listener.close()
          torrentFileSystem.deleteRecursively(root, mustExist = false)
        }
      }
    }
  }

  @Test
  fun trackerOnlyPolicyAlsoSuppressesPublicDiscoveryAfterPublicMetadataIsKnown() = runTest {
    withContext(Dispatchers.Default) {
      withTimeout(10_000) {
        val network = Network()
        val engine = KotlinTorrentEngine(TorrentConfig(
          encryption = TorrentEncryption.DISABLED,
          uploadPolicy = TorrentUploadPolicy.SEED_AFTER_COMPLETION), rawNetwork = network,
          http = http { Bencode.encode(mapOf("interval" to 3600L, "peers" to ByteArray(0))) })
        val root = FileSystem.SYSTEM_TEMPORARY_DIRECTORY /
          "ketch-tracker-only-${InfoHash.fromBytes(torrentRandomBytes(20)).hex}"
        try {
          engine.start()
          val metadata = metadata(false)
          val session = engine.addTask(TorrentTaskSpec("restricted", metadata,
            (root / "empty").toString(), emptySet(),
            magnetUri = "magnet:?xt=urn:btih:${metadata.infoHash.hex}&x.pe=127.0.0.2:9",
            privacy = TorrentDiscoveryPrivacy.TRACKER_ONLY,
          ))
          session.resume()
          session.trackerStatus.first {
            it.singleOrNull()?.outcome == TrackerStatus.Outcome.SUCCEEDED
          }
          session.pause()
          assertTrue(network.connections.value.isEmpty())
          assertEquals(0, network.udp.load())
        } finally {
          engine.stop()
          torrentFileSystem.deleteRecursively(root, mustExist = false)
        }
      }
    }
  }
}
