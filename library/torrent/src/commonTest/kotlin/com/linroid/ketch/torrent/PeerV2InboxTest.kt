package com.linroid.ketch.torrent

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import okio.Buffer
import okio.FileSystem
import okio.ByteString.Companion.toByteString
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class PeerV2InboxTest {
  private class Connection : TorrentConnection {
    override val remote = PeerEndpoint("127.0.0.1", 1)
    val input = Buffer()
    var closed = false
    var activeReads = 0
    override suspend fun readExactly(size: Int): ByteArray {
      activeReads++
      try {
        if (input.size < size) awaitCancellation()
        return input.readByteArray(size.toLong())
      } finally { activeReads-- }
    }
    override suspend fun write(bytes: ByteArray) { check(!closed) }
    override fun close() { closed = true }
    fun add(message: PeerMessage) { input.write(PeerWire.encode(message, pieceCount = 2)) }
  }

  private class Fixture(clock: () -> Long) {
    val connection = Connection()
    val budget = TorrentBufferBudget(200_000)
    val root = ByteArray(32).toByteString()
    val selector = PeerHashSelector(root, 0, 0, 2, 0)
    val hashes = PeerHashExchange(budget, { 16_385L }, timeoutMs = 10, clock = clock)
    val transport = PeerHashTransport(connection, hashes, budget, pieceCount = 2)
    private val info = TorrentV2Info.parse(Bencode.encode(mapOf("meta version" to 2L,
      "piece length" to 16_384L, "file tree" to mapOf("a" to mapOf("" to mapOf(
        "length" to 16_385L, "pieces root" to root.toByteArray()))))))
    val blocks = PeerBlockExchange(TorrentContentLayout.from(info), transport, budget,
      timeoutMs = 10, clock = clock)
    fun ready() {
      connection.add(PeerMessage.Bitfield(byteArrayOf(192.toByte())))
      connection.add(PeerMessage.Control(PeerMessage.Signal.UNCHOKE))
    }
    suspend fun ready(inbox: PeerV2Inbox) {
      repeat(2) {
        val frame = assertIs<PeerV2Inbox.Event.Frame>(inbox.next()).value
        try { blocks.receive(frame) } finally { frame.close() }
      }
    }
  }

  @Test
  fun silentPeerCannotHideABlockDeadlineBehindAnIdleRead() = runTest {
    val f = Fixture { testScheduler.currentTime }
    f.ready()
    assertFailsWith<IllegalStateException> {
      PeerV2Inbox.run(f.transport, f.blocks) { inbox ->
        f.ready(inbox)
        assertNotNull(f.blocks.request(PeerMessage.Request(0, 0, 3)))
        inbox.next()
      }
    }
    assertEquals(10L, testScheduler.currentTime)
    assertTrue(f.connection.closed)
    assertEquals(0, f.connection.activeReads)
    assertEquals(0, f.budget.allocated)
  }

  @Test
  fun hashExpiryIsDeliveredWithoutClosingTheHealthyStream() = runTest {
    val f = Fixture { testScheduler.currentTime }
    PeerV2Inbox.run(f.transport, f.blocks) { inbox ->
      val ticket = assertNotNull(f.transport.request(f.selector))
      val timeout = assertIs<PeerV2Inbox.Event.HashTimeout>(inbox.next())
      assertSame(ticket, timeout.tickets.single())
      assertEquals(10L, testScheduler.currentTime)
      assertEquals(0, f.hashes.pendingCount)
      assertFalse(f.connection.closed)
      assertNotNull(f.transport.request(f.selector))
    }
    assertTrue(f.connection.closed)
    assertEquals(0, f.budget.allocated)
    assertEquals(0, f.connection.activeReads)
  }

  @Test
  fun backwardHashClockExpiresOwnershipInsteadOfSpinningOnZeroDelay() = runTest {
    var now = 5L
    val f = Fixture { now }
    PeerV2Inbox.run(f.transport, f.blocks) { inbox ->
      val ticket = assertNotNull(f.transport.request(f.selector))
      now = 4
      val timeout = assertIs<PeerV2Inbox.Event.HashTimeout>(inbox.next())
      assertSame(ticket, timeout.tickets.single())
      assertEquals(0, f.hashes.pendingCount)
      assertEquals(0L, testScheduler.currentTime)
    }
    assertEquals(0, f.budget.allocated)
  }

  @Test
  fun cancellationReclaimsQueuedAndBlockedSenderFramesButPreservesDeliveredOwnership() = runTest {
    val f = Fixture { testScheduler.currentTime }
    repeat(4) { f.connection.add(PeerMessage.KeepAlive) }
    var delivered: PeerHashTransport.Frame? = null
    val ready = CompletableDeferred<Unit>()
    val actor = async {
      PeerV2Inbox.run(f.transport, f.blocks) { inbox ->
        delivered = assertIs<PeerV2Inbox.Event.Frame>(inbox.next()).value
        ready.complete(Unit)
        awaitCancellation()
      }
    }
    ready.await()
    runCurrent()
    assertTrue(f.budget.allocated >= 3 * 512)
    actor.cancel()
    actor.join()
    assertTrue(f.connection.closed)
    assertEquals(0, f.connection.activeReads)
    assertEquals(512, f.budget.allocated)
    assertNotNull(delivered).close()
    assertEquals(0, f.budget.allocated)
  }

  @Test
  fun malformedInputAndActorFailuresJoinTheReaderAndReleasePendingRequests() = runTest {
    val f = Fixture { testScheduler.currentTime }
    f.connection.input.writeInt(Int.MAX_VALUE)
    assertFailsWith<IllegalArgumentException> {
      PeerV2Inbox.run(f.transport, f.blocks) { inbox ->
        assertNotNull(f.transport.request(f.selector))
        inbox.next()
      }
    }
    assertTrue(f.connection.closed)
    assertEquals(0, f.budget.allocated)
    assertEquals(0, f.connection.activeReads)
    val next = Fixture { testScheduler.currentTime }
    assertFailsWith<IllegalArgumentException> {
      PeerV2Inbox.run(next.transport, next.blocks) {
        assertNotNull(next.transport.request(next.selector))
        throw IllegalArgumentException("Actor dispatch failed")
      }
    }
    assertTrue(next.connection.closed)
    assertEquals(0, next.budget.allocated)
    assertEquals(0, next.connection.activeReads)
  }

  @Test
  fun readyCommandsAndFramesAlternateWithoutTransferringQueuedCommandOwnership() = runTest {
    val f = Fixture { testScheduler.currentTime }
    repeat(4) { f.connection.add(PeerMessage.KeepAlive) }
    val commands = Channel<Int>(2)
    commands.send(10)
    commands.send(20)
    try {
      PeerV2Inbox.run(f.transport, f.blocks) { inbox ->
        runCurrent()
        assertEquals(10, assertIs<PeerV2Inbox.Event.Command<Int>>(inbox.next(commands)).value)
        assertIs<PeerV2Inbox.Event.Frame>(inbox.next(commands)).value.close()
        assertEquals(20, assertIs<PeerV2Inbox.Event.Command<Int>>(inbox.next(commands)).value)
        assertIs<PeerV2Inbox.Event.Frame>(inbox.next(commands)).value.close()
      }
    } finally { commands.cancel() }
    assertEquals(0, f.budget.allocated)
  }

  @Test
  fun blockedStorageDoesNotPreventTheActorFromEnforcingPeerDeadlines() = runTest {
    val f = Fixture { testScheduler.currentTime }
    f.ready()
    val bytes = byteArrayOf(1, 2, 3)
    val document = TorrentV2Document.parse(Bencode.encode(mapOf("info" to mapOf(
      "meta version" to 2L, "piece length" to 16_384L, "file tree" to mapOf(
        "a" to mapOf("" to mapOf("length" to 3L, "pieces root" to sha256Digest(bytes))))
    ), "piece layers" to emptyMap<String, Any>())))
    val path = FileSystem.SYSTEM_TEMPORARY_DIRECTORY /
      "ketch-inbox-worker-${InfoHash.fromBytes(torrentRandomBytes(20)).hex}"
    val slots = Semaphore(1)
    val store = TorrentV2PieceStore(document, path, emptySet(), "test", f.budget, slots)
    val assembly = assertNotNull(TorrentV2PieceAssembly.create(
      TorrentContentLayout.from(document.info), 0, f.budget))
    assembly.accept(PeerBlockExchange.Response.Block(PeerBlockExchange.Ticket(assembly.request(0)),
      bytes, assertNotNull(f.budget.reserve(512))))
    try {
      store.initialize()
      slots.acquire()
      try {
        assertFailsWith<IllegalStateException> {
          TorrentV2CommitWorker.run(
            store = store,
            dispatcher = StandardTestDispatcher(testScheduler),
          ) { worker ->
            PeerV2Inbox.run(f.transport, f.blocks) { inbox ->
              f.ready(inbox)
              assertNotNull(f.blocks.request(PeerMessage.Request(0, 0, 3)))
              assertNotNull(worker.trySubmit(assembly))
              runCurrent()
              assertTrue(worker.completions.tryReceive().isFailure)
              inbox.next(worker.completions)
            }
          }
        }
      } finally { slots.release() }
      assertEquals(10L, testScheduler.currentTime)
      assertTrue(f.connection.closed)
      assertFalse(store.completed())
      assertEquals(0, f.budget.allocated)
    } finally {
      assembly.close()
      store.cleanup()
    }
  }

}
