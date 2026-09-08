package com.linroid.ketch.torrent

import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TorrentConnectionBudgetTest {
  @Test
  fun liveReductionClosesExcessAndWaitingConnectRespectsNewBound() = runTest {
    val sockets = mutableListOf<FakeConnection>()
    val network = object : TorrentNetwork {
      override suspend fun connect(remote: PeerEndpoint): TorrentConnection =
        FakeConnection(remote).also { sockets += it }
      override suspend fun listen(local: PeerEndpoint): TorrentListener = error("unused")
      override suspend fun bindUdp(local: PeerEndpoint): TorrentDatagramSocket = error("unused")
      override fun close() { sockets.forEach { it.close() } }
    }
    val bounded = TorrentConnectionBudget(network, 2)
    val first = bounded.connect(PeerEndpoint("127.0.0.1", 1))
    bounded.connect(PeerEndpoint("127.0.0.1", 2))
    val pending = async { bounded.connect(PeerEndpoint("127.0.0.1", 3)) }
    delay(100)
    assertEquals(2, sockets.size)
    bounded.set(1)
    assertTrue(sockets[1].closed)
    assertFalse(sockets[0].closed)
    delay(100)
    assertFalse(pending.isCompleted)
    first.close()
    pending.await().close()
    assertEquals(3, sockets.size)
    bounded.close()
    assertTrue(sockets.all { it.closed })
    pending.cancelAndJoin()
  }

  private class FakeConnection(override val remote: PeerEndpoint) : TorrentConnection {
    var closed = false
    override suspend fun readExactly(size: Int): ByteArray = error("unused")
    override suspend fun write(bytes: ByteArray) = Unit
    override fun close() { closed = true }
  }
}
