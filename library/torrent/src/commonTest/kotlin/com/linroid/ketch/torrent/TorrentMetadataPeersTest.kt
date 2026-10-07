package com.linroid.ketch.torrent

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeoutOrNull
import okio.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class TorrentMetadataPeersTest {
  private val a = PeerEndpoint("192.0.2.1", 6881)
  private val b = PeerEndpoint("192.0.2.2", 6881)

  @Test
  fun failedPeer_isAskedAgainAfterTheRetryDelay() = runTest {
    val peers = Channel<PeerEndpoint>(Channel.UNLIMITED).apply { trySend(a) }
    val asked = mutableListOf<Long>()
    val result = fetchFromMetadataPeers("Metadata", peers) {
      asked += currentTime
      if (asked.size < 3) throw IOException("busy") else "info"
    }
    assertEquals("info", result)
    assertEquals(listOf(0L, 5_000L, 20_000L), asked)
  }

  @Test
  fun peerFoundWhileARetryWaits_isAskedFirst() = runTest {
    val peers = Channel<PeerEndpoint>(Channel.UNLIMITED).apply { trySend(a) }
    launch {
      delay(1_000)
      peers.send(b)
    }
    val asked = mutableListOf<Pair<PeerEndpoint, Long>>()
    val result = fetchFromMetadataPeers("Metadata", peers) { endpoint ->
      asked += endpoint to currentTime
      if (endpoint == a) throw IOException("refused") else "from b"
    }
    assertEquals("from b", result)
    assertEquals(listOf(a to 0L, b to 1_000L), asked)
  }

  @Test
  fun peerThatKeepsFailing_isGivenUpAfterEveryRetry() = runTest {
    val peers = Channel<PeerEndpoint>(Channel.UNLIMITED).apply { trySend(a) }
    var asked = 0
    val result = withTimeoutOrNull(120_000) {
      fetchFromMetadataPeers("Metadata", peers) {
        asked++
        throw IOException("unreachable")
      }
    }
    assertNull(result)
    assertEquals(1 + METADATA_RETRY_DELAYS_MS.size, asked)
  }

  @Test
  fun peerFoundAgain_isNotQueuedTwice() = runTest {
    val peers = Channel<PeerEndpoint>(Channel.UNLIMITED)
    repeat(5) { peers.trySend(a) }
    var asked = 0
    val found = mutableListOf<Int>()
    withTimeoutOrNull(1_000) {
      fetchFromMetadataPeers("Metadata", peers, onPeer = { found += it }) {
        asked++
        throw IOException("busy")
      }
    }
    assertEquals(1, asked)
    assertEquals(listOf(1), found)
  }

  @Test
  fun privateTorrent_endsTheLookup() = runTest {
    val peers = Channel<PeerEndpoint>(Channel.UNLIMITED).apply { trySend(a); trySend(b) }
    assertFailsWith<PrivateTorrentMagnetException> {
      fetchFromMetadataPeers<String>("Metadata", peers) { throw PrivateTorrentMagnetException() }
    }
  }
}
