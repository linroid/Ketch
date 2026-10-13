package com.linroid.ketch.torrent.v1

import com.linroid.ketch.torrent.TorrentUploadPolicy
import com.linroid.ketch.torrent.crypto.sha1Digest
import com.linroid.ketch.torrent.crypto.torrentRandomBytes
import com.linroid.ketch.torrent.dht.DhtCodec
import com.linroid.ketch.torrent.engine.TorrentBufferBudget
import com.linroid.ketch.torrent.metainfo.Bencode
import com.linroid.ketch.torrent.metainfo.InfoHash
import com.linroid.ketch.torrent.metainfo.TorrentMetadata
import com.linroid.ketch.torrent.net.PeerEndpoint
import com.linroid.ketch.torrent.net.TorrentConnection
import com.linroid.ketch.torrent.net.createTorrentNetwork
import com.linroid.ketch.torrent.peer.HOLEPUNCH_EXTENSION
import com.linroid.ketch.torrent.peer.HolepunchMessage
import com.linroid.ketch.torrent.peer.HolepunchType
import com.linroid.ketch.torrent.peer.PEX_FLAG_HOLEPUNCH
import com.linroid.ketch.torrent.peer.PeerExtensions
import com.linroid.ketch.torrent.peer.PeerHandshake
import com.linroid.ketch.torrent.peer.PeerMessage
import com.linroid.ketch.torrent.peer.PeerWire
import com.linroid.ketch.torrent.storage.TorrentPieceStore
import com.linroid.ketch.torrent.storage.torrentFileSystem
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
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
import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.concurrent.atomics.update

/**
 * Hole punching (BEP 55) in a v1 swarm, with scripted peers on loopback: it relays a rendezvous
 * between two peers, dials a peer a relay names over uTP first, and asks the peer that
 * introduced an endpoint it cannot reach to punch through.
 */
@OptIn(ExperimentalAtomicApi::class)
class TorrentSwarmHolepunchTest {
  private val metadata = TorrentMetadata.fromBencode(Bencode.encode(mapOf("info" to mapOf(
    "name" to "file", "length" to 4L, "piece length" to 4L,
    "pieces" to sha1Digest(byteArrayOf(1, 2, 3, 4))))))

  private fun swarmTest(
    complete: Boolean,
    body: suspend CoroutineScope.(TorrentPieceStore, TorrentSwarmPexEndpointTest.Network) -> Unit,
  ) = runTest {
    withContext(Dispatchers.Default) {
      withTimeout(20_000) {
        coroutineScope {
          val root: Path = FileSystem.SYSTEM_TEMPORARY_DIRECTORY /
            "ketch-holepunch-${InfoHash.fromBytes(torrentRandomBytes(20)).hex}"
          val store = TorrentPieceStore(metadata, root / "file", emptySet(), "test")
          store.initialize()
          if (complete) store.commit(0, byteArrayOf(1, 2, 3, 4))
          // Port 9 on loopback stands for a peer behind a NAT: dialing it fails at once.
          val network = TorrentSwarmPexEndpointTest.Network(createTorrentNetwork()) {
            it.host == "127.0.0.1" && it.port != UNREACHABLE.port
          }
          try {
            body(store, network)
          } finally {
            network.close()
            torrentFileSystem.deleteRecursively(root, mustExist = false)
          }
        }
      }
    }
  }

  /**
   * A peer that dials the swarm and says it takes part in hole punching (as ID 9) and listens
   * on [listenPort]; returns its wire once the swarm unchoked it, so its handshake was read.
   */
  private suspend fun joinedPeer(
    network: TorrentSwarmPexEndpointTest.Network,
    incoming: Channel<TorrentConnection>,
    listenPort: Int,
  ): PeerWire {
    val listener = network.transport.listen(PeerEndpoint("127.0.0.1", 0))
    val theirs = try {
      val client = network.transport.connect(listener.local)
      incoming.send(listener.accept())
      client
    } finally {
      listener.close()
    }
    val wire = PeerWire(theirs, metadata)
    wire.handshake(PeerHandshake(metadata.infoHash, torrentRandomBytes(20), true, false))
    wire.send(PeerMessage.Extended(0, Bencode.encode(mapOf(
      "m" to mapOf(HOLEPUNCH_EXTENSION to PEER_HOLEPUNCH_ID.toLong(), "ut_pex" to 7L),
      "p" to listenPort.toLong(),
    ))))
    wire.send(PeerMessage.Control(PeerMessage.Signal.INTERESTED))
    return wire
  }

  private suspend fun PeerWire.awaitUnchoke() {
    while (read() != PeerMessage.Control(PeerMessage.Signal.UNCHOKE)) { }
  }

  private suspend fun PeerWire.readHolepunch(): HolepunchMessage {
    while (true) {
      val message = read()
      if (message is PeerMessage.Extended && message.id == PEER_HOLEPUNCH_ID) {
        return assertNotNull(HolepunchMessage.decode(message.payload))
      }
    }
  }

  private suspend fun PeerWire.readUntil(match: (PeerMessage) -> Boolean): PeerMessage {
    while (true) {
      val message = read()
      if (match(message)) return message
    }
  }

  private suspend fun PeerWire.sendHolepunch(message: HolepunchMessage) =
    send(PeerMessage.Extended(PeerExtensions.HOLEPUNCH, message.encode()))

  @Test
  fun rendezvous_betweenTwoPeers_sendsEachTheOther() =
    swarmTest(complete = true) { store, network ->
    val incoming = Channel<TorrentConnection>(2)
    val budget = TorrentBufferBudget(2 * 1024 * 1024)
    val swarm = launch {
      TorrentSwarm(store, network, budget,
        uploadPolicy = { TorrentUploadPolicy.SEED_AFTER_COMPLETION }, allowLocalPeers = true,
        utpEnabled = { true },
      ).run(Channel(), incoming)
    }
    try {
      val first = joinedPeer(network, incoming, listenPort = 6001)
      first.awaitUnchoke()
      val second = joinedPeer(network, incoming, listenPort = 6002)
      second.awaitUnchoke()
      first.sendHolepunch(HolepunchMessage(HolepunchType.RENDEZVOUS,
        PeerEndpoint("127.0.0.1", 6002)))
      // Neither reached us over uTP: each is dialed where it listens.
      assertEquals(HolepunchMessage(HolepunchType.CONNECT, PeerEndpoint("127.0.0.1", 6002)),
        first.readHolepunch())
      assertEquals(HolepunchMessage(HolepunchType.CONNECT, PeerEndpoint("127.0.0.1", 6001)),
        second.readHolepunch())
    } finally {
      swarm.cancelAndJoin()
    }
  }

  @Test
  fun connect_fromARelay_dialsThePeerOverUtpFirst() = connectDials(complete = false)

  /** A seed behind a NAT is how leechers behind theirs reach it, so it dials back too. */
  @Test
  fun connect_whileSeeding_dialsThePeerToo() = connectDials(complete = true)

  private fun connectDials(complete: Boolean) = swarmTest(complete) { store, network ->
    val incoming = Channel<TorrentConnection>(1)
    val budget = TorrentBufferBudget(2 * 1024 * 1024)
    val utpFirst = AtomicReference(emptyList<PeerEndpoint>())
    val swarm = launch {
      TorrentSwarm(store, network, budget, allowLocalPeers = true, utpEnabled = { true },
        uploadPolicy = { TorrentUploadPolicy.SEED_AFTER_COMPLETION },
        onUtpPeers = { peers -> utpFirst.update { it + peers } },
      ).run(Channel(), incoming)
    }
    val target = network.transport.listen(PeerEndpoint("127.0.0.1", 0))
    val reached = CompletableDeferred<Unit>()
    val server = launch {
      target.accept().close()
      reached.complete(Unit)
    }
    try {
      val relay = joinedPeer(network, incoming, listenPort = 6001)
      relay.sendHolepunch(HolepunchMessage(HolepunchType.CONNECT, target.local))
      reached.await()
      assertTrue(target.local in network.dialed(), "${network.dialed()}")
      assertTrue(target.local in utpFirst.load(), "${utpFirst.load()}")
    } finally {
      swarm.cancelAndJoin()
      server.cancelAndJoin()
      target.close()
    }
  }

  @Test
  fun connects_fromOnePeer_areLimitedToEightAMinute() =
    swarmTest(complete = false) { store, network ->
    val incoming = Channel<TorrentConnection>(1)
    val budget = TorrentBufferBudget(2 * 1024 * 1024)
    val swarm = launch {
      TorrentSwarm(store, network, budget, allowLocalPeers = true, utpEnabled = { true })
        .run(Channel(), incoming)
    }
    try {
      val relay = joinedPeer(network, incoming, listenPort = 6001)
      // Hosts the network refuses at once: each CONNECT the swarm takes is one dial.
      val targets = (1..12).map { PeerEndpoint("127.0.0.2", 20_000 + it) }
      for (target in targets) relay.sendHolepunch(HolepunchMessage(HolepunchType.CONNECT, target))
      withTimeout(5_000) {
        while (network.dialed().count { it in targets } < 8) delay(20)
      }
      delay(500)
      // Dialed in parallel, in any order: the first eight, and none after them.
      assertEquals(targets.take(8).toSet(), network.dialed().filter { it in targets }.toSet())
    } finally {
      swarm.cancelAndJoin()
    }
  }

  @Test
  fun utpOff_neitherOffersNorActsOnHolepunch() = swarmTest(complete = false) { store, network ->
    val incoming = Channel<TorrentConnection>(1)
    val budget = TorrentBufferBudget(2 * 1024 * 1024)
    val swarm = launch {
      TorrentSwarm(store, network, budget, allowLocalPeers = true).run(Channel(), incoming)
    }
    try {
      val relay = joinedPeer(network, incoming, listenPort = 6001)
      val handshake = relay.readUntil { it is PeerMessage.Extended && it.id == 0 }
      val offered = PeerExtensions().apply {
        receive((handshake as PeerMessage.Extended).payload, 1024)
      }
      assertEquals(0, offered.id(HOLEPUNCH_EXTENSION))
      val target = PeerEndpoint("127.0.0.2", 20_001)
      relay.sendHolepunch(HolepunchMessage(HolepunchType.CONNECT, target))
      delay(500)
      assertTrue(target !in network.dialed(), "${network.dialed()}")
    } finally {
      swarm.cancelAndJoin()
    }
  }

  /** A peer that punches speaks uTP, and over uTP it sees the port it must introduce us by. */
  @Test
  fun pexPeerThatPunches_isDialedOverUtpFirst() = swarmTest(complete = false) { store, network ->
    val incoming = Channel<TorrentConnection>(1)
    val budget = TorrentBufferBudget(2 * 1024 * 1024)
    val utpFirst = AtomicReference(emptyList<PeerEndpoint>())
    val swarm = launch {
      TorrentSwarm(store, network, budget, allowLocalPeers = true, utpEnabled = { true },
        onUtpPeers = { peers -> utpFirst.update { it + peers } },
      ).run(Channel(), incoming)
    }
    try {
      val introducer = joinedPeer(network, incoming, listenPort = 6001)
      val punches = PeerEndpoint("127.0.0.2", 20_001)
      val plain = PeerEndpoint("127.0.0.2", 20_002)
      introducer.send(PeerMessage.Extended(PeerExtensions.PEX, Bencode.encode(mapOf(
        "added" to DhtCodec.compactEndpoint(punches) + DhtCodec.compactEndpoint(plain),
        "added.f" to byteArrayOf(PEX_FLAG_HOLEPUNCH.toByte(), 0),
      ))))
      withTimeout(5_000) { while (punches !in utpFirst.load()) delay(20) }
      assertTrue(plain !in utpFirst.load(), "${utpFirst.load()}")
    } finally {
      swarm.cancelAndJoin()
    }
  }

  @Test
  fun unreachableIntroducedPeer_asksItsIntroducerToPunch() =
    swarmTest(complete = false) { store, network ->
    val incoming = Channel<TorrentConnection>(1)
    val budget = TorrentBufferBudget(2 * 1024 * 1024)
    val swarm = launch {
      TorrentSwarm(store, network, budget, allowLocalPeers = true, utpEnabled = { true })
        .run(Channel(), incoming)
    }
    try {
      val introducer = joinedPeer(network, incoming, listenPort = 6001)
      introducer.send(PeerMessage.Extended(PeerExtensions.PEX, Bencode.encode(mapOf(
        "added" to DhtCodec.compactEndpoint(UNREACHABLE)))))
      // The swarm cannot dial it, so it asks the peer that named it.
      assertEquals(HolepunchMessage(HolepunchType.RENDEZVOUS, UNREACHABLE),
        introducer.readHolepunch())
      assertTrue(UNREACHABLE in network.dialed())
    } finally {
      swarm.cancelAndJoin()
    }
  }

  private companion object {
    const val PEER_HOLEPUNCH_ID = 9
    val UNREACHABLE = PeerEndpoint("127.0.0.1", 9)
  }
}
