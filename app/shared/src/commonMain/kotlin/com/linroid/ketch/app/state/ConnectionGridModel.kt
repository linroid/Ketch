package com.linroid.ketch.app.state

import com.linroid.ketch.api.ActiveConnection
import com.linroid.ketch.api.ActiveConnections
import com.linroid.ketch.api.KetchApi
import com.linroid.ketch.api.log.KetchLogger
import com.linroid.ketch.api.log.describeCauses
import com.linroid.ketch.app.i18n.UiText
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.retryWhen
import kotlinx.coroutines.flow.stateIn
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * A device whose live connections the grid can show.
 *
 * @property deviceId id of the device.
 * @property name name to show for it.
 * @property api where its connections are read from.
 * @property supported whether it lists [com.linroid.ketch.api.KetchFeatures.ACTIVE_CONNECTIONS].
 * @property online whether the app is connected to it; a device that is not is left out.
 */
data class ConnectionSource(
  val deviceId: String,
  val name: UiText,
  val api: KetchApi,
  val supported: Boolean,
  val online: Boolean,
)

/** Which way a connection's bytes are moving now. */
enum class TrafficDirection {
  /** Receiving only. */
  Down,

  /** Sending only. */
  Up,

  /** Receiving and sending. */
  Both,

  /** Neither, since the last sample. */
  Idle,
}

/** A connection across devices: the device's id and the connection's id there. */
data class ConnectionCellKey(val deviceId: String, val id: Long)

/**
 * One cell of the connections grid.
 *
 * @property key the connection across devices.
 * @property task the task the connection transfers for.
 * @property direction which way its bytes move.
 * @property level how busy it is, from 0 (idle) to 4; see [trafficLevel].
 * @property age how long it has been open when its device sampled it.
 * @property connection what its device reports.
 */
data class ConnectionCell(
  val key: ConnectionCellKey,
  val task: TaskKey,
  val direction: TrafficDirection,
  val level: Int,
  val age: Duration,
  val connection: ActiveConnection,
)

/**
 * The connections of one task, as the grid groups them.
 *
 * @property key the task.
 * @property name the task's name in the list; `null` when the list does not show it.
 * @property cells its connections, oldest first.
 */
data class TaskConnections(
  val key: TaskKey,
  val name: String?,
  val cells: List<ConnectionCell>,
)

/**
 * The connections of one device.
 *
 * @property deviceId id of the device.
 * @property name name to show for it.
 * @property tasks its tasks with connections, in the order the Downloads list shows them.
 */
data class DeviceConnections(
  val deviceId: String,
  val name: UiText,
  val tasks: List<TaskConnections>,
)

/**
 * The live connections of the shown devices.
 *
 * @property devices each device with connections, in the order the devices show.
 * @property total how many connections are open, including those past the limit.
 * @property downloadBps bytes per second received over every connection.
 * @property uploadBps bytes per second sent over every connection.
 * @property supported whether a shown device reports its connections at all.
 * @property unsupported names of the online devices that do not report connections.
 */
data class ConnectionGridState(
  val devices: List<DeviceConnections> = emptyList(),
  val total: Int = 0,
  val downloadBps: Long = 0,
  val uploadBps: Long = 0,
  val supported: Boolean = false,
  val unsupported: List<UiText> = emptyList(),
) {
  /** Every cell, oldest first, as the Pulse bar's strip fills them. */
  val cells: List<ConnectionCell> = devices
    .flatMap { device -> device.tasks.flatMap { it.cells } }
    .sortedWith(compareBy({ it.connection.openedAt }, { it.key.deviceId }, { it.key.id }))

  /** How many connections are open past the cells listed. */
  val overflow: Int get() = (total - cells.size).coerceAtLeast(0)

  /** Cells receiving data, alone or while sending. */
  val downloading: Int
    get() = cells.count { it.direction == TrafficDirection.Down || it.direction.isBoth }

  /** Cells sending data, alone or while receiving. */
  val uploading: Int
    get() = cells.count { it.direction == TrafficDirection.Up || it.direction.isBoth }

  private val TrafficDirection.isBoth: Boolean get() = this == TrafficDirection.Both
}

/**
 * Collects the live connections of the shown devices for the connections grid.
 *
 * Each device that is online and lists the feature is asked for its own stream; a failed or
 * finished stream leaves that device empty and is asked again after [retryDelay], while the
 * others carry on. Streams run only while [state] is collected, and stop
 * [SharingStarted.WhileSubscribed] five seconds after the grid leaves the screen.
 *
 * @param sources the shown devices.
 * @param rows tasks in the order the Downloads list shows them, then the ones it does not; they
 *   order and name each device's tasks.
 * @param retryDelay how long to wait before asking a device again, by the failures in a row.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ConnectionGridModel(
  private val sources: Flow<List<ConnectionSource>>,
  rows: Flow<List<TaskRow>>,
  private val scope: CoroutineScope,
  private val retryDelay: (failures: Int) -> Duration = ::connectionRetryDelay,
) {
  private val log = KetchLogger("ConnectionGrid")
  private val shared = mutableMapOf<Int, StateFlow<ConnectionGridState>>()

  private val names: Flow<List<Pair<TaskKey, String>>> = rows
    .map { list -> list.map { it.key to it.name } }
    .distinctUntilChanged()

  /**
   * The grid of at most [limit] connections across the shown devices, shared by every collector
   * asking for the same [limit]; empty until the first sample.
   */
  fun state(limit: Int): StateFlow<ConnectionGridState> = shared.getOrPut(limit) {
    observe(limit)
      .stateIn(scope, SharingStarted.WhileSubscribed(STOP_TIMEOUT), ConnectionGridState())
  }

  /**
   * The grid of at most [limit] connections; with several devices, each gets an equal share of
   * [limit].
   */
  fun observe(limit: Int): Flow<ConnectionGridState> = sources
    .distinctUntilChanged()
    .flatMapLatest { list ->
      val live = list.filter { it.supported && it.online }
      val share = if (live.size > 1) (limit / live.size).coerceAtLeast(1) else limit
      val samples = if (live.isEmpty()) {
        flowOf(emptyList())
      } else {
        combine(live.map { source -> samples(source, share).map { source to it } }) { it.toList() }
      }
      combine(samples, names) { latest, order -> build(list, latest, order) }
    }
    .distinctUntilChanged()

  private fun samples(source: ConnectionSource, limit: Int): Flow<DeviceSample> = flow {
    var failures = 0
    val stream = flow<DeviceSample> {
      source.api.activeConnections(limit).collect { emit(DeviceSample.Data(it)) }
      throw StreamEnded()
    }
    emitAll(
      stream
        .onEach { failures = 0 }
        .retryWhen { cause, _ ->
          if (cause is UnsupportedOperationException) return@retryWhen false
          log.d { "Connections stream failed, asking again: ${cause.describeCauses()}" }
          emit(DeviceSample.Empty)
          delay(retryDelay(failures++))
          true
        }
        .catch { cause ->
          if (cause !is UnsupportedOperationException) throw cause
          log.d { "Connections are not supported: ${cause.describeCauses()}" }
          emit(DeviceSample.Unsupported)
        },
    )
  }.onStart { emit(DeviceSample.Empty) }

  private fun build(
    sources: List<ConnectionSource>,
    samples: List<Pair<ConnectionSource, DeviceSample>>,
    order: List<Pair<TaskKey, String>>,
  ): ConnectionGridState {
    val position = HashMap<TaskKey, Int>(order.size)
    val taskNames = HashMap<TaskKey, String>(order.size)
    order.forEachIndexed { index, (key, name) ->
      if (key !in position) {
        position[key] = index
        taskNames[key] = name
      }
    }
    val devices = ArrayList<DeviceConnections>()
    var total = 0
    var down = 0L
    var up = 0L
    for ((source, sample) in samples) {
      val snapshot = (sample as? DeviceSample.Data)?.connections ?: continue
      total += snapshot.total
      down += snapshot.downloadBps
      up += snapshot.uploadBps
      val tasks = snapshot.connections
        .map { connectionCell(source.deviceId, it, snapshot) }
        .groupBy { it.task }
        .map { (key, cells) ->
          TaskConnections(
            key = key,
            name = taskNames[key],
            cells = cells.sortedWith(compareBy({ it.connection.openedAt }, { it.key.id })),
          )
        }
        .sortedWith(
          compareBy(
            { position[it.key] ?: Int.MAX_VALUE },
            { it.cells.first().connection.openedAt },
            { it.key.taskId },
          ),
        )
      if (tasks.isNotEmpty()) devices += DeviceConnections(source.deviceId, source.name, tasks)
    }
    val refused = samples.filter { it.second == DeviceSample.Unsupported }
      .mapTo(HashSet()) { it.first.deviceId }
    return ConnectionGridState(
      devices = devices,
      total = total,
      downloadBps = down,
      uploadBps = up,
      supported = sources.any { it.supported && it.deviceId !in refused },
      unsupported = sources
        .filter { it.online && (!it.supported || it.deviceId in refused) }
        .map { it.name },
    )
  }

  private sealed interface DeviceSample {
    data object Empty : DeviceSample

    data object Unsupported : DeviceSample

    data class Data(val connections: ActiveConnections) : DeviceSample
  }

  /** A stream that ended without failing, which is asked for again like a failed one. */
  private class StreamEnded : Exception()

  private companion object {
    const val STOP_TIMEOUT = 5_000L
  }
}

/** The cell of [connection] on the device [deviceId], sampled in [snapshot]. */
internal fun connectionCell(
  deviceId: String,
  connection: ActiveConnection,
  snapshot: ActiveConnections,
): ConnectionCell {
  val direction = trafficDirection(connection.downloadBps, connection.uploadBps)
  return ConnectionCell(
    key = ConnectionCellKey(deviceId, connection.id),
    task = TaskKey(deviceId, connection.taskId),
    direction = direction,
    level = trafficLevel(maxOf(connection.downloadBps, connection.uploadBps)),
    age = (snapshot.sampledAt - connection.openedAt).coerceAtLeast(Duration.ZERO),
    connection = connection,
  )
}

/** Which way bytes move at [downloadBps] in and [uploadBps] out. */
internal fun trafficDirection(downloadBps: Long, uploadBps: Long): TrafficDirection = when {
  downloadBps > 0 && uploadBps > 0 -> TrafficDirection.Both
  downloadBps > 0 -> TrafficDirection.Down
  uploadBps > 0 -> TrafficDirection.Up
  else -> TrafficDirection.Idle
}

/**
 * How busy a connection moving [bytesPerSecond] is, on fixed steps so cells do not change color
 * as the fastest one does: 0 when idle, 1 under 16 KB/s, 2 under 256 KB/s, 3 under 2 MB/s and 4
 * from 2 MB/s.
 */
internal fun trafficLevel(bytesPerSecond: Long): Int = when {
  bytesPerSecond <= 0 -> 0
  bytesPerSecond < LEVEL_2_FROM -> 1
  bytesPerSecond < LEVEL_3_FROM -> 2
  bytesPerSecond < LEVEL_4_FROM -> 3
  else -> 4
}

/** 1 s after the first failure in a row, doubling up to 30 s. */
internal fun connectionRetryDelay(failures: Int): Duration =
  (1L shl failures.coerceIn(0, MAX_DOUBLINGS)).seconds.coerceAtMost(MAX_RETRY_DELAY)

private const val LEVEL_2_FROM = 16L * 1024
private const val LEVEL_3_FROM = 256L * 1024
private const val LEVEL_4_FROM = 2L * 1024 * 1024
private const val MAX_DOUBLINGS = 5
private val MAX_RETRY_DELAY = 30.seconds
