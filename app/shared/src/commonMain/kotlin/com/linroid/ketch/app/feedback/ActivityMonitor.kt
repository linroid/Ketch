package com.linroid.ketch.app.feedback

import com.linroid.ketch.api.DownloadState
import com.linroid.ketch.api.DownloadTask
import com.linroid.ketch.api.log.KetchLogger
import com.linroid.ketch.app.state.TaskKey
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * A device whose activity [ActivityMonitor] reports.
 *
 * @property deviceId id of the device, used in the task keys of its events.
 * @property tasks the device's tasks.
 * @property online whether the app is connected to the device; the embedded one always is.
 */
class ActivitySource(
  val deviceId: String,
  val tasks: Flow<List<DownloadTask>>,
  val online: Flow<Boolean> = flowOf(true),
)

/**
 * Turns what happens on the devices it watches into [ActivityEvent]s.
 *
 * The first task list of a device is its baseline: it produces no events, so tasks that are
 * already finished stay quiet, and the tasks in it that are queued or downloading are reported
 * once as [ActivityEvent.Recovered]. A task that shows up later counts as added only when it was
 * created after the monitor started; older ones, such as tasks the engine restores after an
 * empty first list, join the baseline.
 *
 * Completions are held for up to [COALESCE_WINDOW] while other tasks of the device are still
 * running: more than [COALESCE_LIMIT] of them become one [ActivityEvent.CompletedBatch]. The
 * completion that leaves the device with nothing queued or downloading is reported at once; when
 * more than one task finished since the device was last idle, [ActivityEvent.QueueDrained]
 * replaces it and any completions still held. A device that stays offline for [OFFLINE_GRACE]
 * is reported, and so is its return.
 *
 * @param devices devices to watch; a device keeps its baseline while it stays in the list.
 * @param scope runs the monitor until it is cancelled.
 * @param clock tells which tasks were created after the monitor started.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ActivityMonitor(
  devices: Flow<List<ActivitySource>>,
  private val scope: CoroutineScope,
  clock: Clock = Clock.System,
) {
  private val log = KetchLogger("ActivityMonitor")
  private val startedAt = clock.now()
  private val output = Channel<ActivityEvent>(EVENT_BUFFER, BufferOverflow.DROP_OLDEST)
  private val timers = Channel<Signal>(Channel.UNLIMITED)

  // Only touched by the single coroutine that handles signals.
  private val trackers = HashMap<String, DeviceTracker>()

  /** Events in the order they happened. Each event goes to one collector. */
  val events: Flow<ActivityEvent> = output.receiveAsFlow()

  init {
    scope.launch {
      merge(signals(devices), timers.receiveAsFlow()).collect(::handle)
    }
  }

  private fun signals(devices: Flow<List<ActivitySource>>): Flow<Signal> =
    devices.flatMapLatest { sources ->
      sources.map(::deviceSignals).merge()
        .onStart { emit(Signal.Devices(sources.map { it.deviceId }.toSet())) }
    }

  private fun deviceSignals(source: ActivitySource): Flow<Signal> = merge(
    source.online.distinctUntilChanged().map { Signal.Online(source.deviceId, it) },
    source.tasks.flatMapLatest(::phases).map { Signal.Tasks(source.deviceId, it) }
  )

  // Progress updates do not matter here, so each task only emits when its kind of state changes.
  private fun phases(tasks: List<DownloadTask>): Flow<List<TaskSnapshot>> {
    if (tasks.isEmpty()) return flowOf(emptyList())
    val flows = tasks.map { task ->
      task.state
        .distinctUntilChanged { old, new -> old::class == new::class }
        .map { TaskSnapshot(task, it) }
    }
    return combine(flows) { it.toList() }
  }

  private fun handle(signal: Signal) {
    when (signal) {
      is Signal.Devices -> trackers.keys.retainAll(signal.ids)
      is Signal.Tasks -> onTasks(signal.deviceId, signal.tasks)
      is Signal.Online -> onOnline(signal.deviceId, signal.online)
      is Signal.WindowEnd -> {
        val tracker = trackers[signal.deviceId] ?: return
        if (!tracker.windowOpen || tracker.windowGeneration != signal.generation) return
        tracker.windowOpen = false
        flushHeld(tracker)
      }
      is Signal.OfflineGrace -> {
        val tracker = trackers[signal.deviceId] ?: return
        if (tracker.online != false || tracker.offlineGeneration != signal.generation) return
        tracker.offlineReported = true
        send(ActivityEvent.DeviceOffline(signal.deviceId))
      }
    }
  }

  private fun onTasks(deviceId: String, tasks: List<TaskSnapshot>) {
    val tracker = trackers.getOrPut(deviceId) { DeviceTracker() }
    val isBaseline = !tracker.loaded
    tracker.loaded = true
    tracker.states.keys.retainAll(tasks.map { it.task.taskId }.toSet())

    val events = ArrayList<ActivityEvent>()
    val completions = ArrayList<ActivityEvent.Completed>()
    var preexisting = false
    var recovered = 0
    for ((task, state) in tasks) {
      val previous = tracker.states.put(task.taskId, state)
      val key = TaskKey(deviceId, task.taskId)
      when {
        previous != null -> when {
          state is DownloadState.Completed && previous !is DownloadState.Completed ->
            completions += ActivityEvent.Completed(key, task.request, state)
          state is DownloadState.Failed && previous !is DownloadState.Failed ->
            events += ActivityEvent.Failed(key, task.request, state)
        }
        isBaseline || task.createdAt < startedAt -> {
          preexisting = true
          if (state is DownloadState.Queued || state is DownloadState.Downloading) recovered++
        }
        else -> events += ActivityEvent.Added(key, task.request)
      }
    }
    if (preexisting && !tracker.recoveryReported) {
      tracker.recoveryReported = true
      if (recovered > 0) send(ActivityEvent.Recovered(deviceId, recovered))
    }
    events.forEach(::send)
    onCompletions(deviceId, tracker, tasks, completions)
  }

  private fun onCompletions(
    deviceId: String,
    tracker: DeviceTracker,
    tasks: List<TaskSnapshot>,
    completions: List<ActivityEvent.Completed>,
  ) {
    for (completion in completions) {
      tracker.runFiles++
      tracker.runBytes += completion.state.totalBytes ?: 0
      tracker.held += completion
    }
    val busy = tasks.any {
      it.state is DownloadState.Queued || it.state is DownloadState.Downloading
    }
    when {
      !busy -> {
        if (completions.isNotEmpty() && tracker.runFiles > 1) {
          tracker.held.clear()
          send(ActivityEvent.QueueDrained(deviceId, tracker.runFiles, tracker.runBytes))
        } else {
          flushHeld(tracker)
        }
        tracker.windowOpen = false
        tracker.runFiles = 0
        tracker.runBytes = 0
      }
      tracker.held.isNotEmpty() && !tracker.windowOpen -> {
        tracker.windowOpen = true
        val generation = ++tracker.windowGeneration
        schedule(COALESCE_WINDOW, Signal.WindowEnd(deviceId, generation))
      }
    }
  }

  private fun onOnline(deviceId: String, online: Boolean) {
    val tracker = trackers.getOrPut(deviceId) { DeviceTracker() }
    val previous = tracker.online
    tracker.online = online
    if (previous == null || previous == online) return
    val generation = ++tracker.offlineGeneration
    if (!online) {
      schedule(OFFLINE_GRACE, Signal.OfflineGrace(deviceId, generation))
    } else if (tracker.offlineReported) {
      tracker.offlineReported = false
      send(ActivityEvent.DeviceOnline(deviceId))
    }
  }

  private fun flushHeld(tracker: DeviceTracker) {
    if (tracker.held.isEmpty()) return
    val held = tracker.held.toList()
    tracker.held.clear()
    if (held.size > COALESCE_LIMIT) {
      send(ActivityEvent.CompletedBatch(held))
    } else {
      held.forEach(::send)
    }
  }

  private fun schedule(after: Duration, signal: Signal) {
    scope.launch {
      delay(after)
      timers.send(signal)
    }
  }

  private fun send(event: ActivityEvent) {
    log.d { "Event ${describe(event)}" }
    output.trySend(event)
  }

  // Requests can hold credentials and cookies, so only ids are logged.
  private fun describe(event: ActivityEvent): String = when (event) {
    is ActivityEvent.Added -> "added taskId=${event.taskKey.taskId} on ${event.taskKey.deviceId}"
    is ActivityEvent.Completed ->
      "completed taskId=${event.taskKey.taskId} on ${event.taskKey.deviceId}"
    is ActivityEvent.CompletedBatch -> "${event.completions.size} completed"
    is ActivityEvent.Failed -> "failed taskId=${event.taskKey.taskId} on ${event.taskKey.deviceId}"
    is ActivityEvent.Recovered -> "${event.count} recovered on ${event.deviceId}"
    is ActivityEvent.QueueDrained -> "queue drained after ${event.files} on ${event.deviceId}"
    is ActivityEvent.DeviceOffline -> "${event.deviceId} offline"
    is ActivityEvent.DeviceOnline -> "${event.deviceId} online"
  }

  private class DeviceTracker {
    var loaded = false
    var recoveryReported = false
    val states = HashMap<String, DownloadState>()
    val held = ArrayList<ActivityEvent.Completed>()
    var windowOpen = false
    var windowGeneration = 0
    var runFiles = 0
    var runBytes = 0L
    var online: Boolean? = null
    var offlineReported = false
    var offlineGeneration = 0
  }

  private data class TaskSnapshot(
    val task: DownloadTask,
    val state: DownloadState,
  )

  private sealed interface Signal {
    class Devices(val ids: Set<String>) : Signal
    class Tasks(val deviceId: String, val tasks: List<TaskSnapshot>) : Signal
    class Online(val deviceId: String, val online: Boolean) : Signal
    class WindowEnd(val deviceId: String, val generation: Int) : Signal
    class OfflineGrace(val deviceId: String, val generation: Int) : Signal
  }

  companion object {
    /** How long completions are held to see whether more follow. */
    val COALESCE_WINDOW: Duration = 10.seconds

    /** Most completions in one window reported one by one; more become a batch. */
    const val COALESCE_LIMIT: Int = 3

    /** How long a device stays offline before it is reported. */
    val OFFLINE_GRACE: Duration = 5.seconds

    private const val EVENT_BUFFER = 64
  }
}
