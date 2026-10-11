package com.linroid.ketch.remote

import com.linroid.ketch.api.TaskInterest
import com.linroid.ketch.endpoints.stream.StreamEvents
import com.linroid.ketch.endpoints.stream.StreamSubscription
import com.linroid.ketch.endpoints.stream.TaskInterval
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlin.time.Duration

/** What one caller of a [RemoteKetch] wants from its event stream. */
internal sealed interface StreamInterest {
  data class Tasks(val interest: TaskInterest) : StreamInterest
  data class Stats(val interval: Duration) : StreamInterest
  data class Torrent(val taskId: String) : StreamInterest
  data class Connections(val limit: Int) : StreamInterest
}

/**
 * What the callers of one [RemoteKetch] want from its event stream, merged into one
 * subscription. Callers hold an interest while they run ([holding]); the stream sends the
 * merged subscription each time it changes, and again on every new connection.
 *
 * @param baseProgress how often every task reports progress whatever the callers ask, or `null`
 *   for only as they ask
 */
internal class StreamInterests(private val baseProgress: Duration?) {
  private class Registry(
    val nextId: Long = 0,
    val entries: Map<Long, StreamInterest> = emptyMap(),
  )

  private val registry = MutableStateFlow(Registry())

  /** The subscription the callers ask for, with revision 0, each time it changes. */
  val subscriptions: Flow<StreamSubscription> =
    registry.map { subscriptionOf(it.entries.values, baseProgress) }.distinctUntilChanged()

  /** Whether some caller holds [interest]. */
  fun isHeld(predicate: (StreamInterest) -> Boolean): Boolean =
    registry.value.entries.values.any(predicate)

  /** Runs [block] while [interest] is held. */
  suspend fun <T> holding(interest: StreamInterest, block: suspend () -> T): T {
    var id = 0L
    registry.update { current ->
      id = current.nextId
      Registry(current.nextId + 1, current.entries + (id to interest))
    }
    try {
      return block()
    } finally {
      registry.update { Registry(it.nextId, it.entries - id) }
    }
  }
}

/**
 * The subscription that serves every one of [interests], and the progress of every task every
 * [baseProgress]. Intervals are rounded up to the server's steps; task IDs a rule for every task
 * already covers as often are left out, and beyond the server's limits the latest are dropped.
 */
internal fun subscriptionOf(
  interests: Collection<StreamInterest>,
  baseProgress: Duration?,
): StreamSubscription {
  val progress = IntervalRules()
  val segments = IntervalRules()
  baseProgress?.let { progress.add(it, null) }
  var stats: Long? = null
  val torrents = LinkedHashSet<String>()
  var connections: Int? = null
  for (interest in interests) {
    when (interest) {
      is StreamInterest.Tasks -> {
        val taskIds = interest.interest.taskIds
        interest.interest.progressInterval?.let { progress.add(it, taskIds) }
        interest.interest.segmentsInterval?.let { segments.add(it, taskIds) }
      }
      is StreamInterest.Stats -> {
        val step = stepOf(interest.interval)
        stats = stats?.let { minOf(it, step) } ?: step
      }
      is StreamInterest.Torrent -> torrents += interest.taskId
      is StreamInterest.Connections -> {
        connections = connections?.let { maxOf(it, interest.limit) } ?: interest.limit
      }
    }
  }
  return StreamSubscription(
    revision = 0,
    tasks = true,
    progress = progress.rules(),
    segments = segments.rules(),
    statsIntervalMs = stats,
    torrents = torrents.take(StreamEvents.MAX_TORRENTS),
    connectionsLimit = connections,
  )
}

private fun stepOf(interval: Duration): Long =
  StreamEvents.intervalStep(interval.inWholeMilliseconds.coerceAtLeast(1))

/** Task IDs per interval step, and the fastest step that covers every task. */
private class IntervalRules {
  private var everyTask: Long? = null
  private val byStep = HashMap<Long, LinkedHashSet<String>>()

  fun add(interval: Duration, taskIds: Set<String>?) {
    val step = stepOf(interval)
    if (taskIds == null) {
      everyTask = everyTask?.let { minOf(it, step) } ?: step
    } else {
      byStep.getOrPut(step) { LinkedHashSet() } += taskIds
    }
  }

  fun rules(): List<TaskInterval> {
    val rules = ArrayList<TaskInterval>()
    var budget = StreamEvents.MAX_TASK_IDS
    val seen = HashSet<String>()
    for (step in byStep.keys.sorted()) {
      // Every task already reports this often, and an ID keeps its shortest step only.
      if (everyTask?.let { it <= step } == true) break
      val taskIds = byStep.getValue(step).filter { seen.add(it) }.take(budget)
      if (taskIds.isEmpty()) continue
      budget -= taskIds.size
      rules += TaskInterval(step, taskIds)
    }
    everyTask?.let { rules += TaskInterval(it, null) }
    return rules
  }
}
