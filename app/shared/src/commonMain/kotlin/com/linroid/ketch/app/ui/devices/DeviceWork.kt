package com.linroid.ketch.app.ui.devices

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import com.linroid.ketch.api.DownloadState
import com.linroid.ketch.api.DownloadTask
import com.linroid.ketch.app.i18n.UiText
import com.linroid.ketch.app.i18n.text
import com.linroid.ketch.app.instance.DevicePresence
import com.linroid.ketch.app.instance.InstanceEntry
import com.linroid.ketch.app.state.DevicePulse
import com.linroid.ketch.app.state.PulseState
import com.linroid.ketch.app.state.SpeedMode
import com.linroid.ketch.app.state.deviceId
import com.linroid.ketch.app.state.waitsInQueue
import com.linroid.ketch.app.util.displayName
import ketch.app.shared.generated.resources.Res
import ketch.app.shared.generated.resources.device_pause_all_here
import ketch.app.shared.generated.resources.device_retry_failed
import ketch.app.shared.generated.resources.device_start_now
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.datetime.TimeZone
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Instant

/**
 * A downloading task in a device's lane.
 *
 * @property remainingBytes bytes it still needs; `null` while its size is unknown.
 */
internal data class LaneBlock(val taskId: String, val remainingBytes: Long?)

/**
 * What a device's tasks add up to, beyond the counts its [DevicePresence] has: the blocks of its
 * lane, the task that would start next, and the bytes left.
 *
 * @property blocks its downloading tasks, oldest first.
 * @property next the waiting task that would start next, for "Start … now".
 * @property downloadedBytes bytes received by the downloading tasks of known size.
 * @property sizeBytes total size of those tasks.
 * @property sizesKnown whether every downloading task has a known size.
 * @property pendingBytes bytes the downloading and waiting tasks of known size still need.
 */
@Immutable
internal data class DeviceWork(
  val blocks: List<LaneBlock> = emptyList(),
  val next: DownloadTask? = null,
  val downloadedBytes: Long = 0,
  val sizeBytes: Long = 0,
  val sizesKnown: Boolean = true,
  val pendingBytes: Long = 0,
)

/**
 * Sums up [tasks] in the states they are in.
 *
 * @param positions the [DownloadTask.queuePosition] of each waiting task, by task id, as far as
 *   the device reports them.
 */
internal fun deviceWork(
  tasks: List<Pair<DownloadTask, DownloadState>>,
  positions: Map<String, Int> = emptyMap(),
): DeviceWork {
  val blocks = ArrayList<LaneBlock>()
  var downloaded = 0L
  var size = 0L
  var sizesKnown = true
  var pending = 0L
  for ((task, state) in tasks.sortedBy { it.first.createdAt }) {
    when {
      state is DownloadState.Downloading -> {
        val progress = state.progress
        if (progress.totalBytes > 0) {
          val remaining = (progress.totalBytes - progress.downloadedBytes).coerceAtLeast(0)
          downloaded += progress.downloadedBytes
          size += progress.totalBytes
          pending += remaining
          blocks += LaneBlock(task.taskId, remaining)
        } else {
          sizesKnown = false
          blocks += LaneBlock(task.taskId, null)
        }
      }
      state.waitsInQueue || state is DownloadState.Scheduled -> {
        pending += task.request.resolvedSource?.totalBytes?.coerceAtLeast(0) ?: 0
      }
    }
  }
  return DeviceWork(
    blocks = blocks,
    next = nextWaiting(tasks, positions),
    downloadedBytes = downloaded,
    sizeBytes = size,
    sizesKnown = sizesKnown,
    pendingBytes = pending,
  )
}

/**
 * The waiting task that would start first: the one the device puts first in its queue when it
 * reports [positions], otherwise a queued one before a scheduled one, then the highest priority,
 * then the oldest.
 */
internal fun nextWaiting(
  tasks: List<Pair<DownloadTask, DownloadState>>,
  positions: Map<String, Int> = emptyMap(),
): DownloadTask? {
  val waiting = tasks.filter { (_, state) ->
    state.waitsInQueue || state is DownloadState.Scheduled
  }
  waiting.firstOrNull { (task, state) -> state.waitsInQueue && positions[task.taskId] == 1 }
    ?.let { return it.first }
  return waiting
    .sortedWith(
      compareBy<Pair<DownloadTask, DownloadState>> { (_, state) ->
        if (state.waitsInQueue) 0 else 1
      }
        .thenByDescending { (task, _) -> task.request.priority.ordinal }
        .thenBy { (task, _) -> task.createdAt }
    )
    .firstOrNull()?.first
}

/**
 * Shares of the lane each of [blocks] takes, adding up to 1: its remaining bytes, with blocks
 * of unknown size taking the average of the others, and every block at least [MIN_BLOCK_SHARE]
 * so a nearly finished task stays visible.
 */
internal fun laneShares(blocks: List<LaneBlock>): List<Float> {
  if (blocks.isEmpty()) return emptyList()
  val known = blocks.mapNotNull { it.remainingBytes }
  val fallback = if (known.isEmpty()) 1.0 else known.average().coerceAtLeast(1.0)
  val raw = blocks.map { block -> block.remainingBytes?.toDouble() ?: fallback }
  val total = raw.sum()
  val floored = raw.map { if (total > 0) maxOf(it / total, MIN_BLOCK_SHARE) else 1.0 }
  val sum = floored.sum()
  return floored.map { (it / sum).toFloat() }
}

/** One of the buttons at the bottom of a device card, shown only when it has work to do. */
internal sealed interface NextAction {
  /** What the button says. */
  val label: UiText

  /** Retries the device's failed downloads, without switching to it. */
  data class RetryFailed(val count: Int) : NextAction {
    override val label: UiText get() = Res.plurals.device_retry_failed.text(count)
  }

  /** Pauses everything the device is downloading or has queued. */
  data object PauseAll : NextAction {
    override val label: UiText get() = Res.string.device_pause_all_here.text()
  }

  /** Starts [task], the next waiting one, at once. */
  data class StartNow(val task: DownloadTask, val name: String) : NextAction {
    override val label: UiText get() = Res.string.device_start_now.text(clipName(name))
  }
}

/**
 * The buttons of [device]'s card, in the order they show: Retry failed while downloads failed,
 * Pause all here while some download (it also pauses those waiting in the queue), and Start the
 * next waiting download now.
 */
internal fun nextActions(device: DevicePresence, work: DeviceWork): List<NextAction> =
  buildList {
    if (device.failures > 0) add(NextAction.RetryFailed(device.failures))
    if (device.counts.downloading > 0) add(NextAction.PauseAll)
    work.next?.let { task ->
      add(NextAction.StartNow(task, displayName(task.requestState.value, task.state.value)))
    }
  }

/**
 * The Devices page's subtitle, "Downloading 3 files on 2 devices · all done ≈ 14:32", about the
 * devices the app keeps connected; [mode] adds the Slow lane when it holds this device back.
 */
internal fun fleetSentence(
  devices: List<DevicePresence>,
  work: Map<String, DeviceWork>,
  mode: SpeedMode,
  now: Instant,
  timeZone: TimeZone,
): UiText {
  val pulses = devices.filter { it.connected }.map { device ->
    val summary = work[device.deviceId] ?: DeviceWork()
    DevicePulse(
      deviceId = device.deviceId,
      name = device.name,
      health = device.health,
      counts = device.counts,
      failures = device.failures,
      speed = device.speed,
      cap = device.cap,
      downloadedBytes = summary.downloadedBytes,
      sizeBytes = summary.sizeBytes,
      sizesKnown = summary.sizesKnown,
      pendingBytes = summary.pendingBytes,
      disk = device.disk,
      history = device.history,
    )
  }
  return PulseState(devices = pulses, allDevices = true, mode = mode).sentence(now, timeZone)
}

/** The [DeviceWork] of each of [entries] by device id, kept current a few times a second. */
@OptIn(ExperimentalCoroutinesApi::class)
@Composable
internal fun rememberDeviceWork(entries: List<InstanceEntry>): Map<String, DeviceWork> {
  val flow = remember(entries) {
    if (entries.isEmpty()) {
      flowOf(emptyMap())
    } else {
      combine(entries.map { entry -> workOf(entry).map { entry.deviceId to it } }) { it.toMap() }
    }
  }
  return produceState(emptyMap(), flow) {
    flow.distinctUntilChanged().conflate().collect {
      value = it
      delay(UPDATE_INTERVAL)
    }
  }.value
}

@OptIn(ExperimentalCoroutinesApi::class)
private fun workOf(entry: InstanceEntry): Flow<DeviceWork> =
  entry.instance.tasks.flatMapLatest { tasks ->
    if (tasks.isEmpty()) {
      flowOf(DeviceWork())
    } else {
      val states = tasks.map { task ->
        combine(task.state, task.queuePosition) { state, position -> Triple(task, state, position) }
      }
      combine(states) { snapshots ->
        val positions = snapshots.mapNotNull { (task, _, position) ->
          position?.let { task.taskId to it }
        }.toMap()
        deviceWork(snapshots.map { (task, state, _) -> task to state }, positions)
      }
    }
  }

private const val MIN_BLOCK_SHARE = 0.04
private val UPDATE_INTERVAL = 250.milliseconds
