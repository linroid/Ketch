package com.linroid.ketch.app.snapshot

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.linroid.ketch.api.SpeedLimit
import com.linroid.ketch.app.RecordingConfigStore
import com.linroid.ketch.app.instance.InstanceFactory
import com.linroid.ketch.app.instance.InstanceManager
import com.linroid.ketch.app.instance.RemoteInstance
import com.linroid.ketch.app.state.AppController
import com.linroid.ketch.app.state.ObservedPeak
import com.linroid.ketch.app.state.SettingsCategory
import com.linroid.ketch.app.state.SettingsTarget
import com.linroid.ketch.app.state.SpeedModeController
import com.linroid.ketch.app.state.SpeedScheduler
import com.linroid.ketch.app.state.deviceId
import com.linroid.ketch.app.theme.KetchDensity
import com.linroid.ketch.app.theme.KetchTheme
import com.linroid.ketch.app.ui.settings.SettingsCategoryContent
import com.linroid.ketch.app.ui.settings.SettingsCategoryPage
import com.linroid.ketch.config.DensityMode
import com.linroid.ketch.config.IntakePreferences
import com.linroid.ketch.config.KetchConfig
import com.linroid.ketch.config.ServerConfig
import com.linroid.ketch.config.SpeedLimitMode
import com.linroid.ketch.config.SpeedRule
import com.linroid.ketch.config.SpeedSettings
import com.linroid.ketch.config.TorrentSettings
import com.linroid.ketch.config.Weekday
import com.linroid.ketch.remote.ConnectionState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.datetime.TimeZone
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.time.Duration.Companion.days

/**
 * The device pages of Settings (Downloads, Speed, Network, BitTorrent and Sharing) on their own,
 * at the width of the Settings window's page pane and on a phone, and through the app root; see
 * [SnapshotHarness] for how to run it.
 */
class SettingsDeviceSnapshots {
  @BeforeTest
  fun enabled() = requireSnapshots()

  @Test
  fun speed_autoWithRules_showsModeLimitsAndRules() {
    pageSnapshots("settings-speed", SettingsCategory.Speed)
    pageSnapshots(
      name = "settings-speed-full",
      category = SettingsCategory.Speed,
      sizes = listOf(Pane),
      themes = listOf(SnapshotTheme.Light),
      setup = DeviceSetup(speed = SpeedSettings(standard = SpeedLimit.Unlimited)),
    )
  }

  @Test
  fun downloads_pinnedFolders_showsFolderAndQueue() {
    pageSnapshots("settings-downloads", SettingsCategory.Downloads)
    pageSnapshots(
      name = "settings-downloads-android-data",
      category = SettingsCategory.Downloads,
      sizes = listOf(Pane),
      themes = listOf(SnapshotTheme.Light),
      setup = DeviceSetup(
        folder = "/storage/emulated/0/Android/data/com.linroid.ketch/files/Download",
      ),
    )
  }

  @Test
  fun network_twoOfThreeSelected_showsChips() {
    pageSnapshots("settings-network", SettingsCategory.Network, sizes = listOf(Pane, PhonePage))
    pageSnapshots(
      name = "settings-network-error",
      category = SettingsCategory.Network,
      sizes = listOf(Pane),
      themes = listOf(SnapshotTheme.Light),
      setup = DeviceSetup(networksFail = true),
    )
  }

  @Test
  fun bitTorrent_trackers_listsThem() {
    pageSnapshots("settings-bittorrent", SettingsCategory.BitTorrent, sizes = listOf(Pane))
  }

  @Test
  fun sharing_offAndOn_showsThePairCard() {
    pageSnapshots(
      name = "settings-sharing-off",
      category = SettingsCategory.Sharing,
      setup = DeviceSetup(sharing = false),
    )
    pageSnapshots("settings-sharing-on", SettingsCategory.Sharing)
    pageSnapshots(
      name = "settings-sharing-nocode",
      category = SettingsCategory.Sharing,
      sizes = listOf(Pane),
      themes = listOf(SnapshotTheme.Light),
      setup = DeviceSetup(token = null),
    )
    pageSnapshots(
      name = "settings-sharing-restart",
      category = SettingsCategory.Sharing,
      sizes = listOf(Pane),
      setup = DeviceSetup(pendingPort = 9000),
      interact = {
        click(AdvancedRowX, AdvancedRowY)
        hover(0.dp, 0.dp)
      },
    )
  }

  @Test
  fun remote_devicePages_saveOnTheDeviceUntilItRestarts() {
    val remote = DeviceSetup(remote = true)
    for (category in listOf(SettingsCategory.Downloads, SettingsCategory.Speed)) {
      pageSnapshots(
        name = "settings-remote-${category.name.lowercase()}",
        category = category,
        sizes = listOf(Pane),
        setup = remote,
      )
    }
    for (category in listOf(SettingsCategory.BitTorrent, SettingsCategory.Sharing)) {
      pageSnapshots(
        name = "settings-remote-${category.name.lowercase()}",
        category = category,
        sizes = listOf(PhonePage),
        themes = listOf(SnapshotTheme.Light),
        setup = remote,
      )
    }
  }

  @Test
  fun app_deviceSettingsPages_openThroughIntents() {
    val sizes = listOf(SnapshotSize.Medium, SnapshotSize.Phone)
    appPageSnapshots("app-settings-speed", sizes) { openSettings(SettingsTarget.Page.Speed) }
    appPageSnapshots("app-settings-sharing", sizes) { openSettings(SettingsTarget.Page.Sharing) }
    appPageSnapshots("app-device-picker-sharing", listOf(SnapshotSize.Phone)) { openDevices() }
  }

  /**
   * Renders [category]'s page in the Settings page frame at each of [sizes] in each of
   * [themes], over a fresh [DeviceEnvironment] each time.
   */
  private fun pageSnapshots(
    name: String,
    category: SettingsCategory,
    sizes: List<SnapshotSize> = listOf(Pane, PhonePage),
    themes: List<SnapshotTheme> = SnapshotTheme.entries,
    setup: DeviceSetup = DeviceSetup(),
    interact: suspend SnapshotScene.() -> Unit = {},
  ) {
    for (size in sizes) {
      for (theme in themes) {
        withEnvironment({ DeviceEnvironment(setup, theme, size.density.toMode()) }) { environment ->
          SnapshotHarness.capture("$name-${theme.id}-${size.id}", size, interact) {
            PageFrame(environment, theme, size.density, category, remote = setup.remote)
          }
        }
      }
    }
  }

  /** Renders the app root at each of [sizes] in light and dark, after [open] runs. */
  private fun appPageSnapshots(
    name: String,
    sizes: List<SnapshotSize>,
    open: suspend AppScenario.() -> Unit,
  ) {
    for (size in sizes) {
      for (theme in SnapshotTheme.entries) {
        withEnvironment({ DeviceEnvironment(DeviceSetup(), theme, size.density.toMode()) }) {
          captureApp(name, size, theme, it, open)
        }
      }
    }
  }

  private companion object {
    /** The page pane of the 860 dp Settings window, beside its 232 dp category list. */
    val Pane = SnapshotSize(628.dp, 1240.dp, KetchDensity.Compact)

    /** A phone tall enough for a whole page. */
    val PhonePage = SnapshotSize(390.dp, 1720.dp, KetchDensity.Comfortable)

    /** The Advanced row of the Sharing page in [Pane], under the restart notice. */
    val AdvancedRowX = 300.dp
    val AdvancedRowY = 465.dp
  }
}

@Composable
private fun PageFrame(
  environment: DeviceEnvironment,
  theme: SnapshotTheme,
  density: KetchDensity,
  category: SettingsCategory,
  remote: Boolean,
) {
  val state = environment.controller.state
  val instances = state.instances.value
  KetchTheme(
    darkTheme = theme == SnapshotTheme.Dark,
    density = density.toMode(),
    reduceMotion = true,
  ) {
    Box(Modifier.fillMaxSize().background(KetchTheme.colors.canvas)) {
      SettingsCategoryPage(
        category = category,
        inset = KetchTheme.density.pagePadding,
        onBack = null,
        content = {
          SettingsCategoryContent(
            category = it,
            state = state,
            device = if (remote) instances.last() else instances.first(),
            systemDeviceName = environment.data.deviceName,
          )
        },
      )
    }
  }
}

/**
 * What a device page snapshot starts from.
 *
 * @property speed speed settings of the embedded device.
 * @property sharing whether the device is shared with other devices.
 * @property token the access code of its sharing server.
 * @property folder the download folder chosen for it; `null` uses its Downloads folder.
 * @property pendingPort a port saved after sharing started, which asks for a restart.
 * @property remote whether the pages show the NAS, a remote device, instead of this one.
 * @property networksFail whether asking the device for its networks fails.
 */
private data class DeviceSetup(
  val speed: SpeedSettings = SpeedSettings(
    mode = SpeedLimitMode.Auto,
    standard = SpeedLimit.mbps(20),
    rules = listOf(
      SpeedRule(
        days = setOf(
          Weekday.Monday,
          Weekday.Tuesday,
          Weekday.Wednesday,
          Weekday.Thursday,
          Weekday.Friday,
        ),
        start = "09:00",
        end = "18:00",
      ),
      SpeedRule(days = setOf(Weekday.Saturday, Weekday.Sunday), start = "20:00", end = "23:30"),
    ),
  ),
  val sharing: Boolean = true,
  val token: String? = DeviceEnvironment.TOKEN,
  val folder: String? = null,
  val pendingPort: Int? = null,
  val remote: Boolean = false,
  val networksFail: Boolean = false,
)

/**
 * The sample's embedded device with what the device pages show: a speed mode with a week of
 * observed speed, three network interfaces, extra trackers, pinned folders, and a sharing server
 * that only pretends to listen.
 */
private class DeviceEnvironment(
  setup: DeviceSetup,
  theme: SnapshotTheme,
  density: DensityMode,
) : SnapshotEnvironment {
  override val data = SampleData.downloads(
    SampleData.DOWNLOAD_CONFIG.copy(defaultDirectory = setup.folder ?: SampleData.DOWNLOAD_DIR),
  ).let { sample ->
    SampleData(
      tasks = sample.tasks,
      downloadConfig = sample.downloadConfig,
      remotes = if (setup.remote) listOf(Nas) else emptyList(),
      ui = { ui ->
        ui.copy(
          intake = mapOf(
            "local" to IntakePreferences(
              favoriteFolders = listOf("/Users/alex/Movies", "/Volumes/Archive/Linux ISO images"),
            ),
          ),
        )
      },
    )
  }
  private val api = NetworkedApi(SampleKetchApi(data), fail = setup.networksFail)
  private val store = RecordingConfigStore(config(theme, density, setup))
  private val speedScope = CoroutineScope(SupervisorJob() + SnapshotHarness.ui)
  private val instanceManager = InstanceManager(
    factory = InstanceFactory(
      deviceName = data.deviceName,
      embeddedFactory = { api },
      localServerFactory = PretendServer,
      // The NAS answers like this device, from its own copy of the sample.
      remoteFactory = { config ->
        RemoteInstance(
          instance = NetworkedApi(SampleKetchApi(data)),
          remoteConfig = config,
          connectionState = MutableStateFlow(ConnectionState.Connected),
        )
      },
    ),
    initialRemotes = data.remotes,
    configStore = store,
  )
  private val speedMode = SpeedModeController(
    config = { api.status().config },
    apply = { api.updateConfig(it) },
    scope = speedScope,
    settings = setup.speed,
    observedPeak = ObservedPeak(
      bytesPerSecond = PEAK,
      atEpochMillis = (SampleData.NOW - 1.days).toEpochMilliseconds(),
    ),
    scheduler = SpeedScheduler { TimeZone.UTC },
    clock = SampleData.CLOCK,
  )

  /** The controller the pages and the app root show. */
  override val controller = AppController(
    instanceManager = instanceManager,
    context = SnapshotHarness.ui,
    speedMode = speedMode,
    clock = SampleData.CLOCK,
  )

  init {
    if (setup.sharing) instanceManager.startServer()
    setup.pendingPort?.let { port ->
      controller.appSettings.saveServer(controller.appSettings.config.server.copy(port = port))
    }
    check(instanceManager.instances.value.first().deviceId == "local")
  }

  override fun close() {
    controller.close()
    speedScope.cancel()
    instanceManager.close()
  }

  private fun config(theme: SnapshotTheme, density: DensityMode, setup: DeviceSetup) =
    data.config(theme, density).let { config: KetchConfig ->
      config.copy(
        speed = setup.speed,
        torrent = TorrentSettings(
          trackers = listOf(
            "udp://tracker.opentrackr.org:1337/announce",
            "udp://open.demonii.com:1337/announce",
            "https://tracker.example.org:443/announce",
          ),
        ),
        server = if (setup.sharing) {
          ServerConfig(apiToken = setup.token, mdnsEnabled = true)
        } else {
          ServerConfig(host = ServerConfig.LOOPBACK_HOST)
        },
      )
    }

  companion object {
    private const val PEAK = 10_840_000L

    /** Access code of the sharing server. */
    const val TOKEN = "3f2a9c1e5b7d4a60b8e2c9f1a3d5e7b9"

    /** The remote device of [DeviceSetup.remote]. */
    val Nas = SampleData.NAS.copy(name = "NAS-Basement")
  }
}

