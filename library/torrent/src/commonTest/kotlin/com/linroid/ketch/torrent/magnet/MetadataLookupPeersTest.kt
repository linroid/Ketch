package com.linroid.ketch.torrent.magnet

import com.linroid.ketch.torrent.net.PeerEndpoint
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MetadataLookupPeersTest {
  private fun peer(n: Int) = PeerEndpoint("192.0.2.${n % 250}", 6881 + n)

  @Test
  fun peersThatSentMetadata_areTakenOnceByTheDownload() = runTest {
    val kept = MetadataLookupPeers()
    val answering = AnsweringPeers()
    answering.add(peer(1))
    answering.add(peer(2))
    answering.add(peer(1))
    kept.keep("tag", answering.all)
    assertEquals(listOf(peer(1), peer(2)), kept.take("tag"))
    assertTrue(kept.take("tag").isEmpty())
  }

  @Test
  fun lookups_keepAFewPeersOfTheNewestTorrents() = runTest {
    val answering = AnsweringPeers()
    repeat(100) { answering.add(peer(it)) }
    assertEquals(MetadataLookupPeers.MAX_PEERS, answering.all.size)
    val kept = MetadataLookupPeers()
    repeat(MetadataLookupPeers.MAX_TORRENTS + 1) { kept.keep("tag$it", listOf(peer(it))) }
    // The oldest torrent's peers were forgotten.
    assertTrue(kept.take("tag0").isEmpty())
    assertEquals(listOf(peer(1)), kept.take("tag1"))
  }
}
