package com.linroid.ketch.torrent

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import okio.Buffer
import okio.FileSystem
import okio.IOException
import okio.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * A v1 swarm tells peers only where others listen: a peer that dialed us is known by the port it
 * gives in its extension handshake (BEP 10 `p`), never by the ephemeral port it dialed from.
 */
class TorrentSwarmPexEndpointTest {
  private val metadata = TorrentMetadata.fromBencode(Bencode.encode(mapOf("info" to mapOf(
    "name" to "file", "length" to 4L, "piece length" to 4L,
    "pieces" to sha1Digest(byteArrayOf(1, 2, 3, 4))))))

  /**
   * Runs [body] with a fresh store, a counting network that reaches only loopback, and a
   * temporary directory.
   */
  private fun swarmTest(
    complete: Boolean,
    body: suspend CoroutineScope.(TorrentPieceStore, Network) -> Unit,
  ) = runTest {
    withContext(Dispatchers.Default) {
      withTimeout(20_000) {
        coroutineScope {
          val root: Path = FileSystem.SYSTEM_TEMPORARY_DIRECTORY /
            "ketch-pex-endpoint-${InfoHash.fromBytes(torrentRandomBytes(20)).hex}"
          val store = TorrentPieceStore(metadata, root / "file", emptySet(), "test")
          store.initialize()
          if (complete) store.commit(0, byteArrayOf(1, 2, 3, 4))
          val network = Network(createTorrentNetwork()) { it.host == "127.0.0.1" }
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
   * Records every endpoint the swarm dials; the test's own peers use [transport]. Endpoints
   * [reachable] refuses fail at once, without leaving the machine.
   */
  internal class Network(
    val transport: TorrentNetwork,
    private val reachable: (PeerEndpoint) -> Boolean = { true },
  ) : TorrentNetwork by transport {
    private val mutex = Mutex()
    private val dials = mutableListOf<PeerEndpoint>()

    override suspend fun connect(remote: PeerEndpoint): TorrentConnection {
      mutex.withLock { dials += remote }
      if (!reachable(remote)) throw IOException("Network is unreachable")
      return transport.connect(remote)
    }

    suspend fun dialed(): List<PeerEndpoint> = mutex.withLock { dials.toList() }
  }

  /** A peer that dialed us: our accepted side, as the engine would pass it, and its own. */
  private class Dialer(
    val ours: TorrentConnection,
    val theirs: TorrentConnection,
    val wire: PeerWire,
  )

  private suspend fun dialer(network: Network): Dialer {
    val listener = network.transport.listen(PeerEndpoint("127.0.0.1", 0))
    try {
      val client = network.transport.connect(listener.local)
      return Dialer(listener.accept(), client, PeerWire(client, metadata))
    } finally {
      listener.close()
    }
  }

  private suspend fun PeerWire.handshake() {
    handshake(PeerHandshake(metadata.infoHash, torrentRandomBytes(20), true, false))
    // What the swarm sends first: its extension handshake, bitfield and interest.
    while (read() != PeerMessage.Control(PeerMessage.Signal.INTERESTED)) { }
  }

  private fun extensionHandshake(values: Map<String, Any>) =
    PeerMessage.Extended(0, Bencode.encode(mapOf("m" to emptyMap<String, Long>()) + values))

  @Test
  fun incomingPeerIsAdvertisedByItsListenPortOnly() = swarmTest(complete = true) { store, network ->
    val incoming = Channel<TorrentConnection>(1)
    val peers = Channel<PeerEndpoint>(2)
    val budget = TorrentBufferBudget(2 * 1024 * 1024)
    val swarm = launch {
      TorrentSwarm(store, network, budget,
        uploadPolicy = { TorrentUploadPolicy.SEED_AFTER_COMPLETION }, allowLocalPeers = true,
      ).run(peers, incoming)
    }
    val workers = mutableListOf<Job>()
    try {
      // A peer we dial: reachable where we dialed it.
      val outgoingListener = network.transport.listen(PeerEndpoint("127.0.0.1", 0))
      val outgoingReady = CompletableDeferred<Unit>()
      workers += launch {
        val connection = outgoingListener.accept()
        try {
          val wire = PeerWire(connection, metadata)
          wire.handshake()
          wire.send(PeerMessage.Control(PeerMessage.Signal.INTERESTED))
          while (wire.read() != PeerMessage.Control(PeerMessage.Signal.UNCHOKE)) { }
          outgoingReady.complete(Unit)
          awaitCancellation()
        } finally { connection.close() }
      }
      peers.send(outgoingListener.local)
      // A peer that dials us from an ephemeral port and listens elsewhere.
      val incomingListener = network.transport.listen(PeerEndpoint("127.0.0.1", 0))
      val dialer = dialer(network)
      val source = dialer.ours.remote
      incoming.send(dialer.ours)
      val incomingReady = CompletableDeferred<Unit>()
      workers += launch {
        try {
          dialer.wire.handshake()
          dialer.wire.send(extensionHandshake(mapOf("p" to incomingListener.local.port.toLong())))
          // Answered in order, so the swarm read the extension handshake before this.
          dialer.wire.send(PeerMessage.Control(PeerMessage.Signal.INTERESTED))
          while (dialer.wire.read() != PeerMessage.Control(PeerMessage.Signal.UNCHOKE)) { }
          incomingReady.complete(Unit)
          awaitCancellation()
        } finally { dialer.theirs.close() }
      }
      outgoingReady.await()
      incomingReady.await()
      // A third peer asks for peer exchange and hears about both, each by where it listens.
      val observerListener = network.transport.listen(PeerEndpoint("127.0.0.1", 0))
      peers.send(observerListener.local)
      val connection = observerListener.accept()
      try {
        val wire = PeerWire(connection, metadata)
        wire.handshake()
        wire.send(extensionHandshake(mapOf("m" to mapOf("ut_pex" to 7L))))
        val message = assertIs<PeerMessage.Extended>(wire.readUntil {
          it is PeerMessage.Extended && it.id == 7
        })
        // Read raw: a receiver keeps one endpoint per host, and these all share loopback.
        val fields = Bencode.parse(message.payload)
        val added = TorrentTracker.compactEntries(assertNotNull(fields["added"]?.bytes), false)
        val flags = assertNotNull(fields["added.f"]?.bytes).map { it.toInt() and 0xff }
        val advertised = added.map { assertNotNull(it) }.zip(flags).toMap()
        val listening = PeerEndpoint("127.0.0.1", incomingListener.local.port)
        assertFalse(source in advertised)
        // Only the peer we reached ourselves is known to accept connections.
        assertEquals(mapOf(outgoingListener.local to PEX_FLAG_REACHABLE, listening to 0),
          advertised)
      } finally {
        connection.close()
      }
    } finally {
      swarm.cancelAndJoin()
      workers.forEach { it.cancelAndJoin() }
    }
    assertEquals(0, budget.allocated)
  }

  @Test
  fun incomingPeerIsNeverRedialedOnItsSourcePort() = swarmTest(complete = false) { store, network ->
    val incoming = Channel<TorrentConnection>(1)
    val peers = Channel<PeerEndpoint>()
    val budget = TorrentBufferBudget(2 * 1024 * 1024)
    val swarm = launch { TorrentSwarm(store, network, budget).run(peers, incoming) }
    val listener = network.transport.listen(PeerEndpoint("127.0.0.1", 0))
    val redialed = CompletableDeferred<Unit>()
    val server = launch {
      val connection = listener.accept()
      redialed.complete(Unit)
      try { awaitCancellation() } finally { connection.close() }
    }
    try {
      val dialer = dialer(network)
      val source = dialer.ours.remote
      incoming.send(dialer.ours)
      dialer.wire.handshake()
      dialer.wire.send(extensionHandshake(mapOf("p" to listener.local.port.toLong())))
      delay(200)
      // The peer leaves; it only ever accepts connections where it listens.
      dialer.theirs.close()
      withTimeout(5_000) { redialed.await() }
      // Long enough for the bounded retry of a dialed peer.
      delay(2_000)
      val dialed = network.dialed()
      assertTrue(PeerEndpoint("127.0.0.1", listener.local.port) in dialed, "$dialed")
      assertFalse(source in dialed, "$dialed")
    } finally {
      swarm.cancelAndJoin()
      server.cancelAndJoin()
    }
    assertEquals(0, budget.allocated)
  }

  @Test
  fun droppedPexEntriesMakeNoRoomForMore() = swarmTest(complete = false) { store, network ->
    val incoming = Channel<TorrentConnection>(1)
    val peers = Channel<PeerEndpoint>()
    val budget = TorrentBufferBudget(2 * 1024 * 1024)
    val swarm = launch {
      TorrentSwarm(store, network, budget, allowLocalPeers = true).run(peers, incoming)
    }
    val dialer = dialer(network)
    try {
      incoming.send(dialer.ours)
      val wire = dialer.wire
      wire.handshake()
      wire.send(extensionHandshake(mapOf("m" to mapOf("ut_metadata" to 3L))))
      // Hosts no connection reaches: the swarm dials each and fails at once.
      fun hosts(from: Int) =
        List(MAX_PEX_INTRODUCTIONS) { PeerEndpoint("10.9.${from + it}.1", 6881) }
      fun compact(peers: List<PeerEndpoint>) =
        peers.fold(Buffer()) { buffer, peer -> buffer.write(DhtCodec.compactEndpoint(peer)) }
          .readByteArray()
      suspend fun introduced(): Set<String> =
        network.dialed().map { it.host }.filter { it.startsWith("10.9.") }.toSet()
      val first = hosts(0)
      wire.send(PeerMessage.Extended(PeerExtensions.PEX,
        Bencode.encode(mapOf("added" to compact(first)))))
      withTimeout(5_000) { while (introduced().size < first.size) delay(10) }
      // Each later message drops what the peer introduced last and names as many new hosts.
      var previous = first
      for (from in listOf(100, 200)) {
        val next = hosts(from)
        wire.send(PeerMessage.Extended(PeerExtensions.PEX, Bencode.encode(mapOf(
          "added" to compact(next), "dropped" to compact(previous)))))
        previous = next
      }
      // Its messages are handled in order: once this one is answered, the swarm has both.
      wire.send(TorrentMetadataExchange.metadataMessage(PeerExtensions.METADATA, 0, 0))
      wire.readUntil { it is PeerMessage.Extended && it.id == 3 }
      // Long enough for the swarm to dial whatever it queued, as it dialed the first hosts.
      delay(1_000)
      assertEquals(first.map { it.host }.toSet(), introduced())
    } finally {
      swarm.cancelAndJoin()
      dialer.theirs.close()
    }
    assertEquals(0, budget.allocated)
  }

  private suspend fun PeerWire.readUntil(match: (PeerMessage) -> Boolean): PeerMessage {
    while (true) {
      val message = read()
      if (match(message)) return message
    }
  }
}
