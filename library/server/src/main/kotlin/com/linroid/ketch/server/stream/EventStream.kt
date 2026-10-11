package com.linroid.ketch.server.stream

import com.linroid.ketch.api.ActiveConnections
import com.linroid.ketch.api.DownloadState
import com.linroid.ketch.endpoints.model.TasksResponse
import com.linroid.ketch.endpoints.stream.StreamEvents
import com.linroid.ketch.endpoints.stream.StreamHello
import com.linroid.ketch.endpoints.stream.StreamStats
import com.linroid.ketch.endpoints.stream.StreamSubscribed
import com.linroid.ketch.endpoints.stream.StreamSubscription
import com.linroid.ketch.endpoints.stream.StreamTaskRef
import com.linroid.ketch.endpoints.stream.TaskInterval
import com.linroid.ketch.server.api.ServerJson
import com.linroid.ketch.torrent.api.TorrentSnapshot
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.ComparableTimeMark
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.TimeSource

/** An event as a stream sends it: its name, its JSON and its `id`, which counts the events. */
internal data class OutgoingEvent(val event: String, val data: String, val id: String)

/** Thrown for a subscription to a stream that is closed or not the caller's. */
internal class UnknownStreamException : Exception("No such event stream")

/**
 * One client's event stream. Changes arrive in an inbox (task IDs, the latest subscription, the
 * latest torrent and connection items); a single writer, [run], turns them into events, so the
 * order of events is the order the writer chose:
 *
 * 1. `subscribed`, then snapshots of the topics it adds;
 * 2. task lifecycle and state changes, at once;
 * 3. torrent and connection items;
 * 4. progress and segments whose interval is due, then stats.
 *
 * The writer remembers the versions it sent of each task and sends the difference to the
 * current [TaskFrame], so a client that reads slowly skips intermediate progress rather than
 * queueing it.
 */
internal class EventStream(
  val id: String,
  val owner: String?,
  private val tracker: TaskTracker,
  private val torrents: SharedSource<String, TorrentSnapshot>,
  private val connections: SharedSource<Int, ActiveConnections>,
  private val timeSource: TimeSource.WithComparableMarks,
  private val scope: CoroutineScope,
  private val onClose: (EventStream) -> Unit,
) {
  private val lock = Any()
  private val wake = Channel<Unit>(Channel.CONFLATED)

  // The inbox, guarded by lock.
  private var applied = StreamSubscription()
  private var newSubscription: StreamSubscription? = applied
  private val dirty = LinkedHashSet<String>()
  private val torrentJobs = HashMap<String, Job>()
  private val torrentInbox = LinkedHashMap<String, SourceItem<TorrentSnapshot>>()
  private var connectionsJob: Job? = null
  private var connectionsInbox: SourceItem<ActiveConnections>? = null
  private var closed = false

  // The writer's own state.
  private var subscription: StreamSubscription? = null
  private var progressRules = IntervalRules.NONE
  private var segmentsRules = IntervalRules.NONE
  private val sent = HashMap<String, SentTask>()
  private val pendingProgress = HashMap<Long, LinkedHashSet<String>>()
  private val pendingSegments = HashMap<Long, LinkedHashSet<String>>()
  private val progressFlushed = HashMap<Long, ComparableTimeMark>()
  private val segmentsFlushed = HashMap<Long, ComparableTimeMark>()
  private var statsSentAt: ComparableTimeMark? = null
  private var lastStats: StreamStats? = null
  private var sequence = 0L

  private val listener: (String) -> Unit = ::taskChanged

  init {
    tracker.addListener(listener)
  }

  /**
   * Applies [next], already validated, unless its revision is not above the applied one.
   * Returns the subscription that applies.
   */
  fun apply(next: StreamSubscription): StreamSubscribed = synchronized(lock) {
    if (closed) throw UnknownStreamException()
    if (next.revision > applied.revision) {
      val previousLimit = applied.connectionsLimit
      applied = next
      newSubscription = next
      followTorrents(next.torrents.toSet())
      if (next.connectionsLimit != previousLimit) followConnections(next.connectionsLimit)
      wake.trySend(Unit)
    }
    StreamSubscribed(applied.revision, applied)
  }

  /** Sends the stream's events through [send] until it is closed or the caller is cancelled. */
  suspend fun run(send: suspend (OutgoingEvent) -> Unit) {
    try {
      send(event(StreamEvents.HELLO, encode(hello())))
      while (true) {
        process(takeInbox(), send)
        val wait = nextWait()
        val woken = when {
          wait == null -> wake.receiveCatching()
          wait.isPositive() -> withTimeoutOrNull(wait) { wake.receiveCatching() }
          else -> null
        }
        if (woken?.isClosed == true) return
      }
    } finally {
      close()
    }
  }

  /** Closes the stream; [run] returns. */
  fun close() {
    synchronized(lock) {
      if (closed) return
      closed = true
    }
    wake.close()
    tracker.removeListener(listener)
    scope.cancel()
    onClose(this)
  }

  private fun hello() = StreamHello(id, StreamEvents.PROTOCOL, StreamEvents.INTERVALS_MS)

  // -- Inbox --

  private fun taskChanged(taskId: String) {
    synchronized(lock) { dirty += taskId }
    wake.trySend(Unit)
  }

  private fun followTorrents(taskIds: Set<String>) {
    for (taskId in torrentJobs.keys - taskIds) {
      torrentJobs.remove(taskId)?.cancel()
      torrentInbox.remove(taskId)
    }
    for (taskId in taskIds - torrentJobs.keys) {
      torrentJobs[taskId] = scope.launch {
        torrents.subscribe(taskId).collect { item ->
          synchronized(lock) {
            if (taskId in torrentJobs) torrentInbox[taskId] = item
          }
          wake.trySend(Unit)
        }
      }
    }
  }

  private fun followConnections(limit: Int?) {
    connectionsJob?.cancel()
    connectionsJob = null
    connectionsInbox = null
    if (limit == null) return
    connectionsJob = scope.launch {
      connections.subscribe(limit).collect { item ->
        synchronized(lock) {
          // Items of a limit no longer asked for are dropped.
          if (applied.connectionsLimit == limit) connectionsInbox = item
        }
        wake.trySend(Unit)
      }
    }
  }

  private fun takeInbox(): Inbox = synchronized(lock) {
    val inbox = Inbox(
      subscription = newSubscription,
      taskIds = dirty.toList(),
      torrents = torrentInbox.toList(),
      connections = connectionsInbox,
    )
    newSubscription = null
    dirty.clear()
    torrentInbox.clear()
    connectionsInbox = null
    inbox
  }

  private class Inbox(
    val subscription: StreamSubscription?,
    val taskIds: List<String>,
    val torrents: List<Pair<String, SourceItem<TorrentSnapshot>>>,
    val connections: SourceItem<ActiveConnections>?,
  )

  // -- Writer --

  private suspend fun process(inbox: Inbox, send: suspend (OutgoingEvent) -> Unit) {
    inbox.subscription?.let { subscribe(it, send) }
    for (taskId in inbox.taskIds) processTask(taskId, send)
    val current = subscription ?: return
    for ((taskId, item) in inbox.torrents) {
      if (taskId in current.torrents) send(torrentEvent(taskId, item))
    }
    inbox.connections?.let { item ->
      if (current.connectionsLimit != null) connectionsEvent(item)?.let { send(it) }
    }
    flushDue(send)
  }

  private suspend fun subscribe(next: StreamSubscription, send: suspend (OutgoingEvent) -> Unit) {
    val previous = subscription
    val previousProgress = progressRules
    val previousSegments = segmentsRules
    subscription = next
    progressRules = IntervalRules.of(next.progress)
    segmentsRules = IntervalRules.of(next.segments)
    send(event(StreamEvents.SUBSCRIBED, encode(StreamSubscribed(next.revision, next))))
    if (next.tasks && previous?.tasks != true) sendTasks(send)
    if (next.statsIntervalMs != previous?.statsIntervalMs) {
      statsSentAt = null
      lastStats = null
    }
    // Rules may have moved tasks between intervals; tasks they newly name are sent at once.
    pendingProgress.clear()
    pendingSegments.clear()
    for ((taskId, known) in sent) {
      val frame = tracker.frame(taskId) ?: continue
      if (hasNewProgress(frame, known)) {
        val step = progressRules.step(taskId)
        when {
          step == null -> {}
          previousProgress.step(taskId) == null -> sendProgress(frame, known, send)
          else -> pendingProgress.getOrPut(step) { LinkedHashSet() } += taskId
        }
      }
      if (frame.segmentsVersion > known.segmentsVersion) {
        val step = segmentsRules.step(taskId)
        when {
          step == null -> {}
          previousSegments.step(taskId) == null -> sendSegments(frame, known, send)
          else -> pendingSegments.getOrPut(step) { LinkedHashSet() } += taskId
        }
      }
    }
  }

  private suspend fun sendTasks(send: suspend (OutgoingEvent) -> Unit) {
    val frames = tracker.frames()
    sent.clear()
    for (frame in frames) sent[frame.taskId] = SentTask.of(frame)
    send(event(StreamEvents.TASKS, encode(TasksResponse(frames.map { it.snapshot }))))
  }

  private suspend fun processTask(taskId: String, send: suspend (OutgoingEvent) -> Unit) {
    val tasksOn = subscription?.tasks == true
    val frame = tracker.frame(taskId)
    if (frame == null) {
      if (sent.remove(taskId) != null) {
        pendingProgress.values.forEach { it -= taskId }
        pendingSegments.values.forEach { it -= taskId }
        if (tasksOn) send(event(StreamEvents.TASK_REMOVED, encode(StreamTaskRef(taskId))))
      }
      return
    }
    val known = sent[taskId]
    val current = when {
      known == null && tasksOn -> {
        send(event(StreamEvents.TASK_ADDED, frame.addedJson))
        SentTask.of(frame).also { sent[taskId] = it }
      }
      // Without the lifecycle, the client learns of the task from its progress or segments.
      known == null -> SentTask(frame.stateVersion, frame.requestVersion, 0, 0)
        .also { sent[taskId] = it }
      else -> known
    }
    if (tasksOn && frame.stateVersion > current.stateVersion) {
      val withRequest = frame.requestVersion > current.requestVersion
      val json = if (withRequest) frame.stateJson else frame.stateOnlyJson
      send(event(StreamEvents.TASK_STATE, json))
      current.stateVersion = frame.stateVersion
      current.requestVersion = frame.requestVersion
      current.progressVersion = frame.progressVersion
    }
    if (hasNewProgress(frame, current)) {
      progressRules.step(taskId)?.let { pendingProgress.getOrPut(it) { LinkedHashSet() } += taskId }
    }
    if (frame.segmentsVersion > current.segmentsVersion) {
      segmentsRules.step(taskId)?.let { pendingSegments.getOrPut(it) { LinkedHashSet() } += taskId }
    }
  }

  private fun hasNewProgress(frame: TaskFrame, known: SentTask): Boolean =
    frame.state is DownloadState.Downloading && frame.progressVersion > known.progressVersion

  private suspend fun sendProgress(
    frame: TaskFrame,
    known: SentTask,
    send: suspend (OutgoingEvent) -> Unit,
  ) {
    if (!hasNewProgress(frame, known)) return
    // A state change the client has not had yet carries the progress, and comes first.
    if (subscription?.tasks == true && frame.stateVersion > known.stateVersion) return
    send(event(StreamEvents.TASK_PROGRESS, frame.progressJson))
    known.progressVersion = frame.progressVersion
  }

  private suspend fun sendSegments(
    frame: TaskFrame,
    known: SentTask,
    send: suspend (OutgoingEvent) -> Unit,
  ) {
    if (frame.segmentsVersion <= known.segmentsVersion) return
    send(event(StreamEvents.TASK_SEGMENTS, frame.segmentsJson))
    known.segmentsVersion = frame.segmentsVersion
  }

  private suspend fun flushDue(send: suspend (OutgoingEvent) -> Unit) {
    val now = timeSource.markNow()
    flush(pendingProgress, progressFlushed, now) { frame, known ->
      sendProgress(frame, known, send)
    }
    flush(pendingSegments, segmentsFlushed, now) { frame, known ->
      sendSegments(frame, known, send)
    }
    val interval = subscription?.statsIntervalMs ?: return
    val sentAt = statsSentAt
    if (sentAt != null && now - sentAt < interval.milliseconds) return
    statsSentAt = now
    val stats = statsOf(tracker.frames())
    if (stats != lastStats) {
      lastStats = stats
      send(event(StreamEvents.STATS, encode(stats)))
    }
  }

  /**
   * Sends the pending tasks of each step whose interval passed since it last sent: the first
   * change after a quiet interval goes out at once, later ones at most once per interval.
   */
  private suspend fun flush(
    pending: HashMap<Long, LinkedHashSet<String>>,
    flushed: HashMap<Long, ComparableTimeMark>,
    now: ComparableTimeMark,
    sendOne: suspend (TaskFrame, SentTask) -> Unit,
  ) {
    for ((step, taskIds) in pending) {
      if (taskIds.isEmpty()) continue
      val last = flushed[step]
      if (last != null && now - last < step.milliseconds) continue
      flushed[step] = now
      val batch = taskIds.toList()
      taskIds.clear()
      for (taskId in batch) {
        val frame = tracker.frame(taskId) ?: continue
        val known = sent[taskId] ?: continue
        sendOne(frame, known)
      }
    }
  }

  /** How long the writer may wait for the inbox before an interval is due; `null` for ever. */
  private fun nextWait(): Duration? {
    var wait: Duration? = null
    fun consider(due: Duration) {
      wait = wait?.coerceAtMost(due) ?: due
    }
    for ((step, taskIds) in pendingProgress) {
      if (taskIds.isNotEmpty()) consider(dueIn(progressFlushed[step], step))
    }
    for ((step, taskIds) in pendingSegments) {
      if (taskIds.isNotEmpty()) consider(dueIn(segmentsFlushed[step], step))
    }
    subscription?.statsIntervalMs?.let { consider(dueIn(statsSentAt, it)) }
    return wait
  }

  private fun dueIn(last: ComparableTimeMark?, intervalMs: Long): Duration =
    if (last == null) Duration.ZERO else intervalMs.milliseconds - last.elapsedNow()

  private fun torrentEvent(taskId: String, item: SourceItem<TorrentSnapshot>) = when (item) {
    is SourceItem.Value -> event(StreamEvents.TORRENT, encode(item.value))
    SourceItem.Removed -> event(StreamEvents.TORRENT_REMOVED, encode(StreamTaskRef(taskId)))
    is SourceItem.Failed -> event(StreamEvents.ERROR, encode(item.error))
  }

  private fun connectionsEvent(item: SourceItem<ActiveConnections>) = when (item) {
    is SourceItem.Value -> event(StreamEvents.CONNECTIONS, encode(item.value))
    // Live connections have no subject that could go away.
    SourceItem.Removed -> null
    is SourceItem.Failed -> event(StreamEvents.ERROR, encode(item.error))
  }

  private fun event(name: String, data: String) = OutgoingEvent(name, data, (++sequence).toString())

  private inline fun <reified T> encode(value: T): String = ServerJson.encodeToString(value)

  /** The versions of a task this stream sent. */
  private class SentTask(
    var stateVersion: Long,
    var requestVersion: Long,
    var progressVersion: Long,
    var segmentsVersion: Long,
  ) {
    companion object {
      fun of(frame: TaskFrame) = SentTask(
        frame.stateVersion,
        frame.requestVersion,
        frame.progressVersion,
        frame.segmentsVersion,
      )
    }
  }
}

/** The interval step of each task under a list of [TaskInterval] rules; the shortest wins. */
internal class IntervalRules private constructor(
  private val everyTask: Long?,
  private val byTask: Map<String, Long>,
) {
  /** The interval of [taskId] in milliseconds, or `null` when no rule names it. */
  fun step(taskId: String): Long? {
    val own = byTask[taskId] ?: return everyTask
    return if (everyTask == null) own else minOf(own, everyTask)
  }

  companion object {
    val NONE = IntervalRules(null, emptyMap())

    fun of(rules: List<TaskInterval>): IntervalRules {
      var everyTask: Long? = null
      val byTask = HashMap<String, Long>()
      for (rule in rules) {
        val taskIds = rule.taskIds
        if (taskIds == null) {
          everyTask = minOf(everyTask ?: rule.intervalMs, rule.intervalMs)
        } else {
          for (taskId in taskIds) byTask.merge(taskId, rule.intervalMs, ::minOf)
        }
      }
      return IntervalRules(everyTask, byTask)
    }
  }
}

/** [StreamStats] of the tasks that are downloading among [frames]. */
internal fun statsOf(frames: List<TaskFrame>): StreamStats {
  var speed = 0L
  var downloaded = 0L
  var total = 0L
  var remaining = 0L
  var downloading = 0
  var unknownSizes = 0
  for (frame in frames) {
    val progress = (frame.state as? DownloadState.Downloading)?.progress ?: continue
    downloading++
    speed += progress.bytesPerSecond
    if (progress.totalBytes > 0) {
      downloaded += progress.downloadedBytes
      total += progress.totalBytes
      remaining += (progress.totalBytes - progress.downloadedBytes).coerceAtLeast(0)
    } else {
      unknownSizes++
    }
  }
  return StreamStats(speed, downloaded, total, remaining, downloading, unknownSizes)
}
