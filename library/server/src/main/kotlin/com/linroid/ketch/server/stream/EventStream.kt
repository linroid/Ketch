package com.linroid.ketch.server.stream

import com.linroid.ketch.api.ActiveConnections
import com.linroid.ketch.api.DownloadState
import com.linroid.ketch.api.KetchStats
import com.linroid.ketch.endpoints.model.TasksResponse
import com.linroid.ketch.endpoints.stream.StreamEvents
import com.linroid.ketch.endpoints.stream.StreamHello
import com.linroid.ketch.endpoints.stream.StreamSubscribed
import com.linroid.ketch.endpoints.stream.StreamSubscription
import com.linroid.ketch.endpoints.stream.StreamTaskRef
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
 * 1. `subscribed`, then the snapshot of every task after the first, and the current progress
 *    and segments of tasks it newly asks for;
 * 2. task lifecycle and state changes, at once;
 * 3. progress, segments, torrent and connection items whose interval is due, then stats; a
 *    torrent's removal and a topic's failure never wait.
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
  private var appliedPlan = StreamPlan()
  private var newSubscription: Pair<StreamSubscription, StreamPlan>? = applied to appliedPlan
  private val dirty = LinkedHashSet<String>()
  private val torrentJobs = HashMap<String, Job>()
  private val torrentInbox = LinkedHashMap<String, SourceItem<TorrentSnapshot>>()
  private var connectionsJob: Job? = null
  private var connectionsInbox: SourceItem<ActiveConnections>? = null
  private var closed = false

  // The writer's own state.
  private var subscription: StreamSubscription? = null
  private var plan = StreamPlan()
  private val sent = HashMap<String, SentTask>()
  private val pendingProgress = HashMap<Long, LinkedHashSet<String>>()
  private val pendingSegments = HashMap<Long, LinkedHashSet<String>>()
  private val progressFlushed = HashMap<Long, ComparableTimeMark>()
  private val segmentsFlushed = HashMap<Long, ComparableTimeMark>()
  private val pendingTorrents = LinkedHashMap<String, SourceItem<TorrentSnapshot>>()
  private val torrentsSent = HashMap<String, ComparableTimeMark>()
  private var pendingConnections: SourceItem<ActiveConnections>? = null
  private var connectionsSentAt: ComparableTimeMark? = null
  private var statsSentAt: ComparableTimeMark? = null
  private var lastStats: KetchStats? = null
  private var sequence = 0L

  private val listener: (String) -> Unit = ::taskChanged

  init {
    tracker.addListener(listener)
  }

  /**
   * Applies [next], whose interests [nextPlan] merges, unless its revision is not above the
   * applied one. Returns the subscription that applies.
   */
  fun apply(next: StreamSubscription, nextPlan: StreamPlan): StreamSubscribed =
    synchronized(lock) {
      if (closed) throw UnknownStreamException()
      if (next.revision > applied.revision) {
        val previousLimit = appliedPlan.connectionsLimit
        applied = next
        appliedPlan = nextPlan
        newSubscription = next to nextPlan
        followTorrents(nextPlan.torrents.keys)
        if (nextPlan.connectionsLimit != previousLimit) {
          followConnections(nextPlan.connectionsLimit)
        }
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
          if (appliedPlan.connectionsLimit == limit) connectionsInbox = item
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
    val subscription: Pair<StreamSubscription, StreamPlan>?,
    val taskIds: List<String>,
    val torrents: List<Pair<String, SourceItem<TorrentSnapshot>>>,
    val connections: SourceItem<ActiveConnections>?,
  )

  // -- Writer --

  private suspend fun process(inbox: Inbox, send: suspend (OutgoingEvent) -> Unit) {
    inbox.subscription?.let { (next, nextPlan) -> subscribe(next, nextPlan, send) }
    for (taskId in inbox.taskIds) processTask(taskId, send)
    for ((taskId, item) in inbox.torrents) {
      if (taskId in plan.torrents) pendingTorrents[taskId] = item
    }
    inbox.connections?.let { item ->
      if (plan.connectionsLimit != null) pendingConnections = item
    }
    flushDue(send)
  }

  private suspend fun subscribe(
    next: StreamSubscription,
    nextPlan: StreamPlan,
    send: suspend (OutgoingEvent) -> Unit,
  ) {
    val first = subscription == null
    val previous = plan
    subscription = next
    plan = nextPlan
    send(event(StreamEvents.SUBSCRIBED, encode(StreamSubscribed(next.revision, next))))
    if (first) sendTasks(send)
    // A torrent followed again, or a new connection limit, sends its first item at once.
    pendingTorrents.keys.retainAll(nextPlan.torrents.keys)
    torrentsSent.keys.retainAll(nextPlan.torrents.keys)
    if (nextPlan.connectionsLimit != previous.connectionsLimit) {
      pendingConnections = null
      connectionsSentAt = null
    }
    if (nextPlan.statsIntervalMs != previous.statsIntervalMs) {
      statsSentAt = null
      lastStats = null
    }
    // Rules may have moved tasks between intervals; tasks they newly name are sent at once.
    pendingProgress.clear()
    pendingSegments.clear()
    for ((taskId, known) in sent) {
      val frame = tracker.frame(taskId) ?: continue
      if (hasNewProgress(frame, known)) {
        val step = plan.progress.step(taskId)
        when {
          step == null -> {}
          previous.progress.step(taskId) == null -> sendProgress(frame, known, send)
          else -> pendingProgress.getOrPut(step) { LinkedHashSet() } += taskId
        }
      }
      if (frame.segmentsVersion > known.segmentsVersion) {
        val step = plan.segments.step(taskId)
        when {
          step == null -> {}
          previous.segments.step(taskId) == null -> sendSegments(frame, known, send)
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
    // Before the first subscription, the task snapshot that follows it covers every task.
    if (subscription == null) return
    val frame = tracker.frame(taskId)
    if (frame == null) {
      if (sent.remove(taskId) != null) {
        pendingProgress.values.forEach { it -= taskId }
        pendingSegments.values.forEach { it -= taskId }
        send(event(StreamEvents.TASK_REMOVED, encode(StreamTaskRef(taskId))))
      }
      return
    }
    var current = sent[taskId]
    if (current == null) {
      send(event(StreamEvents.TASK_ADDED, frame.addedJson))
      current = SentTask.of(frame)
      sent[taskId] = current
    }
    if (frame.stateVersion > current.stateVersion) {
      val withRequest = frame.requestVersion > current.requestVersion
      val json = if (withRequest) frame.stateJson else frame.stateOnlyJson
      send(event(StreamEvents.TASK_STATE, json))
      current.stateVersion = frame.stateVersion
      current.requestVersion = frame.requestVersion
      current.progressVersion = frame.progressVersion
    }
    if (hasNewProgress(frame, current)) {
      plan.progress.step(taskId)?.let { pendingProgress.getOrPut(it) { LinkedHashSet() } += taskId }
    }
    if (frame.segmentsVersion > current.segmentsVersion) {
      plan.segments.step(taskId)?.let { pendingSegments.getOrPut(it) { LinkedHashSet() } += taskId }
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
    if (frame.stateVersion > known.stateVersion) return
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
    flushTorrents(now, send)
    flushConnections(now, send)
    val interval = plan.statsIntervalMs ?: return
    val sentAt = statsSentAt
    if (sentAt != null && now - sentAt < interval.milliseconds) return
    statsSentAt = now
    val stats = KetchStats.of(tracker.frames().map { it.state })
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

  private suspend fun flushTorrents(
    now: ComparableTimeMark,
    send: suspend (OutgoingEvent) -> Unit,
  ) {
    val due = pendingTorrents.entries.filter { (taskId, item) ->
      val last = torrentsSent[taskId]
      val step = plan.torrents[taskId] ?: return@filter false
      item !is SourceItem.Value || last == null || now - last >= step.milliseconds
    }
    for ((taskId, item) in due) {
      pendingTorrents.remove(taskId)
      torrentsSent[taskId] = now
      send(torrentEvent(taskId, item))
    }
  }

  private suspend fun flushConnections(
    now: ComparableTimeMark,
    send: suspend (OutgoingEvent) -> Unit,
  ) {
    val item = pendingConnections ?: return
    val last = connectionsSentAt
    val interval = plan.connectionsIntervalMs.milliseconds
    if (item is SourceItem.Value && last != null && now - last < interval) return
    pendingConnections = null
    connectionsSentAt = now
    connectionsEvent(item)?.let { send(it) }
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
    for (taskId in pendingTorrents.keys) {
      plan.torrents[taskId]?.let { consider(dueIn(torrentsSent[taskId], it)) }
    }
    if (pendingConnections != null) consider(dueIn(connectionsSentAt, plan.connectionsIntervalMs))
    plan.statsIntervalMs?.let { consider(dueIn(statsSentAt, it)) }
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
