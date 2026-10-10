package com.linroid.ketch.torrent

import com.linroid.ketch.core.engine.HttpEngine
import com.linroid.ketch.core.engine.ServerInfo
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
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
import kotlin.concurrent.atomics.AtomicInt
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** How a hybrid owner finds peers in both of its swarms, the v1 hash's and the v2 hash's. */
@OptIn(ExperimentalAtomicApi::class)
class KotlinTorrentV2DiscoveryTest {
  private val v1Peer = PeerEndpoint("10.1.0.1", 6881)
  private val v2Peer = PeerEndpoint("10.2.0.2", 6882)

  private data class Announce(val tracker: String, val topic: String, val event: String?)

  /** One recorded dial: where to, and the handshake we sent. */
  private data class Dial(val remote: PeerEndpoint, val tag: ByteString, val upgrade: Boolean)

  /** Tracker [answers] per topic ("v1" or "v2"), recorded; throws where [fails] says so. */
  private inner class Trackers(
    private val fixture: TorrentV2Fixture,
    private val fails: (tracker: String, topic: String) -> Boolean = { _, _ -> false },
  ) : HttpEngine {
    val announces = MutableStateFlow<List<Announce>>(emptyList())
    val release = CompletableDeferred<Unit>()
    private val v1 = fixture.document.identity.v1!!.toBytes().toByteString()

    override suspend fun head(url: String, headers: Map<String, String>): ServerInfo =
      error("Unused")

    override suspend fun download(
      url: String,
      range: LongRange?,
      headers: Map<String, String>,
      onData: suspend (ByteArray) -> Unit,
    ) {
      val tracker = url.substringBefore('?')
      val hash = url.substringAfter("info_hash=").substringBefore('&').split('%').drop(1)
        .map { it.toInt(16).toByte() }.toByteArray().toByteString()
      val topic = if (hash == v1) "v1" else "v2"
      val event = url.substringAfter("&event=", "").substringBefore('&').ifEmpty { null }
      announces.update { it + Announce(tracker, topic, event) }
      release.await()
      if (fails(tracker, topic)) throw IOException("Tracker unavailable")
      val peer = if (topic == "v1") v1Peer else v2Peer
      onData(Bencode.encode(mapOf("interval" to 3600L, "peers" to listOf(
        mapOf("ip" to peer.host, "port" to peer.port.toLong())))))
    }

    override fun close() = Unit
  }

  /** Records every dial and its handshake, then hangs up; listens and binds like [inner]. */
  private class Recording(private val inner: TorrentNetwork) : TorrentNetwork by inner {
    val dials = MutableStateFlow<List<Dial>>(emptyList())
    val udp = AtomicInt(0)

    override suspend fun connect(remote: PeerEndpoint): TorrentConnection =
      object : TorrentConnection {
        override val remote = remote
        override suspend fun write(bytes: ByteArray) {
          if (bytes.size == 68) dials.update {
            it + Dial(remote, bytes.copyOfRange(28, 48).toByteString(),
              bytes[27].toInt() and 0x10 != 0)
          }
        }
        override suspend fun readExactly(size: Int): ByteArray = throw IOException("Hung up")
        override fun close() = Unit
      }

    override suspend fun bindUdp(local: PeerEndpoint): TorrentDatagramSocket {
      udp.fetchAndAdd(1)
      return inner.bindUdp(local)
    }
  }

  /** A peer arriving at the owner from [host]; it never completes a handshake. */
  private class Arrival(host: String) : TorrentConnection {
    override val remote = PeerEndpoint(host, 51_000)
    override suspend fun write(bytes: ByteArray) = Unit
    override suspend fun readExactly(size: Int): ByteArray = throw IOException("Hung up")
    override fun close() = Unit
  }

  private fun root(): Path = (FileSystem.SYSTEM_TEMPORARY_DIRECTORY /
    "ketch-v2-discovery-${InfoHash.fromBytes(torrentRandomBytes(20)).hex}")
    .also { torrentFileSystem.createDirectories(it) }

  /** Runs [body] against a downloading hybrid owner of [fixture], then checks for leaks. */
  private suspend fun owner(
    fixture: TorrentV2Fixture,
    http: Trackers,
    network: Recording,
    config: TorrentConfig = TorrentConfig(dhtEnabled = false),
    tiers: List<List<String>>,
    magnetUri: String? = null,
    body: suspend (TorrentV2DownloadSession) -> Unit,
  ) = withContext(Dispatchers.Default) {
    withTimeout(30_000) {
      val root = root()
      val engine = KotlinTorrentEngine(config, network, TorrentHttp(http))
      try {
        engine.start()
        val session = engine.addV2Task(TorrentV2TaskSpec("discovery", fixture.document,
          (root / "payload").toString(), trackerTiers = tiers, magnetUri = magnetUri,
          privacy = TorrentDiscoveryPrivacy.PUBLIC))
        session.resume()
        session.state.first { it == TorrentSessionState.DOWNLOADING }
        body(session)
        engine.removeTorrent(fixture.document.info.hash.hex, deleteFiles = false)
        assertEquals(0, engine.admittedSessionBytes)
      } finally {
        http.release.complete(Unit)
        engine.stop()
        network.close()
        torrentFileSystem.deleteRecursively(root, mustExist = false)
      }
      assertEquals(0, engine.admittedSessionBytes)
      assertEquals(0, engine.allocatedExchangeBytes)
    }
  }

  @Test
  fun hybridAnnouncesBothTopicsWithIndependentTiers() = runTest {
    val fixture = TorrentV2Fixture.build(listOf("a" to 40_000, "b" to 5), hybrid = true)
    // The first tier's tracker knows only the v1 swarm; the v2 topic falls back to the second.
    val http = Trackers(fixture) { tracker, topic ->
      tracker == "http://a/announce" && topic == "v2"
    }
    http.release.complete(Unit)
    val network = Recording(createTorrentNetwork())
    val v1 = fixture.document.identity.v1!!.toBytes().toByteString()
    val v2 = fixture.document.info.hash.wireBytes().toByteString()
    owner(fixture, http, network,
      tiers = listOf(listOf("http://a/announce"), listOf("http://b/announce"))) {
      // The v2 peer hangs up on the v2 tag, so it is tried again in v1 mode.
      val dials = network.dials.first { dials ->
        dials.any { it.remote == v1Peer } && dials.count { it.remote == v2Peer } >= 2
      }
      assertEquals(Dial(v1Peer, v1, upgrade = true), dials.first { it.remote == v1Peer })
      val v2Dials = dials.filter { it.remote == v2Peer }
      assertEquals(Dial(v2Peer, v2, upgrade = false), v2Dials[0])
      assertEquals(Dial(v2Peer, v1, upgrade = true), v2Dials[1])
      val announces = http.announces.value
      assertTrue(Announce("http://a/announce", "v1", "started") in announces)
      assertTrue(Announce("http://a/announce", "v2", "started") in announces)
      assertTrue(Announce("http://b/announce", "v2", "started") in announces)
      // The v1 topic's tiers never fell back: its first tracker answered it.
      assertFalse(announces.any { it.tracker == "http://b/announce" && it.topic == "v1" })
    }
  }

  @Test
  fun privateHybridUsesNoDhtOrExtraTrackers() = runTest {
    val fixture = TorrentV2Fixture.build(listOf("a" to 40_000, "b" to 5), hybrid = true,
      privateTorrent = true)
    val http = Trackers(fixture)
    http.release.complete(Unit)
    val network = Recording(createTorrentNetwork())
    val explicit = PeerEndpoint("10.9.9.9", 6889)
    val magnet = MagnetUri(fixture.document.identity, trackers = listOf("http://a/announce"),
      explicitPeers = listOf("${explicit.host}:${explicit.port}")).toUri()
    owner(fixture, http, network, TorrentConfig(dhtEnabled = true,
      additionalTrackers = listOf("http://extra/announce")),
      tiers = listOf(listOf("http://a/announce")), magnetUri = magnet) {
      network.dials.first { dials ->
        dials.any { it.remote == v1Peer } && dials.any { it.remote == v2Peer }
      }
      // Give public discovery, were it running, time to show itself.
      delay(500)
      assertEquals(setOf("http://a/announce"), http.announces.value.map { it.tracker }.toSet())
      assertEquals(setOf("v1", "v2"), http.announces.value.map { it.topic }.toSet())
      assertEquals(0, network.udp.load())
      assertEquals(setOf(v1Peer, v2Peer), network.dials.value.map { it.remote }.toSet())
    }
  }

  @Test
  fun privateHybridTopicsAnnounceToOneTracker() = runTest {
    val fixture = TorrentV2Fixture.build(listOf("a" to 40_000, "b" to 5), hybrid = true,
      privateTorrent = true)
    // Each tracker knows one of the hashes: topics that failed over on their own would end up
    // on different private trackers, whichever order the tiers were shuffled into.
    val http = Trackers(fixture) { tracker, topic ->
      tracker == "http://a/announce" && topic == "v1" ||
        tracker == "http://b/announce" && topic == "v2"
    }
    http.release.complete(Unit)
    val network = Recording(createTorrentNetwork())
    owner(fixture, http, network,
      tiers = listOf(listOf("http://a/announce", "http://b/announce"))) {
      network.dials.first { it.isNotEmpty() }
      // Give a topic that would fail over time to do so.
      delay(500)
      val announces = http.announces.value
      assertEquals(1, announces.map { it.tracker }.toSet().size, "$announces")
      assertEquals(setOf("v1", "v2"), announces.map { it.topic }.toSet())
      // Only the peers of the tracker in use are dialed.
      assertEquals(1, network.dials.value.map { it.remote }.toSet().size)
    }
  }

  @Test
  fun trackerHostsOfBothTopicsFeedThePrivateAllowlist() = runTest {
    val fixture = TorrentV2Fixture.build(listOf("a" to 40_000, "b" to 5), hybrid = true,
      privateTorrent = true)
    val http = Trackers(fixture)
    val network = Recording(createTorrentNetwork())
    owner(fixture, http, network, tiers = listOf(listOf("http://a/announce"))) { session ->
      http.announces.first { announces -> announces.map { it.topic }.toSet().size == 2 }
      // No tracker has answered yet, so no host may connect.
      assertFalse(session.accept(Arrival(v1Peer.host)))
      assertFalse(session.accept(Arrival(v2Peer.host)))
      http.release.complete(Unit)
      // Each topic's tracker admits the hosts it returned; neither replaces the other's.
      while (!session.accept(Arrival(v1Peer.host))) delay(10)
      while (!session.accept(Arrival(v2Peer.host))) delay(10)
      assertTrue(session.accept(Arrival(v1Peer.host)))
      assertFalse(session.accept(Arrival("10.3.0.3")))
    }
  }
}
