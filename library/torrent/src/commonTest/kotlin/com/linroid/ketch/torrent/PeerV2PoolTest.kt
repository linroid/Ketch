package com.linroid.ketch.torrent

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.SendChannel
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import okio.Buffer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class PeerV2PoolTest {
  private val info = TorrentV2Info.parse(Bencode.encode(mapOf("meta version" to 2L,
    "piece length" to 16_384L, "file tree" to mapOf("a" to mapOf("" to mapOf(
      "length" to 3L, "pieces root" to sha256Digest(byteArrayOf(1, 2, 3))))))))
  private val layout = TorrentContentLayout.from(info)

  private class Connection : TorrentConnection {
    override val remote = PeerEndpoint("127.0.0.1", 1)
    val input = Channel<ByteArray>(Channel.UNLIMITED)
    private val bytes = Buffer()
    var closed = false
    var readers = 0
    override suspend fun readExactly(size: Int): ByteArray {
      readers++
      try {
        while (bytes.size < size) bytes.write(input.receive())
        return bytes.readByteArray(size.toLong())
      } finally { readers-- }
    }
    override suspend fun write(bytes: ByteArray) {
      check(!closed)
      val buffer = Buffer().write(bytes)
      val size = buffer.readInt()
      val message = PeerWire.decode(buffer.readByteArray(size.toLong()))
      if (message is PeerMessage.Request) add(PeerMessage.Piece(0, 0, byteArrayOf(1, 2, 3)))
    }
    fun add(message: PeerMessage) {
      check(input.trySend(PeerWire.encode(message, pieceCount = 1)).isSuccess)
    }
    override fun close() {
      closed = true
      input.cancel()
    }
  }

  private inner class Fixture(ready: Boolean = true, clock: () -> Long) {
    val buffers = TorrentBufferBudget(200_000)
    val connection = Connection()
    val transport = PeerHashTransport(connection, PeerHashExchange(buffers, { null }), buffers,
      pieceCount = 1)
    val blocks = PeerBlockExchange(layout, transport, buffers, clock = clock)
    init {
      if (ready) {
        connection.add(PeerMessage.Bitfield(byteArrayOf(128.toByte())))
        connection.add(PeerMessage.Control(PeerMessage.Signal.UNCHOKE))
      }
    }
    fun checkClosed() {
      assertTrue(connection.closed)
      assertEquals(0, connection.readers)
      assertEquals(0, buffers.allocated)
    }
  }

  @Test
  fun stopBeforeStartupJoinsBeforeTerminalAndCapacityReturnsOnlyAfterRetirement() = runTest {
    val state = TorrentBufferBudget(2_000_000)
    val first = Fixture { testScheduler.currentTime }
    val second = Fixture { testScheduler.currentTime }
    PeerV2Pool.run(state, maxPeers = 1) { pool ->
      val peer = assertNotNull(pool.attach(first.transport, first.blocks))
      assertNull(pool.attach(second.transport, second.blocks))
      assertFalse(second.connection.closed)
      assertTrue(pool.stop(peer))
      var terminal: PeerV2Pool.Event.Closed? = null
      while (terminal == null) {
        val event = pool.events.receive()
        if (event is PeerV2Pool.Event.Closed) terminal = event
        event.close()
      }
      first.checkClosed()
      assertEquals(1, pool.size)
      assertNull(pool.attach(second.transport, second.blocks))
      assertTrue(pool.retire(terminal))
      val replacement = assertNotNull(pool.attach(second.transport, second.blocks))
      assertFalse(pool.retire(terminal))
      assertFalse(pool.stop(peer))
      assertEquals(1, pool.size)
      assertSame(second.blocks, replacement.blocks)
    }
    first.checkClosed()
    second.checkClosed()
    assertEquals(0, state.allocated)
  }

  @Test
  fun malformedPeerIsIsolatedWhileAnotherDeliversOrderedAcknowledgementAndPayload() = runTest {
    val state = TorrentBufferBudget(2_000_000)
    val bad = Fixture(ready = false) { testScheduler.currentTime }
    bad.connection.input.send(Buffer().writeInt(Int.MAX_VALUE).readByteArray())
    val good = Fixture { testScheduler.currentTime }
    val scheduler = assertNotNull(TorrentV2PieceScheduler.create(layout, emptySet(),
      BooleanArray(1), good.buffers, state))
    try {
      PeerV2Pool.run(state, maxPeers = 2, capacity = 1) { pool ->
        val broken = assertNotNull(pool.attach(bad.transport, bad.blocks))
        val peer = assertNotNull(pool.attach(good.transport, good.blocks))
        var commands: SendChannel<PeerV2DownloadActor.Command>? = null
        var updates = 0
        var retired = false
        while (commands == null || updates < 2 || !retired) {
          val event = pool.events.receive()
          try {
            when (event) {
              is PeerV2Pool.Event.Ready -> if (event.peer === peer) commands = event.commands
              is PeerV2Pool.Event.Message -> if (event.peer === peer) {
                assertIs<PeerV2DownloadActor.Event.Update>(event.value)
                updates++
              }
              is PeerV2Pool.Event.Closed -> {
                assertSame(broken, event.peer)
                assertIs<IllegalArgumentException>(event.cause)
                bad.checkClosed()
                retired = pool.retire(event)
              }
            }
          } finally { event.close() }
        }
        assertFalse(good.connection.closed)
        assertTrue(scheduler.begin(0))
        val plan = assertNotNull(scheduler.planNext(good.blocks) { true })
        assertNotNull(commands).send(PeerV2DownloadActor.Command.Request(plan))
        val ack = assertIs<PeerV2Pool.Event.Message>(pool.events.receive())
        try {
          assertSame(peer, ack.peer)
          val requested = assertIs<PeerV2DownloadActor.Event.Requested>(ack.value)
          assertTrue(scheduler.resolve(requested.plan, requested.ticket))
        } finally { ack.close() }
        val response = assertIs<PeerV2Pool.Event.Message>(pool.events.receive())
        try {
          assertSame(peer, response.peer)
          val block = assertIs<PeerV2DownloadActor.Event.Response>(response.value)
          assertTrue(scheduler.receive(good.blocks, block.value))
        } finally { response.close() }
      }
    } finally {
      scheduler.close()
    }
    bad.checkClosed()
    good.checkClosed()
    assertEquals(0, state.allocated)
  }

  @Test
  fun childAdmissionFailureStillClosesTheTransferredConnectionBeforeTerminal() = runTest {
    val state = TorrentBufferBudget(20_000)
    val f = Fixture { testScheduler.currentTime }
    PeerV2Pool.run(state, maxPeers = 1, capacity = 1) { pool ->
      assertNotNull(pool.attach(f.transport, f.blocks))
      val terminal = assertIs<PeerV2Pool.Event.Closed>(pool.events.receive())
      assertIs<IllegalStateException>(terminal.cause)
      f.checkClosed()
      assertTrue(pool.retire(terminal))
      assertEquals(0, pool.size)
    }
    assertEquals(0, state.allocated)
  }

  @Test
  fun shutdownReclaimsBothQueuesAndForwardersButLeavesDeliveredFramesConsumerOwned() = runTest {
    val state = TorrentBufferBudget(2_000_000)
    val f = Fixture { testScheduler.currentTime }
    repeat(30) { f.connection.add(PeerMessage.KeepAlive) }
    var delivered: PeerV2Pool.Event.Message? = null
    try {
      PeerV2Pool.run(state, maxPeers = 1, capacity = 1) { pool ->
        assertNotNull(pool.attach(f.transport, f.blocks))
        assertIs<PeerV2Pool.Event.Ready>(pool.events.receive())
        delivered = assertIs<PeerV2Pool.Event.Message>(pool.events.receive())
        runCurrent()
        assertTrue(f.buffers.allocated > 1000)
      }
      assertTrue(f.connection.closed)
      assertEquals(0, f.connection.readers)
      assertEquals(0, state.allocated)
      assertEquals(520, f.buffers.allocated)
    } finally { delivered?.close() }
    f.checkClosed()
  }
}
