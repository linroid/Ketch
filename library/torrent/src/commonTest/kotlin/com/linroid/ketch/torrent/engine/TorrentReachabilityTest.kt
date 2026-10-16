package com.linroid.ketch.torrent.engine

import com.linroid.ketch.torrent.TorrentConfig
import com.linroid.ketch.torrent.api.TorrentReachability
import com.linroid.ketch.torrent.api.TorrentReachabilityStatus
import com.linroid.ketch.torrent.portmap.PortMapLayers
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

  @Test
  fun status_forwardedOnlyOnceTheDhtAgrees() {
    val public = PortMapLayers(2, 7081, "20.0.113.7", public = true)
    fun status(observed: String?, dht: Boolean = true, incoming: Long = 0,
      layers: PortMapLayers? = public) = reachabilityStatus(incoming, layers, observed, dht)
    assertEquals(TorrentReachabilityStatus.FORWARDED, status("20.0.113.7"))
    // The DHT has not seen us yet: not confirmed.
    assertEquals(TorrentReachabilityStatus.UNKNOWN, status(null))
    // Nothing could confirm it without DHT.
    assertEquals(TorrentReachabilityStatus.FORWARDED, status(null, dht = false))
    assertEquals(TorrentReachabilityStatus.BLOCKED, status("20.0.113.99"))
    assertEquals(TorrentReachabilityStatus.BLOCKED,
      status(null, layers = public.copy(externalAddress = "10.0.0.2", public = false)))
    assertEquals(TorrentReachabilityStatus.NOT_FORWARDED, status(null, layers = null))
    assertEquals(TorrentReachabilityStatus.REACHABLE, status(null, incoming = 1, layers = null))
  }
}
