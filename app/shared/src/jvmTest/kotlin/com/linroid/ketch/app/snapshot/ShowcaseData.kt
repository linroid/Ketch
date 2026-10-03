package com.linroid.ketch.app.snapshot

import com.linroid.ketch.api.DownloadConfig
import com.linroid.ketch.api.DownloadPriority
import com.linroid.ketch.api.DownloadProgress
import com.linroid.ketch.api.DownloadSchedule
import com.linroid.ketch.api.DownloadState
import com.linroid.ketch.api.KetchApi
import com.linroid.ketch.api.SpeedLimit
import com.linroid.ketch.app.RecordingConfigStore
import com.linroid.ketch.app.instance.InstanceFactory
import com.linroid.ketch.app.instance.InstanceManager
import com.linroid.ketch.app.instance.RemoteInstance
import com.linroid.ketch.app.state.AiDiscoveryProviderFactory
import com.linroid.ketch.app.state.AppController
import com.linroid.ketch.app.state.LOCAL_DEVICE_ID
import com.linroid.ketch.app.state.ListTestTask
import com.linroid.ketch.app.state.TaskKey
import com.linroid.ketch.config.DensityMode
import com.linroid.ketch.config.RemoteConfig
import com.linroid.ketch.config.UiPreferences
import com.linroid.ketch.remote.ConnectionState
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.yield
import kotlin.math.PI
import kotlin.math.roundToLong
import kotlin.math.sin
import kotlin.random.Random
import kotlin.time.Duration
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.DurationUnit
import kotlin.time.Instant

/**
 * The devices and downloads of the README showcase, free of real brands, sites and people:
 * "Studio", a Mac whose downloads every screen shows, "Home server", a Linux box downloading on
 * its own, and a laptop the desktop window runs on and the phones reach as a remote. The desktop
 * and the phones all show Studio as their active remote, so every screen names it the same way.
 * Device ids are picked for their pennant hues: a calm slate for Studio, and the same hue for
 * the laptop as a remote as for the embedded device it is on the desktop.
 */
internal object ShowcaseData {
  /** Name of the Mac whose downloads every screen shows. */
  const val STUDIO: String = "Studio"

  /** Device id of [STUDIO], a remote on every screen. */
  const val STUDIO_ID: String = "studio.lan:8642"

  /** Host name of the laptop the desktop window runs on, which it calls "This Mac". */
  const val LAPTOP: String = "Laptop"

  /** Device id of [LAPTOP] on the phones, which reach it as a remote. */
  const val LAPTOP_ID: String = "laptop.home:8642"

  /** Device id of the home server everywhere. */
  const val HOME_ID: String = "home-server.local:8642"

  /** The ISO the desktop and the iOS phone inspect. */
  const val ISO: String = "server-26.10-amd64.iso"

  /** Task id of [ISO]. */
  const val ISO_ID: String = "os-installer"

  /**
   * Rates of [ISO]'s eight lanes in bytes per second; the first has finished, and the others,
   * each a round tenth of a MB/s, add up to the 17.5 MB/s the task reports.
   */
  val ISO_LANE_RATES: List<Long> = listOf(0.0, 2.8, 2.4, 2.6, 2.1, 2.5, 2.7, 2.4)
    .map { (it * MIB).roundToLong() }

  const val STUDIO_DIR: String = "/Users/ketch/Downloads"
  const val HOME_DIR: String = "/srv/downloads"
  const val LAPTOP_DIR: String = "/Users/ketch/Downloads"

  val StudioRemote: RemoteConfig = RemoteConfig(host = "studio.lan", port = 8642, name = STUDIO)
  val HomeRemote: RemoteConfig =
    RemoteConfig(host = "home-server.local", port = 8642, name = "Home server")
  val LaptopRemote: RemoteConfig = RemoteConfig(host = "laptop.home", port = 8642, name = LAPTOP)

  /** The release-style version every device reports, without the build's `-dev` suffix. */
  val VERSION: String = KetchApi.VERSION.substringBefore('-')

  /** Studio's settings: three download slots, all taken, so two downloads wait for one. */
  val StudioConfig: DownloadConfig = DownloadConfig(
    defaultDirectory = STUDIO_DIR,
    maxConcurrentDownloads = 3,
    maxConnectionsPerDownload = 8,
  )

  /** Studio's downloads in every lively state. */
  fun studioTasks(): List<ListTestTask> = listOf(
    downloading(
      id = ISO_ID,
      url = "https://mirror.example.org/releases/26.10/$ISO",
      total = 6_114_656_256,
      speed = ISO_LANE_RATES.sum(),
      // Far enough from done that no lane finishes while the iOS sheet's rates settle.
      lanes = listOf(1.0, 0.89, 0.84, 0.78, 0.71, 0.64, 0.53, 0.42),
      ago = 7.minutes,
      dir = STUDIO_DIR,
    ),
    downloading(
      id = "shard-03",
      url = "https://data.example.net/corpus/dataset-shard-03.tar",
      total = 13 * GIB,
      speed = 6_500_000,
      lanes = listOf(0.71, 0.56, 0.47, 0.34),
      ago = 42.minutes,
      priority = DownloadPriority.HIGH,
      dir = STUDIO_DIR,
    ),
    task(
      id = "timelapse",
      url = "magnet:?xt=urn:btih:4c7f3e2b9d1a8f6e5c0b7a3d2e1f9c8b7a6d5e4f" +
        "&dn=sunrise-timelapse-8k.mov&tr=udp%3A%2F%2Ftracker.example.org%3A6969",
      state = DownloadState.Downloading(
        DownloadProgress((1_288_490_188 * 0.41).toLong(), 1_288_490_188, 3_100_000),
      ),
      ago = 12.minutes,
      speedLimit = SpeedLimit.mbps(5),
      dir = STUDIO_DIR,
    ),
    // Waiting behind the three running downloads, in the engine's order: priority, then age.
    task(
      id = "shard-04",
      url = "https://data.example.net/corpus/dataset-shard-04.tar",
      state = DownloadState.Queued,
      ago = 41.minutes,
      dir = STUDIO_DIR,
      queuePosition = 1,
    ),
    task(
      id = "assets",
      url = "https://files.example.com/design/design-assets-2026.zip",
      state = DownloadState.Queued,
      ago = 3.minutes,
      dir = STUDIO_DIR,
      queuePosition = 2,
    ),
    task(
      id = "model",
      url = "https://models.example.net/weights/local-model-8b-q4.gguf",
      state = DownloadState.Scheduled(DownloadSchedule.AtTime(TONIGHT)),
      ago = 20.minutes,
      dir = STUDIO_DIR,
    ),
    task(
      id = "sdk",
      url = "https://downloads.example.com/sdk/sdk-tools-2026.2-arm64.dmg",
      state = DownloadState.Paused(DownloadProgress(497_025_024, 1_342_177_280)),
      ago = 1.days + 3.hours,
      segments = lanes(1_342_177_280, listOf(0.62, 0.41, 0.3, 0.15)),
      dir = STUDIO_DIR,
    ),
    completed(
      id = "report",
      url = "https://files.example.com/shared/quarterly-report.pdf",
      total = 2_621_440,
      time = 1.seconds,
      ago = 50.minutes,
      dir = STUDIO_DIR,
    ),
    completed(
      id = "podcast",
      url = "https://media.example.com/episodes/podcast-episode-142.mp3",
      total = 86 * MIB,
      time = 9.seconds,
      ago = 2.hours,
      dir = STUDIO_DIR,
    ),
    completed(
      id = "recording",
      url = "https://audio.example.org/field/field-recording-0928.wav",
      total = 412 * MIB,
      time = 38.seconds,
      ago = 5.hours,
      dir = STUDIO_DIR,
    ),
    completed(
      id = "photos",
      url = "https://photos.example.net/share/vacation-photos-2026.zip",
      total = 1_932_735_283,
      time = 3.minutes + 12.seconds,
      ago = 1.days + 6.hours,
      dir = STUDIO_DIR,
    ),
  )

  /** The laptop's own downloads: one talk on its way. */
  fun laptopTasks(): List<ListTestTask> = listOf(
    downloading(
      id = "talk",
      url = "https://media.example.com/talks/conference-talk-2026.mp4",
      total = 1_610_612_736,
      speed = 3_250_000,
      lanes = listOf(0.66, 0.48),
      ago = 6.minutes,
      dir = LAPTOP_DIR,
    ),
  )

  /** The home server's own downloads: two running and one done. */
  fun homeTasks(): List<ListTestTask> = listOf(
    downloading(
      id = "backup",
      url = "https://backup.example.net/archive/archive-backup-2026-09.tar.gz",
      total = 38 * GIB,
      speed = 5_400_000,
      lanes = listOf(0.52, 0.37, 0.28, 0.44),
      ago = 2.hours,
      dir = HOME_DIR,
    ),
    downloading(
      id = "nightly",
      url = "https://builds.example.org/nightly/nightly-build-2026-10-01.img",
      total = 3 * GIB,
      speed = 2_900_000,
      lanes = listOf(0.71, 0.58),
      ago = 25.minutes,
      dir = HOME_DIR,
    ),
    completed(
      id = "media",
      url = "https://media.example.com/library/lecture-series-s02.zip",
      total = 7 * GIB,
      time = 14.minutes,
      ago = 4.hours,
      dir = HOME_DIR,
    ),
  )

  /**
   * Moves [task]'s segments on by [rates] bytes per second over [elapsed], and its progress with
   * them, as a running download reports it. Lanes that are done stay done.
   */
  fun advance(task: ListTestTask, rates: List<Long>, elapsed: Duration) {
    val seconds = elapsed.toDouble(DurationUnit.SECONDS)
    task.segments.update { segments ->
      segments.mapIndexed { index, segment ->
        val step = (rates.getOrElse(index) { 0L } * seconds).roundToLong()
        val done = (segment.downloadedBytes + step).coerceAtMost(segment.totalBytes)
        segment.copy(downloadedBytes = done)
      }
    }
    val state = task.state.value as? DownloadState.Downloading ?: return
    val downloaded = task.segments.value.sumOf { it.downloadedBytes }
    task.state.value = DownloadState.Downloading(state.progress.copy(downloadedBytes = downloaded))
  }
}

/** Which app a [ShowcaseEnvironment] runs: the laptop's desktop app, or a phone. */
internal enum class ShowcaseDevice { Desktop, Phone }

/**
 * The devices, config and controller behind one screen of the showcase, run on
 * [SnapshotHarness.ui] with the clock at [SampleData.NOW]. Studio and the home server are
 * connected remotes and Studio is active. The [ShowcaseDevice.Desktop] runs on the laptop, its
 * embedded device; a [ShowcaseDevice.Phone] has none and reaches the laptop as a third remote.
 *
 * @param aiProviderFactory AI discovery of the platform; `null` where it is not supported.
 * @param ui changes to the app's view preferences.
 */
internal class ShowcaseEnvironment(
  device: ShowcaseDevice,
  theme: SnapshotTheme,
  density: DensityMode,
  aiProviderFactory: AiDiscoveryProviderFactory? = null,
  ui: (UiPreferences) -> UiPreferences = { it },
) : SnapshotEnvironment {
  /** Studio's downloads, whose flows a scenario can move on. */
  val studioTasks: List<ListTestTask> = ShowcaseData.studioTasks()

  private val homeTasks = ShowcaseData.homeTasks()
  private val laptopTasks = ShowcaseData.laptopTasks()
  private val desktop = device == ShowcaseDevice.Desktop
  private val localTasks = if (desktop) laptopTasks else emptyList()
  private val laptopId = if (desktop) LOCAL_DEVICE_ID else ShowcaseData.LAPTOP_ID
  override val data = SampleData(
    tasks = localTasks,
    downloadConfig = ShowcaseData.StudioConfig,
    remotes = listOfNotNull(
      ShowcaseData.StudioRemote,
      ShowcaseData.HomeRemote,
      ShowcaseData.LaptopRemote.takeUnless { desktop },
    ),
    deviceName = if (desktop) ShowcaseData.LAPTOP else PHONE_NAME,
    ui = { ui(it.copy(lastDeviceId = ShowcaseData.STUDIO_ID)) },
  )
  private val studio = SampleKetchApi(
    name = ShowcaseData.STUDIO,
    initial = studioTasks,
    config = ShowcaseData.StudioConfig,
    directory = ShowcaseData.STUDIO_DIR,
    version = ShowcaseData.VERSION,
  )
  private val home = SampleKetchApi(
    name = "Home server",
    initial = homeTasks,
    config = DownloadConfig(defaultDirectory = ShowcaseData.HOME_DIR, maxConcurrentDownloads = 2),
    os = "Linux",
    directory = ShowcaseData.HOME_DIR,
    version = ShowcaseData.VERSION,
  )
  private val laptop = SampleKetchApi(
    name = ShowcaseData.LAPTOP,
    initial = laptopTasks,
    config = DownloadConfig(defaultDirectory = ShowcaseData.LAPTOP_DIR),
    directory = ShowcaseData.LAPTOP_DIR,
    version = ShowcaseData.VERSION,
  )
  private val instanceManager = InstanceManager(
    factory = InstanceFactory(
      deviceName = data.deviceName,
      // The phones run remote only, as the JVM would call an embedded device "This Mac".
      embeddedFactory = if (desktop) ({ laptop }) else null,
      remoteFactory = { config ->
        val api = when (config.host) {
          ShowcaseData.StudioRemote.host -> studio
          ShowcaseData.LaptopRemote.host -> laptop
          else -> home
        }
        RemoteInstance(api, config, MutableStateFlow(ConnectionState.Connected))
      },
    ),
    initialRemotes = data.remotes,
    configStore = RecordingConfigStore(data.config(theme, density)),
  )

  override val controller: AppController = AppController(
    instanceManager = instanceManager,
    aiProviderFactory = aiProviderFactory,
    context = SnapshotHarness.ui,
    clock = SampleData.CLOCK,
  )

  /** Key of Studio's task [taskId] in this app. */
  fun studioKey(taskId: String): TaskKey = TaskKey(ShowcaseData.STUDIO_ID, taskId)

  /**
   * Fills the speed history with three minutes of samples of every device, then waits until
   * the active device's downloads are listed and every device has reported its status.
   */
  override suspend fun start() {
    // Let the store forget the tasks of the empty list it starts from.
    repeat(STARTUP_YIELDS) { yield() }
    val states = studioTasks.associate { studioKey(it.taskId) to it.state.value } +
      homeTasks.associate { TaskKey(ShowcaseData.HOME_ID, it.taskId) to it.state.value } +
      laptopTasks.associate { TaskKey(laptopId, it.taskId) to it.state.value }
    seedSpeedHistory(controller.speedHistory, states, Random(SEED)) { second, random ->
      0.84 + 0.12 * sin(second * PI / 23) + 0.08 * random.nextDouble()
    }
    controller.taskList.rows.first { it.size == studioTasks.size }
    instanceManager.presence.first { list -> list.all { it.disk != null } }
  }

  /**
   * Plays [seconds] of each device's live speed: once a second, as the app samples it for the
   * device cards' and the Pulse bar's sparklines, its running downloads swing around their speed
   * together, then settle back on it for the last samples.
   */
  suspend fun playSpeedHistory(seconds: Int) {
    val devices = listOf(studioTasks, homeTasks, laptopTasks)
    val speeds = devices.flatten().associateWith { task ->
      (task.state.value as? DownloadState.Downloading)?.progress?.bytesPerSecond
    }
    val random = Random(SEED)
    fun swing(factors: List<Double>) = devices.forEachIndexed { index, tasks ->
      for (task in tasks) {
        val speed = speeds[task] ?: continue
        val state = task.state.value as? DownloadState.Downloading ?: continue
        val progress = state.progress.copy(bytesPerSecond = (speed * factors[index]).roundToLong())
        task.state.value = DownloadState.Downloading(progress)
      }
    }
    for (second in 0 until seconds) {
      swing(
        devices.indices.map { index ->
          SWING_BASE + SWING_WAVE * sin(second * SWING_STEP + index * SWING_PHASE) +
            SWING_NOISE * random.nextDouble()
        },
      )
      delay(1.seconds)
    }
    swing(devices.map { 1.0 })
    delay(SETTLE_SAMPLES.seconds)
  }

  override fun close() {
    controller.close()
    instanceManager.close()
  }

  private companion object {
    const val PHONE_NAME = "Phone"
    const val SEED = 26
    const val STARTUP_YIELDS = 3
    const val SWING_BASE = 0.76
    const val SWING_WAVE = 0.18
    const val SWING_NOISE = 0.1
    const val SWING_STEP = 0.75
    const val SWING_PHASE = 2.1
    const val SETTLE_SAMPLES = 2
  }
}

private val TONIGHT = Instant.parse("2026-10-01T23:00:00Z")
private const val GIB = 1L shl 30
private const val MIB = 1L shl 20
