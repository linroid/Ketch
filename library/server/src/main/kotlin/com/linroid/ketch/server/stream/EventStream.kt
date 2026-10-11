package com.linroid.ketch.server.stream

import com.linroid.ketch.api.DownloadState
import com.linroid.ketch.api.Interest
import com.linroid.ketch.endpoints.model.TasksResponse
import com.linroid.ketch.endpoints.stream.InterestError
import com.linroid.ketch.endpoints.stream.InterestRef
import com.linroid.ketch.endpoints.stream.StreamEvents
import com.linroid.ketch.endpoints.stream.StreamHello
import com.linroid.ketch.endpoints.stream.StreamSubscribed
import com.linroid.ketch.endpoints.stream.StreamSubscription
import com.linroid.ketch.endpoints.stream.StreamTaskRef
import com.linroid.ketch.endpoints.stream.UnknownInterest
import com.linroid.ketch.server.api.ServerJson
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
 * latest state of each interest); a single writer, [run], turns them into events, so the order of
 * events is the order the writer chose:
 *
 * 1. `subscribed`, then the snapshot of every task after the first, and the current progress
 *    and segments of tasks it newly asks for;
 * 2. task lifecycle and state changes, at once;
 * 3. progress, segments and interest values whose interval is due; an interest's end or failure
 *    never waits, and sends the value it held back first.
 *
 * The writer remembers the versions it sent of each task and interest and sends the difference
 * to the current [TaskFrame] or [TopicState], so a client that reads slowly skips intermediate
 * values rather than queueing them.
 */
internal class EventStream(
  val id: String,
  val owner: String?,
  private val tracker: TaskTracker,
  private val sources: SharedSource<Interest<*>>,
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
  private val follows = HashMap<Long, Follow>()
  private val topicInbox = LinkedHashMap<Long, TopicState>()
  private var closed = false

  // The writer's own state.
  private var subscription: StreamSubscription? = null
  private var plan = StreamPlan()
  private val sent = HashMap<String, SentTask>()
  private val pendingProgress = HashMap<Long, LinkedHashSet<String>>()
  private val pendingSegments = HashMap<Long, LinkedHashSet<String>>()
  private val progressFlushed = HashMap<Long, ComparableTimeMark>()
  private val segmentsFlushed = HashMap<Long, ComparableTimeMark>()
  private val topics = HashMap<Long, SentTopic>()
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
        applied = next
        appliedPlan = nextPlan
        newSubscription = next to nextPlan
        followInterests(nextPlan.interests)
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

  /** Follows the [planned] interests, under [lock]: new ones start, dropped ones stop. */
  private fun followInterests(planned: Map<Long, PlannedInterest>) {
    for (id in follows.keys - planned.keys) {
      follows.remove(id)?.job?.cancel()
      topicInbox.remove(id)
    }
    for ((id, next) in planned) {
      val current = follows[id]
      if (current?.interest == next.interest) continue
      // An ID given to another interest starts over.
      current?.job?.cancel()
      topicInbox.remove(id)
      val follow = Follow(next.interest)
      follows[id] = follow
      if (next.interest is UnknownInterest) {
        val message = "This server does not know the interest"
        topicInbox[id] = TopicState(end = TopicEnd.Failed(StreamEvents.UNSUPPORTED, message))
        continue
      }
      follow.job = scope.launch {
        sources.subscribe(next.interest).collect { state ->
          synchronized(lock) {
            if (follows[id] === follow) topicInbox[id] = state
          }
          wake.trySend(Unit)
        }
      }
    }
  }

  private fun takeInbox(): Inbox = synchronized(lock) {
    val inbox = Inbox(
      subscription = newSubscription,
      taskIds = dirty.toList(),
      topics = topicInbox.toList(),
    )
    newSubscription = null
    dirty.clear()
    topicInbox.clear()
    inbox
  }

  private class Inbox(
    val subscription: Pair<StreamSubscription, StreamPlan>?,
    val taskIds: List<String>,
    val topics: List<Pair<Long, TopicState>>,
  )

  /** An interest the inbox follows; [job] collects its states, none for an unknown interest. */
  private class Follow(val interest: Interest<*>) {
    var job: Job? = null
  }

  // -- Writer --

  private suspend fun process(inbox: Inbox, send: suspend (OutgoingEvent) -> Unit) {
    inbox.subscription?.let { (next, nextPlan) -> subscribe(next, nextPlan, send) }
    for (taskId in inbox.taskIds) processTask(taskId, send)
    for ((id, state) in inbox.topics) topics[id]?.state = state
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
    // Nothing more goes out for dropped interests; new ones send their first value at once.
    topics.keys.retainAll(nextPlan.interests.keys)
    for ((id, planned) in nextPlan.interests) {
      if (topics[id]?.interest != planned.interest) topics[id] = SentTopic(planned.interest)
    }
    // Rules may have moved tasks between intervals; tasks they newly name are sent at once, and
    // their step then waits its interval, as after any flush.
    pendingProgress.clear()
    pendingSegments.clear()
    val now = timeSource.markNow()
    for ((taskId, known) in sent) {
      val frame = tracker.frame(taskId) ?: continue
      if (hasNewProgress(frame, known)) {
        val step = plan.progress.step(taskId)
        when {
          step == null -> {}
          previous.progress.step(taskId) == null -> {
            sendProgress(frame, known, send)
            if (known.progressVersion == frame.progressVersion) progressFlushed[step] = now
          }
          else -> pendingProgress.getOrPut(step) { LinkedHashSet() } += taskId
        }
      }
      if (frame.segmentsVersion > known.segmentsVersion) {
        val step = plan.segments.step(taskId)
        when {
          step == null -> {}
          previous.segments.step(taskId) == null -> {
            sendSegments(frame, known, send)
            segmentsFlushed[step] = now
          }
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
    flushTopics(now, send)
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

  /**
   * Sends each interest's new value once its interval passed since the last, and its end or
   * failure at once, after the value it held back.
   */
  private suspend fun flushTopics(
    now: ComparableTimeMark,
    send: suspend (OutgoingEvent) -> Unit,
  ) {
    for ((id, topic) in topics) {
      val state = topic.state ?: continue
      if (topic.ended) continue
      val step = plan.interests[id]?.step ?: continue
      val end = state.end
      if (state.version > topic.sentVersion) {
        val last = topic.sentAt
        if (end == null && last != null && now - last < step.milliseconds) continue
        val json = checkNotNull(state.json)
        send(event(StreamEvents.INTEREST, """{"id":$id,"value":$json}"""))
        topic.sentVersion = state.version
        topic.sentAt = now
      }
      when (end) {
        null -> {}
        TopicEnd.Completed -> send(event(StreamEvents.INTEREST_END, encode(InterestRef(id))))
        is TopicEnd.Failed -> {
          val error = InterestError(id, end.error, end.message)
          send(event(StreamEvents.INTEREST_ERROR, encode(error)))
        }
      }
      if (end != null) topic.ended = true
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
    for ((id, topic) in topics) {
      val state = topic.state ?: continue
      if (topic.ended || state.version <= topic.sentVersion) continue
      plan.interests[id]?.let { consider(dueIn(topic.sentAt, it.step)) }
    }
    return wait
  }

  private fun dueIn(last: ComparableTimeMark?, intervalMs: Long): Duration =
    if (last == null) Duration.ZERO else intervalMs.milliseconds - last.elapsedNow()

  private fun event(name: String, data: String) = OutgoingEvent(name, data, (++sequence).toString())

  private inline fun <reified T> encode(value: T): String = ServerJson.encodeToString(value)

  /** What this stream sent of an [interest]: the latest state it has, and the version it sent. */
  private class SentTopic(val interest: Interest<*>) {
    var state: TopicState? = null
    var sentVersion = 0L
    var sentAt: ComparableTimeMark? = null
    var ended = false
  }

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
