package com.linroid.ketch.torrent

import com.linroid.ketch.core.engine.HttpEngine
import com.linroid.ketch.core.engine.ServerInfo
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
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
    override suspend fun bindUdp(local: PeerEndpoint): TorrentDatagramSocket {
      udp.fetchAndAdd(1)
      error("Unexpected public discovery")
    }
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
    withContext(Dispatchers.Default) {
      withTimeout(15_000) {
        val metadata = metadata(true)
        val network = Network()
        val listener = network.listen(PeerEndpoint("127.0.0.1", 0))
        val requests = MutableStateFlow<List<String>>(emptyList())
        val engine = KotlinTorrentEngine(TorrentConfig(), rawNetwork = network,
          http = http { url ->
            requests.value += url
            if (url.startsWith("https://unavailable/")) throw IOException("Tracker unavailable")
            assertTrue(url.startsWith("https://trusted/"))
            Bencode.encode(mapOf("interval" to 3600L, "peers" to byteArrayOf(127, 0, 0, 1,
              (listener.local.port ushr 8).toByte(), listener.local.port.toByte())))
          })
        val server = async {
          val connection = listener.accept()
          try {
            val wire = PeerWire(connection)
            wire.handshake(PeerHandshake(metadata.infoHash, torrentRandomBytes(20), true, false))
            val hello = wire.read() as PeerMessage.Extended
            assertEquals(null, Bencode.parse(hello.payload)["m"]?.get("ut_pex"))
            wire.send(PeerMessage.Extended(0, Bencode.encode(mapOf(
              "m" to mapOf("ut_metadata" to 7L), "metadata_size" to metadata.infoBytes.size.toLong()
            ))))
            val request = wire.read() as PeerMessage.Extended
            assertEquals(7, request.id)
            wire.send(TorrentMetadataExchange.response(1, 0, metadata))
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
          server.await()
          assertEquals(listOf(listener.local), network.connections.value)
          assertEquals(0, network.udp.load())
          assertTrue(requests.value.any { "event=stopped" in it })
          val previousRequests = requests.value
          assertFailsWith<PrivateTorrentMagnetException> { engine.fetchMetadata(magnet) }
          assertEquals(previousRequests, requests.value)
          assertEquals(listOf(listener.local), network.connections.value)
          assertEquals(0, network.udp.load())
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
    val engine = KotlinTorrentEngine(TorrentConfig(), rawNetwork = network,
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
        val engine = KotlinTorrentEngine(TorrentConfig(), rawNetwork = network,
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
