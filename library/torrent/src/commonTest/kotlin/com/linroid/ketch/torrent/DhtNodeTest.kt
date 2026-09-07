package com.linroid.ketch.torrent

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DhtNodeTest {
  @Test
  fun ipv4_coldBootstrapAnnounceLookupAndWarmRestore() = runTest { discover(false) }

  @Test
  fun ipv6_coldBootstrapAnnounceLookupAndWarmRestore() = runTest { discover(true) }

  private suspend fun discover(ipv6: Boolean) = withContext(Dispatchers.Default) {
    withTimeout(20_000) {
      coroutineScope {
        val network = createTorrentNetwork()
        val host = if (ipv6) "::1" else "127.0.0.1"
        suspend fun node() = DhtNode(network.bindUdp(PeerEndpoint(host, 0)), this,
          allowLocalAddresses = true, queryTimeoutMs = 500).also { it.start() }
        val router = node()
        val announcer = node()
        val seeker = node()
        val middle = node()
        try {
          val hash = InfoHash.fromBytes(torrentRandomBytes(20))
          announcer.bootstrap(listOf(router.local))
          announcer.peers(hash, announcePort = 6881)
          middle.bootstrap(listOf(router.local))
          seeker.bootstrap(listOf(middle.local))
          val found = seeker.peers(hash)
          assertEquals(listOf(PeerEndpoint(if (ipv6) "0:0:0:0:0:0:0:1" else host, 6881)), found)
          val restored = DhtRoutingTable.restore(seeker.snapshot())
          assertTrue(restored.second.isNotEmpty())
          val warm = node()
          try {
            warm.bootstrap(restored.second.map { it.endpoint })
            assertEquals(found, warm.peers(hash))
          } finally {
            warm.close()
          }
        } finally {
          middle.close()
          seeker.close()
          announcer.close()
          router.close()
          network.close()
        }
      }
    }
  }
}
