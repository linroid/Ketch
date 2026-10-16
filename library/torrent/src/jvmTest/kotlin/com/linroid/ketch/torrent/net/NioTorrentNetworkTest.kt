package com.linroid.ketch.torrent.net

import kotlinx.coroutines.async
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.util.concurrent.Executors
import kotlin.random.Random
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class NioTorrentNetworkTest {
  private val executor = Executors.newFixedThreadPool(2) { runnable ->
    Thread(runnable, WORKER).apply { isDaemon = true }
  }
  private val dispatcher = executor.asCoroutineDispatcher()

  @AfterTest
  fun tearDown() {
    executor.shutdownNow()
  }

  @Test
  fun manyConnectionsExchangeOnTwoThreadsAndTheSelector() = runBlocking(dispatcher) {
    withTimeout(30_000) {
      // Start the shared selector before taking the snapshot.
      NioSelector.shared
      val before = Thread.getAllStackTraces().keys.toSet()
      val network = NioTorrentNetwork()
      try {
        val listener = network.listen(PeerEndpoint("127.0.0.1", 0))
        val pairs = 200
        coroutineScope {
          val servers = async { List(pairs) { listener.accept() } }
          val clients = List(pairs) { network.connect(listener.local) }
          val accepted = servers.await()
          // Each payload starts with its index, as the accept order is not the dial order.
          val payloads = List(pairs) { index ->
            Random.nextBytes(PAYLOAD_BYTES).also {
              it[0] = (index shr 8).toByte()
              it[1] = index.toByte()
            }
          }
          clients.mapIndexed { index, client -> async { client.write(payloads[index]) } }.awaitAll()
          val received = accepted.map { server -> async { server.readExactly(PAYLOAD_BYTES) } }
            .awaitAll()
          val byIndex = received.associateBy {
            (it[0].toInt() and 0xff shl 8) or (it[1].toInt() and 0xff)
          }
          assertEquals(pairs, byIndex.size)
          payloads.forEachIndexed { index, payload -> assertContentEquals(payload, byIndex[index]) }
        }
        listener.close()
      } finally {
        network.close()
      }
      val created = Thread.getAllStackTraces().keys.filter { it !in before }
        .map { it.name }
        // The test's own pool starts its threads on demand.
        .filterNot { it.startsWith("kotlinx.coroutines.DefaultExecutor") || it.startsWith(WORKER) }
      assertTrue(created.isEmpty(), "Threads created: $created")
    }
  }

  @Test
  fun writeLargerThanTheSocketBuffersWaitsForTheReader() = runBlocking(dispatcher) {
    withTimeout(30_000) {
      val network = NioTorrentNetwork()
      try {
        val listener = network.listen(PeerEndpoint("127.0.0.1", 0))
        coroutineScope {
          val accepted = async { listener.accept() }
          val client = network.connect(listener.local)
          val server = accepted.await()
          val payload = Random.nextBytes(8 * 1024 * 1024)
          val write = launch { client.write(payload) }
          // Let the kernel buffers fill so the writer waits for OP_WRITE.
          delay(100)
          val received = ByteArray(payload.size)
          var offset = 0
          while (offset < payload.size) {
            val chunk = minOf(48 * 1024, payload.size - offset)
            server.readExactly(chunk).copyInto(received, offset)
            offset += chunk
          }
          write.join()
          assertContentEquals(payload, received)
        }
        listener.close()
      } finally {
        network.close()
      }
    }
  }

  @Test
  fun cancelledReadLeavesTheConnectionReadable() = runBlocking(dispatcher) {
    withTimeout(10_000) {
      val network = NioTorrentNetwork()
      try {
        val listener = network.listen(PeerEndpoint("127.0.0.1", 0))
        coroutineScope {
          val accepted = async { listener.accept() }
          val client = network.connect(listener.local)
          val server = accepted.await()
          val pending = launch { server.readExactly(1) }
          delay(50)
          pending.cancelAndJoin()
          client.write(byteArrayOf(9, 8))
          assertContentEquals(byteArrayOf(9, 8), server.readExactly(2))
        }
        listener.close()
      } finally {
        network.close()
      }
    }
  }

  @Test
  fun udpBurstArrivesInOrderOnLoopback() = runBlocking(dispatcher) {
    withTimeout(10_000) {
      val network = NioTorrentNetwork()
      try {
        val sender = network.bindUdp(PeerEndpoint("127.0.0.1", 0))
        val receiver = network.bindUdp(PeerEndpoint("127.0.0.1", 0))
        val received = async { List(100) { receiver.receive() } }
        repeat(100) { sender.send(receiver.local, byteArrayOf(it.toByte())) }
        val datagrams = received.await()
        assertEquals((0 until 100).toList(), datagrams.map { it.bytes.single().toInt() })
        assertTrue(datagrams.all { it.remote.port == sender.local.port })
      } finally {
        network.close()
      }
    }
  }

  @Test
  fun closedNetworkFailsAWaitingAccept() = runBlocking(dispatcher) {
    withTimeout(10_000) {
      val network = NioTorrentNetwork()
      val listener = network.listen(PeerEndpoint("127.0.0.1", 0))
      val pending = async { runCatching { listener.accept() } }
      delay(50)
      withContext(dispatcher) { network.close() }
      assertTrue(pending.await().isFailure)
    }
  }

  private companion object {
    /** Larger than a connection's read buffer, so both read paths run. */
    const val PAYLOAD_BYTES = 40 * 1024
    const val WORKER = "nio-test-worker"
  }
}
