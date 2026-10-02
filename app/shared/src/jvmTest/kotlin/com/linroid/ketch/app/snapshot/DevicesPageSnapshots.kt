package com.linroid.ketch.app.snapshot

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
import com.linroid.ketch.api.NetworkInterfaceConfig
import com.linroid.ketch.api.NetworkInterfaceInfo
import com.linroid.ketch.api.NetworkInterfaces
import com.linroid.ketch.api.ResolvedSource
import com.linroid.ketch.api.SpeedLimit
import com.linroid.ketch.api.SystemInfo
import com.linroid.ketch.app.App
import com.linroid.ketch.app.RecordingConfigStore
import com.linroid.ketch.app.instance.DevicePresence
import com.linroid.ketch.app.instance.InstanceFactory
import com.linroid.ketch.app.instance.InstanceManager
import com.linroid.ketch.app.instance.LocalServerHandle
import com.linroid.ketch.app.instance.RemoteInstance
import com.linroid.ketch.app.state.AppController
import com.linroid.ketch.app.state.ListTestTask
import com.linroid.ketch.app.state.SpeedModeController
import com.linroid.ketch.app.state.StatusFilter
import com.linroid.ketch.app.theme.KetchDensity
import com.linroid.ketch.config.DensityMode
import com.linroid.ketch.config.RemoteConfig
import com.linroid.ketch.config.ServerConfig
import com.linroid.ketch.remote.ConnectionState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import kotlin.math.sin
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

/**
 * The Devices page (W4-DEVICES-PAGE): a card per device with its speed, lane, counts, storage,
 * networks, sharing and next actions, offline and unauthorized devices, the single-device "Add
 * a device" card, the ⋯ menu and keyboard focus; see [SnapshotHarness] for how to run it.
 */
class DevicesPageSnapshots {
  @BeforeTest
  fun enabled() = requireSnapshots()

  @Test
  fun devicesPage_fleet_showsACardPerDevice() {
    for (size in listOf(SnapshotSize.Desktop, Wide1440, SnapshotSize.Medium, SnapshotSize.Phone)) {
      for (theme in SnapshotTheme.entries) {
        devicesPageSnapshot("devices-page", size, theme)
      }
    }
    devicesPageSnapshot("devices-page", SnapshotSize.SmallDesktop, SnapshotTheme.Light)
    devicesPageSnapshot("devices-page", PhoneTall, SnapshotTheme.Dark)
  }

  @Test
  fun devicesPage_singleDevice_offersToAddAnother() {
    for (size in listOf(SnapshotSize.Desktop, SnapshotSize.Phone)) {
      for (theme in SnapshotTheme.entries) {
        devicesPageSnapshot("devices-page-single", size, theme, fleet = DevicesPageFleet.Single)
      }
    }
  }

  @Test
  fun devicesPage_idleDevice_readsIdle() {
    devicesPageSnapshot(
      name = "devices-page-idle",
      size = SnapshotSize.Desktop,
      theme = SnapshotTheme.Light,
      fleet = DevicesPageFleet.Idle,
    )
  }

  @Test
  fun devicesPage_cardMenu_listsTheDeviceCommands() {
    for (theme in SnapshotTheme.entries) {
      devicesPageSnapshot("devices-page-menu", SnapshotSize.Desktop, theme) {
        // The ⋯ of the NAS card, the second in the first row.
        scene.click(NasMenu.first, NasMenu.second)
      }
    }
  }

  @Test
  fun devicesPage_speedPill_opensThatDevicesSpeedOptions() {
    devicesPageSnapshot("devices-page-speed-active", SnapshotSize.Desktop, SnapshotTheme.Light) {
      scene.click(MacSpeedOptions.first, MacSpeedOptions.second)
    }
    devicesPageSnapshot("devices-page-speed-remote", SnapshotSize.Desktop, SnapshotTheme.Dark) {
      scene.click(NasSpeedOptions.first, NasSpeedOptions.second)
    }
  }

  @Test
  fun devicesPage_keyboardFocus_ringsTheControls() {
    devicesPageSnapshot("devices-page-focus", SnapshotSize.Desktop, SnapshotTheme.Light) {
      repeat(FOCUS_TABS) { scene.pressKey(Key.Tab) }
    }
  }

  @Test
  fun devicesPage_failedCount_opensTheFailedTabOnTheNas() {
    devicesPageSnapshot("devices-page-failed", SnapshotSize.Desktop, SnapshotTheme.Light) {
      scene.click(NasFailed.first, NasFailed.second)
      check(state.statusFilter == StatusFilter.Failed) { "The Failed tab did not open" }
      withTimeoutOrNull(5.seconds) { state.activeInstance.first { it?.label == NAS_NAME } }
      assertEquals(NAS_NAME, state.activeInstance.value?.label)
    }
  }

  private companion object {
    val Wide1440 = SnapshotSize(1440.dp, 900.dp, KetchDensity.Compact)
    val PhoneTall = SnapshotSize(390.dp, 2160.dp, KetchDensity.Comfortable)
    const val FOCUS_TABS = 9
    const val NAS_NAME = "NAS-Basement"

    // Where the Desktop snapshot draws the NAS card's ⋯, its Failed count and the chevrons of
    // the speed pills.
    val NasMenu: Pair<Dp, Dp> = 868.dp to 110.dp
    val NasFailed: Pair<Dp, Dp> = 848.dp to 252.dp
    val MacSpeedOptions: Pair<Dp, Dp> = 533.dp to 189.dp
    val NasSpeedOptions: Pair<Dp, Dp> = 873.dp to 189.dp
  }
}

/** Which devices a [devicesPageSnapshot] shows. */
private enum class DevicesPageFleet {
  /**
   * This Mac downloading, the NAS downloading under a cap, Den-PC offline, a seedbox locked and
   * a Garage-Pi the app does not keep connected.
   */
  Mixed,

  /** This Mac alone, downloading. */
  Single,

  /** This Mac alone, with nothing to do. */
  Idle,
}

/**
 * Renders the app on its Devices page over [fleet], after the speed history has [history]
 * samples of varying speed, then runs [setup].
 */
private fun devicesPageSnapshot(
  name: String,
  size: SnapshotSize,
  theme: SnapshotTheme,
  fleet: DevicesPageFleet = DevicesPageFleet.Mixed,
  history: Int = HISTORY_SAMPLES,
  setup: suspend AppScenario.() -> Unit = {},
): File {
  val density = when (size.density) {
    KetchDensity.Compact -> DensityMode.Compact
    KetchDensity.Comfortable -> DensityMode.Comfortable
  }
  val environment = runBlocking(SnapshotHarness.ui) {
    DevicesPageEnvironment(fleet, theme, density)
  }
  try {
    runBlocking(SnapshotHarness.ui) {
      val timeout = DevicesPageEnvironment.START_TIMEOUT + history.seconds
      withTimeoutOrNull(timeout) { environment.start(history) }
        ?: error("The devices of $name never settled")
    }
    return SnapshotHarness.capture(
      name = "$name-${theme.id}-${size.id}",
      size = size,
      interact = {
        openDevicesPage(size)
        AppScenario(environment.controller, environment.data, this).setup()
      },
    ) {
      App(environment.controller)
    }
  } finally {
    runBlocking(SnapshotHarness.ui) { environment.close() }
  }
}

/**
 * Opens the Devices destination from the sidebar, the rail or the phone's ⋮ menu, then rests
 * the pointer in the page's margin, where it hovers nothing.
 */
private suspend fun SnapshotScene.openDevicesPage(size: SnapshotSize) {
  when {
    size.width < 600.dp -> {
      click(size.width - 28.dp, 32.dp)
      settle()
      // The Devices item, near the bottom of the menu sheet.
      click(100.dp, size.height - 88.dp)
    }
    size.width < 1024.dp -> click(36.dp, 170.dp)
    else -> click(100.dp, 114.dp)
  }
  hover(size.width - 4.dp, size.height / 2)
}

private const val HISTORY_SAMPLES = 10

/**
 * The devices of a [DevicesPageFleet]: this Mac over the sample downloads, sharing on the
 * network over Wi-Fi and Ethernet, and the remote devices, which answer from fakes.
 */
private class DevicesPageEnvironment(
  fleet: DevicesPageFleet,
  theme: SnapshotTheme,
  density: DensityMode,
) {
  val data = SampleData(
    tasks = if (fleet == DevicesPageFleet.Idle) emptyList() else SampleData.downloads().tasks,
    remotes = if (fleet == DevicesPageFleet.Mixed) {
      listOf(Nas, DenPc, Seedbox, GaragePi)
    } else {
      emptyList()
    },
  )
  private val clock = DevicesPageClock(SampleData.NOW - OFFLINE_FOR)
  private val nasTasks = if (fleet == DevicesPageFleet.Mixed) sampleNasTasks() else emptyList()

  // The downloading tasks of every device, in the states they start in.
  private val base = (data.tasks + nasTasks.filterIsInstance<ListTestTask>())
    .filter { it.state.value is DownloadState.Downloading }
    .associateWith { it.state.value as DownloadState.Downloading }
  private val denPc = MutableStateFlow<ConnectionState>(ConnectionState.Connected)
  private val local = DevicesPageLocalApi(SampleKetchApi(data))
  private val speedScope = CoroutineScope(SupervisorJob() + SnapshotHarness.ui)
  private val instanceManager = InstanceManager(
    factory = InstanceFactory(
      deviceName = data.deviceName,
      embeddedFactory = { local },
      localServerFactory = {
        object : LocalServerHandle {
          override fun stop() {}
        }
      },
      remoteFactory = { config -> remote(config) },
    ),
    initialRemotes = data.remotes,
    configStore = RecordingConfigStore(
      data.config(theme, density).copy(
        server = ServerConfig(apiToken = "3f2a9c1e5b7d4a60", mdnsEnabled = true),
      )
    ),
    clock = clock,
  )
  private val speedMode = SpeedModeController(
    config = { local.status().config },
    apply = { local.updateConfig(it) },
    scope = speedScope,
    clock = SampleData.CLOCK,
  )

  /** The controller the app root shows. */
  val controller = AppController(
    instanceManager = instanceManager,
    context = SnapshotHarness.ui,
    speedMode = speedMode,
    clock = SampleData.CLOCK,
  )

  init {
    instanceManager.startServer()
  }

  /**
   * Waits for every device's first status, takes Den-PC offline [OFFLINE_FOR] before
   * [SampleData.NOW], and lets the speed history gather [history] samples while the speeds
   * wander, so the sparklines have a shape; the sample speeds then return.
   */
  suspend fun start(history: Int) {
    val presence = instanceManager.presence
    val count = data.remotes.size + 1
    presence.first { devices -> devices.size == count && devices.all { it.ready } }
    if (data.remotes.isNotEmpty()) {
      denPc.value = ConnectionState.Disconnected("Connection refused")
      presence.first { devices -> devices.any { it.deviceId == DEN_PC_ID && it.lastSeen != null } }
    }
    clock.now = SampleData.NOW
    if (history > 0 && base.isNotEmpty()) {
      val wander = speedScope.launch { wanderSpeeds() }
      presence.first { devices -> devices.first().history.size >= history }
      wander.cancelAndJoin()
      base.forEach { (task, state) -> task.state.value = state }
    }
  }

  // Moves each downloading task's speed along its own wave, a few times a second.
  private suspend fun wanderSpeeds() {
    var tick = 0
    while (true) {
      base.entries.forEachIndexed { index, (task, state) ->
        val phase = tick * WANDER_STEP + index
        val wave = 0.75 + 0.2 * sin(phase) + 0.1 * sin(phase * 2.7)
        val progress = state.progress
        task.state.value = DownloadState.Downloading(
          progress.copy(bytesPerSecond = (progress.bytesPerSecond * wave).toLong()),
        )
      }
      tick++
      delay(WANDER_INTERVAL)
    }
  }

  fun close() {
    controller.close()
    speedScope.cancel()
    instanceManager.close()
  }

  private val DevicePresence.ready: Boolean
    get() = !health.isOnline || status != null && disk != null

  private fun remote(config: RemoteConfig): RemoteInstance {
    val (api, state) = when (config.host) {
      Nas.host -> DevicesPageRemoteApi(nasStatus(), nasTasks) to
        MutableStateFlow<ConnectionState>(ConnectionState.Connected)
      DenPc.host -> DevicesPageRemoteApi(denPcStatus(), emptyList()) to denPc
      GaragePi.host -> DevicesPageRemoteApi(denPcStatus(), emptyList()) to
        MutableStateFlow<ConnectionState>(ConnectionState.Disconnected())
      else -> DevicesPageRemoteApi(denPcStatus(), emptyList()) to
        MutableStateFlow<ConnectionState>(ConnectionState.Unauthorized)
    }
    return RemoteInstance(instance = api, remoteConfig = config, connectionState = state)
  }

  companion object {
    val Nas = RemoteConfig(host = "nas.local", name = "NAS-Basement")
    val DenPc = RemoteConfig(host = "den-pc.local", name = "Den-PC")
    val Seedbox = RemoteConfig(host = "seedbox.example.net", port = 443, secure = true)
    val GaragePi = RemoteConfig(host = "garage-pi.local", name = "Garage-Pi", watch = false)
    const val DEN_PC_ID = "den-pc.local:8642"
    val OFFLINE_FOR: Duration = 2.hours
    val START_TIMEOUT: Duration = 10.seconds
    val WANDER_INTERVAL: Duration = 300.milliseconds
    const val WANDER_STEP = 0.45
  }
}

/** A clock whose time a scenario moves. */
private class DevicesPageClock(@Volatile var now: Instant) : Clock {
  override fun now(): Instant = now
}

/** [sample] downloading over Wi-Fi and Ethernet, with a VPN it does not use. */
private class DevicesPageLocalApi(private val sample: SampleKetchApi) : KetchApi by sample {
  override suspend fun networkInterfaces(): NetworkInterfaces = NetworkInterfaces(
    supported = true,
    available = listOf(
      NetworkInterfaceInfo("en0", "en0", listOf("192.168.1.20")),
      NetworkInterfaceInfo("en7", "en7", listOf("10.0.0.4")),
      NetworkInterfaceInfo("utun3", "utun3", listOf("100.101.7.12"))
    ),
    config = NetworkInterfaceConfig(listOf("en0", "en7")),
  )
}

/** A remote device that answers with [status] and lists [tasks]. */
private class DevicesPageRemoteApi(
  private var status: KetchStatus,
  tasks: List<DownloadTask>,
) : KetchApi {
  override val backendLabel: String = status.name
  override val tasks: StateFlow<List<DownloadTask>> = MutableStateFlow(tasks)

  override suspend fun status(): KetchStatus = status

  override suspend fun updateConfig(config: DownloadConfig) {
    status = status.copy(config = config)
  }

  override suspend fun networkInterfaces(): NetworkInterfaces = NetworkInterfaces(
    supported = true,
    available = listOf(NetworkInterfaceInfo("eth0", "eth0", listOf("192.168.1.40"))),
  )

  override suspend fun download(request: DownloadRequest): DownloadTask =
    throw UnsupportedOperationException("Not in snapshots")

  override suspend fun resolve(url: String, properties: Map<String, String>): ResolvedSource =
    throw UnsupportedOperationException("Not in snapshots")

  override suspend fun start() {}

  override fun close() {}
}

private const val GB = 1_000_000_000L

private fun nasStatus(): KetchStatus = KetchStatus(
  name = "NAS-Basement",
  version = KetchApi.VERSION,
  revision = KetchApi.REVISION,
  uptime = 12.days.inWholeSeconds,
  config = DownloadConfig(
    defaultDirectory = "/volume1/downloads",
    speedLimit = SpeedLimit.mbps(20),
  ),
  system = system(
    os = "Linux",
    arch = "amd64",
    directory = "/volume1/downloads",
    total = 4_000_787_030_016,
    usable = 1_979_120_929_996,
  ),
)

private fun denPcStatus(): KetchStatus = KetchStatus(
  name = "Den-PC",
  version = KetchApi.VERSION,
  revision = KetchApi.REVISION,
  uptime = 5.hours.inWholeSeconds,
  config = DownloadConfig(),
  system = system(
    os = "Windows 11",
    arch = "amd64",
    directory = "C:\\Users\\sam\\Downloads",
    total = 1_000_202_273_280,
    usable = 312_000_000_000,
  ),
)

private fun system(os: String, arch: String, directory: String, total: Long, usable: Long) =
  SystemInfo(
    os = os,
    arch = arch,
    separator = if (os.startsWith("Windows")) "\\" else "/",
    javaVersion = "21",
    availableProcessors = 4,
    maxMemory = 0,
    totalMemory = 0,
    freeMemory = 0,
    downloadDirectory = directory,
    totalSpace = total,
    freeSpace = usable,
    usableSpace = usable,
  )

private fun sampleNasTasks(): List<DownloadTask> = listOf(
  nasTask(
    id = "debian",
    url = "https://cdimage.debian.org/debian-cd/12.7.0/amd64/iso-dvd/" +
      "debian-12.7.0-amd64-DVD-1.iso",
    state = DownloadState.Downloading(DownloadProgress(1_520_000_000, 3_900_000_000, 2_880_000)),
    ago = 25.minutes,
  ),
  nasTask(
    id = "proxmox",
    url = "https://enterprise.proxmox.com/iso/proxmox-ve_8.2-1.iso",
    state = DownloadState.Downloading(DownloadProgress(910_000_000, 1_340_000_000, 1_420_000)),
    ago = 18.minutes,
  ),
  nasTask(
    id = "fedora",
    url = "https://download.fedoraproject.org/pub/fedora/41/Fedora-Workstation-Live-41.iso",
    state = DownloadState.Queued,
    ago = 12.minutes,
  ),
  nasTask(
    id = "nixos",
    url = "https://channels.nixos.org/nixos-24.05/latest-nixos-gnome-x86_64-linux.iso",
    state = DownloadState.Queued,
    ago = 9.minutes,
  ),
  nasTask(
    id = "photos",
    url = "https://photos.example.net/share/family-photos-2025.zip",
    state = DownloadState.Failed(KetchError.Http(404, "Not Found")),
    ago = 2.hours,
  ),
  nasTask(
    id = "backup",
    url = "https://backups.example.net/weekly/home-backup-2026-09-27.tar.zst",
    state = DownloadState.Completed(
      outputPath = "/volume1/downloads/home-backup-2026-09-27.tar.zst",
      totalBytes = 48 * GB,
      downloadTime = 1.hours,
    ),
    ago = 4.days,
  ),
)

private fun nasTask(id: String, url: String, state: DownloadState, ago: Duration) = ListTestTask(
  taskId = id,
  state = state,
  request = DownloadRequest(url = url, destination = Destination("/volume1/downloads/")),
  createdAt = SampleData.NOW - ago,
)
