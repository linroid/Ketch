package com.linroid.ketch.app.state

import com.linroid.ketch.api.DownloadConfig
import com.linroid.ketch.api.DownloadPriority
import com.linroid.ketch.api.DownloadRequest
import com.linroid.ketch.api.DownloadState
import com.linroid.ketch.api.DownloadTask
import com.linroid.ketch.api.Segment
import com.linroid.ketch.app.util.FileKind
import com.linroid.ketch.app.util.FileType
import com.linroid.ketch.app.util.RowContent
import com.linroid.ketch.app.util.RowContext
import com.linroid.ketch.app.util.RowStatus
import com.linroid.ketch.app.util.STALL_THRESHOLD
import com.linroid.ketch.app.util.SearchQuery
import com.linroid.ketch.app.util.SearchTarget
import com.linroid.ketch.app.util.TaskOrigin
import com.linroid.ketch.app.util.decodePath
import com.linroid.ketch.app.util.displayName
import com.linroid.ketch.app.util.referer
import com.linroid.ketch.app.util.rowContent
import com.linroid.ketch.app.util.urlHost
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.sample
import kotlinx.coroutines.flow.stateIn
import kotlinx.datetime.TimeZone
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant
import com.linroid.ketch.app.util.isTorrent as isTorrentRequest

/**
 * One device whose tasks the Downloads list shows.
 *
 * @property deviceId id of the device in [TaskKey]s.
 * @property device name and capabilities of the device.
 * @property tasks the device's tasks.
 * @property config the device's download configuration, which explains why queued tasks wait;
 *   `null` while unknown.
 * @property slowLane whether Slow lane caps the device.
 */
class TaskListSource(
  val deviceId: String,
  val device: DeviceInfo,
  val tasks: Flow<List<DownloadTask>>,
  val config: Flow<DownloadConfig?> = flowOf(null),
  val slowLane: Flow<Boolean> = flowOf(false),
)

/**
 * A task as the Downloads list shows it. [TaskListModel] builds a new row only when something it
 * shows changed, so an unchanged row stays the same instance and skips recomposition.
 *
 * @property key device and id of the task.
 * @property task the task, for commands.
 * @property request the current request, from [DownloadTask.requestState].
 * @property state the current state.
 * @property segments the current segments.
 * @property createdAt when the task was added.
 * @property device the device that runs the task.
 * @property content the row text, from [rowContent].
 * @property speedSamples speed once a second while downloading, oldest first, at most
 *   [TaskListModel.SPEED_SAMPLES]; kept while the task is paused.
 */
data class TaskRow(
  val key: TaskKey,
  val task: DownloadTask,
  val request: DownloadRequest,
  override val state: DownloadState,
  val segments: List<Segment>,
  override val createdAt: Instant,
  val device: DeviceInfo,
  val content: RowContent,
  val speedSamples: List<Long> = emptyList(),
) : SearchTarget {
  override val name: String = displayName(request, state)

  /** Category of the file, for its chip. */
  val kind: FileKind = FileKind.of(name, request.url)

  override val fileType: FileType = FileType.of(kind)
  override val host: String? = urlHost(request.url)
  override val refererHost: String? = request.referer?.let(::urlHost)
  override val outputPath: String? =
    ((state as? DownloadState.Completed)?.outputPath ?: request.destination?.value)
      ?.let(::decodePath)
  override val isTorrent: Boolean = isTorrentRequest(request)
  override val origin: TaskOrigin? = TaskOrigin.of(request)
  override val sizeBytes: Long? = when (state) {
    is DownloadState.Downloading -> state.progress.totalBytes.takeIf { it > 0 }
    is DownloadState.Paused -> state.progress.totalBytes.takeIf { it > 0 }
    is DownloadState.Completed -> state.totalBytes
    else -> null
  } ?: request.resolvedSource?.totalBytes?.takeIf { it > 0 }

  override val priority: DownloadPriority
    get() = request.priority
  override val errorTitle: String?
    get() = content.error?.title
  override val isStalled: Boolean
    get() = content.status == RowStatus.Stalled
  override val isLimited: Boolean
    get() = content.limited || !request.speedLimit.isUnlimited
  override val deviceName: String
    get() = device.name

  /** Host for the Source column: the page the link came from, else the link's own host. */
  val sourceHost: String?
    get() = refererHost ?: host

  /** Fraction downloaded: `1` once completed, `null` while the size is unknown. */
  val progress: Float?
    get() = if (state is DownloadState.Completed) 1f else content.progress

  /** Speed while downloading, averaged over the last 3 samples; `null` in other states. */
  val speed: Long?
    get() {
      val downloading = state as? DownloadState.Downloading ?: return null
      val recent = speedSamples.takeLast(SPEED_AVERAGE)
      if (recent.isEmpty()) return downloading.progress.bytesPerSecond
      return recent.sum() / recent.size
    }

  /** Time until a downloading task finishes at its current speed; `null` when unknown. */
  val timeLeft: Duration?
    get() {
      val progress = (state as? DownloadState.Downloading)?.progress ?: return null
      if (progress.totalBytes <= 0 || progress.bytesPerSecond <= 0) return null
      val remaining = (progress.totalBytes - progress.downloadedBytes).coerceAtLeast(0)
      return (remaining / progress.bytesPerSecond).seconds
    }

  /** Open connections of a downloading task; `null` for torrents and other states. */
  val connections: Int?
    get() = if (state is DownloadState.Downloading && !isTorrent) {
      segments.count { !it.isComplete }
    } else {
      null
    }

  private companion object {
    const val SPEED_AVERAGE = 3
  }
}

/**
 * What the Downloads list shows for a tab and search.
 *
 * @property filter the status tab.
 * @property query the search.
 * @property arrangement the sort order and grouping.
 * @property groups the rows on the tab that match the search, grouped and sorted.
 * @property total number of rows on the tab before the search.
 */
data class TaskListView(
  val filter: StatusFilter = StatusFilter.All,
  val query: SearchQuery = SearchQuery.Empty,
  val arrangement: ListArrangement = ListArrangement(),
  val groups: List<RowGroup> = emptyList(),
  val total: Int = 0,
) {
  /** The visible rows in display order, those of collapsed groups included. */
  val rows: List<TaskRow> = groups.flatMap { it.rows }

  /** Keys of [rows]: the order selection ranges follow. */
  val keys: List<TaskKey> = rows.map { it.key }

  /** Number of rows that match the search, as in "12 of 340". */
  val matched: Int
    get() = rows.size
}

/**
 * The tasks of every device in [sources] as immutable [TaskRow]s, plus the tab, search, sort
 * order and grouping the Downloads list shows them in.
 *
 * Task changes are sampled every [UPDATE_INTERVAL] and turned into rows on [dispatcher], so a
 * thousand tasks with many of them downloading cost the UI at most four updates a second. While
 * anything downloads the model also wakes once a second, to catch stalls and to sample each
 * downloading task's speed into [TaskRow.speedSamples]; otherwise once a minute, for countdowns
 * and dates. It also wakes once a second while [view] holds an order that a re-sort may change,
 * so a held list re-sorts even when no task changes.
 *
 * @param sources the devices whose tasks to show.
 * @param scope runs the model until it is cancelled.
 * @param filter the status tab.
 * @param query what is typed in the search field, parsed with [SearchQuery.parse].
 * @param arrangement sort order and grouping of the tab.
 * @param frozen whether the pointer is over the list, a row has focus or a menu is open, which
 *   holds the order still (see [StableArrangement]).
 * @param clock current time, for stalls, countdowns and dates.
 * @param timeZone zone of dates and times.
 * @param dispatcher where rows are built and arranged.
 */
@OptIn(ExperimentalCoroutinesApi::class, FlowPreview::class)
class TaskListModel(
  sources: Flow<List<TaskListSource>>,
  scope: CoroutineScope,
  filter: Flow<StatusFilter> = flowOf(StatusFilter.All),
  query: Flow<String> = flowOf(""),
  arrangement: Flow<ListArrangement> = flowOf(ListArrangement()),
  frozen: Flow<Boolean> = flowOf(false),
  private val clock: Clock = Clock.System,
  private val timeZone: () -> TimeZone = { TimeZone.currentSystemDefault() },
  dispatcher: CoroutineDispatcher = Dispatchers.Default,
) {
  // Only the rows pipeline below touches this state, one frame at a time.
  private val entries = HashMap<TaskKey, Entry>()
  private val moved = HashMap<TaskKey, Moved>()
  private val rings = HashMap<TaskKey, SpeedRing>()
  private val contexts = HashMap<String, RowContext>()
  private var lastRows: List<TaskRow> = emptyList()
  private val downloading = MutableStateFlow(false)
  private var ticks = 0L
  private var sampledTick = 0L

  // Only the view pipeline touches the arranger; it asks for wake-ups through resortPending.
  private val arranger = StableArrangement()
  private val resortPending = MutableStateFlow(false)
  private val resorts = MutableStateFlow(0)

  /** Every task of every device, in device order, unfiltered. */
  val rows: StateFlow<List<TaskRow>> =
    combine(sources.flatMapLatest(::devices), ticks()) { devices, tick -> Frame(devices, tick) }
      .sample(UPDATE_INTERVAL)
      .map(::build)
      .flowOn(dispatcher)
      .stateIn(scope, SharingStarted.Eagerly, emptyList())

  /** Number of tasks on each status tab. */
  val counts: StateFlow<Map<StatusFilter, Int>> =
    rows.map { list -> StatusFilter.counts(list.map { it.state }) }
      .flowOn(dispatcher)
      .stateIn(scope, SharingStarted.Eagerly, StatusFilter.entries.associateWith { 0 })

  /** The rows of the current tab that match the search, grouped and sorted. */
  val view: StateFlow<TaskListView> =
    combine(
      combine(rows, resorts) { list, _ -> list },
      filter,
      query.map(SearchQuery::parse).distinctUntilChanged(),
      arrangement,
      frozen
    ) { list, tab, search, order, holding ->
      val now = clock.now()
      val zone = timeZone()
      val onTab = list.filter { tab.matches(it.state) }
      val matching = if (search.isEmpty) onTab else onTab.filter { search.matches(it, now, zone) }
      val groups = arranger.arrange(
        rows = matching,
        arrangement = order,
        now = now,
        timeZone = zone,
        frozen = holding,
        view = Triple(tab, search, order),
      )
      resortPending.value = arranger.isHeld && !holding
      TaskListView(tab, search, order, groups, onTab.size)
    }
      .flowOn(dispatcher)
      .stateIn(scope, SharingStarted.Eagerly, TaskListView())

  /** The row of the task [key], if it is listed. */
  fun row(key: TaskKey): TaskRow? = rows.value.firstOrNull { it.key == key }

  private fun devices(sources: List<TaskListSource>): Flow<List<DeviceSnapshot>> {
    if (sources.isEmpty()) return flowOf(emptyList())
    return combine(sources.map(::device)) { it.toList() }
  }

  private fun device(source: TaskListSource): Flow<DeviceSnapshot> {
    val tasks = source.tasks.flatMapLatest(::tasks)
    return combine(tasks, source.config, source.slowLane) { snapshots, config, slowLane ->
      DeviceSnapshot(source, snapshots, config, slowLane)
    }
  }

  private fun tasks(tasks: List<DownloadTask>): Flow<List<TaskSnapshot>> {
    if (tasks.isEmpty()) return flowOf(emptyList())
    return combine(
      tasks.map { task ->
        combine(task.requestState, task.state, task.segments) { request, state, segments ->
          TaskSnapshot(task, request, state, segments)
        }
      }
    ) { it.toList() }
  }

  /**
   * Wakes the model once a second while anything downloads or a held order waits to re-sort,
   * otherwise at each minute. The first tick comes at once, so the first rows need not wait for
   * it.
   */
  private fun ticks(): Flow<Long> =
    combine(downloading, resortPending) { active, pending -> active || pending }
      .distinctUntilChanged()
      .flatMapLatest { active ->
        flow {
          if (!active) emit(++ticks)
          while (true) {
            delay(if (active) SAMPLE_INTERVAL else untilNextMinute())
            emit(++ticks)
          }
        }
      }

  private fun untilNextMinute(): Duration {
    val millis = clock.now().toEpochMilliseconds()
    return (MINUTE_MILLIS - millis.mod(MINUTE_MILLIS)).milliseconds
  }

  private fun build(frame: Frame): List<TaskRow> {
    val now = clock.now()
    val zone = timeZone()
    val millis = now.toEpochMilliseconds()
    // Countdowns and dates change by the minute; a finer clock would rebuild every row.
    val minute = Instant.fromEpochMilliseconds(millis - millis.mod(MINUTE_MILLIS))
    val sample = frame.tick != sampledTick
    sampledTick = frame.tick
    val rows = ArrayList<TaskRow>()
    val seen = HashSet<TaskKey>()
    for (device in frame.devices) {
      val context = contextOf(device, minute, zone)
      for (snapshot in device.tasks) {
        val key = TaskKey(device.source.deviceId, snapshot.task.taskId)
        if (seen.add(key)) rows += rowOf(key, snapshot, context, now, sample)
      }
    }
    entries.keys.retainAll(seen)
    moved.keys.retainAll(seen)
    rings.keys.retainAll(seen)
    contexts.keys.retainAll(frame.devices.mapTo(HashSet()) { it.source.deviceId })
    downloading.value = rows.any { it.state is DownloadState.Downloading }
    // Unchanged rows never reach the view, so wake it to re-sort the order it holds.
    if (resortPending.value && rows == lastRows) resorts.value += 1
    lastRows = rows
    return rows
  }

  /** The device's row context, the same instance while nothing in it changes. */
  private fun contextOf(device: DeviceSnapshot, now: Instant, zone: TimeZone): RowContext {
    val running = device.tasks.filter { it.state is DownloadState.Downloading }.map { it.request }
    val context = RowContext(
      device = device.source.device,
      now = now,
      timeZone = zone,
      config = device.config,
      running = running,
      slowLane = device.slowLane,
    )
    val cached = contexts[device.source.deviceId]
    if (cached == context) return cached
    contexts[device.source.deviceId] = context
    return context
  }

  private fun rowOf(
    key: TaskKey,
    snapshot: TaskSnapshot,
    context: RowContext,
    now: Instant,
    sample: Boolean,
  ): TaskRow {
    val stalledFor = stalledFor(key, snapshot.state, now)
    val samples = speedSamples(key, snapshot.state, sample)
    val entry = entries[key]
    if (entry != null && entry.isFor(snapshot, context, samples, stalledFor)) return entry.row
    val task = snapshot.task
    val built = TaskRow(
      key = key,
      task = task,
      request = snapshot.request,
      state = snapshot.state,
      segments = snapshot.segments,
      createdAt = task.createdAt,
      device = context.device,
      content = rowContent(
        request = snapshot.request,
        state = snapshot.state,
        createdAt = task.createdAt,
        context = context,
        segments = snapshot.segments,
        stalledFor = stalledFor,
      ),
      speedSamples = samples,
    )
    val row = if (entry != null && entry.row == built) entry.row else built
    entries[key] = Entry(snapshot, context, samples, stalledFor, row)
    return row
  }

  /**
   * How long a downloading task has received no data, in whole seconds, once that passes
   * [STALL_THRESHOLD]; `null` otherwise.
   */
  private fun stalledFor(key: TaskKey, state: DownloadState, now: Instant): Duration? {
    if (state !is DownloadState.Downloading) {
      moved.remove(key)
      return null
    }
    val bytes = state.progress.downloadedBytes
    val last = moved[key]
    if (last == null || last.bytes != bytes) {
      moved[key] = Moved(bytes, now)
      return null
    }
    return (now - last.at).inWholeSeconds.seconds.takeIf { it > STALL_THRESHOLD }
  }

  private fun speedSamples(key: TaskKey, state: DownloadState, sample: Boolean): List<Long> {
    val ring = rings[key]
    if (!sample || state !is DownloadState.Downloading) return ring?.samples ?: emptyList()
    return (ring ?: SpeedRing().also { rings[key] = it }).add(state.progress.bytesPerSecond)
  }

  private class TaskSnapshot(
    val task: DownloadTask,
    val request: DownloadRequest,
    val state: DownloadState,
    val segments: List<Segment>,
  )

  private class DeviceSnapshot(
    val source: TaskListSource,
    val tasks: List<TaskSnapshot>,
    val config: DownloadConfig?,
    val slowLane: Boolean,
  )

  private class Frame(val devices: List<DeviceSnapshot>, val tick: Long)

  private class Moved(val bytes: Long, val at: Instant)

  /** What a row was built from, compared by identity to skip rebuilding it. */
  private class Entry(
    val snapshot: TaskSnapshot,
    val context: RowContext,
    val samples: List<Long>,
    val stalledFor: Duration?,
    val row: TaskRow,
  ) {
    fun isFor(
      other: TaskSnapshot,
      context: RowContext,
      samples: List<Long>,
      stalledFor: Duration?,
    ): Boolean = snapshot.task === other.task && snapshot.request === other.request &&
      snapshot.state === other.state && snapshot.segments === other.segments &&
      this.context === context && this.samples === samples && this.stalledFor == stalledFor
  }

  private class SpeedRing {
    private val values = ArrayDeque<Long>(SPEED_SAMPLES)

    var samples: List<Long> = emptyList()
      private set

    fun add(value: Long): List<Long> {
      if (values.size == SPEED_SAMPLES) values.removeFirst()
      values.addLast(value)
      samples = values.toList()
      return samples
    }
  }

  companion object {
    /** Shortest time between two updates of [rows]. */
    val UPDATE_INTERVAL: Duration = 250.milliseconds

    /** Time between two speed samples of a downloading task. */
    val SAMPLE_INTERVAL: Duration = 1.seconds

    /** Number of speed samples kept per task. */
    const val SPEED_SAMPLES: Int = 60

    private const val MINUTE_MILLIS = 60_000L
  }
}
