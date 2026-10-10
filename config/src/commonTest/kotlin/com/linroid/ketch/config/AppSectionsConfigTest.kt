package com.linroid.ketch.config

import com.linroid.ketch.api.DownloadCategory
import com.linroid.ketch.api.DownloadConfig
import com.linroid.ketch.api.DownloadPriority
import com.linroid.ketch.api.SpeedLimit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class AppSectionsConfigTest {

  @Test
  fun decode_configWithoutAppSections_usesDefaults() {
    val decoded = ConfigStore.toml.decodeFromString(
      KetchConfig.serializer(),
      """
      |name = "laptop"
      |
      |[server]
      |port = 8642
      |
      |[download]
      |maxConcurrentDownloads = 3
      """.trimMargin(),
    )
    assertEquals(SpeedSettings(), decoded.speed)
    assertEquals(UiPreferences(), decoded.ui)
    assertEquals(DesktopSettings(), decoded.desktop)
    assertEquals(NotificationSettings(), decoded.notifications)
    assertEquals(IntegrationSettings(), decoded.integration)
  }

  @Test
  fun encode_defaultConfig_decodesToDefaults() {
    val encoded = ConfigStore.toml.encodeToString(KetchConfig.serializer(), KetchConfig())
    val decoded = ConfigStore.toml.decodeFromString(KetchConfig.serializer(), encoded)
    assertEquals(KetchConfig(), decoded, encoded)
  }

  @Test
  fun encode_populatedAppSections_roundTrips() {
    val encoded = ConfigStore.toml.encodeToString(KetchConfig.serializer(), populatedAppSections)
    val decoded = ConfigStore.toml.decodeFromString(KetchConfig.serializer(), encoded)
    assertEquals(populatedAppSections, decoded, encoded)
  }

  @Test
  fun decode_handWrittenSpeedRules_readsDaysAndTimes() {
    val decoded = ConfigStore.toml.decodeFromString(
      KetchConfig.serializer(),
      """
      |[speed]
      |mode = "auto"
      |slowLane = "1m"
      |
      |[[speed.rules]]
      |days = ["sat", "sun"]
      |start = "22:00"
      |end = "07:00"
      """.trimMargin(),
    )
    assertEquals(
      SpeedSettings(
        mode = SpeedLimitMode.Auto,
        slowLane = SpeedLimit.mbps(1),
        rules = listOf(
          SpeedRule(days = setOf(Weekday.Saturday, Weekday.Sunday), start = "22:00", end = "07:00"),
        ),
      ),
      decoded.speed,
    )
  }

  @Test
  fun decode_handWrittenCategories_readsRulesInOrder() {
    val decoded = ConfigStore.toml.decodeFromString(
      KetchConfig.serializer(),
      """
      |[download]
      |maxConcurrentDownloads = 3
      |
      |[[download.categories]]
      |folder = "Video"
      |extensions = ["mp4", "mkv"]
      |mimeTypes = ["video/*"]
      |
      |[[download.categories]]
      |folder = "Software/GitHub"
      |hosts = ["github.com"]
      """.trimMargin(),
    )
    assertEquals(
      listOf(
        DownloadCategory(
          folder = "Video",
          extensions = listOf("mp4", "mkv"),
          mimeTypes = listOf("video/*"),
        ),
        DownloadCategory(folder = "Software/GitHub", hosts = listOf("github.com")),
      ),
      decoded.download.categories,
    )
    assertEquals(3, decoded.download.maxConcurrentDownloads)
  }

  @Test
  fun encode_categories_roundTrips() {
    val config = KetchConfig(
      download = DownloadConfig(
        categories = listOf(
          DownloadCategory(folder = "Music", extensions = listOf("mp3", "flac")),
          DownloadCategory(folder = "Docs", mimeTypes = listOf("application/pdf")),
          DownloadCategory(folder = "Mirror", hosts = listOf("mirror.example.org")),
        ),
      ),
    )
    val encoded = ConfigStore.toml.encodeToString(KetchConfig.serializer(), config)
    val decoded = ConfigStore.toml.decodeFromString(KetchConfig.serializer(), encoded)
    assertEquals(config, decoded, encoded)
  }

  @Test
  fun decode_categoryFolderLeavingDownloadFolder_fails() {
    assertFailsWith<IllegalArgumentException> {
      ConfigStore.toml.decodeFromString(
        KetchConfig.serializer(),
        """
        |[[download.categories]]
        |folder = "../Escape"
        |extensions = ["exe"]
        """.trimMargin(),
      )
    }
  }

  @Test
  fun decode_unknownWeekday_fails() {
    assertFailsWith<IllegalArgumentException> {
      ConfigStore.toml.decodeFromString(
        KetchConfig.serializer(),
        """
        |[[speed.rules]]
        |days = ["someday"]
        """.trimMargin(),
      )
    }
  }
}

/** Every app section with a non-default value, including device ids that need quoted keys. */
internal val populatedAppSections = KetchConfig(
  speed = SpeedSettings(
    mode = SpeedLimitMode.Auto,
    standard = SpeedLimit.mbps(20),
    slowLane = SpeedLimit.kbps(512),
    rules = listOf(
      SpeedRule(days = setOf(Weekday.Monday, Weekday.Friday), start = "09:00", end = "18:00"),
      SpeedRule(start = "23:00", end = "06:30"),
    ),
  ),
  ui = UiPreferences(
    layout = DownloadsLayout.Table,
    table = mapOf("All" to "name:240,size:96", "Done" to "name:300"),
    sort = mapOf("Waiting" to "-added"),
    sidebarCollapsed = true,
    lastDeviceId = "nas.local:8642",
    inspectorWidth = 400,
    intake = mapOf(
      "local" to IntakePreferences(
        folder = "/Users/me/Movies",
        connections = 8,
        favoriteFolders = listOf("/Users/me/Movies", "/Volumes/Data/ISO"),
      ),
      "[fd00::20]:8642" to IntakePreferences(priority = DownloadPriority.HIGH),
      "nas.local:8642" to IntakePreferences(folder = "/volume1/downloads"),
    ),
    intakeAdvancedOpen = true,
    clipboardMode = ClipboardMode.Suggest,
    quickAdd = false,
    lastClipHash = "5f2a",
    observedPeak = 12_582_912,
    observedPeakAt = 1_790_000_000_000,
    onboardingVersion = 1,
    setupChecklistShownAt = 1_789_000_000_000,
    setupChecklistDismissed = true,
    density = DensityMode.Comfortable,
    reduceMotion = true,
  ),
  desktop = DesktopSettings(
    closeAction = CloseAction.Background,
    openAtLogin = true,
    startHidden = false,
    dockBadge = DockBadgeMode.FailuresOnly,
    checkForUpdates = false,
  ),
  notifications = NotificationSettings(
    finished = NotificationMode.InApp,
    failed = NotificationMode.Off,
    queueDrained = false,
    deviceOffline = false,
    onlyInBackground = false,
    mutedDevices = listOf("192.168.1.20:8642"),
    successSound = false,
    successVibration = false,
  ),
  integration = IntegrationSettings(magnetHandler = true, torrentFileHandler = true),
)
