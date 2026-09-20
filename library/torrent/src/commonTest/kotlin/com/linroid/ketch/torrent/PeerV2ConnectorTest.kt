package com.linroid.ketch.torrent

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import okio.ByteString.Companion.toByteString
import okio.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
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
      assertEquals(baseline + 1025, state.allocated)
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
