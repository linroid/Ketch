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
