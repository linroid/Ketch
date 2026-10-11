package com.linroid.ketch.server.stream

import com.linroid.ketch.api.Interest
import com.linroid.ketch.endpoints.stream.StreamEvents
import com.linroid.ketch.endpoints.stream.StreamSubscription

/**
 * What a stream sends beyond the task lifecycle, from a [StreamSubscription] by [planOf].
 *
 * @property progress the interval step of each task's progress
 * @property segments the interval step of each named task's segments
 * @property interests the interests followed, by the ID the client gave each
 */
internal class StreamPlan(
  val progress: IntervalRules = IntervalRules.NONE,
  val segments: IntervalRules = IntervalRules.NONE,
  val interests: Map<Long, PlannedInterest> = emptyMap(),
)

/** An [interest] a stream follows, sent at most once per [step] milliseconds. */
internal data class PlannedInterest(val interest: Interest<*>, val step: Long)

/**
 * The plan [subscription] asks for: intervals are rounded up to the server's steps, and where
 * several segment entries name a task, the shortest interval wins.
 *
 * @throws IllegalArgumentException when [subscription] goes beyond the limits of [StreamEvents]
 *   or names an interest ID twice
 */
internal fun planOf(subscription: StreamSubscription): StreamPlan {
  require(subscription.revision >= 0) { "revision must not be negative" }
  require(subscription.interests.size <= StreamEvents.MAX_INTERESTS) {
    "At most ${StreamEvents.MAX_INTERESTS} interests are allowed"
  }
  require(subscription.segments.size <= StreamEvents.MAX_INTERESTS) {
    "At most ${StreamEvents.MAX_INTERESTS} segment entries are allowed"
  }
  val progress = subscription.progressIntervalMs?.let { interval ->
    require(interval > 0) { "progressIntervalMs must be positive" }
    StreamEvents.intervalStep(interval)
  }
  val segments = HashMap<String, Long>()
  var segmentIds = 0
  for ((taskIds, intervalMs) in subscription.segments) {
    val step = StreamEvents.intervalStep(intervalMs)
    segmentIds += taskIds.size
    for (taskId in taskIds) segments.merge(taskId, step, ::minOf)
  }
  require(segmentIds <= StreamEvents.MAX_TASK_IDS) {
    "At most ${StreamEvents.MAX_TASK_IDS} task IDs are allowed"
  }
  val interests = LinkedHashMap<Long, PlannedInterest>()
  for ((id, interest, intervalMs) in subscription.interests) {
    val planned = PlannedInterest(interest, StreamEvents.intervalStep(intervalMs))
    require(interests.put(id, planned) == null) { "Interest ID $id is used twice" }
  }
  return StreamPlan(
    progress = IntervalRules.everyTask(progress),
    segments = IntervalRules.byTask(segments),
    interests = interests,
  )
}

/** The interval step of each task: for every task, or the shortest an interest names it with. */
internal class IntervalRules private constructor(
  private val everyTask: Long?,
  private val byTask: Map<String, Long>,
) {
  /** The interval of [taskId] in milliseconds, or `null` when no interest names it. */
  fun step(taskId: String): Long? {
    val own = byTask[taskId] ?: return everyTask
    return if (everyTask == null) own else minOf(own, everyTask)
  }

  companion object {
    val NONE = IntervalRules(null, emptyMap())

    /** Every task at [step], or none when it is `null`. */
    fun everyTask(step: Long?): IntervalRules = IntervalRules(step, emptyMap())

    /** Each task of [steps] at its step. */
    fun byTask(steps: Map<String, Long>): IntervalRules = IntervalRules(null, steps)
  }
}
