package com.linroid.ketch.app.state

import com.linroid.ketch.api.DownloadConfig
import com.linroid.ketch.api.DownloadRequest
import com.linroid.ketch.api.DownloadState
import com.linroid.ketch.api.DownloadTask
import com.linroid.ketch.api.KetchStatus
import com.linroid.ketch.api.SpeedLimit
import com.linroid.ketch.api.log.KetchLogger
import com.linroid.ketch.api.log.describeCauses
import com.linroid.ketch.app.i18n.ByteUnit
import com.linroid.ketch.app.i18n.UiText
import com.linroid.ketch.app.i18n.clockTime
import com.linroid.ketch.app.i18n.decimal
import com.linroid.ketch.app.i18n.joinText
import com.linroid.ketch.app.i18n.percentText
import com.linroid.ketch.app.i18n.sizeText
import com.linroid.ketch.app.i18n.text
import com.linroid.ketch.app.i18n.verbatim
import com.linroid.ketch.app.i18n.weekdayShortText
import com.linroid.ketch.app.instance.ServerState
import com.linroid.ketch.remote.ConnectionState
import ketch.app.shared.generated.resources.Res
import ketch.app.shared.generated.resources.date_at_time
import ketch.app.shared.generated.resources.group_all_done_at
import ketch.app.shared.generated.resources.pulse_all_quiet
import ketch.app.shared.generated.resources.pulse_all_quiet_free
import ketch.app.shared.generated.resources.pulse_attention
import ketch.app.shared.generated.resources.pulse_connecting
import ketch.app.shared.generated.resources.pulse_downloading
import ketch.app.shared.generated.resources.pulse_downloading_count
import ketch.app.shared.generated.resources.pulse_downloading_on_devices
import ketch.app.shared.generated.resources.pulse_needs_token
import ketch.app.shared.generated.resources.pulse_offline
import ketch.app.shared.generated.resources.pulse_slow_lane
import ketch.app.shared.generated.resources.pulse_slow_lane_until
import ketch.app.shared.generated.resources.pulse_tab_progress
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.transform
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

/** Which devices the Pulse bar sums up. */
sealed interface PulseScope {
  /** One device, usually the active one. */
  data class Device(val deviceId: String) : PulseScope

  /** Every device the model watches. */
  data object AllDevices : PulseScope
}

/**
 * How well the app is connected to a device.
 *
 * @property isOnline whether the device's tasks and speed are current.
 */
sealed class DeviceHealth(val isOnline: Boolean) {
  /**
   * The engine inside the app.
   *
   * @property sharingPort port other devices reach it on; `null` when it is not shared.
   */
  data class Local(val sharingPort: Int? = null) : DeviceHealth(isOnline = true)

  /** A remote device that is connected. */
  data object Live : DeviceHealth(isOnline = true)

  /** A remote device the app is connecting to. */
  data object Connecting : DeviceHealth(isOnline = false)

  /**
   * A remote device the app lost; it keeps retrying.
   *
   * @property reason why the connection failed, when known.
   */
  data class Offline(val reason: String? = null) : DeviceHealth(isOnline = false)

  /** A remote device that rejected the API token; the app stops retrying. */
  data object Unauthorized : DeviceHealth(isOnline = false)
}

/** Health of a remote device whose client reports this connection state. */
fun ConnectionState.toDeviceHealth(): DeviceHealth = when (this) {
  ConnectionState.Connected -> DeviceHealth.Live
  ConnectionState.Connecting -> DeviceHealth.Connecting
  is ConnectionState.Disconnected -> DeviceHealth.Offline(reason)
  ConnectionState.Unauthorized -> DeviceHealth.Unauthorized
}

/** Health of the embedded device while its server is in this state. */
fun ServerState.toDeviceHealth(): DeviceHealth.Local = when {
  this is ServerState.Running && !config.isLoopbackOnly -> DeviceHealth.Local(sharingPort = port)
  else -> DeviceHealth.Local()
}

/**
 * Space on the disk a device saves downloads to.
 *
 * @property usableBytes bytes downloads can still use.
 * @property totalBytes size of the disk.
 * @property directory the device's download directory.
 */
data class DiskSpace(
  val usableBytes: Long,
  val totalBytes: Long,
  val directory: String,
)

/**
 * Task counts of the status tabs; each uses the [StatusFilter] of the same name.
 */
data class PulseCounts(
  val downloading: Int = 0,
  val waiting: Int = 0,
  val paused: Int = 0,
  val done: Int = 0,
  val failed: Int = 0,
) {
  /** Number of tasks on the tab of [filter]. */
  fun count(filter: StatusFilter): Int = when (filter) {
    StatusFilter.All -> downloading + waiting + paused + done + failed
    StatusFilter.Downloading -> downloading
    StatusFilter.Waiting -> waiting
    StatusFilter.Paused -> paused
    StatusFilter.Done -> done
    StatusFilter.Failed -> failed
  }

  /** Counts of this and [other] together. */
  operator fun plus(other: PulseCounts): PulseCounts = PulseCounts(
    downloading = downloading + other.downloading,
    waiting = waiting + other.waiting,
    paused = paused + other.paused,
    done = done + other.done,
    failed = failed + other.failed,
  )

  companion object {
    /** Counts tasks in [states]. */
    fun of(states: Collection<DownloadState>): PulseCounts = PulseCounts(
      downloading = states.count(StatusFilter.Downloading::matches),
      waiting = states.count(StatusFilter.Waiting::matches),
      paused = states.count(StatusFilter.Paused::matches),
      done = states.count(StatusFilter.Done::matches),
      failed = states.count(StatusFilter.Failed::matches),
    )
  }
}

/**
 * What one device is doing.
 *
 * @property deviceId id of the device.
 * @property name name to show for it, such as "This Mac".
 * @property health how well the app is connected to it.
 * @property counts its tasks per status tab.
 * @property failures tasks that failed, not counting canceled ones.
 * @property speed total download speed in bytes per second; `0` while it is not online, since
 *   the last speeds it reported are stale.
 * @property cap its global speed limit.
 * @property downloadedBytes bytes received by its downloading tasks of known size.
 * @property sizeBytes total size of those tasks.
 * @property sizesKnown whether every downloading task has a known size.
 * @property pendingBytes bytes its downloading and waiting tasks of known size still need.
 * @property disk space where it saves downloads; `null` until it has been read.
 * @property history total speed once a second, oldest first, at most [PulseModel.HISTORY_SIZE]
 *   samples.
 */
data class DevicePulse(
  val deviceId: String,
  val name: UiText,
  val health: DeviceHealth,
  val counts: PulseCounts,
  val failures: Int,
  val speed: Long,
  val cap: SpeedLimit,
  val downloadedBytes: Long,
  val sizeBytes: Long,
  val sizesKnown: Boolean,
  val pendingBytes: Long,
  val disk: DiskSpace?,
  val history: List<Long>,
) {
  /** Bytes the downloading tasks still need, or `null` when one has no known size. */
  val remainingBytes: Long?
    get() = if (sizesKnown) (sizeBytes - downloadedBytes).coerceAtLeast(0) else null

  /** Whether the tasks still to download need more space than the disk has left. */
  val isDiskShort: Boolean
    get() = disk != null && pendingBytes > disk.usableBytes
}

/**
 * Global status shown by the Pulse bar, the tray and the window title.
 *
 * @property devices devices in scope, in the order the model was given them.
 * @property allDevices whether the scope is every device rather than one.
 * @property mode speed mode in effect for the scope.
 */
data class PulseState(
  val devices: List<DevicePulse> = emptyList(),
  val allDevices: Boolean = false,
  val mode: SpeedMode = SpeedMode.Full,
) {
  /** Total download speed of the scope, in bytes per second. */
  val totalSpeed: Long get() = devices.sumOf { it.speed }

  /** Task counts of the scope per status tab. */
  val counts: PulseCounts
    get() = devices.fold(PulseCounts()) { sum, device -> sum + device.counts }

  /** Tasks of the scope that failed, not counting canceled ones. */
  val failures: Int get() = devices.sumOf { it.failures }

  /** Lowest global speed limit in the scope; [SpeedLimit.Unlimited] when no device is capped. */
  val cap: SpeedLimit
    get() = devices.map { it.cap }.filterNot { it.isUnlimited }.minByOrNull { it.bytesPerSecond }
      ?: SpeedLimit.Unlimited

  /** Total speed of the scope once a second, oldest first; devices are summed by sample age. */
  val history: List<Long>
    get() {
      val size = devices.maxOfOrNull { it.history.size } ?: 0
      return List(size) { index ->
        devices.sumOf { device ->
          device.history.getOrElse(index - size + device.history.size) { 0 }
        }
      }
    }

  /** Scoped device with the least free space, the one the Pulse bar reports. */
  val diskDevice: DevicePulse?
    get() = devices.filter { it.disk != null }.minByOrNull { it.disk?.usableBytes ?: 0 }

  /** Whether a scoped device lacks the space its tasks still need. */
  val isDiskShort: Boolean get() = devices.any { it.isDiskShort }

  /**
   * Share of the downloading tasks of known size on online devices received so far, or `null`
   * when none.
   */
  val progress: Float?
    get() {
      val online = online
      val size = online.sumOf { it.sizeBytes }
      return if (size > 0) online.sumOf { it.downloadedBytes }.toFloat() / size else null
    }

  /**
   * One line about what the scope is doing, such as "Downloading 3 files on 2 devices · all
   * done ≈ 14:32" or "Idle · 412 GB free on This Mac".
   *
   * Downloads come first, then a device that is not online, then failures. Clock times are
   * local to [timeZone]; ones on another day than [now] name the weekday.
   */
  fun sentence(
    now: Instant = Clock.System.now(),
    timeZone: TimeZone = TimeZone.currentSystemDefault(),
  ): UiText {
    val online = online
    val downloading = online.sumOf { it.counts.downloading }
    val offline = devices.firstOrNull { !it.health.isOnline }
    val base = when {
      downloading > 0 -> activeSentence(online, downloading, now, timeZone)
      offline != null -> offlineSentence(offline)
      failures > 0 -> Res.plurals.pulse_attention.text(failures)
      else -> {
        val disk = diskDevice
        if (disk?.disk == null) {
          Res.string.pulse_all_quiet.text()
        } else {
          Res.string.pulse_all_quiet_free.text(formatSpace(disk.disk.usableBytes), disk.name)
        }
      }
    }
    return listOfNotNull(base, slowLaneSuffix(now, timeZone)).joinText()
  }

  /** Short form for a window title, such as "3 downloading · 45%"; `null` when idle. */
  fun shortSentence(): UiText? {
    val (downloading, percent) = downloadingNow()
    if (downloading == 0) return null
    val count = Res.string.pulse_downloading_count.text(downloading)
    return if (percent == null) count else listOf(count, percentText(percent)).joinText()
  }

  /** Title of the web app's tab: "↓ 45% · Ketch" while downloads run, otherwise "Ketch". */
  fun tabTitle(): UiText {
    val (downloading, percent) = downloadingNow()
    if (downloading == 0) return AppName
    val progress = if (percent == null) {
      verbatim("↓ $downloading")
    } else {
      Res.string.pulse_tab_progress.text(percentText(percent))
    }
    return listOf(progress, AppName).joinText()
  }

  private val online: List<DevicePulse> get() = devices.filter { it.health.isOnline }

  // Tasks downloading on online devices, and the percent of their known size received; the
  // percent is null when no size is known.
  private fun downloadingNow(): Pair<Int, Int?> {
    val online = online
    val size = online.sumOf { it.sizeBytes }
    val percent = if (size > 0) (online.sumOf { it.downloadedBytes } * 100 / size).toInt() else null
    return online.sumOf { it.counts.downloading } to percent
  }

  private fun activeSentence(
    online: List<DevicePulse>,
    downloading: Int,
    now: Instant,
    timeZone: TimeZone,
  ): UiText {
    val busy = online.filter { it.counts.downloading > 0 }
    val files = if (busy.size > 1) {
      Res.plurals.pulse_downloading_on_devices.text(downloading, downloading, busy.size)
    } else {
      Res.plurals.pulse_downloading.text(downloading)
    }
    val finish = finishTime(busy, now) ?: return files
    return listOf(files, Res.string.group_all_done_at.text(clockLabel(finish, now, timeZone)))
      .joinText()
  }

  // Each device's remaining bytes over the speed it can reach under its cap. Bandwidth never
  // moves between devices, so the scope is done when its slowest device is.
  private fun finishTime(busy: List<DevicePulse>, now: Instant): Instant? {
    var latest = Duration.ZERO
    for (device in busy) {
      val remaining = device.remainingBytes ?: return null
      val speed = if (device.cap.isUnlimited) {
        device.speed
      } else {
        minOf(device.speed, device.cap.bytesPerSecond)
      }
      if (speed <= 0) return null
      latest = maxOf(latest, (remaining / speed).seconds)
    }
    if (latest > MAX_ETA) return null
    return now + latest
  }

  private fun offlineSentence(device: DevicePulse): UiText = when (device.health) {
    DeviceHealth.Connecting -> Res.string.pulse_connecting.text(device.name)
    DeviceHealth.Unauthorized -> Res.string.pulse_needs_token.text(device.name)
    else -> Res.string.pulse_offline.text(device.name)
  }

  /** "Slow lane" or "Slow lane until 18:00" while it caps the speed; `null` otherwise. */
  private fun slowLaneSuffix(now: Instant, timeZone: TimeZone): UiText? = when (mode) {
    SpeedMode.Full -> null
    SpeedMode.SlowLane -> Res.string.pulse_slow_lane.text()
    is SpeedMode.Auto -> when {
      !mode.slowLane -> null
      mode.until == null -> Res.string.pulse_slow_lane.text()
      else -> Res.string.pulse_slow_lane_until.text(clockLabel(mode.until, now, timeZone))
    }
  }

  private companion object {
    val MAX_ETA = 7.days

    // The product's name, which every language writes the same.
    val AppName = verbatim("Ketch")
  }
}

/**
 * A device [PulseModel] watches.
 *
 * @property deviceId id of the device.
 * @property name name to show for it.
 * @property tasks its tasks.
 * @property config its current download config, for the speed limit; `null` while it loads.
 * @property status reads its status, for the free space.
 * @property health how well the app is connected to it.
 */
class PulseSource(
  val deviceId: String,
  val name: UiText,
  val tasks: Flow<List<DownloadTask>>,
  val config: Flow<DownloadConfig?>,
  val status: suspend () -> KetchStatus,
  val health: Flow<DeviceHealth> = flowOf(DeviceHealth.Local()),
)

/**
 * Computes [PulseState] from the devices it is given.
 *
 * Each device's task states are summed up at most every [UPDATE_INTERVAL]; health and config
 * changes show at once. Its total speed is sampled once a second while anything downloads,
 * keeping [HISTORY_SIZE] samples, and its free space is read every [DISK_POLL_INTERVAL] while it
 * is online and after each completion. Devices out of [PulseState]'s scope are still watched
 * and listed in [devices], so switching scope keeps their history.
 *
 * @param sources devices to watch. Each source's flows must emit a first value promptly, since
 *   the state waits for every device.
 * @param pulseScope devices the state sums up.
 * @param mode speed mode in effect for the scope.
 * @param scope runs the model until it is cancelled.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PulseModel(
  sources: Flow<List<PulseSource>>,
  pulseScope: Flow<PulseScope>,
  mode: Flow<SpeedMode> = flowOf(SpeedMode.Full),
  scope: CoroutineScope,
) {
  private val log = KetchLogger("PulseModel")
  private val disks = MutableStateFlow<Map<String, DiskSpace>>(emptyMap())
  private val history = MutableStateFlow<Map<String, List<Long>>>(emptyMap())

  private val live: StateFlow<List<DeviceLive>> = sources
    .flatMapLatest { list ->
      if (list.isEmpty()) flowOf(emptyList()) else combine(list.map(::liveFlow)) { it.toList() }
    }
    .stateIn(scope, SharingStarted.Eagerly, emptyList())

  /**
   * Every device the model watches, whatever the scope, in the order it was given them; for
   * example to feed a device's speed to its [SpeedModeController].
   */
  val devices: StateFlow<List<DevicePulse>> =
    combine(live, disks, history) { devices, disks, history ->
      devices.map { it.toPulse(disks[it.deviceId], history[it.deviceId].orEmpty()) }
    }.stateIn(scope, SharingStarted.Eagerly, emptyList())

  /** Status of the devices in scope. */
  val state: StateFlow<PulseState> =
    combine(devices, pulseScope, mode) { devices, focus, speedMode ->
      PulseState(
        devices = when (focus) {
          PulseScope.AllDevices -> devices
          is PulseScope.Device -> devices.filter { it.deviceId == focus.deviceId }
        },
        allDevices = focus == PulseScope.AllDevices,
        mode = speedMode,
      )
    }.stateIn(scope, SharingStarted.Eagerly, PulseState())

  init {
    scope.launch {
      sources.collectLatest { list ->
        val ids = list.map { it.deviceId }.toSet()
        disks.update { it.filterKeys(ids::contains) }
        coroutineScope { list.forEach { launch { pollDisk(it) } } }
      }
    }
    scope.launch { recordHistory() }
  }

  private fun liveFlow(source: PulseSource): Flow<DeviceLive> = combine(
    source.tasks
      .flatMapLatest(::taskStates)
      .throttleLatest(UPDATE_INTERVAL)
      .map(::summarize),
    source.health,
    source.config.map { it?.speedLimit ?: SpeedLimit.Unlimited }.distinctUntilChanged()
  ) { totals, health, cap ->
    DeviceLive(source.deviceId, source.name, health, cap, totals)
  }

  private fun taskStates(tasks: List<DownloadTask>): Flow<List<TaskSample>> {
    if (tasks.isEmpty()) return flowOf(emptyList())
    return combine(tasks.map { task -> task.state.map { TaskSample(task.request, it) } }) {
      it.toList()
    }
  }

  private fun summarize(samples: List<TaskSample>): TaskTotals {
    var failures = 0
    var speed = 0L
    var downloaded = 0L
    var size = 0L
    var sizesKnown = true
    var pending = 0L
    for ((request, state) in samples) {
      when {
        state is DownloadState.Downloading -> {
          val progress = state.progress
          speed += progress.bytesPerSecond
          if (progress.totalBytes > 0) {
            downloaded += progress.downloadedBytes
            size += progress.totalBytes
            pending += (progress.totalBytes - progress.downloadedBytes).coerceAtLeast(0)
          } else {
            sizesKnown = false
          }
        }
        state.waitsInQueue || state is DownloadState.Scheduled -> {
          pending += request.resolvedSource?.totalBytes?.coerceAtLeast(0) ?: 0
        }
        state is DownloadState.Failed -> failures++
      }
    }
    return TaskTotals(
      counts = PulseCounts.of(samples.map { it.state }),
      failures = failures,
      speed = speed,
      downloadedBytes = downloaded,
      sizeBytes = size,
      sizesKnown = sizesKnown,
      pendingBytes = pending,
    )
  }

  // Reads the free space when the device comes online, after each completion, and every
  // DISK_POLL_INTERVAL; a failed read keeps the last known value. A disk of size 0 means the
  // platform could not read it, for example because the download folder does not exist.
  private suspend fun pollDisk(source: PulseSource) {
    val completions = live
      .mapNotNull { devices -> devices.find { it.deviceId == source.deviceId } }
      .map { it.totals.counts.done }
      .distinctUntilChanged()
    source.health.map { it.isOnline }.distinctUntilChanged().collectLatest { online ->
      if (!online) return@collectLatest
      merge(completions.map {}, ticks(DISK_POLL_INTERVAL)).conflate().collect {
        try {
          val system = source.status().system
          if (system.totalSpace > 0) {
            val disk = DiskSpace(system.usableSpace, system.totalSpace, system.downloadDirectory)
            disks.update { it + (source.deviceId to disk) }
          } else {
            disks.update { it - source.deviceId }
          }
        } catch (e: CancellationException) {
          throw e
        } catch (e: Exception) {
          log.d { "Couldn't read free space of deviceId=${source.deviceId}: ${e.describeCauses()}" }
        }
      }
    }
  }

  // Samples every device's speed once a second, and sleeps while every sample is zero.
  private suspend fun recordHistory() {
    while (true) {
      if (history.value.values.all { samples -> samples.all { it == 0L } }) {
        live.first { devices -> devices.any { it.speed > 0 } }
      }
      delay(HISTORY_INTERVAL)
      val speeds = live.value.associate { it.deviceId to it.speed }
      history.update { previous ->
        speeds.mapValues { (deviceId, speed) ->
          (previous[deviceId].orEmpty() + speed).takeLast(HISTORY_SIZE)
        }
      }
    }
  }

  private fun ticks(period: Duration): Flow<Unit> = flow {
    while (true) {
      delay(period)
      emit(Unit)
    }
  }

  private data class TaskSample(
    val request: DownloadRequest,
    val state: DownloadState,
  )

  private data class TaskTotals(
    val counts: PulseCounts,
    val failures: Int,
    val speed: Long,
    val downloadedBytes: Long,
    val sizeBytes: Long,
    val sizesKnown: Boolean,
    val pendingBytes: Long,
  )

  private class DeviceLive(
    val deviceId: String,
    val name: UiText,
    val health: DeviceHealth,
    val cap: SpeedLimit,
    val totals: TaskTotals,
  ) {
    val speed: Long get() = if (health.isOnline) totals.speed else 0

    fun toPulse(disk: DiskSpace?, history: List<Long>): DevicePulse = DevicePulse(
      deviceId = deviceId,
      name = name,
      health = health,
      counts = totals.counts,
      failures = totals.failures,
      speed = speed,
      cap = cap,
      downloadedBytes = totals.downloadedBytes,
      sizeBytes = totals.sizeBytes,
      sizesKnown = totals.sizesKnown,
      pendingBytes = totals.pendingBytes,
      disk = disk,
      history = history,
    )
  }

  companion object {
    /** Samples of speed history kept per device, one a second. */
    const val HISTORY_SIZE: Int = 60

    /** Shortest time between two updates of a device. */
    val UPDATE_INTERVAL: Duration = 250.milliseconds

    /** How often free space is read. */
    val DISK_POLL_INTERVAL: Duration = 30.seconds

    private val HISTORY_INTERVAL = 1.seconds
  }
}

// Emits the first value at once, then the latest value at most once per period.
private fun <T> Flow<T>.throttleLatest(period: Duration): Flow<T> = conflate().transform {
  emit(it)
  delay(period)
}

/** Disk space as "412 GB", "3.1 GB" or "1.8 TB": whole gigabytes from 10 GB, else one decimal. */
internal fun formatSpace(bytes: Long): UiText = when {
  bytes >= ByteUnit.TB.bytes -> ByteUnit.TB.text(decimal(bytes.toDouble() / ByteUnit.TB.bytes, 1))
  bytes >= 10 * ByteUnit.GB.bytes ->
    ByteUnit.GB.text(decimal(bytes.toDouble() / ByteUnit.GB.bytes, 0))
  bytes >= ByteUnit.GB.bytes -> ByteUnit.GB.text(decimal(bytes.toDouble() / ByteUnit.GB.bytes, 1))
  else -> sizeText(bytes)
}

/** "14:32" on the day of [now], "Tue 09:00" on another day; rounded to the nearest minute. */
internal fun clockLabel(instant: Instant, now: Instant, timeZone: TimeZone): UiText {
  val time = (instant + 30.seconds).toLocalDateTime(timeZone)
  if (time.date == now.toLocalDateTime(timeZone).date) return verbatim(clockTime(time))
  return Res.string.date_at_time.text(weekdayShortText(time.dayOfWeek), clockTime(time))
}
