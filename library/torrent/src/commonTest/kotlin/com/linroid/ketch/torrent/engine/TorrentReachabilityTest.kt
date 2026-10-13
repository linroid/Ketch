package com.linroid.ketch.torrent.engine

import com.linroid.ketch.torrent.TorrentConfig
import com.linroid.ketch.torrent.api.TorrentReachability
import com.linroid.ketch.torrent.api.TorrentReachabilityStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals

class TorrentReachabilityTest {
  @Test
  fun engine_unknownUntilStarted_thenNotForwardedWithoutPortMapping() = runTest {
    withContext(Dispatchers.Default) {
      withTimeout(15_000) {
        // Engines built directly never map ports, as nothing should reach the router in tests.
        val engine = KotlinTorrentEngine(TorrentConfig(dhtEnabled = false))
        try {
          assertEquals(TorrentReachability(), engine.reachability())
          engine.start()
          assertEquals(
            TorrentReachability(TorrentReachabilityStatus.NOT_FORWARDED,
              port = engine.listenPort, forwardedLayers = 0, holePunching = true),
            engine.reachability(),
          )
          engine.setUtpEnabled(false)
          assertEquals(false, engine.reachability().holePunching)
        } finally {
          engine.stop()
        }
      }
    }
  }
}
