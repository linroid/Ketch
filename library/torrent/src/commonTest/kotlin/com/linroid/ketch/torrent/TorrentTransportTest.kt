package com.linroid.ketch.torrent

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class TorrentTransportTest {
  @Test
  fun tcp_fragmentedWritesReadExactlyAndCloseInterruptsRead() = runTest {
    withContext(Dispatchers.Default) {
      withTimeout(10_000) {
        val network = createTorrentNetwork()
        try {
          val listener = network.listen(PeerEndpoint("127.0.0.1", 0))
          coroutineScope {
            val accepted = async { listener.accept() }
            val client = network.connect(listener.local)
            val server = accepted.await()
            client.write(byteArrayOf(1, 2))
            client.write(byteArrayOf(3))
            assertContentEquals(byteArrayOf(1, 2, 3), server.readExactly(3))
            client.close()
            assertTrue(runCatching { server.readExactly(1) }.isFailure)
            server.close()
          }
          listener.close()
        } finally {
          network.close()
        }
      }
    }
  }

  @Test
  fun udp_preservesPayloadAndSender() = runTest {
    withContext(Dispatchers.Default) {
      withTimeout(10_000) {
        val network = createTorrentNetwork()
        try {
          val first = network.bindUdp(PeerEndpoint("127.0.0.1", 0))
          val second = network.bindUdp(PeerEndpoint("127.0.0.1", 0))
          first.send(second.local, byteArrayOf(0, 127, -1))
          val datagram = second.receive()
          assertEquals(first.local.port, datagram.remote.port)
          assertContentEquals(byteArrayOf(0, 127, -1), datagram.bytes)
        } finally {
          network.close()
        }
      }
    }
  }

  @Test
  fun ipv6_tcpAndUdpLoopback() = runTest {
    withContext(Dispatchers.Default) {
      withTimeout(10_000) {
        val network = createTorrentNetwork()
        try {
          val listener = network.listen(PeerEndpoint("::1", 0))
          coroutineScope {
            val accept = async { listener.accept() }
            val client = network.connect(listener.local)
            val server = accept.await()
            client.write(byteArrayOf(42))
            assertContentEquals(byteArrayOf(42), server.readExactly(1))
            val pending = async { runCatching { server.readExactly(1) } }
            listener.close()
            val first = network.bindUdp(PeerEndpoint("::1", 0))
            val second = network.bindUdp(PeerEndpoint("::1", 0))
            first.send(second.local, byteArrayOf(7))
            assertContentEquals(byteArrayOf(7), second.receive().bytes)
            network.close()
            assertTrue(pending.await().isFailure)
          }
        } finally {
          network.close()
        }
      }
    }
  }

  @Test
  fun resources_connectionChurnDoesNotRetainClosedHandles() {
    val resources = TorrentResources()
    var closed = 0
    repeat(1000) { resources.register { closed++ }.close() }
    assertEquals(0, resources.activeCount)
    resources.register { closed++ }
    resources.close()
    resources.close()
    assertEquals(1001, closed)
    assertFailsWith<IllegalStateException> { resources.register { closed++ } }
    assertEquals(1002, closed)
  }
}
