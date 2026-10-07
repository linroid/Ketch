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
    val unsubscribed = TorrentSettings(trackerList = false, trackerListUrls = listOf("https://l/t"))
    for (settings in listOf(TorrentSettings(trackers), TorrentSettings(), unsubscribed)) {
      val encoded = ConfigStore.toml
        .encodeToString(KetchConfig.serializer(), KetchConfig(torrent = settings))
      val decoded = ConfigStore.toml.decodeFromString(KetchConfig.serializer(), encoded)
      assertEquals(settings, decoded.torrent, encoded)
    }
  }

  @Test
  fun `tracker lists are subscribed by default and only while switched on`() {
    assertEquals(TorrentSettings.DEFAULT_TRACKER_LISTS, TorrentSettings().subscribedTrackerLists)
    assertEquals(emptyList(), TorrentSettings(trackerList = false).subscribedTrackerLists)
    val custom = TorrentSettings(trackerListUrls = listOf(" https://l.example/t.txt ", "", " "))
    assertEquals(listOf("https://l.example/t.txt"), custom.subscribedTrackerLists)
  }

  @Test
  fun `a list saved by 0_3_0 is kept until the lists change`() {
    val decoded = ConfigStore.toml.decodeFromString(
      KetchConfig.serializer(),
      """
      |[torrent]
      |trackerList = true
      |trackerListUrl = "https://l.example/mine.txt"
      """.trimMargin(),
    )
    assertEquals(listOf("https://l.example/mine.txt"), decoded.torrent.subscribedTrackerLists)
    val edited = decoded.torrent.withTrackerLists(listOf("https://l.example/other.txt"))
    assertEquals(listOf("https://l.example/other.txt"), edited.subscribedTrackerLists)
  }

  @Test
  fun `the default list saved by 0_3_0 subscribes to every default list`() {
    val decoded = ConfigStore.toml.decodeFromString(
      KetchConfig.serializer(),
      """
      |[torrent]
      |trackerListUrl = "${TorrentSettings.DEFAULT_TRACKER_LISTS.first()}"
      """.trimMargin(),
    )
    assertEquals(TorrentSettings.DEFAULT_TRACKER_LISTS, decoded.torrent.subscribedTrackerLists)
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
