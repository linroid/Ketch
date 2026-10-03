package com.linroid.ketch.app.snapshot

import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.linroid.ketch.api.Destination
import com.linroid.ketch.api.DownloadConfig
import com.linroid.ketch.api.DownloadProgress
import com.linroid.ketch.api.DownloadRequest
import com.linroid.ketch.api.DownloadState
import com.linroid.ketch.api.DownloadTask
import com.linroid.ketch.api.KetchError
import com.linroid.ketch.api.ResolvedSource
import com.linroid.ketch.api.Segment
import com.linroid.ketch.app.RecordingConfigStore
import com.linroid.ketch.app.components.DeviceOption
import com.linroid.ketch.app.components.KetchMenuPanel
import com.linroid.ketch.app.i18n.verbatim
import com.linroid.ketch.app.instance.InstanceFactory
import com.linroid.ketch.app.instance.InstanceManager
import com.linroid.ketch.app.instance.RemoteInstance
import com.linroid.ketch.app.state.AppController
import com.linroid.ketch.app.state.DeviceHealth
import com.linroid.ketch.app.state.IntakeRequest
import com.linroid.ketch.app.state.LOCAL_DEVICE_ID
import com.linroid.ketch.app.state.ListTestTask
import com.linroid.ketch.app.state.LocalAppState
import com.linroid.ketch.app.state.TaskKey
import com.linroid.ketch.app.theme.KetchDensity
import com.linroid.ketch.app.theme.KetchTheme
import com.linroid.ketch.app.ui.downloads.actions.SendMode
import com.linroid.ketch.app.ui.downloads.actions.SendTarget
import com.linroid.ketch.app.ui.downloads.actions.rememberRowActionRunner
import com.linroid.ketch.app.ui.downloads.actions.sendEntries
import com.linroid.ketch.app.ui.downloads.actions.sendTargets
import com.linroid.ketch.config.DensityMode
import com.linroid.ketch.config.RemoteConfig
import com.linroid.ketch.remote.ConnectionState
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.yield
import java.io.File
import kotlin.random.Random
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * Every device at once (W4-ALL-DEVICES): the merged Downloads list with its Device column and
 * pennants, a NAS download in the inspector, Send to and its cookie warning, and the add sheet
 * aimed at the NAS. See [SnapshotHarness] for how to run them.
 */
class AllDevicesSnapshots {
  @BeforeTest
  fun enabled() = requireSnapshots()

  @Test
  fun allDevices_table_showsTheDeviceColumn() {
    allDevicesSnapshots("all-devices-table", listOf(SnapshotSize.Desktop))
  }

  @Test
  fun allDevices_hoveredRows_keepTheirDeviceInView() {
    allDevicesSnapshots("all-devices-table-hover", listOf(SnapshotSize.Desktop)) {
      scene.hover(x = 960.dp, y = 278.dp)
    }
    allDevicesSnapshots("all-devices-table-hover-failed", listOf(SnapshotSize.Desktop)) {
      scene.hover(x = 960.dp, y = 602.dp)
    }
  }

  @Test
  fun allDevices_noDownloads_offersToAddOne() {
    allDevicesSnapshots("all-devices-empty", listOf(SnapshotSize.Desktop), empty = true)
  }

  @Test
  fun allDevices_narrow_leadsRowsWithPennants() {
    allDevicesSnapshots("all-devices-list", listOf(SnapshotSize.Medium, SnapshotSize.Phone))
  }

  @Test
  fun allDevices_nasRow_inspectsTheNasDownload() {
    allDevicesSnapshots("all-devices-nas-row", listOf(SnapshotSize.Desktop)) {
      state.inspect(TaskKey(NAS_ID, "imagenet-02"))
    }
  }

  @Test
  fun allDevices_search_offersTheDeviceFacet() {
    allDevicesSnapshots("all-devices-facets", listOf(SnapshotSize.Desktop)) {
      state.searchQuery = "is:downloading"
    }
  }

  @Test
  fun sendTo_cookies_asksFirst() {
    allDevicesSnapshots("all-devices-send-cookies", DesktopAndPhone) {
      val task = ListTestTask(
        taskId = "report",
        state = DownloadState.Paused(DownloadProgress(1_200_000, 2_516_582)),
        request = DownloadRequest(
          url = "https://intranet.example.com/q3-report.pdf",
          headers = mapOf("Cookie" to "session=abc"),
        ),
      )
      val nas = state.instances.value.first { it is RemoteInstance }
      state.sendTo(listOf(task), nas)
    }
  }

  @Test
  fun intake_nasTarget_resolvesOnTheNas() {
    allDevicesSnapshots("all-devices-intake-nas", DesktopAndPhone) {
      state.openIntake(
        IntakeRequest(
          text = "https://releases.ubuntu.com/24.04/ubuntu-24.04-live-server-amd64.iso",
          targetDeviceId = NAS_ID,
        ),
      )
    }
  }

  @Test
  fun resolvingChip_torrentInBackground_showsInTheHeader() {
    allDevicesSnapshots("all-devices-resolving", listOf(SnapshotSize.Desktop)) {
      val session = state.intake.start(IntakeRequest(text = SLOW_MAGNET, targetDeviceId = NAS_ID))
      delay(SETTLE)
      state.intake.finishInBackground(session)
    }
  }

  @Test
  fun sendMenu_altHeld_movesInstead() {
    val size = SnapshotSize(300.dp, 200.dp, KetchDensity.Compact)
    val modes = listOf(
      "all-devices-send-menu" to SendMode(hint = "Hold ⌥ to move instead"),
      "all-devices-send-menu-move" to SendMode(
        move = true,
        hint = "Removed from the old device once sent",
      ),
    )
    for (theme in SnapshotTheme.entries) {
      withEnvironment({ AllDevicesEnvironment(theme, DensityMode.Compact) }) { environment ->
        val state = environment.controller.state
        val presence = runBlocking(SnapshotHarness.ui) {
          withTimeoutOrNull(START_TIMEOUT) {
            state.instanceManager.presence.first { list -> list.all { it.disk != null } }
          }.orEmpty()
        }
        val rows = state.taskList.rows.value.filter { it.key.deviceId == LOCAL_DEVICE_ID }.take(1)
        val targets = sendTargets(state.instances.value, rows, presence) + SendTarget(
          entry = state.instances.value.last(),
          option =
            DeviceOption("den-pc:8642", verbatim("Den-PC"), DeviceHealth.Offline(), "Den-PC"),
        )
        for ((name, mode) in modes) {
          snapshot(name, size, theme) {
            CompositionLocalProvider(LocalAppState provides state) {
              val runner = rememberRowActionRunner()
              KetchMenuPanel(Modifier.padding(KetchTheme.spacing.s2)) {
                sendEntries(rows, runner, targets, mode)
              }
            }
          }
        }
      }
    }
  }
}

/**
 * Renders the app over [AllDevicesEnvironment] showing every device, at [sizes] in both themes;
 * with [empty], neither device has downloads.
 */
private fun allDevicesSnapshots(
  name: String,
  sizes: List<SnapshotSize>,
  empty: Boolean = false,
  setup: suspend AppScenario.() -> Unit = {},
): List<File> = sizes.flatMap { size ->
  SnapshotTheme.entries.map { theme -> allDevicesSnapshot(name, size, theme, empty, setup) }
}

private fun allDevicesSnapshot(
  name: String,
  size: SnapshotSize,
  theme: SnapshotTheme,
  empty: Boolean,
  setup: suspend AppScenario.() -> Unit,
): File = withEnvironment({ AllDevicesEnvironment(theme, size.density.toMode(), empty) }) {
  captureApp(name, size, theme, it) {
    // The Pulse bar's history fills once a second; give it a few samples.
    delay(HISTORY_WAIT)
    setup()
  }
}

/** This Mac with the sample downloads, and a connected NAS-Basement with its own. */
private class AllDevicesEnvironment(
  theme: SnapshotTheme,
  density: DensityMode,
  empty: Boolean = false,
) : SnapshotEnvironment {
  override val data = SampleData(
    tasks = if (empty) emptyList() else SampleData.downloads().tasks,
    remotes = listOf(RemoteConfig(host = "nas.local", port = 8642, name = "NAS-Basement")),
  )
  private val nas = allDevicesNas(if (empty) emptyList() else allDevicesNasTasks())

  private val instanceManager = InstanceManager(
    factory = InstanceFactory(
      deviceName = data.deviceName,
      embeddedFactory = { SampleKetchApi(data) },
      remoteFactory = { config ->
        RemoteInstance(nas, config, MutableStateFlow(ConnectionState.Connected))
      },
    ),
    initialRemotes = data.remotes,
    configStore = RecordingConfigStore(data.config(theme, density)),
  )

  override val controller: AppController = AppController(
    instanceManager = instanceManager,
    context = SnapshotHarness.ui,
    clock = SampleData.CLOCK,
  )

  /**
   * Fills the speed history with three minutes of samples of both devices, shows every device
   * and waits until both devices' tasks are listed.
   */
  override suspend fun start() {
    // Let the store forget the tasks of the empty list it starts from.
    repeat(STARTUP_YIELDS) { yield() }
    val states = data.tasks.associate { TaskKey(LOCAL_DEVICE_ID, it.taskId) to it.state.value } +
      nas.tasks.value.associate { TaskKey(NAS_ID, it.taskId) to it.state.value }
    seedSpeedHistory(controller.speedHistory, states, Random(HISTORY_SEED))
    controller.state.taskList.allRows.first { it.size == data.tasks.size + nas.tasks.value.size }
    check(controller.state.showAllDevices()) { "Fewer than two devices" }
    controller.state.taskList.rows.first { it.size == data.tasks.size + nas.tasks.value.size }
  }

  override fun close() {
    controller.close()
    instanceManager.close()
  }
}

private val DesktopAndPhone = listOf(SnapshotSize.Desktop, SnapshotSize.Phone)
private const val NAS_ID = "nas.local:8642"
private const val NAS_DIR = "/volume1/downloads"
private const val GIB = 1L shl 30
private val START_TIMEOUT = 5.seconds
private const val STARTUP_YIELDS = 3
private const val HISTORY_SEED = 7
private val HISTORY_WAIT = 3.seconds
private val SETTLE = 300.milliseconds

/** A magnet whose file list never arrives, so the add sheet keeps waiting for it. */
private const val SLOW_MAGNET = "magnet:?xt=urn:btih:8a19577fb5f690970ca43a57ff1011ae202244b8" +
  "&dn=Big.Buck.Bunny.4K"

/**
 * NAS-Basement with [tasks] of its own and a bigger disk; it takes what is sent to it, and
 * resolves links but no magnet.
 */
private fun allDevicesNas(tasks: List<DownloadTask>) = SampleDeviceApi(
  status = sampleStatus(
    name = "NAS-Basement",
    uptime = 12.days,
    config = DownloadConfig(defaultDirectory = NAS_DIR, maxConcurrentDownloads = 2),
    system = sampleSystem(
      os = "Linux",
      directory = NAS_DIR,
      total = 4_000_000_000_000,
      usable = 1_800_000_000_000,
      maxMemory = 2 * GIB,
      totalMemory = GIB,
      freeMemory = GIB / 2,
    ),
  ),
  tasks = tasks,
  download = { request ->
    ListTestTask("sent-${request.url.hashCode()}", DownloadState.Queued, request)
  },
  resolve = { url ->
    if (url.startsWith("magnet:")) awaitCancellation()
    ResolvedSource(
      url = url,
      sourceType = "http",
      totalBytes = 2_684_354_560,
      supportsResume = true,
      suggestedFileName = url.substringAfterLast('/'),
      maxSegments = 8,
    )
  },
)

private fun allDevicesNasTasks(): List<ListTestTask> {
  val total = 38 * GIB
  val segments = listOf(0.52, 0.37, 0.28, 0.44).mapIndexed { index, done ->
    val size = total / 4
    val start = index * size
    val end = if (index == 3) total - 1 else start + size - 1
    Segment(index, start, end, ((end - start + 1) * done).toLong())
  }
  return listOf(
    ListTestTask(
      taskId = "imagenet-02",
      state = DownloadState.Downloading(
        DownloadProgress(segments.sumOf { it.downloadedBytes }, total, 2_700_000),
      ),
      request = DownloadRequest(
        url = "https://image-net.org/data/train/imagenet-part02.tar",
        destination = Destination("$NAS_DIR/"),
        connections = 4,
      ),
      createdAt = SampleData.NOW - 2.hours,
      segments = segments,
    ),
    ListTestTask(
      taskId = "debian",
      state = DownloadState.Queued,
      request = DownloadRequest(
        url = "https://cdimage.debian.org/debian-cd/current/amd64/iso-dvd/" +
          "debian-12.7.0-amd64-DVD-1.iso",
        destination = Destination("$NAS_DIR/"),
      ),
      createdAt = SampleData.NOW - 25.minutes,
    ),
    ListTestTask(
      taskId = "backup",
      state = DownloadState.Completed(
        outputPath = "$NAS_DIR/photos-backup-2026-09.tar.gz",
        totalBytes = 48 * GIB,
        downloadTime = 3.hours,
      ),
      request = DownloadRequest(
        url = "https://backup.example.net/photos-backup-2026-09.tar.gz",
        destination = Destination("$NAS_DIR/"),
      ),
      createdAt = SampleData.NOW - 4.hours,
    ),
    ListTestTask(
      taskId = "firmware",
      state = DownloadState.Failed(KetchError.Http(404, "Not Found")),
      request = DownloadRequest(
        url = "https://downloads.example.com/router/firmware-3.2.1.bin",
        destination = Destination("$NAS_DIR/"),
      ),
      createdAt = SampleData.NOW - 1.hours,
    ),
  )
}
