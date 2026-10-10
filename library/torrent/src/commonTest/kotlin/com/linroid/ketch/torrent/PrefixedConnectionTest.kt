package com.linroid.ketch.torrent

import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNotSame
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PrefixedConnectionTest {
  private class Stream(private val bytes: ByteArray) : TorrentConnection {
    override val remote = PeerEndpoint("127.0.0.1", 6881)
    var position = 0
    val reads = mutableListOf<Int>()
    var closed = false

    override suspend fun readExactly(size: Int): ByteArray {
      reads += size
      check(position + size <= bytes.size) { "End of stream" }
      return bytes.copyOfRange(position, position + size).also { position += size }
    }

    override suspend fun write(bytes: ByteArray) = Unit
    override fun close() { closed = true }
  }

  private val header = ByteArray(68) { it.toByte() }

  @Test
  fun servesPrefixAcrossArbitraryReadSizes() = runTest {
    val stream = Stream(ByteArray(0))
    val connection = PrefixedConnection(stream, header)
    val parts = listOf(1, 19, 0, 8, 20, 20).map { connection.readExactly(it) }
    assertContentEquals(header, parts.reduce { left, right -> left + right })
    assertTrue(stream.reads.isEmpty())
    assertFailsWith<IllegalArgumentException> { connection.readExactly(-1) }
  }

  @Test
  fun readsPastPrefixFromStream() = runTest {
    val stream = Stream(ByteArray(10) { (100 + it).toByte() })
    val connection = PrefixedConnection(stream, header.copyOf(4))
    connection.readExactly(1)
    // The prefix tail and the stream's first bytes arrive in one read.
    assertContentEquals(header.copyOfRange(1, 4) + byteArrayOf(100, 101),
      connection.readExactly(5))
    assertEquals(listOf(2), stream.reads)
    assertContentEquals(ByteArray(8) { (102 + it).toByte() }, connection.readExactly(8))
    assertEquals(listOf(2, 8), stream.reads)
    assertEquals(stream.remote, connection.remote)
  }

  @Test
  fun returnsFreshArraysForWholePrefixRead() = runTest {
    val prefix = header.copyOf()
    val connection = PrefixedConnection(Stream(ByteArray(0)), prefix)
    // The caller may reuse its buffer: the connection kept a copy.
    prefix.fill(0)
    val whole = connection.readExactly(68)
    assertNotSame(prefix, whole)
    assertContentEquals(header, whole)
    // Decrypting or otherwise editing a result in place cannot change bytes still to be served.
    val split = PrefixedConnection(Stream(ByteArray(0)), header)
    val first = split.readExactly(20)
    first.fill(0)
    assertContentEquals(header.copyOfRange(20, 68), split.readExactly(48))
  }

  @Test
  fun closeReleasesUnderlyingBudget() = runTest {
    val sockets = mutableListOf<Stream>()
    val network = object : TorrentNetwork {
      override suspend fun connect(remote: PeerEndpoint): TorrentConnection =
        Stream(ByteArray(0)).also { sockets += it }
      override suspend fun listen(local: PeerEndpoint): TorrentListener = error("Unused")
      override suspend fun bindUdp(local: PeerEndpoint): TorrentDatagramSocket = error("Unused")
      override fun close() = Unit
    }
    val budget = TorrentConnectionBudget(network, 1)
    val endpoint = PeerEndpoint("127.0.0.1", 1)
    val connection = PrefixedConnection(budget.connect(endpoint), header)
    assertNull(withTimeoutOrNull(1_000) { budget.connect(endpoint) })
    connection.close()
    assertTrue(sockets.single().closed)
    assertNotNull(withTimeoutOrNull(1_000) { budget.connect(endpoint) }).close()
  }
}
