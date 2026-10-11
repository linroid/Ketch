package com.linroid.ketch.remote

import com.linroid.ketch.api.Interest
import com.linroid.ketch.endpoints.stream.StreamEvents
import com.linroid.ketch.endpoints.stream.StreamInterest
import com.linroid.ketch.endpoints.stream.StreamSubscription
import com.linroid.ketch.torrent.api.TorrentInterest
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.update
import kotlin.time.Duration

/**
 * What the callers of one [RemoteKetch] want from its event stream. Callers hold an interest
 * while they run ([holding]); the stream sends the server the subscription each time it changes,
 * and again on every new connection.
 *
 * @param initialProgress how often every downloading task reports progress, or `null` for only
 *   with its state changes, until [progress] changes
 */
internal class StreamInterests(initialProgress: Duration?) {
  private class Registry(
    val nextId: Long = 0,
    val entries: Map<Long, StreamInterest> = emptyMap(),
  )

  private val registry = MutableStateFlow(Registry())

  /** How often every downloading task reports progress, or `null`. */
  val progress = MutableStateFlow(initialProgress)

  /** While `false`, the stream carries only the task lifecycle and state changes. */
  val active = MutableStateFlow(true)

  /** The subscription to send, with revision 0, each time it changes. */
  val subscriptions: Flow<StreamSubscription> =
    combine(registry, progress, active) { registry, progress, active ->
      if (active) subscriptionOf(registry.entries.values, progress) else StreamSubscription()
    }.distinctUntilChanged()

  /** Runs [block] while [interest] is followed about every [interval]. */
  suspend fun <T> holding(interest: Interest<*>, interval: Duration, block: suspend () -> T): T {
    val held = StreamInterest(interest, interval.inWholeMilliseconds.coerceAtLeast(1))
    var id = 0L
    registry.update { current ->
      id = current.nextId
      Registry(current.nextId + 1, current.entries + (id to held))
    }
    try {
      return block()
    } finally {
      registry.update { Registry(it.nextId, it.entries - id) }
    }
  }
}

/**
 * The subscription that serves [held] interests, with every downloading task's progress every
 * [progress], within the server's limits. Where several callers follow the same interest, the
 * shortest interval wins; segments are merged by interval step, each task at its shortest, the
 * shortest stats interval wins, and one connections interest asks for the largest limit at the
 * shortest interval. Beyond the limits the latest are dropped.
 */
internal fun subscriptionOf(
  held: Collection<StreamInterest>,
  progress: Duration?,
): StreamSubscription {
  val segments = LinkedHashMap<String, Long>()
  var stats: Long? = null
  var connectionsLimit: Int? = null
  var connectionsStep: Long? = null
  val torrents = LinkedHashMap<TorrentInterest, Long>()
  val others = LinkedHashMap<Interest<*>, Long>()
  for ((interest, intervalMs) in held) {
    val step = StreamEvents.intervalStep(intervalMs)
    when (interest) {
      is Interest.Segments -> for (taskId in interest.taskIds) segments.keepShortest(taskId, step)
      Interest.Stats -> stats = minOf(stats ?: step, step)
      is Interest.Connections -> {
        connectionsLimit = maxOf(connectionsLimit ?: interest.limit, interest.limit)
        connectionsStep = minOf(connectionsStep ?: step, step)
      }
      is TorrentInterest -> torrents.keepShortest(interest, step)
      else -> others.keepShortest(interest, step)
    }
  }
  val interests = buildList {
    segments.entries.take(StreamEvents.MAX_TASK_IDS)
      .groupBy({ it.value }, { it.key })
      .entries.sortedBy { it.key }
      .forEach { (step, taskIds) -> add(StreamInterest(Interest.Segments(taskIds.toSet()), step)) }
    stats?.let { add(StreamInterest(Interest.Stats, it)) }
    connectionsLimit?.let { limit ->
      add(StreamInterest(Interest.Connections(limit), checkNotNull(connectionsStep)))
    }
    torrents.entries.take(StreamEvents.MAX_TORRENTS)
      .forEach { (interest, step) -> add(StreamInterest(interest, step)) }
    others.forEach { (interest, step) -> add(StreamInterest(interest, step)) }
  }
  return StreamSubscription(
    progressIntervalMs = progress?.let(::stepOf),
    interests = interests.take(StreamEvents.MAX_INTERESTS),
  )
}

private fun stepOf(interval: Duration): Long =
  StreamEvents.intervalStep(interval.inWholeMilliseconds.coerceAtLeast(1))

/** Keeps [step] for [key] unless it already has a shorter one. */
private fun <K> MutableMap<K, Long>.keepShortest(key: K, step: Long) {
  this[key] = this[key]?.let { minOf(it, step) } ?: step
}
