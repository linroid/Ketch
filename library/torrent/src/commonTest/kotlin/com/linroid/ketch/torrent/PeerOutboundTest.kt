package com.linroid.ketch.torrent

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.runTest
import okio.Buffer
import okio.ByteString.Companion.toByteString
import okio.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class PeerOutboundTest {
  private class Connection : TorrentConnection {
    override val remote = PeerEndpoint("127.0.0.1", 1)
    val output = Buffer()
    var closed = false
    var writing: suspend () -> Unit = {}
    override suspend fun readExactly(size: Int): ByteArray = error("No reads")
    override suspend fun write(bytes: ByteArray) {
      output.write(bytes)
      writing()
    }
    override fun close() { closed = true }
  }

  private val root = ByteArray(32).toByteString()
  private val selector = PeerHashSelector(root, 0, 0, 2, 0)

  @Test
  fun writesPayloadControlAndHashResponsesThroughTheSameFramedStream() = runTest {
    val connection = Connection()
    val budget = TorrentBufferBudget(200_000)
    val exchange = PeerHashExchange(budget, { 16_385L })
    val transport = PeerHashTransport(connection, exchange, budget, pieceCount = 1)
    val messages = listOf(
      PeerMessage.KeepAlive,
      PeerMessage.Control(PeerMessage.Signal.CHOKE),
      PeerMessage.Bitfield(byteArrayOf(128.toByte())),
      PeerMessage.Have(0),
      PeerMessage.Request(0, 0, 1),
      PeerMessage.Cancel(0, 0, 1),
      PeerMessage.Reject(0, 0, 1),
      PeerMessage.Piece(0, 0, byteArrayOf(42)),
      PeerMessage.Port(6881),
      PeerMessage.Extended(1, byteArrayOf(2)),
      PeerMessage.Unknown(99, byteArrayOf(3))
    )
    for (message in messages) {
      transport.send(message)
      val encoded = PeerWire.encode(message, pieceCount = 1)
      assertEquals(encoded.size, PeerWire.encodedSize(message, 1))
      assertTrue(encoded.contentEquals(connection.output.readByteArray()))
      assertEquals(0, budget.allocated)
    }
    for (response in listOf(PeerHashMessage.Reject(selector),
      PeerHashMessage.Hashes(selector, ByteArray(64).toByteString()))) {
      transport.respond(response)
      val size = connection.output.readInt()
      val frame = PeerWire.decode(connection.output.readByteArray(size.toLong()))
      assertEquals(response, PeerHashWire.decode(frame as PeerMessage.Unknown))
      assertEquals(0, budget.allocated)
    }
    transport.close()
  }

  @Test
  fun sendsTheDesktopBitfieldOnlyWhileItsEncoderCreditIsHeld() = runTest {
    val connection = Connection()
    val budget = TorrentBufferBudget(510_000)
    val transport = PeerHashTransport(connection, PeerHashExchange(budget, { null }), budget,
      pieceCount = 1_000_000)
    connection.writing = { assertTrue(budget.allocated >= 500_000) }
    val bitfield = ByteArray(125_000).also { it[it.lastIndex] = 1 }
    transport.send(PeerMessage.Bitfield(bitfield))
    assertEquals(125_001, connection.output.readInt())
    val message = PeerWire.decode(connection.output.readByteArray(), pieceCount = 1_000_000)
    assertTrue(bitfield.contentEquals((message as PeerMessage.Bitfield).bytes))
    assertEquals(0, budget.allocated)
    transport.close()
  }

  @Test
  fun rejectsUnadmittedAndInvalidWritesWithoutEmittingBytesOrClosingTheStream() = runTest {
    val connection = Connection()
    val budget = TorrentBufferBudget(600)
    val transport = PeerHashTransport(connection, PeerHashExchange(budget, { null }), budget,
      pieceCount = 1_000_000)
    assertFailsWith<IllegalStateException> {
      transport.send(PeerMessage.Bitfield(ByteArray(125_000)))
    }
    assertFailsWith<IllegalStateException> {
      transport.respond(PeerHashMessage.Hashes(selector, ByteArray(64).toByteString()))
    }
    assertFailsWith<IllegalArgumentException> {
      transport.respond(PeerHashMessage.Hashes(selector, ByteArray(63).toByteString()))
    }
    assertFailsWith<IllegalArgumentException> {
      transport.send(PeerHashWire.encode(PeerHashMessage.Request(selector)))
    }
    assertFailsWith<IllegalStateException> { transport.respond(PeerHashMessage.Request(selector)) }
    assertEquals(0L, connection.output.size)
    assertEquals(0, budget.allocated)
    assertFalse(connection.closed)
    transport.send(PeerMessage.KeepAlive)
    assertEquals(4L, connection.output.size)
    transport.close()
  }

  @Test
  fun cancelingAnInFlightWriteRetainsCreditUntilUnwoundAndReleasesPendingHashes() = runTest {
    val connection = Connection()
    val budget = TorrentBufferBudget(200_000)
    val exchange = PeerHashExchange(budget, { 16_385L })
    val transport = PeerHashTransport(connection, exchange, budget, pieceCount = 1)
    assertNotNull(transport.request(selector))
    val pendingCredit = budget.allocated
    val entered = CompletableDeferred<Unit>()
    connection.writing = {
      entered.complete(Unit)
      try { awaitCancellation() } finally { assertTrue(budget.allocated > pendingCredit) }
    }
    val write = async { transport.send(PeerMessage.Piece(0, 0, ByteArray(16_384))) }
    entered.await()
    assertTrue(budget.allocated > pendingCredit)
    write.cancel()
    write.join()
    assertTrue(connection.closed)
    assertEquals(0, budget.allocated)
    assertFailsWith<IllegalStateException> { transport.send(PeerMessage.KeepAlive) }
  }

  @Test
  fun partialAndTimedOutHashRepliesCloseTheStreamAndReleaseAllCredit() = runTest {
    for (failure in listOf(true, false)) {
      val connection = Connection()
      val budget = TorrentBufferBudget(200_000)
      val transport = PeerHashTransport(connection, PeerHashExchange(budget, { 16_385L }),
        budget, timeoutMs = 10)
      assertNotNull(transport.request(selector))
      connection.writing = {
        if (failure) throw IOException("Partial write") else awaitCancellation()
      }
      if (failure) {
        assertFailsWith<IOException> { transport.respond(PeerHashMessage.Reject(selector)) }
      } else {
        assertFailsWith<kotlinx.coroutines.TimeoutCancellationException> {
          transport.respond(PeerHashMessage.Reject(selector))
        }
      }
      assertTrue(connection.closed)
      assertEquals(0, budget.allocated)
    }
  }
}
