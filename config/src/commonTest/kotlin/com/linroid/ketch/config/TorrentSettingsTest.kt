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
  fun `trackers saved by the apps load back including an emptied list`() {
    val trackers = listOf(
      "https://t.example/announce?passkey=a1b2&info=\"x\"",
      "udp://[2001:db8::1]:6969/announce",
    )
    for (settings in listOf(TorrentSettings(trackers), TorrentSettings())) {
      val encoded = ConfigStore.toml
        .encodeToString(KetchConfig.serializer(), KetchConfig(torrent = settings))
      val decoded = ConfigStore.toml.decodeFromString(KetchConfig.serializer(), encoded)
      assertEquals(settings, decoded.torrent, encoded)
    }
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
