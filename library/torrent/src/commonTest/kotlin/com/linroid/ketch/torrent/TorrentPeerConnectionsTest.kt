package com.linroid.ketch.torrent

import com.linroid.ketch.api.ConnectionDirection
import com.linroid.ketch.api.ConnectionRoute
import com.linroid.ketch.core.engine.ConnectionReporter
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import okio.FileSystem
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Torrent peers are reported among the task's live connections: one per connected peer, with
 * its direction, endpoint, wire and payload both ways, closed whenever the peer goes.
 */
class TorrentPeerConnectionsTest {
  private val peerAddress = PeerEndpoint("192.0.2.7", 6881)

  @Test
  fun noReporterOpensNothing() {
    assertNull(PeerTraffic.open(ConnectionReporter.None, peerAddress, incoming = false,
      v1 = true))
  }

  @Test
  fun peerOpensWithItsEndpointDirectionAndWire() {
    val recorder = RecordingConnections()
    PeerTraffic.open(recorder, PeerEndpoint("[2001:db8::1]", 51413), incoming = true, v1 = false)
    val spec = recorder.opened.single().spec
    assertEquals("torrent", spec.source)
    assertEquals(PeerTraffic.PROTOCOL, spec.protocol)
    assertEquals(false, spec.secure)
    assertEquals(ConnectionRoute.DIRECT, spec.route)
    assertEquals(ConnectionDirection.INCOMING, spec.direction)
    // Hosts are reported as IP literals without brackets.
    assertEquals("2001:db8::1", spec.host)
    assertEquals(51413, spec.port)
    val peer = assertNotNull(spec.peer)
    assertEquals("v2", peer.wire)
    assertTrue(peer.peerChoking)
    assertFalse(peer.uploadSlot)
    assertFalse(peer.peerInterested)
  }

  @Test
  fun peerStateIsDescribedOnlyWhenItChanges() {
    val recorder = RecordingConnections()
    val traffic = assertNotNull(PeerTraffic.open(recorder, peerAddress, incoming = true,
      v1 = true))
    val connection = recorder.opened.single()
    traffic.state(peerChoking = true, uploadSlot = false, peerInterested = false)
    assertEquals(0, connection.describes)
    traffic.state(peerChoking = false, uploadSlot = true, peerInterested = true)
    traffic.state(peerChoking = false, uploadSlot = true, peerInterested = true)
    assertEquals(1, connection.describes)
    val peer = assertNotNull(connection.spec.peer)
    assertFalse(peer.peerChoking)
    assertTrue(peer.uploadSlot)
    assertTrue(peer.peerInterested)
    // The listen port replaces the source port once, and an invalid one never does.
    traffic.listenPort(6881)
    traffic.listenPort(0)
    traffic.listenPort(51413)
    traffic.listenPort(51413)
    assertEquals(2, connection.describes)
    assertEquals(51413, connection.spec.port)
    traffic.received(16_384)
    traffic.sent(4)
    traffic.close()
    traffic.close()
    assertEquals(16_384, connection.received)
    assertEquals(4, connection.sent)
    assertTrue(connection.closed)
  }

  @Test
  fun v1SwarmReportsEachPeerAndItsTrafficUntilItLeaves() = runTest {
    withContext(Dispatchers.Default) {
      withTimeout(20_000) {
        coroutineScope {
          // Two pieces of two 16 KiB blocks: we have the first, the dialed peer the second.
          val first = ByteArray(32_768) { it.toByte() }
          val second = ByteArray(32_768) { (it * 7).toByte() }
          val metadata = TorrentMetadata.fromBencode(Bencode.encode(mapOf("info" to mapOf(
            "name" to "data", "length" to 65_536L, "piece length" to 32_768L,
            "pieces" to (sha1Digest(first) + sha1Digest(second))
          ))))
          val root = FileSystem.SYSTEM_TEMPORARY_DIRECTORY /
            "ketch-peer-connections-${InfoHash.fromBytes(torrentRandomBytes(20)).hex}"
          val store = TorrentPieceStore(metadata, root / "data", emptySet(), "test")
          store.initialize()
          store.commit(0, first)
          val network = createTorrentNetwork()
          val seederListener = network.listen(PeerEndpoint("127.0.0.1", 0))
          val inbound = network.listen(PeerEndpoint("127.0.0.1", 0))
          val budget = TorrentBufferBudget(1024 * 1024)
          val recorder = RecordingConnections()
          val peers = Channel<PeerEndpoint>(1)
          peers.send(seederListener.local)
          peers.close()
          val incoming = Channel<TorrentConnection>(1)
          val swarm = launch {
            TorrentSwarm(store, network, budget,
              uploadPolicy = { TorrentUploadPolicy.SEED_AFTER_COMPLETION },
              reporter = recorder).run(peers, incoming)
          }
          val seeder = seederListener.accept()
          val serving = launch { exchangePieces(seeder, metadata, second) }
          var joiner: TorrentConnection? = null
          var joined: TorrentConnection? = null
          try {
            // The dialed peer sends us its piece and takes ours.
            eventually("the dialed peer's payload") {
              recorder.opened.singleOrNull()?.let { it.received == 32_768L && it.sent == 32_768L }
                ?: false
            }
            val dialed = recorder.opened.single()
            assertEquals(ConnectionDirection.OUTGOING, dialed.spec.direction)
            assertEquals("127.0.0.1", dialed.spec.host)
            assertEquals(seederListener.local.port, dialed.spec.port)
            assertEquals("v1", dialed.spec.peer?.wire)
            eventually("the dialed peer's upload slot") {
              dialed.spec.peer?.let { it.uploadSlot && it.peerInterested && !it.peerChoking }
                ?: false
            }
            // A peer that dials us is reported at its source port.
            val client = network.connect(inbound.local).also { joiner = it }
            val server = inbound.accept().also { joined = it }
            incoming.send(server)
            PeerWire(client, metadata).handshake(PeerHandshake(metadata.infoHash,
              torrentRandomBytes(20), false, false))
            eventually("the incoming peer") { recorder.opened.size == 2 }
            val dialedUs = recorder.opened[1]
            assertEquals(ConnectionDirection.INCOMING, dialedUs.spec.direction)
            assertEquals(server.remote.port, dialedUs.spec.port)
            assertEquals("v1", dialedUs.spec.peer?.wire)
            // It leaves; the other peer stays.
            client.close()
            eventually("the incoming peer to close") { dialedUs.closed }
            assertFalse(dialed.closed)
            assertEquals(1, recorder.open.size)
          } finally {
            withContext(NonCancellable) {
              serving.cancelAndJoin()
              swarm.cancelAndJoin()
              seeder.close()
              joiner?.close()
              joined?.close()
              seederListener.close()
              inbound.close()
              network.close()
              torrentFileSystem.deleteRecursively(root, mustExist = false)
            }
          }
          // Stopping the swarm closed every peer it reported.
          assertEquals(emptyList(), recorder.open)
          assertEquals(0, budget.allocated)
        }
      }
    }
  }

  @Test
  fun hybridOwnerReportsItsV1PeersInEitherDirection() = runTest {
    for (incoming in listOf(true, false)) hybridDownload(incoming)
  }

  // Pieces of 32 KiB with BEP 47 padding after a (40000 bytes) and b (5 bytes).
  private val hybrid = TorrentV2Fixture.build(listOf("a" to 40_000, "b" to 5, "c" to 20_000),
    hybrid = true)
  private val legacy = TorrentMetadata.fromBencode(hybrid.metainfo, allowHybrid = true)

  private suspend fun hybridDownload(incoming: Boolean) = withContext(Dispatchers.Default) {
    withTimeout(30_000) {
      coroutineScope {
        val root = FileSystem.SYSTEM_TEMPORARY_DIRECTORY /
          "ketch-hybrid-connections-${InfoHash.fromBytes(torrentRandomBytes(20)).hex}"
        torrentFileSystem.createDirectories(root)
        val network = createTorrentNetwork()
        val listener = network.listen(PeerEndpoint("127.0.0.1", 0))
        val requests = mutableListOf<PeerMessage.Request>()
        val recorder = RecordingConnections()
        val engine = KotlinTorrentEngine(TorrentConfig(dhtEnabled = false),
          listenHost = "127.0.0.1")
        val seeder = async(start = CoroutineStart.LAZY) {
          val connection = if (incoming) {
            network.connect(PeerEndpoint("127.0.0.1", engine.listenPort)).also {
              it.write(v1Handshake())
              PeerWire.decodeHandshake(it.readExactly(68), legacy.infoHash)
            }
          } else {
            listener.accept().also {
              PeerWire.decodeHandshake(it.readExactly(68), legacy.infoHash)
              it.write(v1Handshake())
            }
          }
          try { seedV1(connection, requests) } catch (e: CancellationException) {
            throw e
          } catch (_: Exception) {
            // The owner closes its peers once the download completes.
          } finally { connection.close() }
        }
        try {
          engine.start()
          val session = engine.addV2Task(TorrentV2TaskSpec("hybrid-v1", hybrid.document,
            (root / "payload").toString(), privacy = TorrentDiscoveryPrivacy.PUBLIC,
            discover = { peers ->
              if (!incoming) peers.send(listener.local)
              awaitCancellation()
            },
            discoverMode = PeerIdentityHandshake.Mode.V1, connections = recorder))
          session.resume()
          session.state.first { it == TorrentSessionState.DOWNLOADING }
          seeder.start()
          assertEquals(TorrentSessionState.FINISHED, session.state.first {
            it == TorrentSessionState.FINISHED || it == TorrentSessionState.STOPPED
          }, session.failure.value?.stackTraceToString())
          seeder.await()
          val peer = recorder.opened.single()
          assertEquals(if (incoming) ConnectionDirection.INCOMING else ConnectionDirection.OUTGOING,
            peer.spec.direction)
          assertEquals("127.0.0.1", peer.spec.host)
          if (!incoming) assertEquals(listener.local.port, peer.spec.port)
          // A hybrid's peer from its v1 swarm is labeled by the wire it speaks.
          assertEquals("v1", peer.spec.peer?.wire)
          assertEquals(requests.sumOf { it.length.toLong() }, peer.received)
          assertEquals(0, peer.sent)
          // The owner drops its peers once complete, as it does not seed.
          eventually("the peer to close") { peer.closed }
          engine.removeTorrent(hybrid.document.identity.v1!!.hex, deleteFiles = false)
        } finally {
          withContext(NonCancellable) {
            seeder.cancelAndJoin()
            engine.stop()
            listener.close()
            network.close()
            torrentFileSystem.deleteRecursively(root, mustExist = false)
          }
        }
        assertEquals(emptyList(), recorder.open)
        engine.assertNoLeaks()
      }
    }
  }

  private fun v1Handshake(): ByteArray = PeerWire.encodeHandshake(PeerHandshake(legacy.infoHash,
    torrentRandomBytes(20), extensions = false, dht = false))

  /** Offers every piece of [hybrid] and answers each request from its v1 byte stream. */
  private suspend fun seedV1(
    connection: TorrentConnection,
    requests: MutableList<PeerMessage.Request>,
  ) {
    val wire = PeerWire(connection, legacy)
    wire.send(PeerMessage.Bitfield(pieceBitfield(BooleanArray(4) { true })))
    wire.send(PeerMessage.Control(PeerMessage.Signal.UNCHOKE))
    while (true) {
      val message = wire.read()
      if (message !is PeerMessage.Request) continue
      requests += message
      wire.send(PeerMessage.Piece(message.index, message.begin, hybrid.v1Piece(message.index)
        .copyOfRange(message.begin, message.begin + message.length)))
    }
  }

  /**
   * A peer with the second of two pieces: it serves that piece and asks for the first once we
   * unchoke it, until the connection ends.
   */
  private suspend fun exchangePieces(
    connection: TorrentConnection,
    metadata: TorrentMetadata,
    second: ByteArray,
  ) {
    try {
      val wire = PeerWire(connection, metadata)
      wire.handshake(PeerHandshake(metadata.infoHash, torrentRandomBytes(20), false, false))
      wire.send(PeerMessage.Bitfield(pieceBitfield(booleanArrayOf(false, true))))
      wire.send(PeerMessage.Control(PeerMessage.Signal.UNCHOKE))
      wire.send(PeerMessage.Control(PeerMessage.Signal.INTERESTED))
      var asked = false
      while (true) {
        when (val message = wire.read()) {
          PeerMessage.Control(PeerMessage.Signal.UNCHOKE) -> if (!asked) {
            asked = true
            wire.send(PeerMessage.Request(0, 0, 16_384))
            wire.send(PeerMessage.Request(0, 16_384, 16_384))
          }
          is PeerMessage.Request -> wire.send(PeerMessage.Piece(message.index, message.begin,
            second.copyOfRange(message.begin, message.begin + message.length)))
          else -> Unit
        }
      }
    } catch (e: CancellationException) {
      throw e
    } catch (_: Exception) {
      // The swarm closed the connection as it stopped.
    }
  }
}
