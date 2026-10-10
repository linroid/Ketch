package com.linroid.ketch.app.instance

import com.linroid.ketch.api.DownloadTask
import com.linroid.ketch.api.KetchApi
import com.linroid.ketch.api.KetchFeatures
import com.linroid.ketch.api.KetchStatus
import com.linroid.ketch.api.SpeedLimit
import com.linroid.ketch.app.i18n.UiText
import com.linroid.ketch.app.state.DeviceHealth
import com.linroid.ketch.app.state.DevicePulse
import com.linroid.ketch.app.state.DiskSpace
import com.linroid.ketch.app.state.PulseCounts
import com.linroid.ketch.app.state.PulseModel
import com.linroid.ketch.app.state.PulseScope
import com.linroid.ketch.app.state.PulseSource
import com.linroid.ketch.app.state.SpeedMode
import com.linroid.ketch.app.state.deviceId
import com.linroid.ketch.app.state.toDeviceHealth
import com.linroid.ketch.remote.ConnectionState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

/**
 * What one configured device is doing, for its sidebar row, the device switcher, the Devices
 * page and the tray. [InstanceManager.presence] lists one per device.
 *
 * @property entry the device; [api] reaches it.
 * @property name name to show, such as "This Mac" or "NAS-Basement" (see [displayName]).
 * @property detail secondary text: the embedded device's host name, else `host:port`.
 * @property health how well the app is connected to it; [DeviceHealth.Offline] also while it is
 *   not [connected] on purpose, which callers tell apart to show "Not connected".
 * @property connected whether the app keeps a connection to it now; `false` for a remote device
 *   it does not watch, or whose connection it closed in the background. Always `true` for the
 *   embedded device.
 * @property watched whether the app stays connected to it while another device shows
 *   ([com.linroid.ketch.config.RemoteConfig.watch]); always `true` for the embedded device.
 * @property status what it last reported about itself, for its version, uptime, system and
 *   download folder; `null` until it has been read.
 * @property statusAt when [status] was read.
 * @property lastSeen when it was last online, while it is not; `null` while it is online, and
 *   when it has not been online since the app started.
 * @property speed total download speed in bytes per second; `0` while it is not online.
 * @property counts its tasks per status tab; while a remote device is not connected, as of when
 *   it last was.
 * @property failures its failed tasks, not counting canceled ones.
 * @property unseenFailures failures that arrived while it was not shown, or that it had when the
 *   app first reached it; they count as seen once it shows with the app in front.
 * @property cap its global speed limit as of [status]; unlimited until it has been read.
 * @property disk space where it saves downloads; `null` until it has been read.
 * @property speedMode its speed mode; [SpeedMode.Full] for remote devices, whose mode the app
 *   cannot read yet.
 * @property history its total speed once a second, oldest first, at most
 *   [PulseModel.HISTORY_SIZE] samples.
 */
data class DevicePresence(
  val entry: InstanceEntry,
  val name: UiText,
  val detail: String,
  val health: DeviceHealth,
  val connected: Boolean,
  val watched: Boolean,
  val status: KetchStatus?,
  val statusAt: Instant?,
  val lastSeen: Instant?,
  val speed: Long,
  val counts: PulseCounts,
  val failures: Int,
  val unseenFailures: Int,
  val cap: SpeedLimit,
  val disk: DiskSpace?,
  val speedMode: SpeedMode,
  val history: List<Long>,
) {
  /** Id of the device, as in its [com.linroid.ketch.app.state.TaskKey]s. */
  val deviceId: String get() = entry.deviceId

  /** The device's engine, or the client that reaches it. */
  val api: KetchApi get() = entry.instance

  /** Ketch version the device runs, once [status] has been read. */
  val version: String? get() = status?.version

  /** How long the device has been running at [now], counted on from [status]. */
  fun uptimeAt(now: Instant): Duration? {
    val reading = status ?: return null
    val since = statusAt ?: return reading.uptime.seconds
    return reading.uptime.seconds + (now - since).coerceAtLeast(Duration.ZERO)
  }
}

/**
 * Keeps a [DevicePresence] for each of [devices]: their task totals, speed history and free
 * space come from a [PulseModel] over every device, which reads each online device's status
 * every [PulseModel.DISK_POLL_INTERVAL] and after each completion; the rest of the status is
 * kept from those reads. A remote device that is not connected keeps the tasks it listed when
 * it last was, since a fresh client lists none until it connects.
 *
 * @param connected ids of the remote devices whose client is connected or connecting.
 * @param serverState state of the embedded device's server, for its health.
 * @param shown devices the app shows; their failures count as seen while [inForeground].
 * @param localMode speed mode of the embedded device.
 */
@OptIn(ExperimentalCoroutinesApi::class)
internal class DevicePresenceModel(
  devices: StateFlow<List<InstanceEntry>>,
  connected: Flow<Set<String>>,
  private val serverState: Flow<ServerState>,
  shown: Flow<DeviceScope>,
  inForeground: Flow<Boolean>,
  localMode: Flow<SpeedMode>,
  scope: CoroutineScope,
  private val clock: Clock,
) {
  private val statuses = MutableStateFlow<Map<String, StatusReading>>(emptyMap())
  private val unseen = MutableStateFlow<Map<String, Int>>(emptyMap())
  private val lastSeen = MutableStateFlow<Map<String, Instant>>(emptyMap())
  private val lastTasks = MutableStateFlow<Map<String, List<DownloadTask>>>(emptyMap())

  private val pulse = PulseModel(
    sources = devices.map { entries -> entries.map(::sourceOf) },
    pulseScope = flowOf(PulseScope.AllDevices),
    scope = scope,
  )

  /** One presence per device of the list, in its order, once its first totals are in. */
  val presence: StateFlow<List<DevicePresence>> = combine(
    devices,
    pulse.devices,
    combine(statuses, unseen, lastSeen, ::Readings),
    connected,
    localMode,
  ) { entries, pulses, readings, connectedIds, mode ->
    val pulseOf = pulses.associateBy { it.deviceId }
    entries.mapNotNull { entry ->
      pulseOf[entry.deviceId]?.let { presenceOf(entry, it, readings, connectedIds, mode) }
    }
  }.stateIn(scope, SharingStarted.Eagerly, emptyList())

  init {
    scope.launch {
      devices.collect { entries ->
        val ids = entries.map { it.deviceId }.toSet()
        statuses.update { it.filterKeys(ids::contains) }
        lastTasks.update { it.filterKeys(ids::contains) }
      }
    }
    scope.launch { trackUnseen(shown, inForeground) }
    scope.launch { trackLastSeen() }
  }

  private fun sourceOf(entry: InstanceEntry): PulseSource {
    val deviceId = entry.deviceId
    return PulseSource(
      deviceId = deviceId,
      name = entry.displayName,
      tasks = tasksOf(entry),
      config = statuses.map { it[deviceId]?.status?.config }.distinctUntilChanged(),
      status = {
        entry.instance.status().also { status ->
          statuses.update { it + (deviceId to StatusReading(status, clock.now())) }
        }
      },
      health = when (entry) {
        is RemoteInstance -> entry.connectionState.map { it.toDeviceHealth() }
        else -> serverState.map { it.toDeviceHealth() }
      },
      // The embedded engine is this build of Ketch, which supports everything it lists.
      features = when (entry) {
        is RemoteInstance -> statuses.map { it[deviceId]?.status?.features.orEmpty() }
          .distinctUntilChanged()
        else -> flowOf(KetchFeatures.ALL)
      },
    )
  }

  // Otherwise the fresh client of a device that connects again would list no tasks until it
  // loads them, and the failures among them would look new.
  private fun tasksOf(entry: InstanceEntry): Flow<List<DownloadTask>> {
    if (entry !is RemoteInstance) return entry.instance.tasks
    val deviceId = entry.deviceId
    return entry.connectionState
      .map { it == ConnectionState.Connected }
      .distinctUntilChanged()
      .flatMapLatest { connected ->
        if (connected) {
          entry.instance.tasks.onEach { tasks -> lastTasks.update { it + (deviceId to tasks) } }
        } else {
          flowOf(lastTasks.value[deviceId] ?: entry.instance.tasks.value)
        }
      }
  }

  private fun presenceOf(
    entry: InstanceEntry,
    pulse: DevicePulse,
    readings: Readings,
    connectedIds: Set<String>,
    localMode: SpeedMode,
  ): DevicePresence {
    val reading = readings.statuses[pulse.deviceId]
    return DevicePresence(
      entry = entry,
      name = entry.displayName,
      detail = entry.detail,
      health = pulse.health,
      connected = entry !is RemoteInstance || pulse.deviceId in connectedIds,
      watched = (entry as? RemoteInstance)?.remoteConfig?.watch ?: true,
      status = reading?.status,
      statusAt = reading?.at,
      lastSeen = readings.lastSeen[pulse.deviceId],
      speed = pulse.speed,
      counts = pulse.counts,
      failures = pulse.failures,
      unseenFailures = readings.unseen[pulse.deviceId] ?: 0,
      cap = pulse.cap,
      disk = pulse.disk,
      speedMode = if (entry is EmbeddedInstance) localMode else SpeedMode.Full,
      history = pulse.history,
    )
  }

  // A device's first online failures are unseen unless it shows; more failures add to them while
  // it does not, and every one counts as seen while it shows with the app in front. An offline
  // device keeps its count.
  private suspend fun trackUnseen(shown: Flow<DeviceScope>, inForeground: Flow<Boolean>) {
    val known = HashMap<String, Failures>()
    combine(pulse.devices, shown, inForeground, ::Triple).collect { (devices, scope, front) ->
      for (device in devices) {
        val previous = known[device.deviceId]
        val next = when {
          !device.health.isOnline -> previous ?: continue
          previous == null -> Failures(device.failures, unseen = device.failures)
          else -> {
            val more = (device.failures - previous.failures).coerceAtLeast(0)
            Failures(device.failures, minOf(previous.unseen + more, device.failures))
          }
        }
        val seen = front && scope.includes(device.deviceId)
        known[device.deviceId] = if (seen) next.copy(unseen = 0) else next
      }
      known.keys.retainAll(devices.map { it.deviceId }.toSet())
      unseen.value = known.mapValues { it.value.unseen }
    }
  }

  private suspend fun trackLastSeen() {
    var wasOnline = emptyMap<String, Boolean>()
    pulse.devices
      .map { devices -> devices.associate { it.deviceId to it.health.isOnline } }
      .distinctUntilChanged()
      .collect { online ->
        val now = clock.now()
        lastSeen.update { previous ->
          buildMap {
            for ((deviceId, isOnline) in online) {
              when {
                isOnline -> Unit
                wasOnline[deviceId] == true -> put(deviceId, now)
                else -> previous[deviceId]?.let { put(deviceId, it) }
              }
            }
          }
        }
        wasOnline = online
      }
  }

  private class StatusReading(val status: KetchStatus, val at: Instant)

  private class Readings(
    val statuses: Map<String, StatusReading>,
    val unseen: Map<String, Int>,
    val lastSeen: Map<String, Instant>,
  )

  private data class Failures(val failures: Int, val unseen: Int)
}
