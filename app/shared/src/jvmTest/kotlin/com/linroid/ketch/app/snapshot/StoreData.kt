package com.linroid.ketch.app.snapshot

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.linroid.ketch.api.DownloadConfig
import com.linroid.ketch.api.DownloadPriority
import com.linroid.ketch.api.DownloadProgress
import com.linroid.ketch.api.DownloadSchedule
import com.linroid.ketch.api.DownloadState
import com.linroid.ketch.api.KetchApi
import com.linroid.ketch.api.ResolvedSource
import com.linroid.ketch.api.SpeedLimit
import com.linroid.ketch.app.RecordingConfigStore
import com.linroid.ketch.app.instance.InstanceFactory
import com.linroid.ketch.app.instance.InstanceManager
import com.linroid.ketch.app.instance.RemoteInstance
import com.linroid.ketch.app.platform.LocalDeviceKind
import com.linroid.ketch.app.state.AiCandidate
import com.linroid.ketch.app.state.AiDiscoverRequest
import com.linroid.ketch.app.state.AiDiscoverResponse
import com.linroid.ketch.app.state.AiDiscoveryProvider
import com.linroid.ketch.app.state.AiDiscoveryProviderFactory
import com.linroid.ketch.app.state.AiPageRequest
import com.linroid.ketch.app.state.AppController
import com.linroid.ketch.app.state.DiscoveryStep
import com.linroid.ketch.app.state.LOCAL_DEVICE_ID
import com.linroid.ketch.app.state.ListTestTask
import com.linroid.ketch.app.state.ObservedPeak
import com.linroid.ketch.app.state.SpeedModeController
import com.linroid.ketch.app.state.SpeedScheduler
import com.linroid.ketch.app.state.TaskKey
import com.linroid.ketch.app.theme.KetchDensity
import com.linroid.ketch.config.AiSettings
import com.linroid.ketch.config.LlmProvider
import com.linroid.ketch.config.LlmSettings
import com.linroid.ketch.config.SpeedLimitMode
import com.linroid.ketch.config.SpeedRule
import com.linroid.ketch.config.SpeedSettings
import com.linroid.ketch.config.Weekday
import com.linroid.ketch.remote.ConnectionState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.yield
import kotlinx.datetime.TimeZone
import kotlin.math.PI
import kotlin.math.roundToLong
import kotlin.math.sin
import kotlin.random.Random
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

/**
 * A device the app store screenshots show the app on, with the size of its screen in dp.
 *
 * @property kind how the app names the device it runs on: "This phone", "This iPad".
 * @property os the system the device reports, which picks its glyph on other devices.
 * @property directory its download folder.
 * @property tablet whether it shows the wide layout, with a sidebar, rather than a phone's.
 */
internal enum class StoreDevice(
  val kind: LocalDeviceKind,
  val os: String,
  val directory: String,
  val width: Dp,
  val height: Dp,
  val tablet: Boolean,
) {
  /** A 6.9" iPhone, 440 × 956 points. */
  IPhone(LocalDeviceKind.Phone, "iOS 26.0", IOS_DIR, 440.dp, 956.dp, tablet = false),

  /** A 20:9 Android phone. */
  AndroidPhone(LocalDeviceKind.Phone, "Android 16", ANDROID_DIR, 412.dp, 915.dp, tablet = false),

  /** A 13" iPad in landscape, 1376 × 1032 points. */
  IPad(LocalDeviceKind.IPad, "iPadOS 26.0", IOS_DIR, 1376.dp, 1032.dp, tablet = true),

  /** A 10" Android tablet in landscape. */
  AndroidTablet(LocalDeviceKind.Tablet, "Android 16", ANDROID_DIR, 1280.dp, 800.dp, tablet = true),
  ;

  /** Touch density on every store device. */
  val density: KetchDensity get() = KetchDensity.Comfortable

  /** Whether the device runs iOS or iPadOS, where Discover is not offered. */
  val apple: Boolean get() = this == IPhone || this == IPad
}

/**
 * The downloads of the store screenshots, free of real brands, sites and people: the device the
 * app runs on downloads [phoneTasks]; Studio, a Mac, and the home server are paired remotes with
 * the downloads of the README showcase.
 */
internal object StoreData {
  /** The large download whose connections the screenshots show. */
  const val MAPS: String = "offline-maps-europe-2026.zip"

  /** Task id of [MAPS]. */
  const val MAPS_ID: String = "maps"

  /**
   * Rates of [MAPS]'s eight lanes in bytes per second; the first has finished, and the others,
   * each a round tenth of a MB/s, add up to the 15.0 MB/s the task reports.
   */
  val MAPS_LANE_RATES: List<Long> = listOf(0.0, 2.3, 2.1, 2.4, 1.9, 2.2, 2.0, 2.1)
    .map { (it * MIB).roundToLong() }

  /** Links the add sheet is opened with, and the sizes they resolve to. */
  val LinkSizes: Map<String, Long> = linkedMapOf(
    "https://media.example.org/lectures/astronomy-101-complete.zip" to 1_975_517_184,
    "https://downloads.example.com/fonts/type-family-pro.zip" to 48_234_496,
    "https://data.example.net/open/city-transit-gtfs-2026.zip" to 222_298_112,
    "https://images.example.com/packs/night-sky-wallpapers-8k.zip" to 671_088_640,
    "https://maps.example.org/trails/hiking-trails-alps-gpx.zip" to 18_874_368,
  )

  /** The version every device reports. */
  const val VERSION: String = "1.0.0"

  /** The phone's settings: three download slots, all taken, so two downloads wait for one. */
  fun config(directory: String): DownloadConfig = DownloadConfig(
    defaultDirectory = directory,
    maxConcurrentDownloads = 3,
    maxConnectionsPerDownload = 8,
  )

  /**
   * Auto speed mode, whose Slow lane covers weekday and weekend evenings; at [SampleData.NOW], a
   * Thursday afternoon, every device runs at full speed.
   */
  val Speed: SpeedSettings = SpeedSettings(
    mode = SpeedLimitMode.Auto,
    standard = SpeedLimit.Unlimited,
    rules = listOf(
      SpeedRule(
        days = setOf(
          Weekday.Monday,
          Weekday.Tuesday,
          Weekday.Wednesday,
          Weekday.Thursday,
          Weekday.Friday,
        ),
        start = "19:00",
        end = "23:00",
      ),
      SpeedRule(days = setOf(Weekday.Saturday, Weekday.Sunday), start = "10:00", end = "22:00"),
    ),
  )

  /** The downloads of the device the app runs on, in every lively state. */
  fun phoneTasks(dir: String): List<ListTestTask> = listOf(
    downloading(
      id = MAPS_ID,
      url = "https://maps.example.org/offline/$MAPS",
      total = 4_831_838_208,
      speed = MAPS_LANE_RATES.sum(),
      // Far enough from done that no lane finishes while the lane rates settle.
      lanes = listOf(1.0, 0.86, 0.8, 0.74, 0.69, 0.6, 0.51, 0.38),
      ago = 7.minutes,
      dir = dir,
    ),
    downloading(
      id = "course",
      url = "https://learn.example.com/courses/language-course-spanish.zip",
      total = 2_254_857_830,
      speed = 6_200_000,
      lanes = listOf(0.71, 0.56, 0.47, 0.34),
      ago = 18.minutes,
      priority = DownloadPriority.HIGH,
      dir = dir,
    ),
    task(
      id = "archive",
      url = "ftp://ftp.example.org/pub/archive/city-photos-2026.tar",
      state = DownloadState.Downloading(
        DownloadProgress((1_717_986_918 * 0.33).toLong(), 1_717_986_918, 2_600_000),
      ),
      ago = 12.minutes,
      speedLimit = SpeedLimit.mbps(5),
      dir = dir,
    ),
    task(
      id = "podcasts",
      url = "https://media.example.com/podcasts/podcast-archive-2025.zip",
      state = DownloadState.Queued,
      ago = 9.minutes,
      dir = dir,
      queuePosition = 1,
    ),
    task(
      id = "audiobook",
      url = "https://books.example.net/audio/audiobook-the-long-voyage.m4b",
      state = DownloadState.Queued,
      ago = 4.minutes,
      dir = dir,
      queuePosition = 2,
    ),
    task(
      id = "lectures",
      url = "https://learn.example.com/video/lecture-series-season-2.zip",
      state = DownloadState.Scheduled(DownloadSchedule.AtTime(TONIGHT)),
      ago = 20.minutes,
      dir = dir,
    ),
    task(
      id = "raw",
      url = "https://photos.example.net/samples/camera-raw-samples.zip",
      state = DownloadState.Paused(DownloadProgress(497_025_024, 1_342_177_280)),
      ago = 1.days + 3.hours,
      segments = lanes(1_342_177_280, listOf(0.62, 0.41, 0.3, 0.15)),
      dir = dir,
    ),
    completed(
      id = "pass",
      url = "https://travel.example.com/trips/boarding-pass.pdf",
      total = 184_320,
      time = 1.seconds,
      ago = 35.minutes,
      dir = dir,
    ),
    completed(
      id = "episode",
      url = "https://media.example.com/episodes/podcast-episode-143.mp3",
      total = 86 * MIB,
      time = 9.seconds,
      ago = 2.hours,
      dir = dir,
    ),
    completed(
      id = "recording",
      url = "https://audio.example.org/field/field-recording-forest.wav",
      total = 412 * MIB,
      time = 38.seconds,
      ago = 5.hours,
      dir = dir,
    ),
    completed(
      id = "family",
      url = "https://photos.example.net/share/family-photos-summer.zip",
      total = 1_932_735_283,
      time = 3.minutes + 12.seconds,
      ago = 1.days + 6.hours,
      dir = dir,
    ),
  )
}

/**
 * The devices, config and controller behind one store screenshot, run on [SnapshotHarness.ui]
 * with the clock at [SampleData.NOW]: the app on [device], whose embedded device downloads
 * [StoreData.phoneTasks] and is active, with Studio and the home server paired and connected.
 * Speed follows [StoreData.Speed]. Discover is set up with [StoreDiscovery] on Android and not
 * offered on Apple devices.
 */
internal class StoreEnvironment(
  device: StoreDevice,
  theme: SnapshotTheme,
) : SnapshotEnvironment {
  /** The embedded device's downloads, whose flows a scenario can move on. */
  val phoneTasks: List<ListTestTask> = StoreData.phoneTasks(device.directory)

  private val studioTasks = ShowcaseData.studioTasks()
  private val homeTasks = ShowcaseData.homeTasks()
  private val phoneConfig = StoreData.config(device.directory)

  override val data = SampleData(
    tasks = phoneTasks,
    downloadConfig = phoneConfig,
    remotes = listOf(
      ShowcaseData.StudioRemote.copy(os = "Mac OS X"),
      ShowcaseData.HomeRemote.copy(os = "Linux"),
    ),
    deviceName = DEVICE_NAME,
    ui = { it.copy(lastDeviceId = LOCAL_DEVICE_ID) },
  )

  private val phone = SizedLinks(
    SampleKetchApi(
      name = DEVICE_NAME,
      initial = phoneTasks,
      config = phoneConfig,
      os = device.os,
      directory = device.directory,
      version = StoreData.VERSION,
    ),
  )
  private val studio = SampleKetchApi(
    name = ShowcaseData.STUDIO,
    initial = studioTasks,
    config = ShowcaseData.StudioConfig,
    directory = ShowcaseData.STUDIO_DIR,
    version = StoreData.VERSION,
  )
  private val home = SampleKetchApi(
    name = "Home server",
    initial = homeTasks,
    config = DownloadConfig(defaultDirectory = ShowcaseData.HOME_DIR, maxConcurrentDownloads = 2),
    os = "Linux",
    directory = ShowcaseData.HOME_DIR,
    version = StoreData.VERSION,
  )

  private val instanceManager = InstanceManager(
    factory = InstanceFactory(
      deviceName = DEVICE_NAME,
      embeddedFactory = { phone },
      remoteFactory = { config ->
        val api = if (config.host == ShowcaseData.StudioRemote.host) studio else home
        RemoteInstance(api, config, MutableStateFlow(ConnectionState.Connected))
      },
    ),
    initialRemotes = data.remotes,
    configStore = RecordingConfigStore(
      data.config(theme, device.density.toMode()).copy(
        speed = StoreData.Speed,
        ai = if (device.apple) {
          AiSettings()
        } else {
          AiSettings(
            enabled = true,
            llm = LlmSettings(provider = LlmProvider.Anthropic, apiKey = "sk-ant-sample"),
          )
        },
      ),
    ),
    context = SnapshotHarness.ui,
  )

  private val speedScope = CoroutineScope(SupervisorJob() + SnapshotHarness.ui)
  private val speedMode = SpeedModeController(
    config = { phone.status().config },
    apply = { phone.updateConfig(it) },
    scope = speedScope,
    settings = StoreData.Speed,
    observedPeak = ObservedPeak(
      bytesPerSecond = OBSERVED_PEAK,
      atEpochMillis = (SampleData.NOW - 1.days).toEpochMilliseconds(),
    ),
    scheduler = SpeedScheduler { TimeZone.UTC },
    clock = SampleData.CLOCK,
  )

  override val controller: AppController = AppController(
    instanceManager = instanceManager,
    aiProviderFactory = if (device.apple) null else StoreDiscovery,
    context = SnapshotHarness.ui,
    listDispatcher = SnapshotHarness.ui,
    timeSource = SnapshotClock.timeSource,
    speedMode = speedMode,
    clock = SampleData.CLOCK,
  )

  /** Key of the embedded device's task [taskId]. */
  fun phoneKey(taskId: String): TaskKey = TaskKey(LOCAL_DEVICE_ID, taskId)

  /**
   * Fills the speed history with three minutes of samples of every device, then waits until
   * the embedded device's downloads are listed and every device has reported its status.
   */
  override suspend fun start() {
    // Let the store forget the tasks of the empty list it starts from.
    repeat(STARTUP_YIELDS) { yield() }
    val states = phoneTasks.associate { phoneKey(it.taskId) to it.state.value } +
      studioTasks.associate { TaskKey(ShowcaseData.STUDIO_ID, it.taskId) to it.state.value } +
      homeTasks.associate { TaskKey(ShowcaseData.HOME_ID, it.taskId) to it.state.value }
    seedSpeedHistory(controller.speedHistory, states, Random(SEED)) { second, random ->
      0.84 + 0.12 * sin(second * PI / 23) + 0.08 * random.nextDouble()
    }
    controller.taskList.rows.first { it.size == phoneTasks.size }
    instanceManager.presence.first { list -> list.all { it.disk != null } }
  }

  /**
   * Plays [seconds] of each device's live speed: once a second, as the app samples it for the
   * device cards' sparklines, its running downloads swing around their speed together, then
   * settle back on it for the last samples.
   */
  suspend fun playSpeedHistory(seconds: Int) {
    val devices = listOf(phoneTasks, studioTasks, homeTasks)
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
    speedScope.cancel()
    instanceManager.close()
  }

  private companion object {
    const val DEVICE_NAME = "Phone"
    const val OBSERVED_PEAK = 31_457_280L
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

/** [base], resolving the links of [StoreData.LinkSizes] to their own sizes. */
private class SizedLinks(private val base: KetchApi) : KetchApi by base {
  override suspend fun resolve(url: String, properties: Map<String, String>): ResolvedSource {
    val resolved = base.resolve(url, properties)
    return resolved.copy(totalBytes = StoreData.LinkSizes[url] ?: resolved.totalBytes)
  }
}

/** What the store's Discover screenshot asks for. */
internal const val STORE_DISCOVER_QUERY: String = "Offline maps of the Alps for hiking"

/**
 * Discovery of the store screenshots: whatever it is asked, it reports the steps of a search
 * for [STORE_DISCOVER_QUERY] and finds four brand-free downloads.
 */
internal object StoreDiscovery : AiDiscoveryProviderFactory {
  override fun create(settings: AiSettings): AiDiscoveryProvider? {
    if (!settings.isUsable) return null
    return object : AiDiscoveryProvider {
      override suspend fun discover(
        request: AiDiscoverRequest,
        onStep: (DiscoveryStep) -> Unit,
        approve: suspend (AiPageRequest) -> Boolean,
      ): AiDiscoverResponse {
        StoreSteps.forEach(onStep)
        return AiDiscoverResponse(
          query = request.query,
          candidates = StoreCandidates,
          title = "Offline Alps hiking maps",
        )
      }

      override suspend fun verify(): String = "OK"
    }
  }
}

private val StoreSteps = listOf(
  DiscoveryStep("Understanding", "Offline topographic maps of the Alps, for a hiking app"),
  DiscoveryStep("Searched the web", "5 results from maps.example.org"),
  DiscoveryStep("Opened maps.example.org/downloads", "Found 6 download links"),
  DiscoveryStep("Checked the links", "Matched each file's size"),
)

private val StoreCandidates = listOf(
  AiCandidate(
    url = "https://maps.example.org/offline/alps-topo-2026.zip",
    title = "Alps topographic map, 2026 edition",
    fileName = "alps-topo-2026.zip",
    fileSize = 2_147_483_648,
    sourceUrl = "https://maps.example.org/downloads",
    confidence = 0.94f,
    description = "The whole Alps with trails, huts and contour lines.",
  ),
  AiCandidate(
    url = "https://maps.example.org/offline/alps-trails-only-2026.zip",
    title = "Alps hiking trails layer",
    fileName = "alps-trails-only-2026.zip",
    fileSize = 412_090_368,
    sourceUrl = "https://maps.example.org/downloads",
    confidence = 0.81f,
    description = "Just the marked trails, to lay over any base map.",
  ),
  AiCandidate(
    url = "https://mirror.example.net/maps/alps-topo-2026.zip",
    title = "Community mirror",
    fileName = "alps-topo-2026.zip",
    fileSize = 2_147_483_648,
    sourceUrl = "https://maps.example.org/mirrors",
    confidence = 0.62f,
    description = "A mirror of the 2026 edition, closer to some regions.",
  ),
  AiCandidate(
    url = "https://maps.example.org/offline/alps-topo-2025.zip",
    title = "Alps topographic map, 2025 edition",
    fileName = "alps-topo-2025.zip",
    fileSize = 1_975_517_184,
    sourceUrl = "https://maps.example.org/archive",
    confidence = 0.38f,
    description = "Last year's edition, without the newest trails.",
  ),
)

private val TONIGHT = Instant.parse("2026-10-01T23:00:00Z")
private const val IOS_DIR = "/Documents/Downloads"
private const val ANDROID_DIR = "/storage/emulated/0/Download"
private const val MIB = 1L shl 20
