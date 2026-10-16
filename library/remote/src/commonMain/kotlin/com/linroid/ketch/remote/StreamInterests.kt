package com.linroid.ketch.remote

import com.linroid.ketch.api.Interest
import com.linroid.ketch.endpoints.stream.StreamEvents
import com.linroid.ketch.endpoints.stream.StreamInterest
import com.linroid.ketch.endpoints.stream.StreamSegments
import com.linroid.ketch.endpoints.stream.StreamSubscription
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
  /** An interest a caller holds, about every [intervalMs]. */
  private class Held(val interest: Interest<*>, val intervalMs: Long)

  /**
   * The interests held, by hold; [ids] gives each distinct interest held an ID, kept while
   * anyone holds it and never given to another.
   */
  private class Registry(
    val nextHold: Long = 0,
    val held: Map<Long, Held> = emptyMap(),
    val nextId: Long = 1,
    val ids: Map<Interest<*>, Long> = emptyMap(),
  )

  private val registry = MutableStateFlow(Registry())

  /** How often every downloading task reports progress, or `null`. */
  val progress = MutableStateFlow(initialProgress)

  /** While `false`, the stream carries only the task lifecycle and state changes. */
  val active = MutableStateFlow(true)

  /** The subscription to send, with revision 0, each time it changes. */
  val subscriptions: Flow<StreamSubscription> =
    combine(registry, progress, active) { registry, progress, active ->
      if (active) {
        val held = registry.held.values.map { it.interest to it.intervalMs }
        subscriptionOf(held, registry.ids, progress)
      } else {
        StreamSubscription()
      }
    }.distinctUntilChanged()

  /**
   * Runs [block] with the ID of [interest] while it is followed about every [interval]; callers
   * holding an equal interest share its ID.
   */
  suspend fun <T> holding(
    interest: Interest<*>,
    interval: Duration,
    block: suspend (id: Long) -> T,
  ): T {
    val held = Held(interest, interval.inWholeMilliseconds.coerceAtLeast(1))
    var hold = 0L
    var id = 0L
    registry.update { current ->
      hold = current.nextHold
      val known = current.ids[interest]
      id = known ?: current.nextId
      Registry(
        nextHold = current.nextHold + 1,
        held = current.held + (hold to held),
        nextId = if (known == null) current.nextId + 1 else current.nextId,
        ids = if (known == null) current.ids + (interest to id) else current.ids,
      )
    }
    try {
      return block(id)
    } finally {
      registry.update { current ->
        val left = current.held - hold
        val stillHeld = left.values.any { it.interest == interest }
        Registry(
          nextHold = current.nextHold,
          held = left,
          nextId = current.nextId,
          ids = if (stillHeld) current.ids else current.ids - interest,
        )
      }
    }
  }
}

/**
 * The subscription that serves [held] interests, each with its interval in milliseconds and its
 * ID from [ids], with every downloading task's progress every [progress], within the server's
 * limits. Segments are merged by interval step, each task at its shortest; other interests held
 * by several callers are followed once, at the shortest interval. Beyond the limits the latest
 * are dropped.
 */
internal fun subscriptionOf(
  held: Collection<Pair<Interest<*>, Long>>,
  ids: Map<Interest<*>, Long>,
  progress: Duration?,
): StreamSubscription {
  val segments = LinkedHashMap<String, Long>()
  val interests = LinkedHashMap<Interest<*>, Long>()
  for ((interest, intervalMs) in held) {
    val step = StreamEvents.intervalStep(intervalMs)
    if (interest is Interest.Segments) {
      for (taskId in interest.taskIds) segments.keepShortest(taskId, step)
    } else {
      interests.keepShortest(interest, step)
    }
  }
  return StreamSubscription(
    progressIntervalMs = progress?.let(::stepOf),
    segments = segments.entries.take(StreamEvents.MAX_TASK_IDS)
      .groupBy({ it.value }, { it.key })
      .entries.sortedBy { it.key }
      .map { (step, taskIds) -> StreamSegments(taskIds.toSet(), step) },
    interests = interests.entries.take(StreamEvents.MAX_INTERESTS)
      .map { (interest, step) -> StreamInterest(ids.getValue(interest), interest, step) },
  )
}

private fun stepOf(interval: Duration): Long =
  StreamEvents.intervalStep(interval.inWholeMilliseconds.coerceAtLeast(1))

/** Keeps [step] for [key] unless it already has a shorter one. */
private fun <K> MutableMap<K, Long>.keepShortest(key: K, step: Long) {
  this[key] = this[key]?.let { minOf(it, step) } ?: step
}
