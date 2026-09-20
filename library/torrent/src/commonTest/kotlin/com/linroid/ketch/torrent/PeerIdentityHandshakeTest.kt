package com.linroid.ketch.torrent

import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import okio.Buffer
import okio.ByteString
import okio.ByteString.Companion.toByteString
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class PeerIdentityHandshakeTest {
  private val identity = TorrentIdentity(InfoHash.fromBytes(ByteArray(20) { 2 }),
    V2InfoHash.fromBytes(ByteArray(32) { 3 }))
  private val local = ByteArray(20) { 4 }.toByteString()
  private val remote = ByteArray(20) { 5 }.toByteString()
  private val v1 = identity.v1!!.toBytes().toByteString()
  private val v2 = identity.v2!!.wireBytes().toByteString()

  private fun envelope(tag: ByteString, peer: ByteString = remote, upgrade: Boolean = false) =
    Buffer().writeByte(19).writeUtf8("BitTorrent protocol")
      .write(byteArrayOf(0, 0, 0, 0, 0, 0x10, 0, if (upgrade) 0x11 else 1))
      .write(tag).write(peer).readByteArray()

  private class Connection(private val incoming: ByteArray) : TorrentConnection {
    override val remote = PeerEndpoint("127.0.0.1", 1)
    var outgoing = ByteArray(0)
    var reads = 0
    var closed = false
    var readDelay = 0L
    override suspend fun readExactly(size: Int): ByteArray {
      reads++
      assertEquals(68, size)
      delay(readDelay)
      return incoming
    }
    override suspend fun write(bytes: ByteArray) { outgoing = bytes }
    override fun close() { closed = true }
  }

  @Test
  fun directAndOfferedUpgradeRoutesKeepTheFullIdentity() = runTest {
    val handshake = PeerIdentityHandshake(identity, extensions = true, dht = true)
    val budget = TorrentBufferBudget(4096)
    for ((mode, offer, reply) in listOf(
      Triple(PeerIdentityHandshake.Mode.V1, false, v1),
      Triple(PeerIdentityHandshake.Mode.V1, true, v1),
      Triple(PeerIdentityHandshake.Mode.V1, true, v2),
      Triple(PeerIdentityHandshake.Mode.V2, false, v2)
    )) {
      val connection = Connection(envelope(reply))
      val result = handshake.initiate(connection, mode, local, budget, offer, remote)
      val expected = if (reply == v1) PeerIdentityHandshake.Mode.V1 else
        PeerIdentityHandshake.Mode.V2
      assertEquals(expected, result.mode)
      assertEquals(identity, result.identity)
      assertEquals(remote, result.peerId)
      assertTrue(result.extensions && result.dht)
      val sentTag = if (mode == PeerIdentityHandshake.Mode.V1) v1 else v2
      assertContentEquals(envelope(sentTag, local, offer), connection.outgoing)
      assertEquals(0, budget.allocated)
    }
  }

  @Test
  fun responderUpgradesOnlyWhenBothSidesPermitIt() = runTest {
    val handshake = PeerIdentityHandshake(identity)
    for (offer in listOf(false, true)) {
      for (allow in listOf(false, true)) {
        val budget = TorrentBufferBudget(4096)
        val connection = Connection(envelope(v1, upgrade = offer))
        val result = handshake.respond(connection, local, budget, allow)
        val expected = if (offer && allow) v2 else v1
        assertContentEquals(expected.toByteArray(), connection.outgoing.copyOfRange(28, 48))
        assertEquals(if (offer && allow) PeerIdentityHandshake.Mode.V2 else
          PeerIdentityHandshake.Mode.V1, result.mode)
        assertEquals(0, budget.allocated)
      }
    }
    val direct = Connection(envelope(v2))
    assertEquals(PeerIdentityHandshake.Mode.V2,
      handshake.respond(direct, local, TorrentBufferBudget(4096)).mode)
  }

  @Test
  fun invalidSwarmProtocolPeerAndAmbiguousAliasesFailClosed() = runTest {
    val handshake = PeerIdentityHandshake(identity)
    val budget = TorrentBufferBudget(4096)
    val broken = envelope(v1).also { it[1] = 0 }
    for (bytes in listOf(envelope(v2), envelope(v1, local), broken,
      envelope(ByteArray(20).toByteString()))) {
      val connection = Connection(bytes)
      assertFailsWith<IllegalArgumentException> {
        handshake.initiate(connection, PeerIdentityHandshake.Mode.V1, local, budget)
      }
      assertTrue(connection.closed)
      assertEquals(0, budget.allocated)
    }
    val downgrade = Connection(envelope(v1))
    assertFailsWith<IllegalArgumentException> {
      handshake.initiate(downgrade, PeerIdentityHandshake.Mode.V2, local, budget)
    }
    val wrongPeer = Connection(envelope(v1))
    assertFailsWith<IllegalArgumentException> {
      handshake.initiate(wrongPeer, PeerIdentityHandshake.Mode.V1, local, budget,
        expectedPeerId = ByteArray(20).toByteString())
    }
    val collision = PeerIdentityHandshake(TorrentIdentity(InfoHash.fromBytes(v2.toByteArray()),
      identity.v2))
    val ambiguous = Connection(envelope(v2, upgrade = true))
    assertFailsWith<IllegalArgumentException> {
      collision.respond(ambiguous, local, budget, allowUpgrade = true)
    }
    assertTrue(ambiguous.closed && ambiguous.outgoing.isEmpty())
    assertEquals(0, budget.allocated)
  }

  @Test
  fun admissionAndDeadlineFailuresCloseWithoutRetainingCredit() = runTest {
    val handshake = PeerIdentityHandshake(identity)
    val small = TorrentBufferBudget(4095)
    val refused = Connection(envelope(v2))
    assertFailsWith<IllegalStateException> {
      handshake.initiate(refused, PeerIdentityHandshake.Mode.V2, local, small)
    }
    assertTrue(refused.closed && refused.outgoing.isEmpty() && refused.reads == 0)
    val slow = Connection(envelope(v2)).also { it.readDelay = 10_001 }
    val budget = TorrentBufferBudget(4096)
    assertFailsWith<kotlinx.coroutines.TimeoutCancellationException> {
      handshake.initiate(slow, PeerIdentityHandshake.Mode.V2, local, budget)
    }
    assertTrue(slow.closed)
    assertEquals(0, budget.allocated)
  }
}
