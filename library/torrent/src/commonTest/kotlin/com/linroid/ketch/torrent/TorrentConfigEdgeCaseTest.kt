package com.linroid.ketch.torrent

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * Additional edge case tests for [TorrentConfig] beyond the basics
 * covered in [TorrentConfigTest].
 */
class TorrentConfigEdgeCaseTest {

  @Test
  fun metadataTimeout_zeroSeconds() {
    val config = TorrentConfig(metadataTimeoutSeconds = 0)
    assertEquals(0.seconds, config.metadataTimeout)
  }

  @Test
  fun metadataTimeout_largeValue() {
    val config = TorrentConfig(metadataTimeoutSeconds = 3600)
    assertEquals(3600.seconds, config.metadataTimeout)
  }

  @Test
  fun customConfig_allFieldsOverridden() {
    val config = TorrentConfig(
      dhtEnabled = false,
      maxActiveTorrents = 10,
      metadataTimeoutSeconds = 300,
      connectionsPerTorrent = 50,
      enableUpload = true,
      listenPort = 6881,
    )
    assertFalse(config.dhtEnabled)
    assertEquals(10, config.maxActiveTorrents)
    assertEquals(300, config.metadataTimeoutSeconds)
    assertEquals(50, config.connectionsPerTorrent)
    assertTrue(config.enableUpload)
    assertEquals(6881, config.listenPort)
  }

  @Test
  fun defaults_dhtEnabled() {
    // DHT should be enabled by default for peer discovery
    assertTrue(TorrentConfig().dhtEnabled)
  }

  @Test
  fun defaults_uploadDisabled() {
    // Upload (seeding) should be disabled by default for a download
    // manager library
    assertFalse(TorrentConfig().enableUpload)
  }

  @Test
  fun defaults_listenPortZero_meansRandom() {
    assertEquals(0, TorrentConfig().listenPort)
  }
}
