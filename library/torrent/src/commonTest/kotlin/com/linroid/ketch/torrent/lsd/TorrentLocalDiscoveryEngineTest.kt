package com.linroid.ketch.torrent.lsd

import com.linroid.ketch.core.engine.HttpEngine
import com.linroid.ketch.core.engine.ServerInfo
import com.linroid.ketch.torrent.TorrentConfig
import com.linroid.ketch.torrent.TorrentDiscoveryPrivacy
import com.linroid.ketch.torrent.TorrentEncryption
import com.linroid.ketch.torrent.TorrentV2Fixture
import com.linroid.ketch.torrent.crypto.sha1Digest
import com.linroid.ketch.torrent.crypto.torrentRandomBytes
import com.linroid.ketch.torrent.engine.KotlinTorrentEngine
import com.linroid.ketch.torrent.engine.TorrentSessionState
import com.linroid.ketch.torrent.engine.TorrentTaskSpec
import com.linroid.ketch.torrent.engine.TorrentV2TaskSpec
import com.linroid.ketch.torrent.magnet.TorrentMetadataExchange
import com.linroid.ketch.torrent.metainfo.Bencode
import com.linroid.ketch.torrent.metainfo.InfoHash
import com.linroid.ketch.torrent.metainfo.TorrentMetadata
import com.linroid.ketch.torrent.net.PeerEndpoint
import com.linroid.ketch.torrent.net.TorrentConnection
import com.linroid.ketch.torrent.net.TorrentHttp
import com.linroid.ketch.torrent.net.TorrentNetwork
import com.linroid.ketch.torrent.net.createTorrentNetwork
import com.linroid.ketch.torrent.peer.PeerHandshake
import com.linroid.ketch.torrent.peer.PeerMessage
import com.linroid.ketch.torrent.peer.PeerWire
import com.linroid.ketch.torrent.storage.torrentFileSystem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import okio.ByteString
import okio.ByteString.Companion.toByteString
import okio.FileSystem
import okio.IOException
import okio.Path
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** How the engine announces its public torrents on the LAN and dials the peers found there. */
@OptIn(ExperimentalAtomicApi::class)
class TorrentLocalDiscoveryEngineTest {
  /** One dial: where to, and the info hash or v2 tag its handshake named. */
  private data class Dial(val remote: PeerEndpoint, val tag: ByteString)

  /** Records every dial and its handshake, then hangs up; listens like [inner]. */
  private class Recording(private val inner: TorrentNetwork) : TorrentNetwork by inner {
    val dials = MutableStateFlow<List<Dial>>(emptyList())

    override suspend fun connect(remote: PeerEndpoint): TorrentConnection =
      object : TorrentConnection {
        override val remote = remote
        override suspend fun write(bytes: ByteArray) {
          if (bytes.size == 68) dials.update {
            it + Dial(remote, bytes.copyOfRange(28, 48).toByteString())
          }
        }
        override suspend fun readExactly(size: Int): ByteArray = throw IOException("Hung up")
        override fun close() = Unit
      }
  }

  /** A tracker that never answers, for private torrents, which need one. */
  private object SilentTracker : HttpEngine {
    override suspend fun head(url: String, headers: Map<String, String>): ServerInfo =
      error("Unused")

    override suspend fun download(
      url: String,
      range: LongRange?,
      headers: Map<String, String>,
      onData: suspend (ByteArray) -> Unit,
    ) = awaitCancellation()

    override fun close() = Unit
  }

  private fun root(): Path = (FileSystem.SYSTEM_TEMPORARY_DIRECTORY /
    "ketch-lsd-${InfoHash.fromBytes(torrentRandomBytes(20)).hex}")
    .also { torrentFileSystem.createDirectories(it) }

  private fun v1Metadata(privateTorrent: Boolean): TorrentMetadata {
    val bytes = byteArrayOf(1, 2, 3, 4)
    val info = buildMap<String, Any> {
      put("name", "lan")
      put("length", 4L)
      put("piece length", 4L)
      put("pieces", sha1Digest(bytes))
      if (privateTorrent) put("private", 1L)
    }
    return TorrentMetadata.fromBencode(Bencode.encode(buildMap {
      if (privateTorrent) put("announce", "http://tracker/announce")
      put("info", info)
    }))
  }

  /** Runs [body] against a started engine that joins [lan] instead of a multicast group. */
  private suspend fun engine(
    lan: FakeLsdTransport,
    network: TorrentNetwork,
    config: TorrentConfig = TorrentConfig(dhtEnabled = false,
      encryption = TorrentEncryption.DISABLED),
    body: suspend (KotlinTorrentEngine, Path) -> Unit,
  ) = withContext(Dispatchers.Default) {
    withTimeout(30_000) {
      val root = root()
      val engine = KotlinTorrentEngine(config, network, TorrentHttp(SilentTracker),
        localDiscovery = lan::open)
      try {
        engine.start()
        body(engine, root)
      } finally {
        engine.stop()
        network.close()
        torrentFileSystem.deleteRecursively(root, mustExist = false)
      }
    }
  }

  @Test
  fun publicV1Torrent_isAnnouncedAndDialsLanPeers() = runTest {
    val lan = FakeLsdTransport()
    val network = Recording(createTorrentNetwork())
    val metadata = v1Metadata(privateTorrent = false)
    val hash = metadata.infoHash.toBytes().toByteString()
    engine(lan, network) { engine, root ->
      val session = engine.addTask(TorrentTaskSpec("lan", metadata, (root / "out").toString(),
        emptySet()))
      session.resume()
      val announce = lan.sent.first { it.isNotEmpty() }.single()
      assertEquals(listOf(hash), announce.hashes)
      assertEquals(engine.listenPort, announce.port)
      val peer = PeerEndpoint("192.168.1.20", 7000)
      lan.announceFrom(peer.host, peer.port, hash)
      assertEquals(Dial(peer, hash), network.dials.first { it.isNotEmpty() }.first())
      // From the IPv6 group: a unique local sender is dialed like an IPv4 one.
      val ipv6Peer = PeerEndpoint("fd12:3456:0:0:0:0:0:7", 7001)
      lan.announceFrom(ipv6Peer.host, ipv6Peer.port, hash)
      val dials = network.dials.first { dials -> dials.any { it.remote == ipv6Peer } }
      assertEquals(Dial(ipv6Peer, hash), dials.first { it.remote == ipv6Peer })
    }
  }

  @Test
  fun hybridTorrent_announcesBothHashesAndDialsEachSwarm() = runTest {
    val lan = FakeLsdTransport()
    val network = Recording(createTorrentNetwork())
    val fixture = TorrentV2Fixture.build(listOf("a" to 40_000, "b" to 5), hybrid = true)
    val v1 = fixture.document.identity.v1!!.toBytes().toByteString()
    val v2 = fixture.document.info.hash.wireBytes().toByteString()
    engine(lan, network) { engine, root ->
      val session = engine.addV2Task(TorrentV2TaskSpec("lan", fixture.document,
        (root / "payload").toString(), privacy = TorrentDiscoveryPrivacy.PUBLIC))
      session.resume()
      lan.sent.first { sent -> sent.flatMap { it.hashes }.toSet() == setOf(v1, v2) }
      val v2Peer = PeerEndpoint("fd12:3456:0:0:0:0:0:30", 7001)
      val v1Peer = PeerEndpoint("192.168.1.31", 7002)
      lan.announceFrom(v2Peer.host, v2Peer.port, v2)
      lan.announceFrom(v1Peer.host, v1Peer.port, v1)
      val dials = network.dials.first { dials ->
        dials.any { it.remote == v1Peer } && dials.any { it.remote == v2Peer }
      }
      assertEquals(Dial(v2Peer, v2), dials.first { it.remote == v2Peer })
      assertEquals(Dial(v1Peer, v1), dials.first { it.remote == v1Peer })
    }
  }

  @Test
  fun privateTorrent_neverJoinsTheLan() = runTest {
    val lan = FakeLsdTransport()
    val network = Recording(createTorrentNetwork())
    val metadata = v1Metadata(privateTorrent = true)
    engine(lan, network) { engine, root ->
      val session = engine.addTask(TorrentTaskSpec("lan", metadata, (root / "out").toString(),
        emptySet()))
      session.resume()
      session.state.first { it == TorrentSessionState.DOWNLOADING }
      lan.announceFrom("192.168.1.20", 7000, metadata.infoHash.toBytes().toByteString())
      delay(500)
      assertEquals(0, lan.opens.load())
      assertEquals(emptyList(), lan.sent.value)
      assertEquals(emptyList(), network.dials.value)
    }
  }

  @Test
  fun trackerOnlyTorrent_neverJoinsTheLan() = runTest {
    val lan = FakeLsdTransport()
    val network = Recording(createTorrentNetwork())
    val fixture = TorrentV2Fixture.build(listOf("a" to 40_000), hybrid = true)
    engine(lan, network) { engine, root ->
      val session = engine.addV2Task(TorrentV2TaskSpec("lan", fixture.document,
        (root / "payload").toString(), trackerTiers = listOf(listOf("http://tracker/announce")),
        privacy = TorrentDiscoveryPrivacy.TRACKER_ONLY))
      session.resume()
      session.state.first { it == TorrentSessionState.DOWNLOADING }
      delay(500)
      assertEquals(0, lan.opens.load())
      assertEquals(emptyList(), lan.sent.value)
    }
  }

  @Test
  fun setLocalDiscovery_offStopsAnnouncing() = runTest {
    val lan = FakeLsdTransport()
    val network = Recording(createTorrentNetwork())
    val metadata = v1Metadata(privateTorrent = false)
    engine(lan, network) { engine, root ->
      engine.setLocalDiscovery(false)
      val session = engine.addTask(TorrentTaskSpec("lan", metadata, (root / "out").toString(),
        emptySet()))
      session.resume()
      session.state.first { it == TorrentSessionState.DOWNLOADING }
      delay(500)
      assertEquals(0, lan.opens.load())
      engine.setLocalDiscovery(true)
      lan.sent.first { it.isNotEmpty() }
      engine.setLocalDiscovery(false)
      lan.announceFrom("192.168.1.20", 7000, metadata.infoHash.toBytes().toByteString())
      delay(500)
      assertTrue(lan.closes.load() >= 1)
      assertFalse(network.dials.value.any { it.remote.host == "192.168.1.20" })
    }
  }

  @Test
  fun magnet_fetchesMetadataFromALanPeer() = runTest {
    val lan = FakeLsdTransport()
    val loopback = createTorrentNetwork()
    val listener = loopback.listen(PeerEndpoint("127.0.0.1", 0))
    val lanPeer = PeerEndpoint("192.168.1.20", 7000)
    // The LAN address reaches the peer listening on loopback; nothing else is dialed.
    val network = object : TorrentNetwork by loopback {
      override suspend fun connect(remote: PeerEndpoint): TorrentConnection {
        if (remote != lanPeer) throw IOException("Unexpected peer")
        return loopback.connect(listener.local)
      }
    }
    val metadata = v1Metadata(privateTorrent = false)
    val hash = metadata.infoHash.toBytes().toByteString()
    val server = async(Dispatchers.Default) {
      val connection = listener.accept()
      try {
        val wire = PeerWire(connection)
        wire.handshake(PeerHandshake(metadata.infoHash, torrentRandomBytes(20), true, false))
        wire.read() as PeerMessage.Extended
        wire.send(PeerMessage.Extended(0, Bencode.encode(mapOf(
          "m" to mapOf("ut_metadata" to 7L), "metadata_size" to metadata.infoBytes.size.toLong()
        ))))
        assertEquals(7, (wire.read() as PeerMessage.Extended).id)
        wire.send(TorrentMetadataExchange.response(1, 0, metadata))
      } finally { connection.close() }
    }
    try {
      engine(lan, network) { engine, _ ->
        val resolved = async {
          engine.fetchMetadata("magnet:?xt=urn:btih:${metadata.infoHash.hex}")
        }
        assertEquals(listOf(hash), lan.sent.first { it.isNotEmpty() }.single().hashes)
        lan.announceFrom(lanPeer.host, lanPeer.port, hash)
        assertContentEquals(metadata.infoBytes, assertNotNull(resolved.await()).infoBytes)
        // Found: the resolve leaves the group.
        while (lan.closes.load() < 1) delay(10)
      }
    } finally {
      server.cancelAndJoin()
      listener.close()
    }
  }
}
