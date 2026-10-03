package com.linroid.ketch.app.ui.settings

import com.linroid.ketch.api.DownloadConfig
import com.linroid.ketch.api.NetworkInterfaceConfig
import com.linroid.ketch.api.NetworkInterfaceInfo
import com.linroid.ketch.api.NetworkInterfaces
import com.linroid.ketch.api.SpeedLimit
import com.linroid.ketch.app.i18n.load
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
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class SettingsPageTest {
  @Test
  fun generalSummary_lightSignal_namesThemeAndAccent() = runTest {
    assertEquals("Light · Signal", generalSummary(ThemeMode.Light, KetchAccent.Signal).load())
  }

  @Test
  fun notificationsSummary_countsTheEventsReported() = runTest {
    assertEquals("4 on", notificationsSummary(NotificationSettings()).load())
    assertEquals(
      "2 on",
      notificationsSummary(
        NotificationSettings(finished = NotificationMode.Off, queueDrained = false),
      ).load(),
    )
    val off = NotificationSettings(
      finished = NotificationMode.Off,
      failed = NotificationMode.Off,
      queueDrained = false,
      deviceOffline = false,
    )
    assertEquals("Off", notificationsSummary(off).load())
  }

  @Test
  fun notificationsSummary_finishedOff_leavesOutAllDownloadsFinished() = runTest {
    val settings = NotificationSettings(finished = NotificationMode.Off, queueDrained = true)

    assertEquals("2 on", notificationsSummary(settings).load())
  }

  @Test
  fun integrationSummary_extensionConnected_namesTheBrowsers() = runTest {
    val chrome = DetectedBrowser("Chrome", extensionConnected = true)
    val edge = DetectedBrowser("Edge", extensionConnected = true)
    val chromeAndFirefox = IntegrationStatus(listOf(chrome, DetectedBrowser("Firefox")))

    assertEquals("Chrome ✓", integrationSummary(chromeAndFirefox, true, Fill).load())
    assertEquals(
      "Chrome + 1 ✓",
      integrationSummary(IntegrationStatus(listOf(chrome, edge)), true, Fill).load(),
    )
    assertEquals(
      "Extension ✓",
      integrationSummary(IntegrationStatus(extensionConnected = true), true, Fill).load(),
    )
  }

  @Test
  fun integrationSummary_noExtension_saysSoOnDesktopAndNamesTheClipboardElsewhere() = runTest {
    assertEquals(
      "Extension not set up",
      integrationSummary(IntegrationStatus(), true, Fill).load(),
    )
    assertEquals(
      "Suggests copied links",
      integrationSummary(IntegrationStatus(), false, ClipboardMode.Suggest).load(),
    )
  }

  @Test
  fun discoverSummary_setUp_namesProviderAndSearch() = runTest {
    val ready = AiSettings(
      enabled = true,
      llm = LlmSettings(provider = LlmProvider.Anthropic, apiKey = "sk-ant"),
      search = SearchSettings(provider = SearchProvider.Brave, apiKey = "key"),
    )

    assertEquals("Anthropic · Brave search", discoverSummary(ready, ready).load())
    val noSearch = ready.copy(search = SearchSettings())
    assertEquals("Anthropic", discoverSummary(noSearch, noSearch).load())
    val off = ready.copy(enabled = false)
    assertEquals("Off", discoverSummary(off, off).load())
  }

  @Test
  fun discoverSummary_keyOnlyInTheEnvironment_countsAsSetUp() = runTest {
    val saved = AiSettings(enabled = true, llm = LlmSettings(provider = LlmProvider.OpenAi))
    val effective = saved.copy(llm = saved.llm.copy(apiKey = "sk-env"))

    assertEquals("Not set up", discoverSummary(saved, saved).load())
    assertEquals("OpenAI", discoverSummary(saved, effective).load())
  }

  @Test
  fun downloadsSummary_namesFolderAndQueue() = runTest {
    val config = DownloadConfig(defaultDirectory = "/Users/alex/Downloads")

    assertEquals(
      "~/Downloads · 3 at a time",
      downloadsSummary(config.copy(maxConcurrentDownloads = 3)).load(),
    )
    assertEquals(
      "Downloads folder · all at once",
      downloadsSummary(DownloadConfig(maxConcurrentDownloads = 0)).load(),
    )
  }

  @Test
  fun shortFolder_homeAndDeeperFolders_keepTheEndThatMatters() = runTest {
    assertEquals("~/Videos", shortFolder("/home/sam/Videos/").load())
    assertEquals("Linux ISOs", shortFolder("/Users/alex/Movies/Linux ISOs").load())
    assertEquals("Downloads", shortFolder("D:\\Downloads").load())
    assertEquals("~\\Downloads", shortFolder("C:\\Users\\sam\\Downloads\\").load())
    assertEquals("Linux ISOs", shortFolder("C:\\Users\\sam\\Videos\\Linux ISOs").load())
    assertEquals(
      "Download",
      shortFolder("content://com.android.externalstorage.documents/tree/primary%3ADownload")
        .load(),
    )
  }

  @Test
  fun speedSummary_eachMode_namesTheLimitInEffect() = runTest {
    val slow = SpeedLimit.mbps(1)
    val unlimited = SpeedSettings()
    val capped = SpeedSettings(standard = SpeedLimit.mbps(20))

    assertEquals("Full speed", speedSummary(SpeedMode.Full, unlimited, slow).load())
    assertEquals("Full speed · 20 MB/s", speedSummary(SpeedMode.Full, capped, slow).load())
    assertEquals("Slow lane · 1 MB/s", speedSummary(SpeedMode.SlowLane, unlimited, slow).load())
    assertEquals(
      "Auto · Slow lane 1 MB/s",
      speedSummary(SpeedMode.Auto(slowLane = true), unlimited, slow).load(),
    )
    assertEquals("Auto · Full speed", speedSummary(SpeedMode.Auto(), unlimited, slow).load())
  }

  @Test
  fun remoteSpeedSummary_namesTheLimit() = runTest {
    assertEquals("No limit", remoteSpeedSummary(SpeedLimit.Unlimited).load())
    assertEquals("Limit 5 MB/s", remoteSpeedSummary(SpeedLimit.mbps(5)).load())
  }

  @Test
  fun networkSummary_selectedNetworks_joinTheirNames() = runTest {
    val available = listOf(
      NetworkInterfaceInfo("en0", "en0", listOf("192.168.1.20")),
      NetworkInterfaceInfo("en7", "en7", listOf("10.0.0.4")),
    )
    val spread = NetworkInterfaces(true, available, NetworkInterfaceConfig(listOf("en0", "en7")))

    assertEquals("en0 + en7", networkSummary(spread).load())
    assertEquals(
      "System default",
      networkSummary(spread.copy(config = NetworkInterfaceConfig())).load(),
    )
    assertEquals("System default", networkSummary(NetworkInterfaces(supported = false)).load())
    assertNull(networkSummary(null))
  }

  @Test
  fun trackersSummary_countsThem() = runTest {
    assertEquals("No extra trackers", trackersSummary(0).load())
    assertEquals("1 tracker", trackersSummary(1).load())
    assertEquals("3 trackers", trackersSummary(3).load())
  }

  @Test
  fun sharingSummary_eachServerState_saysWhoCanConnect() = runTest {
    val shared = ServerState.Running(ServerConfig(port = 8642))
    val wifi = listOf(NetworkInterfaceInfo("en0", "en0", listOf("192.168.1.20")))

    assertEquals("Off", sharingSummary(ServerState.Stopped, wifi).load())
    assertEquals("On · 192.168.1.20:8642", sharingSummary(shared, wifi).load())
    assertEquals("On · port 8642", sharingSummary(shared, null).load())
    assertEquals(
      "This device only",
      sharingSummary(ServerState.Running(ServerConfig(host = ServerConfig.LOOPBACK_HOST)), wifi)
        .load(),
    )
  }

  private companion object {
    val Fill = ClipboardMode.Fill
  }
}
