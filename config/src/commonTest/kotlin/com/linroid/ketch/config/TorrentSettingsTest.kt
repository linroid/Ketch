package com.linroid.ketch.config

import com.linroid.ketch.api.SpeedLimit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

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
  fun `listen port outside 0 to 65535 is rejected`() {
    assertFailsWith<IllegalArgumentException> { TorrentSettings(listenPort = -1) }
    assertFailsWith<IllegalArgumentException> { TorrentSettings(listenPort = 65536) }
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

  @Test
  fun `upload decodes every id`() {
    for (mode in TorrentUploadMode.entries) {
      assertEquals(mode, torrent("upload = \"${mode.id}\"").upload, mode.id)
    }
    assertEquals(
      listOf("off", "while-downloading", "seed"),
      TorrentUploadMode.entries.map { it.id },
    )
  }

  @Test
  fun `unknown upload mode loads as off`() {
    assertEquals(TorrentUploadMode.Off, torrent("upload = \"always\"").upload)
  }

  @Test
  fun `invalid upload limit loads as unlimited`() {
    assertEquals(SpeedLimit.Unlimited, torrent("uploadLimit = \"fast\"").uploadLimit)
    assertEquals(SpeedLimit.kbps(500), torrent("uploadLimit = \"500k\"").uploadLimit)
  }

  @Test
  fun `upload values of another type load as off and never fail the file`() {
    for (value in listOf("false", "true", "1", "1.5", "[\"seed\"]")) {
      assertEquals(TorrentUploadMode.Off, torrent("upload = $value").upload, value)
    }
  }

  @Test
  fun `upload limit as a bare number is bytes per second`() {
    assertEquals(SpeedLimit.of(1_048_576), torrent("uploadLimit = 1048576").uploadLimit)
    assertEquals(SpeedLimit.Unlimited, torrent("uploadLimit = 0").uploadLimit)
    assertEquals(SpeedLimit.Unlimited, torrent("uploadLimit = -5").uploadLimit)
  }

  @Test
  fun `upload limit of another type or too large loads as unlimited`() {
    for (value in listOf("true", "1.5", "[\"1m\"]", "\"9000000000000m\"")) {
      assertEquals(SpeedLimit.Unlimited, torrent("uploadLimit = $value").uploadLimit, value)
    }
  }

  @Test
  fun `keys after a value of the wrong type still decode`() {
    // What FileConfigStore.load reads: nothing here may move config.toml aside.
    val decoded = ConfigStore.decode(
      """
      |name = "laptop"
      |[torrent]
      |upload = false
      |uploadLimit = true
      |trackers = ["udp://tracker.example:1337/announce"]
      |trackerList = false
      |[server]
      |port = 9000
      """.trimMargin(),
    )
    assertEquals(TorrentUploadMode.Off, decoded.torrent.upload)
    assertEquals(SpeedLimit.Unlimited, decoded.torrent.uploadLimit)
    assertEquals(listOf("udp://tracker.example:1337/announce"), decoded.torrent.trackers)
    assertEquals(false, decoded.torrent.trackerList)
    assertEquals("laptop", decoded.name)
    assertEquals(9000, decoded.server.port)
  }

  @Test
  fun `upload settings round trip through toml`() {
    val settings =
      TorrentSettings(upload = TorrentUploadMode.Seed, uploadLimit = SpeedLimit.mbps(1))
    val encoded = ConfigStore.toml
      .encodeToString(KetchConfig.serializer(), KetchConfig(torrent = settings))
    val decoded = ConfigStore.toml.decodeFromString(KetchConfig.serializer(), encoded)
    assertEquals(settings, decoded.torrent, encoded)
  }

  /** The torrent settings of a config file whose `[torrent]` section holds [line]. */
  private fun torrent(line: String): TorrentSettings = ConfigStore.toml.decodeFromString(
    KetchConfig.serializer(),
    """
    |[torrent]
    |$line
    """.trimMargin(),
  ).torrent
}
