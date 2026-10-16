package com.linroid.ketch.torrent.engine

import com.linroid.ketch.torrent.TorrentConfig
import com.linroid.ketch.torrent.TorrentDiscoveryPrivacy
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.time.Duration.Companion.milliseconds

class MagnetTrackersTest {
  @Test
  fun v2MagnetWithAWebSocketTracker_looksForPeersInstead() = runTest {
    withContext(Dispatchers.Default) {
      withTimeout(20_000) {
        val engine = KotlinTorrentEngine(TorrentConfig(dhtEnabled = false),
          magnetLookupTime = 300.milliseconds)
        val magnet = "magnet:?xt=urn:btmh:1220${"ab".repeat(32)}" +
          "&tr=wss%3A%2F%2Ftracker.test%2Fannounce"
        try {
          engine.start()
          // The tracker is left out and the lookup runs, finding nothing, until it times out.
          assertFailsWith<TimeoutCancellationException> {
            engine.fetchV2Metadata(magnet, TorrentDiscoveryPrivacy.PUBLIC)
          }
        } finally {
          engine.stop()
        }
      }
    }
  }
}
