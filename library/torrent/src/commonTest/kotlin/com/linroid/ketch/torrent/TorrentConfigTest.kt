package com.linroid.ketch.torrent

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.time.Duration.Companion.seconds

class TorrentConfigTest {

  @Test
  fun metadataTimeout_convertsFromSeconds() {
    val config = TorrentConfig(metadataTimeoutSeconds = 60)
    assertEquals(60.seconds, config.metadataTimeout)
  }

  @Test
  fun defaults_areReasonable() {
    val config = TorrentConfig()
    assertEquals(true, config.dhtEnabled)
    assertEquals(5, config.maxActiveTorrents)
    assertEquals(120, config.metadataTimeoutSeconds)
    assertEquals(100, config.connectionsPerTorrent)
    assertEquals(false, config.enableUpload)
    assertEquals(0, config.listenPort)
    assertEquals(0L, config.uploadRateLimit)
    assertEquals(TorrentUploadPolicy.DISABLED, config.effectiveUploadPolicy)
  }

  @Test
  fun uploadRateLimitMustNotBeNegative() {
    assertEquals(1024L, TorrentConfig(uploadRateLimit = 1024).uploadRateLimit)
    assertFailsWith<IllegalArgumentException> { TorrentConfig(uploadRateLimit = -1) }
  }
}
