package com.linroid.ketch.app.snapshot

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.unit.dp
import com.linroid.ketch.api.KetchApi
import com.linroid.ketch.api.NetworkInterfaceConfig
import com.linroid.ketch.api.NetworkInterfaceInfo
import com.linroid.ketch.api.NetworkInterfaces
import com.linroid.ketch.api.SpeedLimit
import com.linroid.ketch.app.App
import com.linroid.ketch.app.RecordingConfigStore
import com.linroid.ketch.app.instance.InstanceFactory
import com.linroid.ketch.app.instance.InstanceManager
import com.linroid.ketch.app.instance.LocalServerHandle
import com.linroid.ketch.app.instance.RemoteInstance
import com.linroid.ketch.app.platform.DesktopHooks
import com.linroid.ketch.app.platform.DetectedBrowser
import com.linroid.ketch.app.platform.IntegrationStatus
import com.linroid.ketch.app.platform.LocalDesktopHooks
import com.linroid.ketch.app.platform.LocalIntegrationStatus
import com.linroid.ketch.app.state.AiDiscoverRequest
import com.linroid.ketch.app.state.AiDiscoverResponse
import com.linroid.ketch.app.state.AiDiscoveryProvider
import com.linroid.ketch.app.state.AiDiscoveryProviderFactory
import com.linroid.ketch.app.state.AppController
import com.linroid.ketch.app.state.DiscoveryStep
import com.linroid.ketch.app.state.LocalAppState
import com.linroid.ketch.app.state.ObservedPeak
import com.linroid.ketch.app.state.SettingsTarget
import com.linroid.ketch.app.state.SpeedModeController
import com.linroid.ketch.app.state.SpeedScheduler
import com.linroid.ketch.app.theme.KetchDensity
import com.linroid.ketch.app.theme.KetchTheme
import com.linroid.ketch.app.ui.settings.SettingsContent
import com.linroid.ketch.config.AiSettings
import com.linroid.ketch.config.DensityMode
import com.linroid.ketch.config.LlmProvider
import com.linroid.ketch.config.LlmSettings
import com.linroid.ketch.config.NotificationMode
import com.linroid.ketch.config.NotificationSettings
import com.linroid.ketch.config.SearchProvider
import com.linroid.ketch.config.SearchSettings
import com.linroid.ketch.config.ServerConfig
import com.linroid.ketch.config.SpeedLimitMode
import com.linroid.ketch.config.SpeedSettings
import com.linroid.ketch.config.TorrentSettings
import com.linroid.ketch.remote.ConnectionState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.TimeZone
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.time.Duration.Companion.days

/**
 * Settings as a whole: its pages beside the list of pages in the desktop Settings window, inside
 * the app at medium width, and as a list on a phone, with search, deep links and the device
 * chip; see [SnapshotHarness] for how to run it.
 */
class SettingsSnapshots {
  @BeforeTest
  fun enabled() = requireSnapshots()

  @Test
  fun settingsWindow_eachPage_showsItBesideTheList() {
    for (page in SettingsTarget.Page.entries) {
      for (theme in SnapshotTheme.entries) {
        windowSnapshot("settings-${page.name.lowercase()}", WindowSize, theme, SettingsTarget(page))
      }
    }
  }

  @Test
  fun settingsWindow_minimumSize_keepsBothPanes() {
    for (theme in SnapshotTheme.entries) {
      windowSnapshot(
        name = "settings-min-general",
        size = MinWindowSize,
        theme = theme,
        target = SettingsTarget(SettingsTarget.Page.General),
      )
    }
    windowSnapshot(
      name = "settings-min-integration",
      size = MinWindowSize,
      theme = SnapshotTheme.Light,
      target = SettingsTarget(SettingsTarget.Page.Integration),
    )
  }

  @Test
  fun settingsWindow_searchTrackers_listsTheTrackersRow() {
    for (theme in SnapshotTheme.entries) {
      windowSnapshot("settings-search", WindowSize, theme, query = "trackers")
    }
    windowSnapshot("settings-search-none", WindowSize, SnapshotTheme.Light, query = "zzz")
  }

  @Test
  fun settingsWindow_searchEnter_jumpsToTheRow() {
    for (theme in SnapshotTheme.entries) {
      windowSnapshot(
        name = "settings-search-jump",
        size = WindowSize,
        theme = theme,
        query = "connections",
        interact = { pressKey(Key.Enter) },
      )
    }
  }

  @Test
  fun settingsWindow_nasTarget_editsTheNasWithoutItsLocalPages() {
    for (theme in SnapshotTheme.entries) {
      windowSnapshot(
        name = "settings-nas-downloads",
        size = WindowSize,
        theme = theme,
        target = SettingsTarget(SettingsTarget.Page.Downloads, NAS_ID),
      )
    }
    windowSnapshot(
      name = "settings-device-menu",
      size = WindowSize,
      theme = SnapshotTheme.Light,
      target = SettingsTarget(SettingsTarget.Page.Speed),
      interact = { click(DeviceChipX, DeviceChipY) },
    )
  }

  @Test
  fun settingsWindow_genericOpen_reopensTheLastPage() {
    windowSnapshot(
      name = "settings-resume",
      size = WindowSize,
      theme = SnapshotTheme.Light,
      target = SettingsTarget(SettingsTarget.Page.General),
      lastPage = SettingsTarget.Page.Network,
    )
  }

  @Test
  fun app_mediumWindow_showsSettingsAsTwoPanes() {
    for (theme in SnapshotTheme.entries) {
      appSettingsSnapshot("app-settings-medium", SnapshotSize.Medium, theme) {
        openSettings(SettingsTarget.Page.General)
      }
    }
    appSettingsSnapshot("app-settings-medium-speed", SnapshotSize.Medium, SnapshotTheme.Dark) {
      openSettings(SettingsTarget.Page.Speed)
    }
  }

  @Test
  fun app_phone_showsTheListThenOnePage() {
    for (theme in SnapshotTheme.entries) {
      appSettingsSnapshot("app-settings-phone-list", PhoneTall, theme) {
        openSettings(SettingsTarget.Page.General)
      }
      appSettingsSnapshot("app-settings-phone-notifications", PhoneTall, theme) {
        openSettings(SettingsTarget.Page.Notifications)
      }
    }
    appSettingsSnapshot("app-settings-phone-general", PhoneTall, SnapshotTheme.Light) {
      state.openSettings(SettingsTarget(SettingsTarget.Page.General, deviceId = "local"))
    }
  }

  @Test
  fun phone_search_listsResultsInAGroup() {
    for (theme in SnapshotTheme.entries) {
      phoneSnapshot("settings-phone-search", theme, query = "speed")
    }
  }

  @Test
  fun card_search_marksTheResultEnterOpens() {
    for (theme in SnapshotTheme.entries) {
      cardSnapshot("settings-card-search", theme, query = "speed")
    }
  }

  /** Renders [SettingsContent] in the Settings window's frame, as the desktop app shows it. */
  private fun windowSnapshot(
    name: String,
    size: SnapshotSize,
    theme: SnapshotTheme,
    target: SettingsTarget? = null,
    query: String = "",
    lastPage: SettingsTarget.Page? = null,
    interact: suspend SnapshotScene.() -> Unit = {},
  ) {
    withEnvironment(theme, size.density, lastPage) { environment ->
      SnapshotHarness.capture("$name-${theme.id}-${size.id}", size, interact) {
        SettingsFrame(environment, theme, size.density, desktop = true) {
          SettingsContent(environment.controller.state, target, onClose = {}, initialQuery = query)
        }
      }
    }
  }

  /** Renders [SettingsContent] on the card of a tablet or the web, beside nothing else. */
  private fun cardSnapshot(name: String, theme: SnapshotTheme, query: String) {
    withEnvironment(theme, CardSize.density, lastPage = null) { environment ->
      SnapshotHarness.capture("$name-${theme.id}-${CardSize.id}", CardSize) {
        SettingsFrame(environment, theme, CardSize.density, desktop = false) {
          SettingsContent(environment.controller.state, null, onClose = {}, initialQuery = query)
        }
      }
    }
  }

  /** Renders [SettingsContent] full screen on a phone, as the app's shell shows it. */
  private fun phoneSnapshot(name: String, theme: SnapshotTheme, query: String) {
    withEnvironment(theme, PhoneTall.density, lastPage = null) { environment ->
      SnapshotHarness.capture("$name-${theme.id}-${PhoneTall.id}", PhoneTall) {
        SettingsFrame(environment, theme, PhoneTall.density, desktop = false) {
          SettingsContent(environment.controller.state, null, onClose = {}, initialQuery = query)
        }
      }
    }
  }

  /** Renders the app root at [size] in [theme] after [open] runs. */
  private fun appSettingsSnapshot(
    name: String,
    size: SnapshotSize,
    theme: SnapshotTheme,
    open: AppScenario.() -> Unit,
  ) {
    withEnvironment(theme, size.density, lastPage = null) { environment ->
      SnapshotHarness.capture(
        name = "$name-${theme.id}-${size.id}",
        size = size,
        interact = { AppScenario(environment.controller, environment.data, this).open() },
      ) {
        App(environment.controller)
      }
    }
  }

  private fun withEnvironment(
    theme: SnapshotTheme,
    density: KetchDensity,
    lastPage: SettingsTarget.Page?,
    block: (SettingsEnvironment) -> Unit,
  ) {
    val environment = runBlocking(SnapshotHarness.ui) {
      SettingsEnvironment(theme, density.toMode(), lastPage)
    }
    try {
      block(environment)
    } finally {
      runBlocking(SnapshotHarness.ui) { environment.close() }
    }
  }

  private companion object {
    /** The Settings window's size until the user resizes it. */
    val WindowSize = SnapshotSize(860.dp, 640.dp, KetchDensity.Compact)

    /** The smallest the Settings window gets. */
    val MinWindowSize = SnapshotSize(640.dp, 480.dp, KetchDensity.Compact)

    /** The card of a tablet or a web page, where Settings shows both panes on a surface. */
    val CardSize = SnapshotSize(760.dp, 640.dp, KetchDensity.Compact)

    /** A phone tall enough for a whole page. */
    val PhoneTall = SnapshotSize(390.dp, 1100.dp, KetchDensity.Comfortable)

    /** The device chip in the list of pages, at the window size. */
    val DeviceChipX = 150.dp
    val DeviceChipY = 298.dp

    const val NAS_ID = "nas.local:8642"
  }
}

/**
 * [content] in the theme and locals of the Settings window: the desktop's hooks and browser
 * status when [desktop], on the canvas.
 */
@Composable
internal fun SettingsFrame(
  environment: SettingsEnvironment,
  theme: SnapshotTheme,
  density: KetchDensity,
  desktop: Boolean,
  content: @Composable () -> Unit,
) {
  CompositionLocalProvider(
    LocalAppState provides environment.controller.state,
    LocalDesktopHooks provides if (desktop) DesktopHooksShown else DesktopHooks.None,
    LocalIntegrationStatus provides if (desktop) SampleIntegration else IntegrationStatus(),
  ) {
    KetchTheme(
      darkTheme = theme == SnapshotTheme.Dark,
      density = density.toMode(),
      reduceMotion = true,
    ) {
      Box(Modifier.fillMaxSize().background(KetchTheme.colors.canvas)) { content() }
    }
  }
}

/** What the desktop app reports while the extension is set up in Chrome only. */
private val SampleIntegration = IntegrationStatus(
  browsers = listOf(
    DetectedBrowser("Chrome", extensionConnected = true),
    DetectedBrowser("Edge"),
    DetectedBrowser("Firefox"),
  ),
  extensionConnected = true,
  magnetHandler = true,
)

/** Desktop hooks that do nothing, so the pages show their desktop rows. */
private val DesktopHooksShown = object : DesktopHooks {
  override val isSupported: Boolean get() = true
}

/** Discovery that can run but is never asked, so the Discover page shows. */
private object IdleDiscovery : AiDiscoveryProviderFactory {
  override fun create(settings: AiSettings): AiDiscoveryProvider = object : AiDiscoveryProvider {
    override suspend fun discover(request: AiDiscoverRequest, onStep: (DiscoveryStep) -> Unit) =
      AiDiscoverResponse(request.query, emptyList())

    override suspend fun verify(): String = "OK"
  }
}

/**
 * The sample's devices with what Settings summarizes: this Mac sharing on the network in Slow
 * lane over Wi-Fi and Ethernet, three extra trackers, Anthropic discovery with Brave search,
 * failures reported only in the app, and the NAS connected.
 *
 * @param lastPage the Settings page shown last, which a generic open reopens.
 */
internal class SettingsEnvironment(
  theme: SnapshotTheme,
  density: DensityMode,
  lastPage: SettingsTarget.Page? = null,
) {
  private val nas = SampleData.NAS.copy(name = "NAS-Basement", watch = true)
  val data = SampleData(
    tasks = SampleData.downloads().tasks,
    remotes = listOf(nas),
    ui = { it.copy(settingsPage = lastPage?.name) },
  )
  private val api = TwoNetworks(SampleKetchApi(data))
  private val speedScope = CoroutineScope(SupervisorJob() + SnapshotHarness.ui)
  private val instanceManager = InstanceManager(
    factory = InstanceFactory(
      deviceName = data.deviceName,
      embeddedFactory = { api },
      localServerFactory = {
        object : LocalServerHandle {
          override fun stop() {}
        }
      },
      remoteFactory = { config ->
        RemoteInstance(
          instance = SampleKetchApi(data),
          remoteConfig = config,
          connectionState = MutableStateFlow(ConnectionState.Connected),
        )
      },
    ),
    initialRemotes = data.remotes,
    configStore = RecordingConfigStore(
      data.config(theme, density).copy(
        speed = SpeedSettings(mode = SpeedLimitMode.SlowLane, slowLane = SpeedLimit.mbps(1)),
        torrent = TorrentSettings(
          trackers = listOf(
            "udp://tracker.opentrackr.org:1337/announce",
            "udp://open.demonii.com:1337/announce",
            "https://tracker.example.org:443/announce",
          ),
        ),
        server = ServerConfig(apiToken = "3f2a9c1e5b7d4a60", mdnsEnabled = true),
        ai = AiSettings(
          enabled = true,
          llm = LlmSettings(provider = LlmProvider.Anthropic, apiKey = "sk-ant-sample"),
          search = SearchSettings(provider = SearchProvider.Brave, apiKey = "brave-sample"),
        ),
        notifications = NotificationSettings(failed = NotificationMode.InApp),
      ),
    ),
  )
  private val speedMode = SpeedModeController(
    config = { api.status().config },
    apply = { api.updateConfig(it) },
    scope = speedScope,
    settings = SpeedSettings(mode = SpeedLimitMode.SlowLane, slowLane = SpeedLimit.mbps(1)),
    observedPeak = ObservedPeak(
      bytesPerSecond = 10_840_000,
      atEpochMillis = (SampleData.NOW - 1.days).toEpochMilliseconds(),
    ),
    scheduler = SpeedScheduler { TimeZone.UTC },
    clock = SampleData.CLOCK,
  )

  /** The controller Settings and the app root show. */
  val controller = AppController(
    instanceManager = instanceManager,
    aiProviderFactory = IdleDiscovery,
    context = SnapshotHarness.ui,
    speedMode = speedMode,
    clock = SampleData.CLOCK,
  )

  init {
    instanceManager.startServer()
  }

  fun close() {
    controller.close()
    speedScope.cancel()
    instanceManager.close()
  }
}

/** [sample] on Wi-Fi, Ethernet and a VPN, with downloads spread over the first two. */
private class TwoNetworks(private val sample: SampleKetchApi) : KetchApi by sample {
  override suspend fun networkInterfaces(): NetworkInterfaces = NetworkInterfaces(
    supported = true,
    available = listOf(
      NetworkInterfaceInfo("en0", "en0", listOf("fe80::1c2a:3bff:fe4d:5e6f", "192.168.1.20")),
      NetworkInterfaceInfo("en7", "en7", listOf("10.0.0.4")),
      NetworkInterfaceInfo("utun3", "utun3", listOf("100.101.7.12")),
    ),
    config = NetworkInterfaceConfig(listOf("en0", "en7")),
  )
}
