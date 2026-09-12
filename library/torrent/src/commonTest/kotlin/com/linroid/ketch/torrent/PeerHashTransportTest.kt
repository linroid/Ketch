package com.linroid.ketch.torrent

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.test.runTest
import okio.Buffer
import okio.ByteString.Companion.toByteString
import okio.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PeerHashTransportTest {
  private val hashes = (sha256Digest(ByteArray(16_384)) + sha256Digest(byteArrayOf(1)))
    .toByteString()
  private val root = sha256Digest(hashes.toByteArray()).toByteString()
  private val selector = PeerHashSelector(root, 0, 0, 2, 0)
  private fun exchange(budget: TorrentBufferBudget) =
    PeerHashExchange(budget, { if (it == root) 16_385L else null })

  private class Connection(val incoming: Buffer = Buffer()) : TorrentConnection {
    override val remote = PeerEndpoint("127.0.0.1", 1)
    val outgoing = Buffer()
    val sizes = mutableListOf<Int>()
    var closed = false
    var failWrite = false
    var writeDelay = 0L
    var afterWrite: () -> Unit = {}
    var bodyDelay = 0L
    override suspend fun readExactly(size: Int): ByteArray {
      sizes += size
      if (sizes.size > 2) delay(bodyDelay)
      return incoming.readByteArray(size.toLong())
    }
    override suspend fun write(bytes: ByteArray) {
      delay(writeDelay)
      if (failWrite) throw IOException("Injected partial write failure")
      outgoing.write(bytes)
      afterWrite()
    }
    override fun close() { closed = true }
  }

  @Test
  fun sendsRequestsAndDispatchesVerifiedHashesWithIndependentFrameAndResultCredit() = runTest {
    val frames = TorrentBufferBudget(65_536)
    val hashesBudget = TorrentBufferBudget(32_768)
    val connection = Connection()
    val transport = PeerHashTransport(connection, exchange(hashesBudget), frames)
    assertNotNull(transport.request(selector))
    val sentSize = connection.outgoing.readInt()
    val sent = assertIs<PeerMessage.Unknown>(PeerWire.decode(
      connection.outgoing.readByteArray(sentSize.toLong())))
    assertEquals(PeerHashMessage.Request(selector), PeerHashWire.decode(sent))
    assertEquals(0, frames.allocated)
    connection.incoming.write(PeerWire.encode(PeerMessage.Control(PeerMessage.Signal.CHOKE)))
    val choke = transport.read()
    assertNull(transport.accept(choke))
    choke.close()
    connection.incoming.write(PeerWire.encode(PeerHashWire.encode(
      PeerHashMessage.Hashes(selector, hashes))))
    val frame = transport.read()
    assertTrue(frames.allocated > 0)
    val verified = assertIs<PeerHashTransport.Event.Verified>(transport.accept(frame)).result
    frame.close()
    assertEquals(0, frames.allocated)
    assertEquals(hashes, verified.hashes)
    transport.close()
    assertTrue(hashesBudget.allocated > 0)
    verified.close()
    assertEquals(0, hashesBudget.allocated)
  }

  @Test
  fun exchangesAndAuthenticatesHashFramesOverLoopbackTcp() = runTest {
    withContext(Dispatchers.Default) {
      withTimeout(10_000) {
        val network = createTorrentNetwork()
        val listener = network.listen(PeerEndpoint("127.0.0.1", 0))
        val server = async {
          val connection = listener.accept()
          val serverBudget = TorrentBufferBudget(65_536)
          val wire = PeerHashTransport(connection, exchange(serverBudget), serverBudget)
          try {
            val frame = wire.read()
            try {
              val request = assertIs<PeerHashTransport.Event.Request>(wire.accept(frame))
              assertEquals(PeerHashMessage.Request(selector), request.request)
              wire.respond(PeerHashMessage.Hashes(selector, hashes))
            } finally { frame.close() }
          } finally {
            wire.close()
            assertEquals(0, serverBudget.allocated)
          }
        }
        var transport: PeerHashTransport? = null
        val frames = TorrentBufferBudget(65_536)
        val hashesBudget = TorrentBufferBudget(32_768)
        try {
          val client = PeerHashTransport(network.connect(listener.local),
            exchange(hashesBudget), frames)
          transport = client
          assertNotNull(client.request(selector))
          val frame = client.read()
          try {
            val result = assertIs<PeerHashTransport.Event.Verified>(client.accept(frame)).result
            try { assertEquals(hashes, result.hashes) } finally { result.close() }
          } finally { frame.close() }
          server.await()
          assertEquals(0, frames.allocated)
          assertEquals(0, hashesBudget.allocated)
        } finally {
          transport?.close()
          listener.close()
          network.close()
          server.cancel()
        }
      }
    }
  }

  @Test
  fun rejectsOversizedOrUnadmittedBodiesBeforeReadingThem() = runTest {
    for (size in listOf(Int.MAX_VALUE, 128)) {
      val connection = Connection(Buffer().writeInt(size).writeByte(0))
      val budget = TorrentBufferBudget(600)
      val transport = PeerHashTransport(connection, exchange(TorrentBufferBudget(32_768)), budget)
      if (size == Int.MAX_VALUE) {
        assertFailsWith<IllegalArgumentException> { transport.read() }
      } else {
        assertFailsWith<IllegalStateException> { transport.read() }
      }
      assertEquals(if (size == Int.MAX_VALUE) listOf(4) else listOf(4, 1), connection.sizes)
      assertTrue(connection.closed)
      assertEquals(0, budget.allocated)
      transport.close()
    }
  }

  @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
  @Test
  fun backpressuredWritesCannotReturnAnExpiredExchangeTicket() = runTest {
    val frames = TorrentBufferBudget(65_536)
    val hashesBudget = TorrentBufferBudget(32_768)
    val connection = Connection().also { it.writeDelay = 20 }
    val exchange = PeerHashExchange(hashesBudget, { if (it == root) 16_385L else null },
      timeoutMs = 10, clock = { testScheduler.currentTime })
    val transport = PeerHashTransport(connection, exchange, frames, timeoutMs = 100)
    assertFailsWith<kotlinx.coroutines.TimeoutCancellationException> {
      transport.request(selector)
    }
    assertEquals(10L, testScheduler.currentTime)
    assertTrue(connection.closed)
    assertEquals(0, frames.allocated)
    assertEquals(0, hashesBudget.allocated)

    var now = 0L
    val late = Connection().also { it.afterWrite = { now = 10 } }
    val nextExchange = PeerHashExchange(hashesBudget, { if (it == root) 16_385L else null },
      timeoutMs = 10, clock = { now })
    val next = PeerHashTransport(late, nextExchange, frames, timeoutMs = 100)
    assertFailsWith<IllegalStateException> { next.request(selector) }
    assertTrue(late.closed)
    assertEquals(0, frames.allocated)
    assertEquals(0, hashesBudget.allocated)
  }

  @Test
  fun failedWritesAndTimedOutBodyReadsReleaseCreditAndCloseTheStream() = runTest {
    val frames = TorrentBufferBudget(65_536)
    val hashesBudget = TorrentBufferBudget(32_768)
    val failed = Connection().also { it.failWrite = true }
    val writer = PeerHashTransport(failed, exchange(hashesBudget), frames)
    assertFailsWith<IOException> { writer.request(selector) }
    assertTrue(failed.closed)
    assertEquals(0, frames.allocated)
    assertEquals(0, hashesBudget.allocated)
    val slow = Connection(Buffer().writeInt(1).writeByte(0)).also { it.bodyDelay = 20 }
    val reader = PeerHashTransport(slow, exchange(hashesBudget), frames, timeoutMs = 10)
    assertFailsWith<kotlinx.coroutines.TimeoutCancellationException> { reader.read() }
    assertTrue(slow.closed)
    assertEquals(0, frames.allocated)
    reader.close()
  }
}
