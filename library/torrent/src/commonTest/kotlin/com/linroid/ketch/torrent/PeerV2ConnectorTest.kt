package com.linroid.ketch.torrent

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import okio.Buffer
import okio.ByteString.Companion.toByteString
import okio.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class PeerV2ConnectorTest {
  private fun document(name: String = "a") = TorrentV2Document.parse(Bencode.encode(mapOf(
    "info" to mapOf("meta version" to 2L, "piece length" to 16_384L,
      "file tree" to mapOf(name to mapOf("" to mapOf("length" to 1L,
        "pieces root" to sha256Digest(byteArrayOf(1)))))),
    "piece layers" to emptyMap<String, Any>())))
  private val document = document()
  private val layout = TorrentContentLayout.from(document.info)
  private val endpoint = PeerEndpoint("127.0.0.1", 1)
  private val peerId = ByteArray(20) { 1 }.toByteString()
  private val serverId = ByteArray(20) { 2 }.toByteString()

  private inner class Network : TorrentNetwork {
    var attempts = 0
    var fail = false
    var cancelOnReturn = false
    var wrongSwarm = false
    var closed = false
    var readers = 0
    private var reply: ByteArray? = null
    val connection = object : TorrentConnection {
      override val remote = endpoint
      override suspend fun write(bytes: ByteArray) {
        check(!closed)
        reply = bytes.copyOf().also {
          serverId.toByteArray().copyInto(it, 48)
          if (wrongSwarm) it[28] = (it[28].toInt() xor 1).toByte()
        }
      }
      override suspend fun readExactly(size: Int): ByteArray {
        readers++
        try {
          if (size == 68) return assertNotNull(reply)
          awaitCancellation()
        } finally { readers-- }
      }
      override fun close() { closed = true }
    }
    override suspend fun connect(remote: PeerEndpoint): TorrentConnection {
      attempts++
      if (fail) throw IOException("Connect failed")
      if (cancelOnReturn) currentCoroutineContext().cancel()
      return connection
    }
    override suspend fun listen(local: PeerEndpoint): TorrentListener = error("Unused")
    override suspend fun bindUdp(local: PeerEndpoint): TorrentDatagramSocket = error("Unused")
    override fun close() = Unit
  }

  /** A peer that dialed us: its handshake waits to be read. */
  private class Dialing : TorrentConnection {
    override val remote = PeerEndpoint("127.0.0.1", 51_000)
    var closed = false
    var reply: ByteArray? = null
    var handshake: ByteArray = ByteArray(0)
    override suspend fun readExactly(size: Int): ByteArray =
      if (size == 68) handshake.copyOf() else awaitCancellation()
    override suspend fun write(bytes: ByteArray) { reply = bytes.copyOf() }
    override fun close() { closed = true }
  }

  private fun dialing() = Dialing().also {
    it.handshake = PeerWire.encodeHandshake(PeerHandshake(
      InfoHash.fromBytes(document.info.hash.wireBytes()), serverId.toByteArray(), false, false))
  }

  @Test
  fun connectedInfoCarriesTheHandshakenSocket() = runTest {
    val network = Network()
    val buffers = TorrentBufferBudget(10_000)
    val state = TorrentBufferBudget(10_000)
    val outgoing = assertNotNull(PeerV2Connector.connect(network, endpoint, document, layout,
      peerId, buffers, state, generation = 7))
    assertSame(network.connection, outgoing.info.link)
    assertEquals(PeerV2Origin.Outgoing(endpoint), outgoing.info.origin)
    assertEquals(serverId, outgoing.info.peerId)
    assertEquals(PeerIdentityHandshake.Mode.V2, outgoing.info.mode)
    assertFalse(outgoing.info.extensions)
    assertEquals(7L, outgoing.generation)
    outgoing.close()
    assertTrue(network.closed)
    // A peer that dialed us is described by the socket it arrived on.
    val socket = dialing()
    val incoming = assertNotNull(PeerV2Connector.respond(socket, document, layout, peerId, buffers,
      state, generation = 3))
    assertSame(socket, incoming.info.link)
    assertEquals(PeerV2Origin.Incoming(socket.remote), incoming.info.origin)
    assertEquals(serverId, incoming.info.peerId)
    assertEquals(3L, incoming.generation)
    // Our answer names the same swarm and our own peer ID.
    val reply = assertNotNull(socket.reply)
    assertEquals(peerId, reply.copyOfRange(48, 68).toByteString())
    incoming.close()
    assertTrue(socket.closed)
    // Without availability admission the answer never starts, and the socket is closed.
    val refused = dialing()
    assertNull(PeerV2Connector.respond(refused, document, layout, peerId, buffers,
      TorrentBufferBudget(1)))
    assertTrue(refused.closed)
    assertNull(refused.reply)
    // A handshake for another swarm is refused and closed too.
    val stranger = Dialing().also {
      it.handshake = PeerWire.encodeHandshake(PeerHandshake(
        InfoHash.fromBytes(torrentRandomBytes(20)), serverId.toByteArray(), false, false))
    }
    assertFailsWith<IllegalArgumentException> {
      PeerV2Connector.respond(stranger, document, layout, peerId, buffers, state)
    }
    assertTrue(stranger.closed)
    assertEquals(0, state.allocated)
    assertEquals(0, buffers.allocated)
  }

  /** A peer that dialed us and already sent its handshake and [frames]; then it goes quiet. */
  private class Scripted(handshake: ByteArray, frames: List<PeerMessage>) : TorrentConnection {
    override val remote = PeerEndpoint("127.0.0.1", 51_001)
    private val input = Buffer().write(handshake).apply {
      frames.forEach { write(PeerWire.encode(it)) }
    }
    var reply: ByteArray? = null
    override suspend fun readExactly(size: Int): ByteArray =
      if (input.size >= size) input.readByteArray(size.toLong()) else awaitCancellation()
    override suspend fun write(bytes: ByteArray) { if (reply == null) reply = bytes.copyOf() }
    override fun close() = Unit
  }

  @Test
  fun hybridV1RouteIgnoresHashMessages() = runTest {
    val hybrid = TorrentV2Fixture.build(listOf("a" to 40_000), hybrid = true)
    val v1 = checkNotNull(hybrid.document.identity.v1)
    val root = checkNotNull(hybrid.document.info.files.single().piecesRoot)
    val request = PeerHashWire.encode(PeerHashMessage.Request(PeerHashSelector(root, 0, 0, 2, 1)))
    val buffers = TorrentBufferBudget(1_000_000)
    val state = TorrentBufferBudget(2_000_000)
    for (tag in listOf(v1.toBytes(), hybrid.document.info.hash.wireBytes())) {
      // A v1 tag without the upgrade bit stays in the v1 swarm; the v2 tag is a v2 peer.
      val legacy = tag.contentEquals(v1.toBytes())
      val socket = Scripted(PeerWire.encodeHandshake(PeerHandshake(InfoHash.fromBytes(tag),
        serverId.toByteArray(), false, false)), listOf(request, PeerMessage.Have(1)))
      val connected = assertNotNull(PeerV2Connector.respond(socket, hybrid.document,
        hybrid.layout, peerId, buffers, state))
      val mode = if (legacy) PeerIdentityHandshake.Mode.V1 else PeerIdentityHandshake.Mode.V2
      assertEquals(mode, connected.route.mode)
      assertEquals(mode, connected.info.mode)
      assertEquals(tag.toByteString(), assertNotNull(socket.reply).copyOfRange(28, 48)
        .toByteString())
      PeerV2Pool.run(state, maxPeers = 1) { pool ->
        val peer = assertNotNull(connected.attach(pool))
        assertIs<PeerV2Pool.Event.Ready>(pool.events.receive())
        val first = assertIs<PeerV2Pool.Event.Message>(pool.events.receive())
        if (legacy) {
          // BEP 52 messages mean nothing in the v1 swarm: no hash request reaches the session.
          val update = assertIs<PeerV2DownloadActor.Event.Update>(first.value)
          assertEquals(21, assertIs<PeerMessage.Unknown>(update.frame.message).id)
        } else {
          val hash = assertIs<PeerV2DownloadActor.Event.Hash>(first.value)
          assertIs<PeerHashTransport.Event.Request>(hash.value)
        }
        first.close()
        val next = assertIs<PeerV2Pool.Event.Message>(pool.events.receive())
        val have = assertIs<PeerV2DownloadActor.Event.Update>(next.value)
        assertEquals(PeerMessage.Have(1), have.frame.message)
        next.close()
        assertTrue(pool.stop(peer))
        assertTrue(pool.retire(assertIs<PeerV2Pool.Event.Closed>(pool.events.receive())))
      }
    }
    assertEquals(0, state.allocated)
    assertEquals(0, buffers.allocated)
  }

  @Test
  fun insufficientAdmissionAndWrongLayoutNeverOpenASocket() = runTest {
    val network = Network()
    val buffers = TorrentBufferBudget(10_000)
    val tiny = TorrentBufferBudget(1)
    assertNull(PeerV2Connector.connect(network, endpoint, document, layout, peerId, buffers, tiny))
    assertEquals(0, network.attempts)
    val other = TorrentContentLayout.from(document("other").info)
    assertFailsWith<IllegalArgumentException> {
      PeerV2Connector.connect(network, endpoint, document, other, peerId, buffers, tiny)
    }
    assertEquals(0, network.attempts)
    assertEquals(0, tiny.allocated)
    assertEquals(0, buffers.allocated)
  }

  @Test
  fun connectAndHandshakeFailuresReleaseAdmissionAndAcquiredSockets() = runTest {
    val buffers = TorrentBufferBudget(10_000)
    val state = TorrentBufferBudget(10_000)
    val failed = Network().also { it.fail = true }
    assertFailsWith<IOException> {
      PeerV2Connector.connect(failed, endpoint, document, layout, peerId, buffers, state)
    }
    assertEquals(0, state.allocated)
    val wrong = Network().also { it.wrongSwarm = true }
    assertFailsWith<IllegalArgumentException> {
      PeerV2Connector.connect(wrong, endpoint, document, layout, peerId, buffers, state)
    }
    assertTrue(wrong.closed)
    assertEquals(0, state.allocated)
    assertEquals(0, buffers.allocated)
  }

  @Test
  fun cancellationAtConnectReturnStillClosesTheReturnedSocket() = runTest {
    val network = Network().also { it.cancelOnReturn = true }
    val buffers = TorrentBufferBudget(10_000)
    val state = TorrentBufferBudget(10_000)
    assertFailsWith<CancellationException> {
      PeerV2Connector.connect(network, endpoint, document, layout, peerId, buffers, state)
    }
    assertTrue(network.closed)
    assertEquals(0, state.allocated)
    assertEquals(0, buffers.allocated)
  }

  @Test
  fun separateHandshakeAdmissionSurvivesPayloadSaturation() = runTest {
    val network = Network()
    val buffers = TorrentBufferBudget(1)
    val payload = assertNotNull(buffers.reserve(1))
    val state = TorrentBufferBudget(10_000)
    val handshakes = TorrentBufferBudget(10_000)
    try {
      val connected = assertNotNull(PeerV2Connector.connect(network, endpoint, document, layout,
        peerId, buffers, state, handshakes = handshakes))
      assertEquals(PeerIdentityHandshake.Mode.V2, connected.route.mode)
      assertEquals(0, handshakes.allocated)
      connected.close()
      assertTrue(network.closed)
      assertEquals(1, buffers.allocated)
      assertEquals(0, state.allocated)
    } finally { payload.close() }
    assertEquals(0, buffers.allocated)
  }

  @Test
  fun transferredAvailabilityStaysAdmittedUntilJoinedRetirement() = runTest {
    val network = Network()
    val buffers = TorrentBufferBudget(200_000)
    val state = TorrentBufferBudget(2_000_000)
    PeerV2Pool.run(state, maxPeers = 1, capacity = 1) { pool ->
      val baseline = state.allocated
      val connected = assertNotNull(PeerV2Connector.connect(network, endpoint, document, layout,
        peerId, buffers, state, expectedPeerId = serverId))
      assertEquals(document.identity, connected.route.identity)
      assertEquals(PeerIdentityHandshake.Mode.V2, connected.route.mode)
      val peer = assertNotNull(connected.attach(pool))
      connected.close()
      assertFalse(network.closed)
      assertIs<PeerV2Pool.Event.Ready>(pool.events.receive())
      runCurrent()
      assertTrue(network.readers > 0)
      assertTrue(pool.stop(peer))
      val terminal = assertIs<PeerV2Pool.Event.Closed>(pool.events.receive())
      assertTrue(network.closed)
      assertEquals(0, network.readers)
      // Availability admission plus the pool's per-peer event slot.
      assertEquals(baseline + 1025 + 8192, state.allocated)
      assertTrue(pool.retire(terminal))
      assertEquals(baseline, state.allocated)
      connected.close()
    }
    assertEquals(0, state.allocated)
    assertEquals(0, buffers.allocated)
  }

  @Test
  fun fullPoolLeavesRejectedHandleOwnedUntilCallerClosesIt() = runTest {
    val buffers = TorrentBufferBudget(200_000)
    val state = TorrentBufferBudget(2_000_000)
    val firstNetwork = Network()
    val rejectedNetwork = Network()
    val first = assertNotNull(PeerV2Connector.connect(firstNetwork, endpoint, document, layout,
      peerId, buffers, state))
    val rejected = assertNotNull(PeerV2Connector.connect(rejectedNetwork, endpoint, document,
      layout, peerId, buffers, state))
    try {
      PeerV2Pool.run(state, maxPeers = 1) { pool ->
        assertNotNull(first.attach(pool))
        assertNull(rejected.attach(pool))
        assertFalse(rejectedNetwork.closed)
        rejected.close()
        assertTrue(rejectedNetwork.closed)
      }
      assertTrue(firstNetwork.closed)
    } finally {
      first.close()
      rejected.close()
    }
    assertEquals(0, state.allocated)
    assertEquals(0, buffers.allocated)
  }
}
