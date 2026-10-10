package com.linroid.ketch.torrent

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import okio.ByteString.Companion.toByteString
import okio.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class PeerV2DialerTest {
  private val document = TorrentV2Document.parse(Bencode.encode(mapOf("info" to mapOf(
    "meta version" to 2L, "piece length" to 16_384L, "file tree" to mapOf(
      "a" to mapOf("" to mapOf("length" to 1L, "pieces root" to sha256Digest(byteArrayOf(1)))))
  ), "piece layers" to emptyMap<String, Any>())))
  private val layout = TorrentContentLayout.from(document.info)

  private class Connection(override val remote: PeerEndpoint) : TorrentConnection {
    var closed = false
    private var reply = ByteArray(0)
    override suspend fun write(bytes: ByteArray) {
      reply = bytes.copyOf()
      ByteArray(20) { 2 }.copyInto(reply, 48)
    }
    override suspend fun readExactly(size: Int): ByteArray = reply
    override fun close() { closed = true }
  }

  private inner class Fixture {
    val state = TorrentBufferBudget(2_000_000)
    val buffers = TorrentBufferBudget(200_000)
    val sockets = mutableListOf<Connection>()
    suspend fun connect(endpoint: PeerEndpoint): PeerV2Connector.Connected? {
      val socket = Connection(endpoint)
      sockets += socket
      val network = object : TorrentNetwork {
        override suspend fun connect(remote: PeerEndpoint): TorrentConnection = socket
        override suspend fun listen(local: PeerEndpoint): TorrentListener = error("Unused")
        override suspend fun bindUdp(local: PeerEndpoint): TorrentDatagramSocket = error("Unused")
        override fun close() = Unit
      }
      return PeerV2Connector.connect(network, endpoint, document, layout,
        ByteArray(20) { 1 }.toByteString(), buffers, state)
    }
    fun released() {
      assertTrue(sockets.all { it.closed })
      assertEquals(0, state.allocated)
      assertEquals(0, buffers.allocated)
    }
  }

  /** A peer that dialed us: its handshake waits to be read. */
  private inner class Dialing(port: Int) : TorrentConnection {
    override val remote = PeerEndpoint("127.0.0.1", port)
    var closed = false
    private val handshake = PeerWire.encodeHandshake(PeerHandshake(
      InfoHash.fromBytes(document.info.hash.wireBytes()), ByteArray(20) { 3 }, false, false))
    override suspend fun readExactly(size: Int): ByteArray =
      if (size == 68) handshake.copyOf() else awaitCancellation()
    override suspend fun write(bytes: ByteArray) = Unit
    override fun close() { closed = true }
  }

  private fun endpoints(count: Int): Channel<PeerEndpoint> = Channel<PeerEndpoint>(count).also {
    repeat(count) { index -> check(it.trySend(PeerEndpoint("127.0.0.1", index + 1)).isSuccess) }
    it.close()
  }

  @Test
  fun boundsConcurrentDialsAndJoinsThemOnCancellation() = runTest {
    val state = TorrentBufferBudget(100_000)
    val input = endpoints(5)
    var active = 0
    var started = 0
    PeerV2Dialer.run(input, state, parallelism = 2, connect = {
      active++
      started++
      try { awaitCancellation() } finally { active-- }
    }) {
      runCurrent()
      assertEquals(2, active)
      assertEquals(2, started)
    }
    assertEquals(0, active)
    assertEquals(0, state.allocated)
    input.cancel()
  }

  @Test
  fun everyFailureReachesItsConsumerThroughAFullQueue() = runTest {
    val state = TorrentBufferBudget(100_000)
    // Room for one: the session counts every dial it queued, so none may be dropped.
    val failures = Channel<PeerV2Dialer.Failure<PeerEndpoint>>(1)
    PeerV2Dialer.run(endpoints(5), state, parallelism = 2,
      connect = { throw IOException("Network is unreachable") }, failures = failures) {
      val ports = List(5) {
        runCurrent()
        assertNotNull(failures.tryReceive().getOrNull()).endpoint.port
      }
      assertEquals((1..5).toSet(), ports.toSet())
    }
    assertEquals(0, state.allocated)
    failures.cancel()
  }

  @Test
  fun retriesAdmissionWithoutDroppingTheEndpointOrSpinning() = runTest {
    val f = Fixture()
    var attempts = 0
    PeerV2Dialer.run(endpoints(1), f.state, parallelism = 1, connect = {
      attempts++
      if (attempts == 1) null else f.connect(it)
    }) { dialer ->
      runCurrent()
      assertEquals(1, attempts)
      advanceTimeBy(249)
      runCurrent()
      assertEquals(1, attempts)
      advanceTimeBy(1)
      val connected = dialer.connections.receive()
      assertEquals(2, attempts)
      connected.close()
    }
    f.released()
  }

  @Test
  fun peerTimeoutIsRecordedAndDoesNotPreventTryingTheNextEndpoint() = runTest {
    val f = Fixture()
    PeerV2Dialer.run(endpoints(2), f.state, parallelism = 1, connect = {
      if (it.port == 1) withTimeout(5) { awaitCancellation() }
      f.connect(it)
    }) { dialer ->
      dialer.connections.receive().close()
      val failure = assertNotNull(dialer.lastFailure.value)
      assertEquals(1, failure.endpoint.port)
      assertIs<TimeoutCancellationException>(failure.cause)
      assertTrue(dialer.connections.receiveCatching().isClosed)
    }
    assertEquals(5L, testScheduler.currentTime)
    f.released()
  }

  @Test
  fun shutdownClosesQueuedAndBlockedSenderConnections() = runTest {
    val f = Fixture()
    val ready = CompletableDeferred<Unit>()
    var connected = 0
    PeerV2Dialer.run(endpoints(3), f.state, parallelism = 3, capacity = 1, connect = {
      val handle = f.connect(it)
      if (++connected == 3) ready.complete(Unit)
      handle
    }) {
      ready.await()
      runCurrent()
      assertEquals(3, f.sockets.size)
      assertTrue(f.sockets.none { it.closed })
    }
    f.released()
  }

  @Test
  fun discoveryFailureEndsAdmissionRetries() = runTest {
    val f = Fixture()
    val input = Channel<PeerEndpoint>(1)
    input.send(PeerEndpoint("127.0.0.1", 1))
    var attempts = 0
    PeerV2Dialer.run(input, f.state, parallelism = 1, connect = {
      attempts++
      null
    }) { dialer ->
      runCurrent()
      assertEquals(1, attempts)
      input.close(IOException("Discovery failed during admission"))
      val result = withTimeout(1000) { dialer.connections.receiveCatching() }
      assertIs<IOException>(result.exceptionOrNull())
      assertEquals("Discovery failed during admission", result.exceptionOrNull()?.message)
      assertEquals(1, attempts)
    }
    f.released()
  }

  @Test
  fun respondWorkersShareOutputAndCloseRefusedConnections() = runTest {
    val f = Fixture()
    val answered = Dialing(50_000)
    val refused = Dialing(50_001)
    val failing = Dialing(50_002)
    val incoming = Channel<PeerV2Dialer.Incoming>(4)
    incoming.send(PeerV2Dialer.Incoming(refused, 0))
    incoming.send(PeerV2Dialer.Incoming(failing, 0))
    incoming.send(PeerV2Dialer.Incoming(answered, 3))
    val failures = Channel<PeerV2Dialer.Failure<PeerEndpoint>>(4)
    PeerV2Dialer.run(endpoints(1), f.state, parallelism = 1, connect = { f.connect(it) },
      incoming = incoming, respondParallelism = 2, failures = failures,
      respond = { peer ->
        when (peer.connection) {
          refused -> null
          failing -> throw IOException("Handshake failed")
          else -> PeerV2Connector.respond(peer.connection, document, layout,
            ByteArray(20) { 1 }.toByteString(), f.buffers, f.state, generation = peer.generation)
        }
      },
    ) { dialer ->
      // Dialed and answered peers arrive on the same output.
      val connected = List(2) { dialer.connections.receive() }
      val answer = connected.single { it.info.origin is PeerV2Origin.Incoming }
      assertEquals(PeerV2Origin.Incoming(answered.remote), answer.info.origin)
      assertEquals(3L, answer.generation)
      assertIs<PeerV2Origin.Outgoing>(connected.single { it !== answer }.info.origin)
      runCurrent()
      assertTrue(refused.closed)
      assertTrue(failing.closed)
      assertFalse(answered.closed)
      connected.forEach { it.close() }
      assertTrue(answered.closed)
      // Responders never retry or report: the peer that dialed us can dial again.
      assertTrue(failures.tryReceive().isFailure)
    }
    incoming.cancel()
    f.released()
  }

  @Test
  fun answeredPeerTheConsumerDoesNotTakeIsClosedAfterTheHandoff() = runTest {
    val f = Fixture()
    val peers = List(2) { Dialing(50_000 + it) }
    val incoming = Channel<PeerV2Dialer.Incoming>(2)
    peers.forEach { incoming.send(PeerV2Dialer.Incoming(it, 0)) }
    PeerV2Dialer.run(endpoints(0), f.state, parallelism = 1, connect = { f.connect(it) },
      incoming = incoming, respondParallelism = 2, handoffMs = 1_000,
      respond = { peer ->
        PeerV2Connector.respond(peer.connection, document, layout,
          ByteArray(20) { 1 }.toByteString(), f.buffers, f.state, generation = peer.generation)
      },
    ) { dialer ->
      // Both are answered; one waits in the output while the other waits to be handed over.
      runCurrent()
      assertTrue(peers.none { it.closed })
      advanceTimeBy(999)
      runCurrent()
      assertTrue(peers.none { it.closed })
      // The consumer, such as a full pool, took neither in time: the waiting one is closed.
      advanceTimeBy(2)
      runCurrent()
      assertEquals(1, peers.count { it.closed })
      val taken = dialer.connections.receive()
      val open = peers.single { !it.closed }
      assertEquals(PeerV2Origin.Incoming(open.remote), taken.info.origin)
      taken.close()
      assertTrue(open.closed)
    }
    incoming.cancel()
    f.released()
  }

  @Test
  fun endpointStreamFailureFollowsAlreadyQueuedConnections() = runTest {
    val f = Fixture()
    val input = Channel<PeerEndpoint>(1)
    input.send(PeerEndpoint("127.0.0.1", 1))
    input.close(IOException("Discovery failed"))
    PeerV2Dialer.run(input, f.state, parallelism = 1, connect = { f.connect(it) }) { dialer ->
      dialer.connections.receive().close()
      val error = dialer.connections.receiveCatching().exceptionOrNull()
      assertIs<IOException>(error)
      assertEquals("Discovery failed", error.message)
    }
    f.released()
  }
}
