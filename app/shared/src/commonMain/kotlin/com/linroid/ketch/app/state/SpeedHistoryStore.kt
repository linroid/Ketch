package com.linroid.ketch.app.state

import androidx.compose.runtime.Composable
import com.linroid.ketch.api.DownloadRequest
import com.linroid.ketch.api.DownloadState
import com.linroid.ketch.api.Segment
import com.linroid.ketch.app.i18n.UiText
import com.linroid.ketch.app.i18n.joinText
import com.linroid.ketch.app.i18n.priorityText
import com.linroid.ketch.app.i18n.resolve
import com.linroid.ketch.app.i18n.text
import com.linroid.ketch.app.i18n.verbatim
import com.linroid.ketch.app.util.SegmentRate
import com.linroid.ketch.app.util.SegmentRateTracker
import ketch.app.shared.generated.resources.Res
import ketch.app.shared.generated.resources.pulse_timeline_added
import ketch.app.shared.generated.resources.pulse_timeline_auto
import ketch.app.shared.generated.resources.pulse_timeline_canceled
import ketch.app.shared.generated.resources.pulse_timeline_completed
import ketch.app.shared.generated.resources.pulse_timeline_connections
import ketch.app.shared.generated.resources.pulse_timeline_failed
import ketch.app.shared.generated.resources.pulse_timeline_limit
import ketch.app.shared.generated.resources.pulse_timeline_limit_removed
import ketch.app.shared.generated.resources.pulse_timeline_paused
import ketch.app.shared.generated.resources.pulse_timeline_priority
import ketch.app.shared.generated.resources.pulse_timeline_queued
import ketch.app.shared.generated.resources.pulse_timeline_resumed
import ketch.app.shared.generated.resources.pulse_timeline_retried
import ketch.app.shared.generated.resources.pulse_timeline_scheduled
import ketch.app.shared.generated.resources.pulse_timeline_started
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlin.math.roundToInt
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant
import kotlin.time.TimeSource

/**
 * Speed of one task once a second, oldest first.
 *
 * Samples are [SpeedHistoryStore.INTERVAL] apart: seconds the task spent paused or waiting read
 * as `0`, so the newest sample was taken at [lastAt] and every earlier one a second before the
 * next.
 *
 * @property lastAt when the newest sample was taken.
 */
class SpeedHistory internal constructor(
  private val samples: LongArray,
  val lastAt: Instant,
  private val laneSamples: Map<Long, LongArray> = emptyMap(),
) {
  /** Number of samples, at most [SpeedHistoryStore.CAPACITY]. */
  val size: Int
    get() = samples.size

  /** Highest speed in bytes per second, `0` when empty. */
  val peak: Long
    get() = samples.maxOrNull() ?: 0

  /** Mean speed in bytes per second over every sample, `0` when empty. */
  val average: Long
    get() = if (samples.isEmpty()) 0 else samples.sum() / samples.size

  /**
   * The share of each sample that each connection carried, in byte order, over the same seconds
   * as the samples. Empty for torrents and for seconds recorded without segments.
   */
  val lanes: List<LaneHistory> = laneSamples.entries
    .sortedBy { it.key }
    .map { (start, values) -> LaneHistory(start, values) }

  /** Speed in bytes per second at [index], `0` being the oldest sample. */
  operator fun get(index: Int): Long = samples[index]

  /** When the sample at [index] was taken. */
  fun timeAt(index: Int): Instant = lastAt - SpeedHistoryStore.INTERVAL * (size - 1 - index)

  /** The samples as a list, oldest first. */
  fun toList(): List<Long> = samples.toList()

  /**
   * This history with [speed] sampled at [now], and with [lanes], when known, as each
   * connection's share of it. Whole seconds missed since [lastAt] are filled with `0`; a sample
   * less than half a second after the last one replaces it. A connection that is gone is kept
   * while any of its samples is above `0`.
   */
  internal fun plus(speed: Long, now: Instant, lanes: Map<Long, Long>? = null): SpeedHistory {
    val elapsed = ((now - lastAt) / SpeedHistoryStore.INTERVAL).roundToInt()
    if (elapsed <= 0) {
      val replaced = samples.copyOf()
      replaced[replaced.lastIndex] = speed
      return SpeedHistory(replaced, lastAt, joinLanes(lanes, gap = -1, size = replaced.size))
    }
    val gap = (elapsed - 1).coerceAtMost(SpeedHistoryStore.CAPACITY)
    val joined = samples + LongArray(gap) + speed
    val start = maxOf(0, joined.size - SpeedHistoryStore.CAPACITY)
    val kept = joined.copyOfRange(start, joined.size)
    return SpeedHistory(kept, now, joinLanes(lanes, gap, kept.size))
  }

  /**
   * Each connection's samples with its share in [lanes] added after [gap] seconds of `0`, or in
   * place of its newest sample when [gap] is negative, trimmed to [size].
   */
  private fun joinLanes(lanes: Map<Long, Long>?, gap: Int, size: Int): Map<Long, LongArray> {
    val keys = laneSamples.keys + lanes.orEmpty().keys
    if (keys.isEmpty()) return emptyMap()
    return buildMap {
      for (key in keys) {
        val old = laneSamples[key] ?: LongArray(samples.size)
        val value = lanes?.get(key) ?: 0L
        val joined = if (gap < 0) {
          old.copyOf().also { it[it.lastIndex] = value }
        } else {
          old + LongArray(gap) + value
        }
        val lane = joined.copyOfRange(joined.size - size, joined.size)
        if (lanes?.containsKey(key) == true || lane.any { it > 0 }) put(key, lane)
      }
    }
  }

  internal companion object {
    fun of(speed: Long, now: Instant, lanes: Map<Long, Long>? = null): SpeedHistory =
      SpeedHistory(
        samples = longArrayOf(speed),
        lastAt = now,
        laneSamples = lanes.orEmpty().mapValues { (_, value) -> longArrayOf(value) },
      )
  }
}

/**
 * The share of a task's speed one connection carried each second, as part of a [SpeedHistory].
 *
 * @property start first byte of the connection's segment, which identifies it.
 */
class LaneHistory internal constructor(val start: Long, private val samples: LongArray) {
  /** Number of samples, the same as the history's. */
  val size: Int
    get() = samples.size

  /** Bytes per second at [index], `0` being the oldest sample. */
  operator fun get(index: Int): Long = samples[index]

  /** The samples as a list, oldest first. */
  fun toList(): List<Long> = samples.toList()
}

/** What kind of change a [TimelineEntry] records, which picks its dot. */
enum class TimelineKind {
  Added,
  Started,
  Resumed,
  Paused,
  Queued,
  Scheduled,

  /** Its connections, speed limit or priority changed. */
  Changed,
  Failed,
  Completed,
  Canceled,
}

/**
 * Something that happened to a task while the app was open, for the Activity tab's timeline.
 *
 * @property at when it happened, or when the app noticed.
 * @property label what happened, such as "Paused" or "Connections 4 → 8".
 */
data class TimelineEntry(val at: Instant, val kind: TimelineKind, val label: UiText)

/**
 * Speed history of every downloading task, sampled once a second from task rows and kept for
 * the last [CAPACITY] seconds, so the inspector's Activity chart is full the moment it opens.
 * It also measures each connection's rate for the Connections tab and records what happens to
 * each task while the app is open.
 *
 * A history starts when its task first downloads, stays while it is paused or finished, and is
 * dropped with the task.
 *
 * @param rows rows of every task, such as [TaskListModel.rows].
 * @param scope samples until it is cancelled; a single thread, such as the main one.
 * @param clock time of each sample and timeline entry.
 * @param timeSource clock that measures the rate of each connection.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SpeedHistoryStore(
  rows: StateFlow<List<TaskRow>>,
  scope: CoroutineScope,
  private val clock: Clock = Clock.System,
  private val timeSource: TimeSource.WithComparableMarks = TimeSource.Monotonic,
) {
  private val state = MutableStateFlow<Map<TaskKey, SpeedHistory>>(emptyMap())
  private val rateState = MutableStateFlow<Map<TaskKey, List<SegmentRate>>>(emptyMap())
  private val timelineState = MutableStateFlow<Map<TaskKey, List<TimelineEntry>>>(emptyMap())

  // The sampling loop alone touches these, and record() before it starts.
  private val trackers = HashMap<TaskKey, SegmentRateTracker>()
  private val splitters = HashMap<TaskKey, LaneSplitter>()

  // The row collector alone touches this.
  private val recorder = TimelineRecorder(since = clock.now())

  /** History of each task that has downloaded. */
  val histories: StateFlow<Map<TaskKey, SpeedHistory>> = state.asStateFlow()

  /**
   * Rate of each connection of every downloading HTTP or FTP task, measured once a second by a
   * [SegmentRateTracker], so a connection that receives nothing for 3 s reads as stalled.
   */
  val rates: StateFlow<Map<TaskKey, List<SegmentRate>>> = rateState.asStateFlow()

  /** What happened to each task since the app opened, oldest first. */
  val timelines: StateFlow<Map<TaskKey, List<TimelineEntry>>> = timelineState.asStateFlow()

  init {
    scope.launch {
      rows.map { list -> list.any { it.state is DownloadState.Downloading } }
        .distinctUntilChanged()
        .collectLatest { active ->
          if (!active) {
            trackers.clear()
            rateState.value = emptyMap()
            return@collectLatest
          }
          while (true) {
            sample(rows.value)
            delay(INTERVAL)
          }
        }
    }
    scope.launch {
      rows.map { list -> list.mapTo(HashSet()) { it.key } }
        .distinctUntilChanged()
        .collect { keys ->
          state.update { current -> current.filterKeys(keys::contains) }
          rateState.update { current -> current.filterKeys(keys::contains) }
          timelineState.update { current -> current.filterKeys(keys::contains) }
        }
    }
    scope.launch {
      rows.collect { list -> observe(list, clock.now()) }
    }
  }

  /** History of the task [key], or `null` if it has not downloaded. */
  fun history(key: TaskKey): SpeedHistory? = state.value[key]

  /** Rates of the connections of the task [key], empty unless it downloads over HTTP or FTP. */
  fun rates(key: TaskKey): List<SegmentRate> = rateState.value[key].orEmpty()

  /**
   * Takes one sample at [now]. [speeds] has every listed task: the speed of a downloading one,
   * `null` for the others. Tasks missing from it are forgotten. [segments] has the segments of
   * downloading tasks whose connections are tracked; each one's speed is split between them by
   * the bytes they received since the last sample.
   */
  internal fun record(
    now: Instant,
    speeds: Map<TaskKey, Long?>,
    segments: Map<TaskKey, List<Segment>> = emptyMap(),
  ) {
    splitters.keys.retainAll(segments.keys)
    val lanes = HashMap<TaskKey, Map<Long, Long>>()
    for ((key, speed) in speeds) {
      val list = segments[key]?.takeIf { it.isNotEmpty() } ?: continue
      if (speed == null) continue
      lanes[key] = splitters.getOrPut(key) { LaneSplitter() }.split(list, speed)
    }
    state.update { current ->
      buildMap {
        for ((key, speed) in speeds) {
          val history = current[key]
          when {
            speed != null -> put(
              key,
              history?.plus(speed, now, lanes[key]) ?: SpeedHistory.of(speed, now, lanes[key])
            )
            history != null -> put(key, history)
          }
        }
      }
    }
  }

  /**
   * Adds the timeline entries that the changes in [rows] since the last call make, noticed at
   * [now].
   */
  internal fun observe(rows: List<TaskRow>, now: Instant) {
    val added = recorder.observe(rows, now)
    if (added.isEmpty()) return
    timelineState.update { current ->
      current + added.mapValues { (key, entries) ->
        (current[key].orEmpty() + entries).takeLast(MAX_TIMELINE)
      }
    }
  }

  private fun sample(rows: List<TaskRow>) {
    val speeds = rows.associate { row ->
      row.key to (row.state as? DownloadState.Downloading)?.progress?.bytesPerSecond
    }
    val segments = rows
      .filter { it.state is DownloadState.Downloading && !it.isTorrent }
      .filter { it.segments.isNotEmpty() }
      .associate { it.key to it.segments }
    record(clock.now(), speeds, segments)
    trackers.keys.retainAll(segments.keys)
    rateState.value = segments.mapValues { (key, list) ->
      trackers.getOrPut(key) { SegmentRateTracker(timeSource) }.update(list)
    }
  }

  companion object {
    /** Time between two samples. */
    val INTERVAL: Duration = 1.seconds

    /** Samples kept per task: five minutes. */
    const val CAPACITY: Int = 300

    /** Timeline entries kept per task, the newest ones. */
    const val MAX_TIMELINE: Int = 50
  }
}

/**
 * Splits each second's speed of one task between its connections, in proportion to the bytes
 * each received since the last split. A second in which none moved keeps the last proportions,
 * and the first one splits evenly between the unfinished connections, or all of them once every
 * one has finished.
 */
internal class LaneSplitter {
  private var bytes: Map<Long, Long> = emptyMap()
  private var shares: Map<Long, Long> = emptyMap()

  /**
   * [speed] split by first byte between the unfinished [segments] and any that received bytes;
   * the parts add up to [speed]. A segment that finished earlier gets no part, so it does not
   * keep an empty lane.
   */
  fun split(segments: List<Segment>, speed: Long): Map<Long, Long> {
    val received = segments.associate { segment ->
      val before = bytes[segment.start] ?: segment.downloadedBytes
      segment.start to (segment.downloadedBytes - before).coerceAtLeast(0)
    }
    bytes = segments.associate { it.start to it.downloadedBytes }
    val open = segments.filter { !it.isComplete }.ifEmpty { segments }
    val starts = open.mapTo(HashSet()) { it.start }
    shares = when {
      received.values.any { it > 0 } -> received.filterValues { it > 0 }
      shares.keys.any { it in starts } -> shares.filterKeys { it in starts }
      else -> open.associate { it.start to 1L }
    }
    return allocate(speed, shares, open.map { it.start })
  }

  /** [total] in proportion to [weights], each of [keys] present, the rounding on the largest. */
  private fun allocate(total: Long, weights: Map<Long, Long>, keys: List<Long>): Map<Long, Long> {
    val sum = weights.values.sum()
    val parts = keys.associateWithTo(LinkedHashMap()) { 0L }
    if (sum <= 0 || total <= 0) return parts
    for ((key, weight) in weights) {
      parts[key] = (total.toDouble() * weight / sum).toLong()
    }
    val largest = weights.maxBy { it.value }.key
    parts[largest] = parts.getValue(largest) + total - parts.values.sum()
    return parts
  }
}

/**
 * Turns changes between snapshots of task rows into [TimelineEntry]s. Tasks seen for the first
 * time only add entries when they were added after [since], when the app opened.
 */
internal class TimelineRecorder(private val since: Instant) {
  private val seen = HashMap<TaskKey, TaskRow>()
  private val started = HashSet<TaskKey>()

  /** New entries of each task in [rows], noticed at [now]; tasks no longer listed are dropped. */
  fun observe(rows: List<TaskRow>, now: Instant): Map<TaskKey, List<TimelineEntry>> {
    val result = HashMap<TaskKey, List<TimelineEntry>>()
    val keys = HashSet<TaskKey>()
    for (row in rows) {
      keys += row.key
      val before = seen.put(row.key, row)
      if (before === row) continue
      val entries = timelineEntries(before, row, now, since, row.key in started)
      if (row.state is DownloadState.Downloading) started += row.key
      if (entries.isNotEmpty()) result[row.key] = entries
    }
    seen.keys.retainAll(keys)
    started.retainAll(keys)
    return result
  }
}

/**
 * Entries for the change from [before] to [after], noticed at [now]: changes of the requested
 * connections, speed limit or priority, then the change of state. A task seen for the first time
 * is added at its creation when that is after [since], when the app opened, and is otherwise
 * taken as it is. [started] tells whether it has downloaded since the app opened, so that it
 * resumes rather than starts.
 */
internal fun timelineEntries(
  before: TaskRow?,
  after: TaskRow,
  now: Instant,
  since: Instant,
  started: Boolean,
): List<TimelineEntry> = buildList {
  if (before == null) {
    if (after.createdAt < since) return@buildList
    add(TimelineEntry(after.createdAt, TimelineKind.Added, Res.string.pulse_timeline_added.text()))
    if (after.state !is DownloadState.Queued) add(stateEntry(null, after, now, started))
    return@buildList
  }
  if (before.request !== after.request) addAll(requestEntries(before.request, after.request, now))
  if (before.state::class != after.state::class) add(stateEntry(before.state, after, now, started))
}

private fun requestEntries(
  before: DownloadRequest,
  after: DownloadRequest,
  now: Instant,
): List<TimelineEntry> = buildList {
  if (before.connections != after.connections) {
    val text = Res.string.pulse_timeline_connections
      .text(connectionsText(before.connections), connectionsText(after.connections))
    add(TimelineEntry(now, TimelineKind.Changed, text))
  }
  if (before.speedLimit != after.speedLimit) {
    val limit = after.speedLimit
    val text = if (limit.isUnlimited) {
      Res.string.pulse_timeline_limit_removed.text()
    } else {
      Res.string.pulse_timeline_limit.text(speedLimitText(limit))
    }
    add(TimelineEntry(now, TimelineKind.Changed, text))
  }
  if (before.priority != after.priority) {
    val text = Res.string.pulse_timeline_priority.text(priorityText(after.priority))
    add(TimelineEntry(now, TimelineKind.Changed, text))
  }
}

private fun stateEntry(
  before: DownloadState?,
  row: TaskRow,
  now: Instant,
  started: Boolean,
): TimelineEntry {
  val (kind, text) = when (row.state) {
    is DownloadState.Downloading -> when {
      before is DownloadState.Failed ->
        TimelineKind.Started to Res.string.pulse_timeline_retried.text()
      before is DownloadState.Paused || started ->
        TimelineKind.Resumed to Res.string.pulse_timeline_resumed.text()
      else -> TimelineKind.Started to Res.string.pulse_timeline_started.text()
    }
    is DownloadState.Paused -> TimelineKind.Paused to Res.string.pulse_timeline_paused.text()
    is DownloadState.Queued -> TimelineKind.Queued to Res.string.pulse_timeline_queued.text()
    is DownloadState.Scheduled ->
      TimelineKind.Scheduled to Res.string.pulse_timeline_scheduled.text()
    is DownloadState.Completed ->
      TimelineKind.Completed to Res.string.pulse_timeline_completed.text()
    is DownloadState.Failed -> {
      val failed = Res.string.pulse_timeline_failed.text()
      TimelineKind.Failed to listOfNotNull(failed, row.content.error?.title).joinText()
    }
    is DownloadState.Canceled -> TimelineKind.Canceled to Res.string.pulse_timeline_canceled.text()
  }
  return TimelineEntry(now, kind, text)
}

private fun connectionsText(connections: Int): UiText =
  if (connections == 0) Res.string.pulse_timeline_auto.text() else verbatim(connections.toString())
