package com.linroid.ketch.torrent

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import okio.Buffer
import okio.ByteString.Companion.toByteString
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class PeerV2DownloadActorTest {
  private val info = TorrentV2Info.parse(Bencode.encode(mapOf("meta version" to 2L,
    "piece length" to 16_384L, "file tree" to mapOf("a" to mapOf("" to mapOf(
      "length" to 3L, "pieces root" to sha256Digest(byteArrayOf(1, 2, 3))))))))
  private val layout = TorrentContentLayout.from(info)

  private class Connection : TorrentConnection {
    override val remote = PeerEndpoint("127.0.0.1", 1)
    private val input = Channel<ByteArray>(Channel.UNLIMITED)
    private val bytes = Buffer()
    var closed = false
    var readers = 0
    var blockWrites: CompletableDeferred<Unit>? = null
    var respond = false
    val writing = CompletableDeferred<Unit>()
    override suspend fun readExactly(size: Int): ByteArray {
      readers++
      try {
        while (bytes.size < size) bytes.write(input.receive())
        return bytes.readByteArray(size.toLong())
      } finally { readers-- }
    }
    override suspend fun write(bytes: ByteArray) {
      check(!closed)
      writing.complete(Unit)
      blockWrites?.await()
      val buffer = Buffer().write(bytes)
      val size = buffer.readInt()
      val request = PeerWire.decode(buffer.readByteArray(size.toLong()))
      if (respond && request is PeerMessage.Request) {
        add(PeerMessage.Piece(0, 0, byteArrayOf(1, 2, 3)))
      }
    }
    fun add(message: PeerMessage) {
      check(input.trySend(PeerWire.encode(message, pieceCount = 1)).isSuccess)
    }
    override fun close() {
      closed = true
      input.cancel()
    }
  }

  private inner class Fixture(clock: () -> Long) {
    val buffers = TorrentBufferBudget(200_000)
    val state = TorrentBufferBudget(2_000_000)
    val connection = Connection()
    val transport = PeerHashTransport(connection, PeerHashExchange(buffers, { 16_385L }), buffers,
      pieceCount = 1)
    val blocks = PeerBlockExchange(layout, transport, buffers, timeoutMs = 100, clock = clock)
    val scheduler = assertNotNull(TorrentV2PieceScheduler.create(layout, emptySet(),
      BooleanArray(1), buffers, state))
    init {
      connection.add(PeerMessage.Bitfield(byteArrayOf(128.toByte())))
      connection.add(PeerMessage.Control(PeerMessage.Signal.UNCHOKE))
      assertTrue(scheduler.begin(0))
    }
    suspend fun ready(actor: PeerV2DownloadActor) {
      repeat(2) { assertIs<PeerV2DownloadActor.Event.Update>(actor.events.receive()).close() }
    }
    fun finish() {
      scheduler.detachPeer(blocks)
      scheduler.close()
      assertTrue(connection.closed)
      assertEquals(0, connection.readers)
      assertEquals(0, state.allocated)
    }
  }

  @Test
  fun acknowledgementPrecedesImmediateResponseAndDeliveredPayloadOutlivesActor() = runTest {
    val f = Fixture { testScheduler.currentTime }
    f.connection.respond = true
    var delivered: PeerV2DownloadActor.Event.Response? = null
    try {
      PeerV2DownloadActor.run(f.transport, f.blocks, f.state) { actor ->
        f.ready(actor)
        val plan = assertNotNull(f.scheduler.planNext(f.blocks) { true })
        actor.commands.send(PeerV2DownloadActor.Command.Request(plan))
        val ack = assertIs<PeerV2DownloadActor.Event.Requested>(actor.events.receive())
        assertSame(plan, ack.plan)
        assertTrue(f.scheduler.resolve(ack.plan, assertNotNull(ack.ticket)))
        delivered = assertIs<PeerV2DownloadActor.Event.Response>(actor.events.receive())
        assertSame(ack.ticket, assertIs<PeerBlockExchange.Response.Block>(delivered.value).ticket)
      }
      f.finish()
      assertEquals(262, f.buffers.allocated)
      assertNotNull(delivered).close()
      assertEquals(0, f.buffers.allocated)
    } finally {
      delivered?.close()
      f.scheduler.close()
    }
  }

  @Test
  fun stalledPeerWriteDoesNotPreventAnotherPeerFromAcknowledgingAndDelivering() = runTest {
    val slow = Fixture { testScheduler.currentTime }
    val fast = Fixture { testScheduler.currentTime }
    slow.connection.blockWrites = CompletableDeferred()
    fast.connection.respond = true
    try {
      PeerV2DownloadActor.run(slow.transport, slow.blocks, slow.state) { slowActor ->
        PeerV2DownloadActor.run(fast.transport, fast.blocks, fast.state) { fastActor ->
          slow.ready(slowActor)
          fast.ready(fastActor)
          val slowPlan = assertNotNull(slow.scheduler.planNext(slow.blocks) { true })
          slowActor.commands.send(PeerV2DownloadActor.Command.Request(slowPlan))
          slow.connection.writing.await()
          val fastPlan = assertNotNull(fast.scheduler.planNext(fast.blocks) { true })
          fastActor.commands.send(PeerV2DownloadActor.Command.Request(fastPlan))
          val ack = assertIs<PeerV2DownloadActor.Event.Requested>(fastActor.events.receive())
          assertTrue(fast.scheduler.resolve(ack.plan, ack.ticket))
          val response = assertIs<PeerV2DownloadActor.Event.Response>(fastActor.events.receive())
          assertTrue(fast.scheduler.receive(fast.blocks, response.value))
          response.close()
          assertTrue(slowActor.events.tryReceive().isFailure)
          assertFalse(slow.connection.closed)
          assertEquals(0L, testScheduler.currentTime)
        }
      }
    } finally {
      slow.finish()
      fast.finish()
    }
    assertEquals(0, slow.buffers.allocated)
    assertEquals(0, fast.buffers.allocated)
  }

  @Test
  fun blockedEventConsumerTimesOutOnlyItsPeerAndReclaimsUndeliveredFrames() = runTest {
    val f = Fixture { testScheduler.currentTime }
    repeat(8) { f.connection.add(PeerMessage.KeepAlive) }
    try {
      PeerV2DownloadActor.run(f.transport, f.blocks, f.state, capacity = 1,
        dispatchTimeoutMs = 10) { actor ->
        delay(20)
        assertTrue(f.connection.closed)
        assertEquals(0, f.connection.readers)
        // The first event is still owned by the queue after the peer fails.
        assertIs<PeerV2DownloadActor.Event.Update>(actor.events.receive()).close()
        assertNotNull(actor.events.receiveCatching().exceptionOrNull())
      }
    } finally { f.finish() }
    assertEquals(0, f.buffers.allocated)
  }

  @Test
  fun hashWriteCannotOutliveAnEarlierBlockResponseDeadline() = runTest {
    val f = Fixture { testScheduler.currentTime }
    try {
      PeerV2DownloadActor.run(f.transport, f.blocks, f.state) { actor ->
        f.ready(actor)
        val plan = assertNotNull(f.scheduler.planNext(f.blocks) { true })
        actor.commands.send(PeerV2DownloadActor.Command.Request(plan))
        val ack = assertIs<PeerV2DownloadActor.Event.Requested>(actor.events.receive())
        assertTrue(f.scheduler.resolve(ack.plan, assertNotNull(ack.ticket)))
        delay(90)
        f.connection.blockWrites = CompletableDeferred()
        val selector = PeerHashSelector(ByteArray(32).toByteString(), 0, 0, 2, 0)
        actor.commands.send(PeerV2DownloadActor.Command.RequestHashes(selector))
        assertNotNull(actor.events.receiveCatching().exceptionOrNull())
        assertEquals(100L, testScheduler.currentTime)
        assertTrue(f.connection.closed)
        assertEquals(0, f.connection.readers)
      }
    } finally { f.finish() }
    assertEquals(0, f.buffers.allocated)
  }

  @Test
  fun scopeExitBeforeChildStartsStillClosesTheConnectionAndReleasesQueueAdmission() = runTest {
    val f = Fixture { testScheduler.currentTime }
    PeerV2DownloadActor.run(f.transport, f.blocks, f.state) { }
    runCurrent()
    f.finish()
    assertEquals(0, f.buffers.allocated)
  }
}
