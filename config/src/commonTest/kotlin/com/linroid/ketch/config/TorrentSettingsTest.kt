package com.linroid.ketch.config

import kotlin.test.Test
import kotlin.test.assertEquals

class TorrentSettingsTest {

  @Test
  fun `trackers decode from a hand-written torrent section`() {
    val decoded = ConfigStore.toml.decodeFromString(
      KetchConfig.serializer(),
      """
      |[torrent]
      |trackers = ["udp://tracker.example:1337/announce", "https://t.example/announce"]
      """.trimMargin(),
    )
    assertEquals(
      listOf("udp://tracker.example:1337/announce", "https://t.example/announce"),
      decoded.torrent.trackers,
    )
  }

  @Test
  fun `config without a torrent section decodes to no extra trackers`() {
    val decoded = ConfigStore.toml.decodeFromString(
      KetchConfig.serializer(),
      """
      |name = "laptop"
      """.trimMargin(),
    )
    assertEquals(TorrentSettings(), decoded.torrent)
  }
}
