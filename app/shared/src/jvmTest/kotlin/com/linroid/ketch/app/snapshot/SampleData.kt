package com.linroid.ketch.app.snapshot

import com.linroid.ketch.api.Destination
import com.linroid.ketch.api.DownloadConfig
import com.linroid.ketch.api.DownloadPriority
import com.linroid.ketch.api.DownloadProgress
import com.linroid.ketch.api.DownloadRequest
import com.linroid.ketch.api.DownloadSchedule
import com.linroid.ketch.api.DownloadState
import com.linroid.ketch.api.DownloadTask
import com.linroid.ketch.api.KetchApi
import com.linroid.ketch.api.KetchError
import com.linroid.ketch.api.KetchFeatures
import com.linroid.ketch.api.KetchStatus
import com.linroid.ketch.api.NetworkInterfaceConfig
import com.linroid.ketch.api.NetworkInterfaceInfo
import com.linroid.ketch.api.NetworkInterfaces
import com.linroid.ketch.api.ResolvedSource
import com.linroid.ketch.api.Segment
import com.linroid.ketch.api.SpeedLimit
import com.linroid.ketch.api.SystemInfo
import com.linroid.ketch.app.RecordingConfigStore
import com.linroid.ketch.app.i18n.UiText
import com.linroid.ketch.app.i18n.verbatim
import com.linroid.ketch.app.instance.InstanceFactory
import com.linroid.ketch.app.instance.InstanceManager
import com.linroid.ketch.app.instance.LocalServerHandle
import com.linroid.ketch.app.instance.PairingRequests
import com.linroid.ketch.app.instance.RemoteInstance
import com.linroid.ketch.app.platform.FileActions
import com.linroid.ketch.app.platform.SystemClipboard
import com.linroid.ketch.app.state.AiDiscoverRequest
import com.linroid.ketch.app.state.AiDiscoverResponse
import com.linroid.ketch.app.state.AiDiscoveryProvider
import com.linroid.ketch.app.state.AiDiscoveryProviderFactory
import com.linroid.ketch.app.state.AppController
import com.linroid.ketch.app.state.DiscoveryStep
import com.linroid.ketch.app.state.LOCAL_DEVICE_ID
import com.linroid.ketch.app.state.ListTestTask
import com.linroid.ketch.app.state.SpeedHistoryStore
import com.linroid.ketch.app.state.SpeedModeController
import com.linroid.ketch.app.state.TaskKey
import com.linroid.ketch.app.util.displayName
import com.linroid.ketch.app.util.extractFilename
import com.linroid.ketch.config.AiSettings
import com.linroid.ketch.config.AppearanceConfig
import com.linroid.ketch.config.ClipboardMode
import com.linroid.ketch.config.DensityMode
import com.linroid.ketch.config.KetchConfig
import com.linroid.ketch.config.RemoteConfig
import com.linroid.ketch.config.ThemeMode
import com.linroid.ketch.config.UiPreferences
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.yield
import kotlin.math.PI
import kotlin.math.roundToLong
import kotlin.math.sin
import kotlin.random.Random
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

/**
 * The devices and downloads a snapshot shows: the embedded device, whose tasks the app lists,
 * and the remote devices next to it.
 *
 * @property tasks tasks of the embedded device; their flows can be changed to vary a scenario.
 * @property downloadConfig download settings of the embedded device, which explain why queued
 *   tasks wait.
 * @property remotes remote devices; they are never connected, so they show as offline.
 * @property deviceName name of the embedded device.
 * @property ui UI preferences on top of the snapshot's theme and density.
 */
internal class SampleData(
  val tasks: List<ListTestTask>,
  val downloadConfig: DownloadConfig = DOWNLOAD_CONFIG,
  val remotes: List<RemoteConfig> = listOf(NAS),
  val deviceName: String = DEVICE_NAME,
  val ui: (UiPreferences) -> UiPreferences = { it },
) {
  /** The task shown as [name], such as `"q3-report.pdf"`. */
  fun task(name: String): ListTestTask =
    tasks.firstOrNull { displayName(it.request, it.state.value) == name }
      ?: error("No sample task named $name in ${tasks.map { displayName(it.request) }}")

  /** Key of the task shown as [name]. */
  fun keyOf(name: String): TaskKey = TaskKey(LOCAL_DEVICE_ID, task(name).taskId)

  /** The saved config of an app showing this data in [theme] at [density]. */
  fun config(theme: SnapshotTheme, density: DensityMode): KetchConfig = KetchConfig(
    name = deviceName,
    download = downloadConfig,
    remotes = remotes,
    appearance = AppearanceConfig(
      theme = if (theme == SnapshotTheme.Dark) ThemeMode.Dark else ThemeMode.Light,
    ),
    ui = ui(
      UiPreferences(
        density = density,
        reduceMotion = true,
        // The snapshot never shows what happens to be on this machine's clipboard.
        clipboardMode = ClipboardMode.Off,
        quickAdd = false,
        onboardingVersion = 1,
      ),
    ),
  )

  companion object {
    /** The fixed time of every snapshot: Thursday 1 October 2026, 14:30 UTC. */
    val NOW: Instant = Instant.parse("2026-10-01T14:30:00Z")

    /** A clock that always reads [NOW]. */
    val CLOCK: Clock = object : Clock {
      override fun now(): Instant = NOW
    }

    const val DEVICE_NAME: String = "MacBook Pro"
    const val DOWNLOAD_DIR: String = "/Users/alex/Downloads"

    /**
     * A NAS on the local network, which ran Linux when the app last reached it; not watched, so
     * the app never connects to it.
     */
    val NAS: RemoteConfig =
      RemoteConfig(host = "nas.local", port = 8642, watch = false, os = "Linux")

    /** Three download slots, all taken by [downloads], so the queued tasks wait for one. */
    val DOWNLOAD_CONFIG: DownloadConfig = DownloadConfig(
      defaultDirectory = DOWNLOAD_DIR,
      maxConcurrentDownloads = 3,
      maxConnectionsPerDownload = 8,
      maxConnectionsPerHost = 1,
    )

    /**
     * A free fourth slot, so `imagenet-part04.tar` waits for its site instead; the engine checks
     * slots first, so one device never shows both reasons.
     */
    val PER_HOST_CONFIG: DownloadConfig = DOWNLOAD_CONFIG.copy(maxConcurrentDownloads = 4)

    /** No downloads yet. */
    fun empty(): SampleData = SampleData(tasks = emptyList())

    /**
     * Downloads in every state: three downloading (two segmented HTTP files and a torrent), two
     * queued, one scheduled for tonight, one paused, four completed, one failed and one
     * canceled.
     */
    fun downloads(downloadConfig: DownloadConfig = DOWNLOAD_CONFIG): SampleData =
      SampleData(tasks = sampleTasks(), downloadConfig = downloadConfig)
  }
}

private val TONIGHT = Instant.parse("2026-10-01T23:00:00Z")
private const val GIB = 1L shl 30
private const val MIB = 1L shl 20

private fun sampleTasks(): List<ListTestTask> = listOf(
  downloading(
    id = "ubuntu",
    url = "https://releases.ubuntu.com/24.04/ubuntu-24.04-desktop-amd64.iso",
    total = 6_114_656_256,
    speed = 18_400_000,
    lanes = listOf(1.0, 0.94, 0.86, 0.79, 0.71, 0.64, 0.52, 0.41),
    ago = 7.minutes,
  ),
  downloading(
    id = "imagenet-03",
    url = "https://image-net.org/data/train/imagenet-part03.tar",
    total = 13 * GIB,
    speed = 6_500_000,
    lanes = listOf(0.46, 0.31, 0.22, 0.09),
    ago = 42.minutes,
    priority = DownloadPriority.HIGH,
  ),
  task(
    id = "archlinux",
    url = "magnet:?xt=urn:btih:4c7f3e2b9d1a8f6e5c0b7a3d2e1f9c8b7a6d5e4f" +
      "&dn=archlinux-2026.10.01-x86_64.iso&tr=udp%3A%2F%2Ftracker.archlinux.org%3A6969",
    state = DownloadState.Downloading(
      DownloadProgress((1_288_490_188 * 0.41).toLong(), 1_288_490_188, 3_100_000),
    ),
    ago = 12.minutes,
    speedLimit = SpeedLimit.mbps(5),
  ),
  // Waiting behind the three running downloads, in the engine's order: priority, then age.
  task(
    id = "imagenet-04",
    url = "https://image-net.org/data/train/imagenet-part04.tar",
    state = DownloadState.Queued,
    ago = 41.minutes,
    queuePosition = 1,
  ),
  task(
    id = "blender",
    url = "https://download.blender.org/release/Blender4.2/blender-4.2-macos-arm64.dmg",
    state = DownloadState.Queued,
    ago = 3.minutes,
    queuePosition = 2,
  ),
  task(
    id = "llama",
    url = "https://huggingface.co/meta-llama/Llama-3.1-8B-Instruct-GGUF/resolve/main/" +
      "llama-3.1-8b-instruct-q4_k_m.gguf",
    state = DownloadState.Scheduled(DownloadSchedule.AtTime(TONIGHT)),
    ago = 20.minutes,
  ),
  task(
    id = "android-studio",
    url = "https://redirector.gvt1.com/edgedl/android/studio/install/2024.2.1.12/" +
      "android-studio-2024.2.1.12-mac_arm.dmg",
    state = DownloadState.Paused(DownloadProgress(497_025_024, 1_342_177_280)),
    ago = 1.days + 3.hours,
    segments = lanes(1_342_177_280, listOf(0.62, 0.41, 0.3, 0.15)),
  ),
  completed(
    id = "q3-report",
    url = "https://docs.northwind.example/finance/q3-report.pdf",
    total = 2_516_582,
    time = 1.4.seconds,
    ago = 2.hours,
  ),
  completed(
    id = "linux",
    url = "https://cdn.kernel.org/pub/linux/kernel/v6.x/linux-6.11.tar.xz",
    total = 145_012_736,
    time = 21.seconds,
    ago = 5.hours,
  ),
  completed(
    id = "photos",
    url = "https://photos.example.net/share/vacation-photos-2026.zip",
    total = 1_932_735_283,
    time = 3.minutes + 12.seconds,
    ago = 1.days + 6.hours,
  ),
  completed(
    id = "podcast",
    url = "https://media.example.fm/episodes/podcast-episode-142.mp3",
    total = 86 * MIB,
    time = 9.seconds,
    ago = 6.days,
  ),
  task(
    id = "weights",
    url = "https://storage.googleapis.com/research-datasets/model-weights.safetensors",
    state = DownloadState.Failed(KetchError.Http(403, "Forbidden")),
    ago = 50.minutes,
  ),
  task(
    id = "recording",
    url = "https://videos.example.org/raw/screen-recording-2026-09-28.mov",
    state = DownloadState.Canceled,
    ago = 3.days,
  ),
)

/** A task of a snapshot device, saving to [dir], added [ago] before [SampleData.NOW]. */
internal fun task(
  id: String,
  url: String,
  state: DownloadState,
  ago: Duration,
  segments: List<Segment> = emptyList(),
  connections: Int = 0,
  priority: DownloadPriority = DownloadPriority.NORMAL,
  speedLimit: SpeedLimit = SpeedLimit.Unlimited,
  dir: String = SampleData.DOWNLOAD_DIR,
  queuePosition: Int? = null,
): ListTestTask = ListTestTask(
  taskId = id,
  state = state,
  request = DownloadRequest(
    url = url,
    destination = Destination("$dir/"),
    connections = connections,
    priority = priority,
    speedLimit = speedLimit,
  ),
  createdAt = SampleData.NOW - ago,
  segments = segments,
  queuePosition = MutableStateFlow(queuePosition),
)

/** A segmented HTTP download whose segment `i` is `lanes[i]` done. */
internal fun downloading(
  id: String,
  url: String,
  total: Long,
  speed: Long,
  lanes: List<Double>,
  ago: Duration,
  priority: DownloadPriority = DownloadPriority.NORMAL,
  dir: String = SampleData.DOWNLOAD_DIR,
): ListTestTask {
  val segments = lanes(total, lanes)
  val progress = DownloadProgress(segments.sumOf { it.downloadedBytes }, total, speed)
  return task(
    id = id,
    url = url,
    state = DownloadState.Downloading(progress),
    ago = ago,
    segments = segments,
    connections = lanes.size,
    priority = priority,
    dir = dir,
  )
}

/** A download of [total] bytes that took [time], saved in [dir]. */
internal fun completed(
  id: String,
  url: String,
  total: Long,
  time: Duration,
  ago: Duration,
  dir: String = SampleData.DOWNLOAD_DIR,
) = task(
  id = id,
  url = url,
  state = DownloadState.Completed(
    outputPath = "$dir/${extractFilename(url)}",
    totalBytes = total,
    downloadTime = time,
  ),
  ago = ago,
  dir = dir,
)

/** [total] bytes split evenly into one segment per entry of [done], each that much done. */
internal fun lanes(total: Long, done: List<Double>): List<Segment> {
  val size = total / done.size
  return done.mapIndexed { index, fraction ->
    val start = index * size
    val end = if (index == done.lastIndex) total - 1 else start + size - 1
    Segment(index, start, end, ((end - start + 1) * fraction).toLong())
  }
}

/**
 * A device of the snapshots named [name], on [os] with its downloads in [directory]: its tasks,
 * starting from [initial], its settings and its disk.
 */
internal class SampleKetchApi(
  private val name: String,
  initial: List<DownloadTask>,
  private var config: DownloadConfig,
  private val os: String = "Mac OS X",
  private val directory: String = SampleData.DOWNLOAD_DIR,
  private val version: String = KetchApi.VERSION,
) : KetchApi {
  /** The embedded device of [data]. */
  constructor(data: SampleData) : this(data.deviceName, data.tasks, data.downloadConfig)

  private val taskList = MutableStateFlow(initial)

  override val backendLabel: String = name
  override val tasks: StateFlow<List<DownloadTask>> = taskList

  override suspend fun download(request: DownloadRequest): DownloadTask {
    // Nothing starts in the snapshots: the new task starts while a slot is free, else it waits
    // behind the others, as the newest task of its priority would.
    val tasks = taskList.value
    val running = tasks.count { it.state.value is DownloadState.Downloading }
    val waiting = tasks.count { it.queuePosition.value != null }
    val task = ListTestTask(
      taskId = "added-${tasks.size}",
      state = DownloadState.Queued,
      request = request,
      createdAt = SampleData.NOW,
      queuePosition = MutableStateFlow(
        (waiting + 1).takeIf { running >= config.maxConcurrentDownloads },
      ),
    )
    taskList.update { it + task }
    return task
  }

  override suspend fun resolve(url: String, properties: Map<String, String>): ResolvedSource =
    ResolvedSource(
      url = url,
      sourceType = "http",
      totalBytes = 734_003_200,
      supportsResume = true,
      suggestedFileName = extractFilename(url),
      maxSegments = 8,
    )

  override suspend fun resolveContent(content: ByteArray, fileName: String?): ResolvedSource =
    throw KetchError.Unsupported()

  override suspend fun status(): KetchStatus = KetchStatus(
    name = name,
    version = version,
    revision = KetchApi.REVISION,
    uptime = 3.days.inWholeSeconds,
    config = config,
    system = SystemInfo(
      os = os,
      arch = "aarch64",
      separator = "/",
      javaVersion = "21",
      availableProcessors = 10,
      maxMemory = 4 * GIB,
      totalMemory = GIB,
      freeMemory = 512 * MIB,
      downloadDirectory = directory,
      totalSpace = 994_662_584_320,
      freeSpace = 412_316_860_416,
      usableSpace = 412_316_860_416,
    ),
    // A device on this version, like the embedded one, whose features the app gets without asking.
    features = KetchFeatures.ALL,
  )

  override suspend fun updateConfig(config: DownloadConfig) {
    this.config = config
  }

  override suspend fun start() {}

  override fun close() {}
}

/**
 * A remote device of the snapshots that answers with [status] and lists [tasks]; it adds and
 * checks links only through [download] and [resolve].
 */
internal class SampleDeviceApi(
  private var status: KetchStatus,
  tasks: List<DownloadTask>,
  private val networks: NetworkInterfaces = NetworkInterfaces(),
  private val download: ((DownloadRequest) -> DownloadTask)? = null,
  private val resolve: (suspend (String) -> ResolvedSource)? = null,
) : KetchApi {
  override val backendLabel: String = status.name
  override val tasks: StateFlow<List<DownloadTask>> = MutableStateFlow(tasks)

  override suspend fun status(): KetchStatus = status

  override suspend fun updateConfig(config: DownloadConfig) {
    status = status.copy(config = config)
  }

  override suspend fun networkInterfaces(): NetworkInterfaces = networks

  override suspend fun download(request: DownloadRequest): DownloadTask =
    download?.invoke(request) ?: throw UnsupportedOperationException("Not in snapshots")

  override suspend fun resolve(url: String, properties: Map<String, String>): ResolvedSource =
    resolve?.invoke(url) ?: throw UnsupportedOperationException("Not in snapshots")

  override suspend fun start() {}

  override fun close() {}
}

/** What a remote device of the snapshots named [name] reports, up for [uptime]. */
internal fun sampleStatus(
  name: String,
  uptime: Duration,
  config: DownloadConfig,
  system: SystemInfo,
): KetchStatus = KetchStatus(
  name = name,
  version = KetchApi.VERSION,
  revision = KetchApi.REVISION,
  uptime = uptime.inWholeSeconds,
  config = config,
  system = system,
)

/** A four-core system on [os] with [usable] of [total] bytes free in [directory]. */
internal fun sampleSystem(
  os: String,
  directory: String,
  total: Long,
  usable: Long,
  arch: String = "amd64",
  separator: String = "/",
  maxMemory: Long = 0,
  totalMemory: Long = 0,
  freeMemory: Long = 0,
): SystemInfo = SystemInfo(
  os = os,
  arch = arch,
  separator = separator,
  javaVersion = "21",
  availableProcessors = 4,
  maxMemory = maxMemory,
  totalMemory = totalMemory,
  freeMemory = freeMemory,
  downloadDirectory = directory,
  totalSpace = total,
  freeSpace = usable,
  usableSpace = usable,
)

/**
 * The devices, config and controller behind one app snapshot, run on [SnapshotHarness.ui] with
 * the clock at [SampleData.NOW].
 *
 * @param aiProviderFactory AI discovery of this platform; `null` where it is not supported.
 * @param embedded the engine of the embedded device, over [data].
 * @param remote the client of a remote device; `null` leaves the app's own, which the
 *   unwatched sample remotes never connect.
 * @param config the saved config, from the one [data] gives.
 * @param speedMode the speed mode of the embedded device, running in the given scope.
 * @param seedHistory whether [start] fills the speed history before the rows are listed.
 * @param wave how far below or above its speed each second of the seeded history is.
 */
internal class SampleEnvironment(
  override val data: SampleData,
  theme: SnapshotTheme,
  density: DensityMode,
  aiProviderFactory: AiDiscoveryProviderFactory? = null,
  embedded: (SampleData) -> KetchApi = ::SampleKetchApi,
  remote: ((RemoteConfig) -> RemoteInstance)? = null,
  config: (KetchConfig) -> KetchConfig = { it },
  speedMode: ((KetchApi, CoroutineScope) -> SpeedModeController)? = null,
  private val seedHistory: Boolean = true,
  private val wave: (second: Int, random: Random) -> Double = SampleWave,
) : SnapshotEnvironment {
  private val speedScope = CoroutineScope(SupervisorJob() + SnapshotHarness.ui)
  private val instanceManager = InstanceManager(
    factory = if (remote == null) {
      InstanceFactory(deviceName = data.deviceName, embeddedFactory = { embedded(data) })
    } else {
      InstanceFactory(
        deviceName = data.deviceName,
        embeddedFactory = { embedded(data) },
        remoteFactory = remote,
      )
    },
    initialRemotes = data.remotes,
    configStore = RecordingConfigStore(config(data.config(theme, density))),
  )

  override val controller: AppController = AppController(
    instanceManager = instanceManager,
    aiProviderFactory = aiProviderFactory,
    context = SnapshotHarness.ui,
    speedMode = speedMode?.invoke(checkNotNull(instanceManager.embedded), speedScope),
    clock = SampleData.CLOCK,
  )

  /**
   * Fills the speed history with three minutes of samples ending at [SampleData.NOW], so the
   * Activity chart has a shape, then waits until the task list has a row for every sample task;
   * without [seedHistory], only waits for the rows.
   *
   * The history takes no sample older than its newest one, so this runs before the store's own
   * sampling starts, which waits for the first rows.
   */
  override suspend fun start() {
    if (!seedHistory) {
      controller.taskList.rows.first { it.size == data.tasks.size }
      return
    }
    // Let the store forget the tasks of the empty list it starts from.
    repeat(STARTUP_YIELDS) { yield() }
    val states = data.tasks.associate { TaskKey(LOCAL_DEVICE_ID, it.taskId) to it.state.value }
    seedSpeedHistory(controller.speedHistory, states, Random(SEED), wave)
    controller.taskList.rows.first { it.size == data.tasks.size }
    val downloading = data.tasks.filter { it.state.value is DownloadState.Downloading }
    check(downloading.all { history(it).size > HISTORY_SECONDS }) {
      "The speed history lost its seeded samples"
    }
  }

  private fun history(task: ListTestTask) =
    checkNotNull(controller.speedHistory.history(TaskKey(LOCAL_DEVICE_ID, task.taskId)))

  override fun close() {
    controller.close()
    speedScope.cancel()
    instanceManager.instances.value.filterIsInstance<RemoteInstance>()
      .forEach { it.instance.close() }
    instanceManager.close()
  }

  private companion object {
    const val SEED = 42
    const val STARTUP_YIELDS = 3
  }
}

/** Seconds of speed history [seedSpeedHistory] records before [SampleData.NOW]. */
internal const val HISTORY_SECONDS = 180

/** A second of seeded history: 80 % of the speed, swinging 15 % with time and 10 % at random. */
internal val SampleWave: (second: Int, random: Random) -> Double = { second, random ->
  0.8 + 0.15 * sin(second * PI / 23) + 0.1 * random.nextDouble()
}

/**
 * Records in [history] a sample a second for [HISTORY_SECONDS] up to [SampleData.NOW] of each
 * downloading task in [states], [wave] of its speed, drawing from [random].
 */
internal fun seedSpeedHistory(
  history: SpeedHistoryStore,
  states: Map<TaskKey, DownloadState>,
  random: Random,
  wave: (second: Int, random: Random) -> Double = SampleWave,
) {
  for (second in HISTORY_SECONDS downTo 0) {
    val speeds = states.mapValues { (_, state) ->
      val speed = (state as? DownloadState.Downloading)?.progress?.bytesPerSecond
      speed?.let { (it * wave(second, random)).roundToLong() }
    }
    history.record(SampleData.NOW - second.seconds, speeds)
  }
}

/** A clipboard holding [text], read without a notice as on Windows and Linux; writes go nowhere. */
internal class SnapshotClipboard(private val text: String? = null) : SystemClipboard {
  override val readsSilently: Boolean = true
  override val pasteEvents: Flow<String> = emptyFlow()

  override suspend fun hasLink(): Boolean = text != null

  override suspend fun readText(): String? = text

  override suspend fun writeText(text: String) {}
}

/** Files that exist and, when [canTrash], can go to the Trash, without touching this machine. */
internal class SnapshotFiles(override val canTrash: Boolean = true) : FileActions {
  override val revealLabel: UiText = verbatim("Show in Finder")
  override val canShare: Boolean = false

  override suspend fun open(path: String) {}

  override suspend fun reveal(path: String) {}

  override suspend fun share(path: String) {}

  override suspend fun exists(path: String): Boolean = true

  override suspend fun moveToTrash(path: String) {}
}

/**
 * [sample] on Wi-Fi at [en0], Ethernet and a VPN, with downloads spread over the first two, or,
 * when it should [fail], no answer about them.
 */
internal class NetworkedApi(
  private val sample: SampleKetchApi,
  private val fail: Boolean = false,
  en0: List<String> = listOf("fe80::1c2a:3bff:fe4d:5e6f", "192.168.1.20"),
) : KetchApi by sample {
  private var networks = NetworkInterfaces(
    supported = true,
    available = listOf(
      NetworkInterfaceInfo("en0", "en0", en0),
      NetworkInterfaceInfo("en7", "en7", listOf("10.0.0.4")),
      NetworkInterfaceInfo("utun3", "utun3", listOf("100.101.7.12")),
    ),
    config = NetworkInterfaceConfig(listOf("en0", "en7")),
  )

  override suspend fun networkInterfaces(): NetworkInterfaces {
    if (fail) throw IllegalStateException("The device didn't answer.")
    return networks
  }

  override suspend fun updateNetworkInterfaces(config: NetworkInterfaceConfig): NetworkInterfaces {
    networks = networks.copy(config = config)
    return networks
  }
}

/** A clock whose time a scenario moves. */
internal class MovableClock(@Volatile var now: Instant) : Clock {
  override fun now(): Instant = now
}

/** Starts a sharing server that only pretends to listen. */
internal val PretendServer: (KetchApi, PairingRequests) -> LocalServerHandle = { _, _ ->
  object : LocalServerHandle {
    override fun stop() {}
  }
}

/** Discovery that can run but is never asked, so the Discover page and tab show. */
internal object IdleDiscovery : AiDiscoveryProviderFactory {
  override fun create(settings: AiSettings): AiDiscoveryProvider = object : AiDiscoveryProvider {
    override suspend fun discover(request: AiDiscoverRequest, onStep: (DiscoveryStep) -> Unit) =
      AiDiscoverResponse(request.query, emptyList())

    override suspend fun verify(): String = "OK"
  }
}
