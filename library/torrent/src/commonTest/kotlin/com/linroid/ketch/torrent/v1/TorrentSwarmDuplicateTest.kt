package com.linroid.ketch.torrent.v1

import com.linroid.ketch.torrent.TorrentUploadPolicy
import com.linroid.ketch.torrent.crypto.sha1Digest
import com.linroid.ketch.torrent.crypto.torrentRandomBytes
import com.linroid.ketch.torrent.engine.TorrentBufferBudget
import com.linroid.ketch.torrent.metainfo.Bencode
import com.linroid.ketch.torrent.metainfo.InfoHash
import com.linroid.ketch.torrent.metainfo.TorrentMetadata
import com.linroid.ketch.torrent.net.PeerEndpoint
import com.linroid.ketch.torrent.net.TorrentConnection
import com.linroid.ketch.torrent.net.TorrentListener
import com.linroid.ketch.torrent.net.TorrentNetwork
import com.linroid.ketch.torrent.net.createTorrentNetwork
import com.linroid.ketch.torrent.peer.PeerHandshake
import com.linroid.ketch.torrent.peer.PeerMessage
import com.linroid.ketch.torrent.peer.PeerWire
import com.linroid.ketch.torrent.peer.pieceBitfield
import com.linroid.ketch.torrent.storage.TorrentPieceStore
import com.linroid.ketch.torrent.storage.torrentFileSystem
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import okio.FileSystem
import okio.IOException
import kotlin.concurrent.atomics.AtomicInt
import kotlin.concurrent.atomics.AtomicLong
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * A v1 swarm keeps one connection to each peer, by the peer ID it claims: on one host, or on any
 * addresses of one machine or network, such as loopback from a magnet's `x.pe` and the LAN address
 * local discovery found. The endpoint of the connection it closes is not dialed again meanwhile.
 */
@OptIn(ExperimentalAtomicApi::class)
class TorrentSwarmDuplicateTest {
  private val payload = byteArrayOf(1, 2, 3, 4)
  private val metadata = TorrentMetadata.fromBencode(Bencode.encode(mapOf("info" to mapOf(
    "name" to "data", "length" to 4L, "piece length" to 4L, "pieces" to sha1Digest(payload)))))
  private val peerId = torrentRandomBytes(20)

  @Test
  fun peerDialedAtTwoLocalAddresses_keepsOneConnection() = swarmTest { rig ->
    // Both reach the one peer, which listens on loopback.
    val lan = PeerEndpoint("192.168.31.11", rig.listener.local.port)
    val kept = CompletableDeferred<PeerWire>()
    val closed = CompletableDeferred<Unit>()
    val peers = Channel<PeerEndpoint>(2)
    peers.send(rig.listener.local)
    peers.send(lan)
    val server = launch {
      repeat(2) {
        val connection = rig.listener.accept()
        launch { answer(connection, kept, closed) }
      }
    }
    val swarm = launch { rig.swarm().run(peers) }
    assertNotNull(withTimeoutOrNull(5_000) { closed.await() },
      "Both connections to one peer were kept")
    rig.assertNotDialedAgain()
    rig.complete(kept.await())
    swarm.join()
    server.cancelAndJoin()
  }

  @Test
  fun peerReachedBothWays_keepsTheConnectionBothEndsKeep() = swarmTest { rig ->
    val peers = Channel<PeerEndpoint>(1)
    peers.send(rig.listener.local)
    val incoming = Channel<TorrentConnection>(1)
    val dialedKept = CompletableDeferred<PeerWire>()
    val dialedClosed = CompletableDeferred<Unit>()
    val server = launch { answer(rig.listener.accept(), dialedKept, dialedClosed) }
    // We listen on a port above the peer's, so both keep the connection the peer dialed, and the
    // one we dialed, connected first, gives way.
    val swarm = launch {
      rig.swarm(listenPortFor = { it.port + 1 }).run(peers, incoming)
    }
    val outgoing = dialedKept.await()
    val ours = rig.network.transport.listen(PeerEndpoint("127.0.0.1", 0))
    val theirs = try {
      val client = rig.network.transport.connect(ours.local)
      incoming.send(ours.accept())
      client
    } finally {
      ours.close()
    }
    val wire = PeerWire(theirs, metadata)
    wire.handshake(PeerHandshake(metadata.infoHash, peerId, false, false))
    assertTrue(wire.read() is PeerMessage.Bitfield)
    assertNotNull(withTimeoutOrNull(5_000) {
      try {
        while (true) outgoing.read()
      } catch (_: IOException) {
      }
    }, "The connection we dialed was kept too")
    rig.assertNotDialedAgain()
    rig.complete(wire)
    swarm.join()
    server.cancelAndJoin()
    theirs.close()
  }

  @Test
  fun displacedDial_isNotDialedAgainForFiveMinutesFromItsClose() = swarmTest(
    complete = true,
  ) { rig ->
    val peers = Channel<PeerEndpoint>(2)
    peers.send(rig.listener.local)
    val incoming = Channel<TorrentConnection>(1)
    val dialedKept = CompletableDeferred<PeerWire>()
    val server = launch { answer(rig.listener.accept(), dialedKept, CompletableDeferred()) }
    // A seed keeps a leecher connected however long it takes nothing. We listen on a port above
    // the leecher's, so both keep the connection it dials.
    val swarm = launch {
      rig.swarm(listenPortFor = { it.port + 1 },
        uploadPolicy = TorrentUploadPolicy.SEED_AFTER_COMPLETION).run(peers, incoming)
    }
    val outgoing = dialedKept.await()
    // Longer than an endpoint stays known after its last dial.
    rig.advance(360_000)
    val ours = rig.network.transport.listen(PeerEndpoint("127.0.0.1", 0))
    val theirs = try {
      val client = rig.network.transport.connect(ours.local)
      incoming.send(ours.accept())
      client
    } finally {
      ours.close()
    }
    val wire = PeerWire(theirs, metadata)
    wire.handshake(PeerHandshake(metadata.infoHash, peerId, false, false))
    assertTrue(wire.read() is PeerMessage.Bitfield)
    assertNotNull(withTimeoutOrNull(5_000) {
      try {
        while (true) outgoing.read()
      } catch (_: IOException) {
      }
    }, "The connection we dialed was kept too")
    // Found again a minute after that connection closed, it is still given up on.
    val dials = rig.network.dials.load()
    rig.advance(60_000)
    peers.send(rig.listener.local)
    delay(500)
    assertEquals(dials, rig.network.dials.load())
    swarm.cancelAndJoin()
    server.cancelAndJoin()
    theirs.close()
  }

  /**
   * Answers a connection as the peer; the first one whose handshake the swarm follows up is
   * [kept], one it closes completes [closed].
   */
  private suspend fun answer(
    connection: TorrentConnection,
    kept: CompletableDeferred<PeerWire>,
    closed: CompletableDeferred<Unit>,
  ) {
    try {
      val wire = PeerWire(connection, metadata)
      try {
        wire.handshake(PeerHandshake(metadata.infoHash, peerId, false, false))
        assertTrue(wire.read() is PeerMessage.Bitfield)
      } catch (_: IOException) {
        closed.complete(Unit)
        return
      }
      kept.complete(wire)
      // The test speaks for the peer from here on, until the connection ends.
      awaitCancellation()
    } finally {
      connection.close()
    }
  }

  private inner class Rig(
    val store: TorrentPieceStore,
    val network: Network,
    val listener: TorrentListener,
  ) {
    private val clock = AtomicLong(0)

    fun swarm(
      listenPortFor: (PeerEndpoint) -> Int = { 0 },
      uploadPolicy: TorrentUploadPolicy = TorrentUploadPolicy.DISABLED,
    ) = TorrentSwarm(store, network, TorrentBufferBudget(1024 * 1024),
      uploadPolicy = { uploadPolicy }, nowMs = { clock.load() }, listenPortFor = listenPortFor)

    /** Moves the swarm's clock [ms] on and lets its loop look. */
    suspend fun advance(ms: Long) {
      clock.addAndFetch(ms)
      delay(300)
    }

    /** The closed connection's endpoint is not retried, though a failed one would be by now. */
    suspend fun assertNotDialedAgain() {
      val dials = network.dials.load()
      clock.addAndFetch(60_000)
      // Several ticks of the swarm's loop.
      delay(500)
      assertEquals(dials, network.dials.load())
    }

    /** The kept connection's peer has the piece, and sends it; the swarm completes. */
    suspend fun complete(wire: PeerWire) {
      wire.send(PeerMessage.Bitfield(pieceBitfield(booleanArrayOf(true))))
      wire.send(PeerMessage.Control(PeerMessage.Signal.UNCHOKE))
      while (true) {
        val message = wire.read()
        if (message is PeerMessage.Request) {
          wire.send(PeerMessage.Piece(0, 0, payload))
          break
        }
      }
    }
  }

  /** Dials loopback whatever host it is asked for, and counts the dials. */
  private class Network(val transport: TorrentNetwork) : TorrentNetwork by transport {
    val dials = AtomicInt(0)

    override suspend fun connect(remote: PeerEndpoint): TorrentConnection {
      dials.addAndFetch(1)
      return transport.connect(PeerEndpoint("127.0.0.1", remote.port))
    }
  }

  /** Runs [body] with an empty store, or a [complete] one. */
  private fun swarmTest(
    complete: Boolean = false,
    body: suspend CoroutineScope.(Rig) -> Unit,
  ) = runTest {
    withContext(Dispatchers.Default) {
      withTimeout(20_000) {
        coroutineScope {
          val root = FileSystem.SYSTEM_TEMPORARY_DIRECTORY /
            "ketch-duplicate-${InfoHash.fromBytes(torrentRandomBytes(20)).hex}"
          val store = TorrentPieceStore(metadata, root / "data", emptySet(), "duplicate")
          if (complete) {
            store.initialize()
            store.commit(0, payload)
          }
          val transport = createTorrentNetwork()
          val listener = transport.listen(PeerEndpoint("127.0.0.1", 0))
          try {
            body(Rig(store, Network(transport), listener))
            assertTrue(store.completed())
          } finally {
            listener.close()
            transport.close()
            torrentFileSystem.deleteRecursively(root, mustExist = false)
          }
        }
      }
    }
  }
}
