package com.linroid.ketch.app.snapshot

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.linroid.ketch.api.Destination
import com.linroid.ketch.api.DownloadConfig
import com.linroid.ketch.api.DownloadProgress
import com.linroid.ketch.api.DownloadRequest
import com.linroid.ketch.api.DownloadState
import com.linroid.ketch.api.DownloadTask
import com.linroid.ketch.api.KetchApi
import com.linroid.ketch.api.KetchError
import com.linroid.ketch.api.KetchStatus
import com.linroid.ketch.api.ResolvedSource
import com.linroid.ketch.api.SystemInfo
import com.linroid.ketch.app.App
import com.linroid.ketch.app.RecordingConfigStore
import com.linroid.ketch.app.components.KetchMenuPanel
import com.linroid.ketch.app.instance.DevicePresence
import com.linroid.ketch.app.instance.InstanceFactory
import com.linroid.ketch.app.instance.InstanceManager
import com.linroid.ketch.app.instance.LocalServerHandle
import com.linroid.ketch.app.instance.RemoteInstance
import com.linroid.ketch.app.state.AppController
import com.linroid.ketch.app.state.LOCAL_DEVICE_ID
import com.linroid.ketch.app.state.ListTestTask
import com.linroid.ketch.app.state.LocalAppState
import com.linroid.ketch.app.state.LocalClock
import com.linroid.ketch.app.state.TaskKey
import com.linroid.ketch.app.theme.KetchDensity
import com.linroid.ketch.app.theme.KetchTheme
import com.linroid.ketch.app.ui.DropHoverState
import com.linroid.ketch.app.ui.shell.BerthKey
import com.linroid.ketch.app.ui.shell.DeviceDrag
import com.linroid.ketch.app.ui.shell.DropBerths
import com.linroid.ketch.app.ui.shell.dropHint
import com.linroid.ketch.app.ui.sidebar.DeviceRowContent
import com.linroid.ketch.app.ui.sidebar.deviceCommands
import com.linroid.ketch.config.DensityMode
import com.linroid.ketch.config.RemoteConfig
import com.linroid.ketch.remote.ConnectionState
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.yield
import java.io.File
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

/**
 * The fleet in the shell (W4-FLEET-SHELL): live device rows and All devices in the sidebar, the
 * rail's device stack with its progress ring, the device switcher, the phone's device sheet,
 * the device menu, device rows under a drag, and the drop berths. This Mac downloads the sample
 * downloads, NAS-Basement downloads with a failure the app has not shown yet, and Den-PC went
 * offline two hours ago. See [SnapshotHarness] for how to run it.
 */
class FleetShellSnapshots {
  @BeforeTest
  fun enabled() = requireSnapshots()

  @Test
  fun sidebar_threeDevices_showsTheirLiveLines() {
    fleetSnapshots("fleet-sidebar", listOf(SnapshotSize.Desktop, SnapshotSize.SmallDesktop))
  }

  @Test
  fun sidebar_allDevices_selectsTheAllDevicesRow() {
    fleetSnapshots("fleet-all", listOf(SnapshotSize.Desktop)) { state.showAllDevices() }
  }

  @Test
  fun sidebar_keyboardFocus_ringsTheDeviceRow() {
    val light = listOf(SnapshotTheme.Light)
    fleetSnapshots("fleet-focus", listOf(SnapshotSize.Desktop), themes = light) {
      // ◧, the appearance toggle, Downloads, Devices, All devices, This Mac, then NAS-Basement.
      repeat(FOCUS_STEPS) { scene.pressKey(Key.Tab) }
    }
  }

  @Test
  fun rail_downloading_ringsAllDevicesWithProgress() {
    fleetSnapshots("fleet-rail", listOf(SnapshotSize.Medium))
    fleetSnapshots("fleet-rail-collapsed", listOf(SnapshotSize.Desktop), collapsed = true)
  }

  @Test
  fun switcher_keyboard_highlightsTheNas() {
    fleetSnapshots("fleet-switcher", listOf(SnapshotSize.Desktop, SnapshotSize.Medium)) {
      state.showInstanceSelector = true
      scene.settle()
      // All devices, then This Mac, then NAS-Basement.
      repeat(3) { scene.pressKey(Key.DirectionDown) }
    }
  }

  @Test
  fun switcher_shortWindow_scrollsTheKeyboardHighlightIntoView() {
    val short = SnapshotSize(1024.dp, 400.dp, KetchDensity.Compact)
    fleetSnapshots("fleet-switcher-short", listOf(short), themes = listOf(SnapshotTheme.Light)) {
      state.showInstanceSelector = true
      scene.settle()
      // Tab moves like ↓: All devices, the three devices, then the ways to add one.
      repeat(SWITCHER_ENTRIES) { scene.pressKey(Key.Tab) }
    }
  }

  @Test
  fun deviceButton_phoneAllDevices_showsEveryPennant() {
    fleetSnapshots("fleet-phone-all", listOf(SnapshotSize.Phone)) { state.showAllDevices() }
  }

  @Test
  fun deviceSheet_phone_listsTheDevicesAndWaysToAddOne() {
    fleetSnapshots("fleet-device-sheet", listOf(SnapshotSize.Phone)) {
      state.showInstanceSelector = true
    }
  }

  @Test
  fun deviceMenu_nas_listsItsCommands() {
    val size = SnapshotSize(280.dp, 420.dp, KetchDensity.Compact)
    for (theme in SnapshotTheme.entries) {
      withFleet(theme, DensityMode.Compact) { environment ->
        val state = environment.controller.state
        val nas = environment.presence(NAS_ID)
        snapshot("fleet-device-menu", size, theme) {
          CompositionLocalProvider(LocalAppState provides state) {
            KetchMenuPanel(Modifier.padding(KetchTheme.spacing.s2)) {
              deviceCommands(state, nas, number = 2, shown = false, onRename = {}, onRemove = {})
            }
          }
        }
      }
    }
  }

  @Test
  fun deviceRows_underADrag_sayWhatADropDoes() {
    val size = SnapshotSize(220.dp, 400.dp, KetchDensity.Compact)
    for (theme in SnapshotTheme.entries) {
      withFleet(theme, DensityMode.Compact) { environment ->
        val nas = environment.presence(NAS_ID)
        val den = environment.presence(DEN_PC_ID)
        val mac = environment.presence(LOCAL_DEVICE_ID)
        val keys = listOf("a", "b").map { TaskKey(LOCAL_DEVICE_ID, it) }
        val rows = DeviceDrag.Rows(keys)
        val cases = listOf(
          "Idle" to (nas to null),
          "Links" to (nas to dropHint(DeviceDrag.Content, nas, move = false)),
          "Rows" to (nas to dropHint(rows, nas, move = false, moveKey = "⌥")),
          "Rows with ⌥" to (nas to dropHint(rows, nas, move = true)),
          "Rows on their own device" to (mac to dropHint(rows, mac, move = false)),
          "Offline" to (den to dropHint(DeviceDrag.Content, den, move = false))
        )
        snapshot("fleet-row-drop", size, theme) {
          Column(Modifier.padding(vertical = KetchTheme.spacing.s2)) {
            for ((label, case) in cases) {
              val (device, hint) = case
              Text(
                text = label,
                style = KetchTheme.typography.caption,
                color = KetchTheme.colors.textTertiary,
                modifier = Modifier.padding(start = KetchTheme.spacing.s4),
              )
              DeviceRowContent(device = device, active = false, hint = hint, onClick = {})
            }
          }
        }
      }
    }
  }

  @Test
  fun berths_linkOverTheNas_fillsItsBerth() {
    for (size in listOf(SnapshotSize.Desktop, SnapshotSize.Medium, SnapshotSize.Phone)) {
      for (theme in SnapshotTheme.entries) {
        berthSnapshot("fleet-berths", size, theme, hovered = NAS_ID)
      }
    }
  }

  @Test
  fun berths_unseen_letClicksReachThePage() {
    val light = listOf(SnapshotTheme.Light)
    fleetSnapshots("fleet-click-through", listOf(SnapshotSize.Desktop), themes = light) {
      // blender-4.2-macos-arm64.dmg, right under This Mac's unseen berth.
      scene.click(500.dp, 338.dp)
    }
  }

  @Test
  fun berths_oneDevice_showsOneBerth() {
    berthSnapshot("fleet-berth-single", SnapshotSize.Desktop, SnapshotTheme.Light, single = true)
  }
}

/** Renders the app over [FleetEnvironment] at each of [sizes] in both themes, then [setup]. */
private fun fleetSnapshots(
  name: String,
  sizes: List<SnapshotSize>,
  collapsed: Boolean = false,
  themes: List<SnapshotTheme> = SnapshotTheme.entries,
  setup: suspend AppScenario.() -> Unit = {},
): List<File> = sizes.flatMap { size ->
  themes.map { theme ->
    withFleet(theme, size.density.toMode(), collapsed) { environment ->
      captureApp(name, size, theme, environment) {
        // The All devices ring and the sidebar's lines follow the first readings.
        delay(READINGS_WAIT)
        setup()
        // The pointer rests in the window's corner, away from rows it would light up.
        scene.hover(size.width - 2.dp, 2.dp)
      }
    }
  }
}

/**
 * Renders the app with a drag over the window: the drop berths over where the content card is,
 * [hovered] naming the device whose berth the drag is on. With [single], This Mac is the only
 * device.
 */
private fun berthSnapshot(
  name: String,
  size: SnapshotSize,
  theme: SnapshotTheme,
  hovered: String? = null,
  single: Boolean = false,
) {
  withFleet(theme, size.density.toMode(), single = single) { environment ->
    val hover = DropHoverState().apply {
      enter(Unit)
      hovered?.let { enter(BerthKey(it)) }
    }
    SnapshotHarness.capture(
      name = "$name-${theme.id}-${size.id}",
      size = size,
      interact = { delay(READINGS_WAIT) },
    ) {
      Box(Modifier.fillMaxSize()) {
        App(environment.controller)
        KetchTheme(
          darkTheme = theme == SnapshotTheme.Dark,
          density = size.density.toMode(),
          reduceMotion = true,
        ) {
          CompositionLocalProvider(LocalClock provides SampleData.CLOCK) {
            Box(Modifier.cardArea(size)) {
              DropBerths(environment.controller.state, hover)
            }
          }
        }
      }
    }
  }
}

/** Where the shell puts the content card of a window of [size], or the phone's content. */
private fun Modifier.cardArea(size: SnapshotSize): Modifier = when {
  size.width < 600.dp -> padding(top = PhoneTopBar)
  size.width < 840.dp -> padding(start = RailWidth)
  else -> {
    val start = if (size.width >= 1024.dp) SidebarWidth else RailWidth
    padding(start = start, top = CardInset, end = CardInset, bottom = CardInset)
      .clip(CardShape)
  }
}

/** Runs [block] over a started [FleetEnvironment] and closes it afterwards. */
private fun <T> withFleet(
  theme: SnapshotTheme,
  density: DensityMode,
  collapsed: Boolean = false,
  single: Boolean = false,
  block: (FleetEnvironment) -> T,
): T = withEnvironment(
  create = { FleetEnvironment(theme, density, collapsed, single) },
  timeout = FleetEnvironment.START_TIMEOUT,
  block = block,
)

/**
 * This Mac over the sample downloads, NAS-Basement connected and downloading with a failure,
 * and Den-PC, which went offline [OFFLINE_FOR] before [SampleData.NOW]; or This Mac alone with
 * [single].
 */
private class FleetEnvironment(
  theme: SnapshotTheme,
  density: DensityMode,
  collapsed: Boolean,
  single: Boolean,
) : SnapshotEnvironment {
  override val data = SampleData(
    tasks = SampleData.downloads().tasks,
    remotes = if (single) emptyList() else listOf(Nas, DenPc),
    ui = { it.copy(sidebarCollapsed = collapsed) },
  )
  private val clock = FleetClock(SampleData.NOW - OFFLINE_FOR)
  private val nasTasks = fleetNasTasks()
  private val denPc = MutableStateFlow<ConnectionState>(ConnectionState.Connected)
  private val instanceManager = InstanceManager(
    factory = InstanceFactory(
      deviceName = data.deviceName,
      embeddedFactory = { SampleKetchApi(data) },
      // This Mac can share its downloads, as on the desktop.
      localServerFactory = {
        object : LocalServerHandle {
          override fun stop() {}
        }
      },
      remoteFactory = { config ->
        if (config.host == Nas.host) {
          RemoteInstance(
            instance = FleetRemoteApi(fleetStatus("NAS-Basement", "Linux"), nasTasks),
            remoteConfig = config,
            connectionState = MutableStateFlow(ConnectionState.Connected),
          )
        } else {
          val api = FleetRemoteApi(fleetStatus("Den-PC", "Windows 11"), emptyList())
          RemoteInstance(api, config, denPc)
        }
      },
    ),
    initialRemotes = data.remotes,
    configStore = RecordingConfigStore(data.config(theme, density)),
    clock = clock,
  )

  override val controller = AppController(
    instanceManager = instanceManager,
    context = SnapshotHarness.ui,
    clock = SampleData.CLOCK,
  )

  /** The presence of the device with [deviceId]. */
  fun presence(deviceId: String): DevicePresence =
    instanceManager.presence.value.first { it.deviceId == deviceId }

  /**
   * Waits for every device's first status and the rows of This Mac and the NAS, then takes
   * Den-PC offline [OFFLINE_FOR] before [SampleData.NOW].
   */
  override suspend fun start() {
    repeat(STARTUP_YIELDS) { yield() }
    val presence = instanceManager.presence
    val count = data.remotes.size + 1
    presence.first { devices -> devices.size == count && devices.all { it.disk != null } }
    val rows = data.tasks.size + if (data.remotes.isEmpty()) 0 else nasTasks.size
    controller.state.taskList.allRows.first { it.size == rows }
    if (data.remotes.isNotEmpty()) {
      denPc.value = ConnectionState.Disconnected("Connection refused")
      presence.first { devices ->
        devices.any { it.deviceId == DEN_PC_ID && it.lastSeen != null }
      }
    }
    clock.now = SampleData.NOW
  }

  override fun close() {
    controller.close()
    instanceManager.close()
  }

  companion object {
    val Nas = RemoteConfig(host = "nas.local", name = "NAS-Basement")
    val DenPc = RemoteConfig(host = "den-pc.local", name = "Den-PC")
    val OFFLINE_FOR: Duration = 2.hours
    val START_TIMEOUT: Duration = 10.seconds
    const val STARTUP_YIELDS = 3
  }
}

/** A clock whose time a scenario moves. */
private class FleetClock(@Volatile var now: Instant) : Clock {
  override fun now(): Instant = now
}

/** A remote device that answers with [status] and lists [tasks]. */
private class FleetRemoteApi(
  private var status: KetchStatus,
  tasks: List<DownloadTask>,
) : KetchApi {
  override val backendLabel: String = status.name
  override val tasks: StateFlow<List<DownloadTask>> = MutableStateFlow(tasks)

  override suspend fun status(): KetchStatus = status

  override suspend fun updateConfig(config: DownloadConfig) {
    status = status.copy(config = config)
  }

  override suspend fun download(request: DownloadRequest): DownloadTask =
    throw UnsupportedOperationException("Not in snapshots")

  override suspend fun resolve(url: String, properties: Map<String, String>): ResolvedSource =
    throw UnsupportedOperationException("Not in snapshots")

  override suspend fun start() {}

  override fun close() {}
}

private fun fleetStatus(name: String, os: String): KetchStatus = KetchStatus(
  name = name,
  version = KetchApi.VERSION,
  revision = KetchApi.REVISION,
  uptime = 12.days.inWholeSeconds,
  config = DownloadConfig(defaultDirectory = "/volume1/downloads"),
  system = SystemInfo(
    os = os,
    arch = "amd64",
    separator = "/",
    javaVersion = "21",
    availableProcessors = 4,
    maxMemory = 0,
    totalMemory = 0,
    freeMemory = 0,
    downloadDirectory = "/volume1/downloads",
    totalSpace = 4_000_787_030_016,
    freeSpace = 1_979_120_929_996,
    usableSpace = 1_979_120_929_996,
  ),
)

private fun fleetNasTasks(): List<DownloadTask> = listOf(
  fleetNasTask(
    id = "debian",
    url = "https://cdimage.debian.org/debian-cd/12.7.0/amd64/iso-dvd/" +
      "debian-12.7.0-amd64-DVD-1.iso",
    state = DownloadState.Downloading(DownloadProgress(1_520_000_000, 3_900_000_000, 2_880_000)),
    ago = 25.minutes,
  ),
  fleetNasTask(
    id = "fedora",
    url = "https://download.fedoraproject.org/pub/fedora/41/Fedora-Workstation-Live-41.iso",
    state = DownloadState.Queued,
    ago = 12.minutes,
  ),
  fleetNasTask(
    id = "photos",
    url = "https://photos.example.net/share/family-photos-2025.zip",
    state = DownloadState.Failed(KetchError.Http(404, "Not Found")),
    ago = 2.hours,
  )
)

private fun fleetNasTask(id: String, url: String, state: DownloadState, ago: Duration) =
  ListTestTask(
    taskId = id,
    state = state,
    request = DownloadRequest(url = url, destination = Destination("/volume1/downloads/")),
    createdAt = SampleData.NOW - ago,
  )

private const val NAS_ID = "nas.local:8642"
private const val DEN_PC_ID = "den-pc.local:8642"
private val READINGS_WAIT = 2.seconds
private const val FOCUS_STEPS = 7
private const val SWITCHER_ENTRIES = 7

private val SidebarWidth: Dp = 220.dp
private val RailWidth: Dp = 72.dp
private val CardInset: Dp = 8.dp
private val PhoneTopBar: Dp = 64.dp
private val CardShape = RoundedCornerShape(16.dp)
