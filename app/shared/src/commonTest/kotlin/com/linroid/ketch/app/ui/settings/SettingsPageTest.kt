package com.linroid.ketch.app.ui.settings

import com.linroid.ketch.api.DownloadConfig
import com.linroid.ketch.api.NetworkInterfaceConfig
import com.linroid.ketch.api.NetworkInterfaceInfo
import com.linroid.ketch.api.NetworkInterfaces
import com.linroid.ketch.api.SpeedLimit
import com.linroid.ketch.app.instance.ServerState
import com.linroid.ketch.app.platform.DetectedBrowser
import com.linroid.ketch.app.platform.IntegrationStatus
import com.linroid.ketch.app.state.SpeedMode
import com.linroid.ketch.app.theme.KetchAccent
import com.linroid.ketch.config.AiSettings
import com.linroid.ketch.config.ClipboardMode
import com.linroid.ketch.config.LlmProvider
import com.linroid.ketch.config.LlmSettings
import com.linroid.ketch.config.NotificationMode
import com.linroid.ketch.config.NotificationSettings
import com.linroid.ketch.config.SearchProvider
import com.linroid.ketch.config.SearchSettings
import com.linroid.ketch.config.ServerConfig
import com.linroid.ketch.config.SpeedSettings
import com.linroid.ketch.config.ThemeMode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class SettingsPageTest {
  @Test
  fun generalSummary_lightSignal_namesThemeAndAccent() {
    assertEquals("Light · Signal", generalSummary(ThemeMode.Light, KetchAccent.Signal))
  }

  @Test
  fun notificationsSummary_countsTheEventsReported() {
    assertEquals("4 on", notificationsSummary(NotificationSettings()))
    assertEquals(
      "2 on",
      notificationsSummary(
        NotificationSettings(finished = NotificationMode.Off, queueDrained = false),
      ),
    )
    val off = NotificationSettings(
      finished = NotificationMode.Off,
      failed = NotificationMode.Off,
      queueDrained = false,
      deviceOffline = false,
    )
    assertEquals("Off", notificationsSummary(off))
  }

  @Test
  fun notificationsSummary_finishedOff_leavesOutAllDownloadsFinished() {
    val settings = NotificationSettings(finished = NotificationMode.Off, queueDrained = true)

    assertEquals("2 on", notificationsSummary(settings))
  }

  @Test
  fun integrationSummary_extensionConnected_namesTheBrowsers() {
    val chrome = DetectedBrowser("Chrome", extensionConnected = true)
    val edge = DetectedBrowser("Edge", extensionConnected = true)

    assertEquals(
      "Chrome ✓",
      integrationSummary(IntegrationStatus(listOf(chrome, DetectedBrowser("Firefox"))), true, Fill),
    )
    assertEquals(
      "Chrome + 1 ✓",
      integrationSummary(IntegrationStatus(listOf(chrome, edge)), true, Fill),
    )
    assertEquals(
      "Extension ✓",
      integrationSummary(IntegrationStatus(extensionConnected = true), true, Fill),
    )
  }

  @Test
  fun integrationSummary_noExtension_saysSoOnDesktopAndNamesTheClipboardElsewhere() {
    assertEquals("Extension not set up", integrationSummary(IntegrationStatus(), true, Fill))
    assertEquals(
      "Suggests copied links",
      integrationSummary(IntegrationStatus(), false, ClipboardMode.Suggest),
    )
  }

  @Test
  fun discoverSummary_setUp_namesProviderAndSearch() {
    val ready = AiSettings(
      enabled = true,
      llm = LlmSettings(provider = LlmProvider.Anthropic, apiKey = "sk-ant"),
      search = SearchSettings(provider = SearchProvider.Brave, apiKey = "key"),
    )

    assertEquals("Anthropic · Brave search", discoverSummary(ready, ready))
    val noSearch = ready.copy(search = SearchSettings())
    assertEquals("Anthropic", discoverSummary(noSearch, noSearch))
    val off = ready.copy(enabled = false)
    assertEquals("Off", discoverSummary(off, off))
  }

  @Test
  fun discoverSummary_keyOnlyInTheEnvironment_countsAsSetUp() {
    val saved = AiSettings(enabled = true, llm = LlmSettings(provider = LlmProvider.OpenAi))
    val effective = saved.copy(llm = saved.llm.copy(apiKey = "sk-env"))

    assertEquals("Not set up", discoverSummary(saved, saved))
    assertEquals("OpenAI", discoverSummary(saved, effective))
  }

  @Test
  fun downloadsSummary_namesFolderAndQueue() {
    val config = DownloadConfig(defaultDirectory = "/Users/alex/Downloads")

    assertEquals(
      "~/Downloads · 3 at a time",
      downloadsSummary(config.copy(maxConcurrentDownloads = 3)),
    )
    assertEquals(
      "Downloads folder · all at once",
      downloadsSummary(DownloadConfig(maxConcurrentDownloads = 0)),
    )
  }

  @Test
  fun shortFolder_homeAndDeeperFolders_keepTheEndThatMatters() {
    assertEquals("~/Videos", shortFolder("/home/sam/Videos/"))
    assertEquals("Linux ISOs", shortFolder("/Users/alex/Movies/Linux ISOs"))
    assertEquals("Downloads", shortFolder("D:\\Downloads"))
    assertEquals(
      "Download",
      shortFolder("content://com.android.externalstorage.documents/tree/primary%3ADownload"),
    )
  }

  @Test
  fun speedSummary_eachMode_namesTheLimitInEffect() {
    val slow = SpeedLimit.mbps(1)
    val unlimited = SpeedSettings()
    val capped = SpeedSettings(standard = SpeedLimit.mbps(20))

    assertEquals("Full speed", speedSummary(SpeedMode.Full, unlimited, slow))
    assertEquals("Full speed · 20 MB/s", speedSummary(SpeedMode.Full, capped, slow))
    assertEquals("Slow lane · 1 MB/s", speedSummary(SpeedMode.SlowLane, unlimited, slow))
    assertEquals(
      "Auto · Slow lane 1 MB/s",
      speedSummary(SpeedMode.Auto(slowLane = true), unlimited, slow),
    )
    assertEquals("Auto · Full speed", speedSummary(SpeedMode.Auto(), unlimited, slow))
  }

  @Test
  fun remoteSpeedSummary_namesTheLimit() {
    assertEquals("No limit", remoteSpeedSummary(SpeedLimit.Unlimited))
    assertEquals("Limit 5 MB/s", remoteSpeedSummary(SpeedLimit.mbps(5)))
  }

  @Test
  fun networkSummary_selectedNetworks_joinTheirNames() {
    val available = listOf(
      NetworkInterfaceInfo("en0", "en0", listOf("192.168.1.20")),
      NetworkInterfaceInfo("en7", "en7", listOf("10.0.0.4")),
    )
    val spread = NetworkInterfaces(true, available, NetworkInterfaceConfig(listOf("en0", "en7")))

    assertEquals("en0 + en7", networkSummary(spread))
    assertEquals("System default", networkSummary(spread.copy(config = NetworkInterfaceConfig())))
    assertEquals("System default", networkSummary(NetworkInterfaces(supported = false)))
    assertNull(networkSummary(null))
  }

  @Test
  fun trackersSummary_countsThem() {
    assertEquals("No extra trackers", trackersSummary(0))
    assertEquals("1 tracker", trackersSummary(1))
    assertEquals("3 trackers", trackersSummary(3))
  }

  @Test
  fun sharingSummary_eachServerState_saysWhoCanConnect() {
    val shared = ServerState.Running(ServerConfig(port = 8642))
    val wifi = listOf(NetworkInterfaceInfo("en0", "en0", listOf("192.168.1.20")))

    assertEquals("Off", sharingSummary(ServerState.Stopped, wifi))
    assertEquals("On · 192.168.1.20:8642", sharingSummary(shared, wifi))
    assertEquals("On · port 8642", sharingSummary(shared, null))
    assertEquals(
      "This device only",
      sharingSummary(ServerState.Running(ServerConfig(host = ServerConfig.LOOPBACK_HOST)), wifi),
    )
  }

  private companion object {
    val Fill = ClipboardMode.Fill
  }
}
