package com.linroid.ketch.torrent.mse

import com.linroid.ketch.torrent.TorrentEncryption
import com.linroid.ketch.torrent.net.PeerEndpoint
import com.linroid.ketch.torrent.net.TorrentConnection
import com.linroid.ketch.torrent.net.TorrentDatagramSocket
import com.linroid.ketch.torrent.net.TorrentIpFilter
import com.linroid.ketch.torrent.net.TorrentListener
import com.linroid.ketch.torrent.net.TorrentNetwork
import com.linroid.ketch.torrent.net.numericAddress
import kotlinx.coroutines.test.runTest
import okio.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** The filter sees the address a dial reaches, also when the peer is listed by name. */
class TorrentPeerFilterTest {
  /** Records where it was asked to connect and refuses every connection. */
  private class Refusing : TorrentNetwork {
    val dialed = mutableListOf<PeerEndpoint>()

    override suspend fun connect(remote: PeerEndpoint): TorrentConnection {
      dialed += remote
      throw IOException("refused")
    }

    override suspend fun listen(local: PeerEndpoint): TorrentListener = error("unused")
    override suspend fun bindUdp(local: PeerEndpoint): TorrentDatagramSocket = error("unused")
    override fun close() = Unit
  }

  private fun network(
    delegate: Refusing,
    names: Map<String, List<String>> = emptyMap(),
    lookups: MutableList<String> = mutableListOf(),
  ) = TorrentPeerEncryption(delegate, TorrentEncryption.DISABLED, resolve = { host ->
    lookups += host
    names.getValue(host).map { checkNotNull(numericAddress(it)) }
  })

  private val tag = ByteArray(20)

  @Test
  fun `a blocked address is never dialed and others are dialed as they are`() = runTest {
    val delegate = Refusing()
    val network = network(delegate)
    network.filter = { TorrentIpFilter.parse(listOf("203.0.113.0/24")) }

    val blocked = assertFailsWith<IOException> {
      network.dial(PeerEndpoint("203.0.113.7", 6881), tag)
    }
    assertTrue("blocked" in blocked.message.orEmpty())
    assertFailsWith<IOException> { network.dial(PeerEndpoint("198.51.100.9", 6881), tag) }

    assertEquals(listOf(PeerEndpoint("198.51.100.9", 6881)), delegate.dialed)
  }

  @Test
  fun `a peer listed by name is dialed at an address the filter allows`() = runTest {
    val delegate = Refusing()
    val names = mapOf(
      "mixed.example" to listOf("203.0.113.7", "198.51.100.9"),
      "blocked.example" to listOf("203.0.113.7", "203.0.113.8"),
    )
    val network = network(delegate, names)
    network.filter = { TorrentIpFilter.parse(listOf("203.0.113.0/24")) }

    assertFailsWith<IOException> { network.dial(PeerEndpoint("mixed.example", 6881), tag) }
    val blocked = assertFailsWith<IOException> {
      network.dial(PeerEndpoint("blocked.example", 6881), tag)
    }

    // The name was resolved here, and the connection goes to the address that was checked.
    assertEquals(listOf(PeerEndpoint("198.51.100.9", 6881)), delegate.dialed)
    assertTrue("blocked" in blocked.message.orEmpty())
  }

  @Test
  fun `without a filter names are left to the network to resolve`() = runTest {
    val delegate = Refusing()
    val lookups = mutableListOf<String>()
    val network = network(delegate, lookups = lookups)

    assertFailsWith<IOException> { network.dial(PeerEndpoint("peer.example", 6881), tag) }

    assertEquals(listOf(PeerEndpoint("peer.example", 6881)), delegate.dialed)
    assertTrue(lookups.isEmpty())
  }
}
