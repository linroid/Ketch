package com.linroid.ketch.torrent

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.channels.SendChannel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import okio.ByteString
import okio.ByteString.Companion.toByteString
import okio.FileSystem
import okio.Path
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** Peers that dial a v2 or hybrid owner, over real loopback sockets. */
class TorrentV2IncomingTest {
  private val bytes = byteArrayOf(1, 2, 3, 4)
  private val handshakes = TorrentBufferBudget(65_536)

  private fun document(hybrid: Boolean = false, privateTorrent: Boolean = false):
    TorrentV2Document {
    val info = mutableMapOf<String, Any>("name" to "pack", "meta version" to 2L,
      "piece length" to 16_384L, "file tree" to mapOf("a" to mapOf("" to mapOf(
        "length" to 4L, "pieces root" to sha256Digest(bytes)))))
    if (hybrid) {
      info["files"] = listOf(mapOf("path" to listOf("a"), "length" to 4L))
      info["pieces"] = sha1Digest(bytes)
    }
    if (privateTorrent) info["private"] = 1L
    return TorrentV2Document.parse(Bencode.encode(mapOf("info" to info,
      "piece layers" to emptyMap<String, Any>())))
  }

  private fun root(): Path = (FileSystem.SYSTEM_TEMPORARY_DIRECTORY /
    "ketch-v2-incoming-${InfoHash.fromBytes(torrentRandomBytes(20)).hex}")
    .also { torrentFileSystem.createDirectories(it) }

  private fun peerId(value: Int): ByteString = ByteArray(20) { value.toByte() }.toByteString()

  private fun spec(
    document: TorrentV2Document,
    output: Path,
    discover: suspend (SendChannel<PeerEndpoint>) -> Unit = { awaitCancellation() },
  ) = TorrentV2TaskSpec("incoming", document, output.toString(),
    privacy = TorrentDiscoveryPrivacy.PUBLIC, discover = discover)

  private suspend fun loopback(body: suspend () -> Unit) = withContext(Dispatchers.Default) {
    withTimeout(30_000) { body() }
  }

  /** Runs [body] against a started engine owning [document], downloading, then checks leaks. */
  private suspend fun owner(
    document: TorrentV2Document,
    discover: suspend (SendChannel<PeerEndpoint>) -> Unit = { awaitCancellation() },
    body: suspend (KotlinTorrentEngine, TorrentV2DownloadSession, Path) -> Unit,
  ) {
    val root = root()
    val output = root / "payload"
    val engine = KotlinTorrentEngine(TorrentConfig(dhtEnabled = false), listenHost = "127.0.0.1")
    try {
      engine.start()
      val session = engine.addV2Task(spec(document, output, discover))
      session.resume()
      session.state.first { it == TorrentSessionState.DOWNLOADING }
      body(engine, session, output)
      engine.removeTorrent(document.info.hash.hex, deleteFiles = false)
      assertEquals(TorrentSessionState.STOPPED, session.state.value)
      assertEquals(0, engine.admittedSessionBytes)
    } finally {
      engine.stop()
      assertEquals(0, engine.admittedSessionBytes)
      assertEquals(0, engine.allocatedExchangeBytes)
      torrentFileSystem.deleteRecursively(root, mustExist = false)
    }
  }

  private suspend fun PeerWire.readUntil(match: (PeerMessage) -> Boolean): PeerMessage {
    while (true) {
      val message = read()
      if (match(message)) return message
    }
  }

  /** Offers the whole payload and answers the owner's request for it. */
  private suspend fun serve(wire: PeerWire, announce: Boolean = true) {
    if (announce) {
      wire.send(PeerMessage.Bitfield(byteArrayOf(128.toByte())))
      wire.readUntil { it == PeerMessage.Control(PeerMessage.Signal.INTERESTED) }
    }
    wire.send(PeerMessage.Control(PeerMessage.Signal.UNCHOKE))
    val request = assertIs<PeerMessage.Request>(wire.readUntil { it is PeerMessage.Request })
    assertEquals(0, request.index)
    wire.send(PeerMessage.Piece(0, request.begin,
      bytes.copyOfRange(request.begin, request.begin + request.length)))
  }

  private suspend fun finished(session: TorrentV2DownloadSession, output: Path) {
    assertEquals(TorrentSessionState.FINISHED, session.state.first {
      it == TorrentSessionState.FINISHED || it == TorrentSessionState.STOPPED
    }, session.failure.value?.stackTraceToString())
    assertContentEquals(bytes, torrentFileSystem.read(output / "a") { readByteArray() })
  }

  /** The socket ends without another byte; a timeout would mean it stayed open. */
  private suspend fun assertClosedByOwner(connection: TorrentConnection) {
    val failure = assertNotNull(runCatching {
      withTimeout(5_000) { connection.readExactly(1) }
    }.exceptionOrNull(), "The owner kept the connection open")
    assertFalse(failure is TimeoutCancellationException, "The owner kept the connection open")
  }

  @Test
  fun incomingV2PeerServesAnEngineOwnerThroughTheRouteTable() = runTest {
    loopback {
      val document = document()
      owner(document) { engine, session, output ->
        assertEquals(setOf(document.info.hash.wireBytes().toByteString()), engine.incomingTags())
        val network = createTorrentNetwork()
        try {
          val connection = network.connect(PeerEndpoint("127.0.0.1", engine.listenPort))
          try {
            val route = PeerIdentityHandshake(document.identity).initiate(connection,
              PeerIdentityHandshake.Mode.V2, peerId(1), handshakes)
            assertEquals(PeerIdentityHandshake.Mode.V2, route.mode)
            serve(PeerWire(connection, pieceCount = 1))
            finished(session, output)
          } finally { connection.close() }
        } finally { network.close() }
      }
    }
  }

  @Test
  fun hybridOwnerUpgradesIncomingV1TagWithUpgradeBit() = runTest {
    loopback {
      val document = document(hybrid = true)
      owner(document) { engine, session, output ->
        val network = createTorrentNetwork()
        try {
          val connection = network.connect(PeerEndpoint("127.0.0.1", engine.listenPort))
          try {
            // The v1 tag routes to the hybrid owner, which takes the offered upgrade.
            val route = PeerIdentityHandshake(document.identity).initiate(connection,
              PeerIdentityHandshake.Mode.V1, peerId(1), handshakes, allowUpgrade = true)
            assertEquals(PeerIdentityHandshake.Mode.V2, route.mode)
            serve(PeerWire(connection, pieceCount = 1))
            finished(session, output)
          } finally { connection.close() }
        } finally { network.close() }
      }
    }
  }

  @Test
  fun connectionLimitChangeKeepsIncomingPeerConnected() = runTest {
    loopback {
      val document = document()
      owner(document) { engine, session, output ->
        val network = createTorrentNetwork()
        try {
          val connection = network.connect(PeerEndpoint("127.0.0.1", engine.listenPort))
          try {
            PeerIdentityHandshake(document.identity).initiate(connection,
              PeerIdentityHandshake.Mode.V2, peerId(1), handshakes)
            val wire = PeerWire(connection, pieceCount = 1)
            wire.send(PeerMessage.Bitfield(byteArrayOf(128.toByte())))
            wire.send(PeerMessage.Control(PeerMessage.Signal.UNCHOKE))
            val request = assertIs<PeerMessage.Request>(
              wire.readUntil { it is PeerMessage.Request })
            // Limit changes reach the running pool; a restart would close this only peer.
            session.setConnections(2)
            delay(200)
            session.setConnections(40)
            delay(200)
            wire.send(PeerMessage.Piece(0, request.begin, bytes))
            finished(session, output)
          } finally { connection.close() }
        } finally { network.close() }
      }
    }
  }

  @Test
  fun duplicatePeerIdKeepsTheSameConnectionOnBothEnds() = runTest {
    loopback {
      // Below every peer ID and above every peer ID: on one host, the ports decide, not the IDs.
      for (clientByte in listOf(0x00, 0xff)) {
        val clientId = peerId(clientByte)
        val document = document()
        val network = createTorrentNetwork()
        val listener = network.listen(PeerEndpoint("127.0.0.1", 0))
        val gate = CompletableDeferred<Unit>()
        try {
          owner(document, discover = {
            gate.await()
            it.send(listener.local)
            awaitCancellation()
          }) { engine, session, output ->
            val dialed = network.connect(PeerEndpoint("127.0.0.1", engine.listenPort))
            var accepted: TorrentConnection? = null
            try {
              PeerIdentityHandshake(document.identity).initiate(dialed,
                PeerIdentityHandshake.Mode.V2, clientId, handshakes)
              val first = PeerWire(dialed, pieceCount = 1)
              first.send(PeerMessage.Bitfield(byteArrayOf(128.toByte())))
              // Interest shows the owner attached this connection before it dials us.
              first.readUntil { it == PeerMessage.Control(PeerMessage.Signal.INTERESTED) }
              gate.complete(Unit)
              val second = listener.accept()
              accepted = second
              PeerIdentityHandshake(document.identity).respond(second, clientId, handshakes)
              // As libtorrent decides it, each end keeps the socket the side whose listen port is
              // lower dialed; the client listens on its listener's port.
              assertTrue(engine.listenPort != listener.local.port)
              val ownerDialedKept = engine.listenPort < listener.local.port
              if (ownerDialedKept) {
                assertClosedByOwner(dialed)
                serve(PeerWire(second, pieceCount = 1))
              } else {
                assertClosedByOwner(second)
                serve(first, announce = false)
              }
              finished(session, output)
            } finally {
              dialed.close()
              accepted?.close()
            }
          }
        } finally {
          gate.complete(Unit)
          listener.close()
          network.close()
        }
      }
    }
  }

  /** A session-level owner whose discovery hands its sink to the test. */
  private suspend fun restricted(
    document: TorrentV2Document,
    privacy: TorrentDiscoveryPrivacy,
    maxPeers: Int = 4,
    body: suspend (TorrentV2DownloadSession, TorrentV2DiscoverySink, TorrentNetwork,
      () -> Int) -> Unit,
  ) = coroutineScope {
    val root = root()
    val buffers = TorrentBufferBudget(4 * 1024 * 1024)
    val state = TorrentBufferBudget(16 * 1024 * 1024)
    val network = createTorrentNetwork()
    val output = root / "payload"
    val store = TorrentV2PieceStore(document, output, emptySet(), "restricted", buffers,
      Semaphore(4))
    val sinks = CompletableDeferred<TorrentV2DiscoverySink>()
    var starts = 0
    val topic = TrackerTopic.V2(document.info.hash)
    val session = TorrentV2DownloadSession.open(this, document,
      TorrentContentLayout.from(document.info), emptySet(), store,
      TorrentV2Runtime(network, peerId(9), buffers, state),
      TorrentV2SessionOptions(maxPeers = maxPeers, privacy = privacy, waitForPeers = true,
        discovery = { sink ->
          starts++
          sink.trackerPeers(topic, listOf(PeerEndpoint("127.0.0.2", 6881)))
          sinks.complete(sink)
          awaitCancellation()
        }))
    try {
      session.restore(null)
      session.resume()
      session.state.first { it == TorrentSessionState.DOWNLOADING }
      body(session, sinks.await(), network) { starts }
      finished(session, output)
    } finally {
      withContext(NonCancellable) {
        session.close()
        network.close()
        torrentFileSystem.deleteRecursively(root, mustExist = false)
      }
    }
    assertEquals(TorrentSessionState.STOPPED, session.state.value)
    assertEquals(0, buffers.allocated)
    assertEquals(0, state.allocated)
  }

  /** A connection a peer opened to us, as the engine would hand it to [session]. */
  private class Arrival(val client: TorrentConnection, val server: TorrentConnection) {
    fun close() {
      client.close()
      server.close()
    }
  }

  private suspend fun arrive(network: TorrentNetwork, listener: TorrentListener): Arrival {
    val client = network.connect(listener.local)
    return Arrival(client, listener.accept())
  }

  @Test
  fun privateOwnerRejectsHostsTrackersDidNotReturn() = runTest {
    loopback {
      val cases = listOf(document(privateTorrent = true) to TorrentDiscoveryPrivacy.PUBLIC,
        document() to TorrentDiscoveryPrivacy.TRACKER_ONLY)
      for ((document, privacy) in cases) {
        restricted(document, privacy) { session, sink, network, _ ->
          val listener = network.listen(PeerEndpoint("127.0.0.1", 0))
          try {
            // The trackers returned 127.0.0.2 only; a peer from 127.0.0.1 is closed unanswered.
            val stranger = arrive(network, listener)
            try { assertFalse(session.accept(stranger.server)) } finally { stranger.close() }
            sink.trackerPeers(TrackerTopic.V2(document.info.hash),
              listOf(PeerEndpoint("127.0.0.1", 6881)))
            val known = arrive(network, listener)
            try {
              assertTrue(session.accept(known.server))
              PeerIdentityHandshake(document.identity).initiate(known.client,
                PeerIdentityHandshake.Mode.V2, peerId(1), handshakes)
              serve(PeerWire(known.client, pieceCount = 1))
              session.state.first { it == TorrentSessionState.FINISHED }
            } finally { known.close() }
          } finally { listener.close() }
        }
      }
    }
  }

  @Test
  fun fullOwnerClosesPeersThatDialItUnanswered() = runTest {
    loopback {
      val document = document()
      restricted(document, TorrentDiscoveryPrivacy.PUBLIC, maxPeers = 1) { session, _, network, _ ->
        val listener = network.listen(PeerEndpoint("127.0.0.1", 0))
        try {
          val first = arrive(network, listener)
          try {
            assertTrue(session.accept(first.server))
            PeerIdentityHandshake(document.identity).initiate(first.client,
              PeerIdentityHandshake.Mode.V2, peerId(1), handshakes)
            val wire = PeerWire(first.client, pieceCount = 1)
            wire.send(PeerMessage.Bitfield(byteArrayOf(128.toByte())))
            wire.readUntil { it == PeerMessage.Control(PeerMessage.Signal.INTERESTED) }
            // The only place is taken. More peers dial in than the responders and the handoff
            // hold, and none of them hears our handshake: each is closed instead of left waiting.
            repeat(8) { index ->
              val extra = arrive(network, listener)
              try {
                // Refused, the engine closes it, as the route does.
                if (!session.accept(extra.server)) extra.server.close()
                val answer = runCatching {
                  withTimeout(5_000) {
                    PeerIdentityHandshake(document.identity).initiate(extra.client,
                      PeerIdentityHandshake.Mode.V2, peerId(index + 2), handshakes)
                  }
                }
                val failure = assertNotNull(answer.exceptionOrNull(), "Peer $index was answered")
                assertFalse(failure is TimeoutCancellationException, "Peer $index was kept open")
              } finally { extra.close() }
            }
            // The peer in the pool carries on.
            serve(wire, announce = false)
            session.state.first { it == TorrentSessionState.FINISHED }
          } finally { first.close() }
        } finally { listener.close() }
      }
    }
  }

  /** Holds the cancellation of the peer's reads until [release], as a slow socket would. */
  private class Held(private val delegate: TorrentConnection) : TorrentConnection by delegate {
    val canceling = CompletableDeferred<Unit>()
    val release = CompletableDeferred<Unit>()
    val closed = CompletableDeferred<Unit>()

    override suspend fun readExactly(size: Int): ByteArray {
      try {
        return delegate.readExactly(size)
      } catch (error: Throwable) {
        // Stopping the peer cancels its reader and closes the socket; hold either way.
        if (!currentCoroutineContext().isActive) withContext(NonCancellable) {
          canceling.complete(Unit)
          release.await()
        }
        throw error
      }
    }

    override fun close() {
      closed.complete(Unit)
      delegate.close()
    }
  }

  @Test
  fun privateResetRevokesHostsBeforeDroppingPeersWithoutRestartingThePool() = runTest {
    loopback {
      val document = document(privateTorrent = true)
      val topic = TrackerTopic.V2(document.info.hash)
      restricted(document, TorrentDiscoveryPrivacy.PUBLIC) { session, sink, network, starts ->
        val listener = network.listen(PeerEndpoint("127.0.0.1", 0))
        var held: Held? = null
        try {
          sink.trackerPeers(topic, listOf(PeerEndpoint("127.0.0.1", 6881)))
          val old = arrive(network, listener)
          try {
            val peer = Held(old.server)
            held = peer
            assertTrue(session.accept(peer))
            PeerIdentityHandshake(document.identity).initiate(old.client,
              PeerIdentityHandshake.Mode.V2, peerId(1), handshakes)
            val wire = PeerWire(old.client, pieceCount = 1)
            wire.send(PeerMessage.Bitfield(byteArrayOf(128.toByte())))
            wire.readUntil { it == PeerMessage.Control(PeerMessage.Signal.INTERESTED) }
            val reset = async { sink.reset() }
            peer.canceling.await()
            assertFalse(reset.isCompleted)
            // The hosts are revoked before the old peer has finished closing.
            val stale = arrive(network, listener)
            try { assertFalse(session.accept(stale.server)) } finally { stale.close() }
            peer.release.complete(Unit)
            reset.await()
            assertTrue(peer.closed.isCompleted)
            assertClosedByOwner(old.client)
          } finally { old.close() }
          val stillStale = arrive(network, listener)
          try { assertFalse(session.accept(stillStale.server)) } finally { stillStale.close() }
          // The new tracker's peers join the same transfer: discovery never restarted.
          sink.trackerPeers(topic, listOf(PeerEndpoint("127.0.0.1", 6881)))
          val fresh = arrive(network, listener)
          try {
            assertTrue(session.accept(fresh.server))
            PeerIdentityHandshake(document.identity).initiate(fresh.client,
              PeerIdentityHandshake.Mode.V2, peerId(2), handshakes)
            serve(PeerWire(fresh.client, pieceCount = 1))
            session.state.first { it == TorrentSessionState.FINISHED }
          } finally { fresh.close() }
          assertEquals(1, starts())
        } finally {
          held?.release?.complete(Unit)
          listener.close()
        }
      }
    }
  }
}
