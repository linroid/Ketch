package com.linroid.ketch.server.stream

import com.linroid.ketch.api.Interest
import com.linroid.ketch.endpoints.stream.StreamEvents
import com.linroid.ketch.endpoints.stream.StreamSubscription
import com.linroid.ketch.endpoints.stream.UnknownInterest
import com.linroid.ketch.torrent.api.TorrentInterest

/**
 * What a stream sends beyond the task lifecycle, merged from the interests of a
 * [StreamSubscription] by [planOf].
 *
 * @property progress the interval step of each task's progress
 * @property segments the interval step of each named task's segments
 * @property statsIntervalMs how often stats are sent, or `null` for never
 * @property torrents the interval step of the snapshots of each torrent task followed
 * @property connectionsLimit the limit of the live connections sent, or `null` for none
 * @property connectionsIntervalMs the interval step of the live connections
 */
internal class StreamPlan(
  val progress: IntervalRules = IntervalRules.NONE,
  val segments: IntervalRules = IntervalRules.NONE,
  val statsIntervalMs: Long? = null,
  val torrents: Map<String, Long> = emptyMap(),
  val connectionsLimit: Int? = null,
  val connectionsIntervalMs: Long = StreamEvents.INTERVALS_MS.first(),
)

/**
 * The plan [subscription] asks for: intervals are rounded up to the server's steps, and where
 * several interests name a task, the shortest interval wins; the largest connection limit and
 * the shortest stats interval win. Interests this server does not know, which decode as
 * [UnknownInterest], are left out.
 *
 * @throws IllegalArgumentException when [subscription] goes beyond the limits of [StreamEvents]
 */
internal fun planOf(subscription: StreamSubscription): StreamPlan {
  require(subscription.revision >= 0) { "revision must not be negative" }
  val interests = subscription.interests
  require(interests.size <= StreamEvents.MAX_INTERESTS) {
    "At most ${StreamEvents.MAX_INTERESTS} interests are allowed"
  }
  val progress = subscription.progressIntervalMs?.let { interval ->
    require(interval > 0) { "progressIntervalMs must be positive" }
    StreamEvents.intervalStep(interval)
  }
  val segments = HashMap<String, Long>()
  var segmentIds = 0
  var stats: Long? = null
  val torrents = LinkedHashMap<String, Long>()
  var connectionsLimit: Int? = null
  var connectionsInterval: Long? = null
  for ((interest, intervalMs) in interests) {
    val step = StreamEvents.intervalStep(intervalMs)
    when (interest) {
      is Interest.Segments -> {
        segmentIds += interest.taskIds.size
        for (taskId in interest.taskIds) segments.merge(taskId, step, ::minOf)
      }
      Interest.Stats -> stats = minOf(stats ?: step, step)
      is Interest.Connections -> {
        connectionsLimit = maxOf(connectionsLimit ?: interest.limit, interest.limit)
        connectionsInterval = minOf(connectionsInterval ?: step, step)
      }
      is TorrentInterest -> torrents.merge(interest.taskId, step, ::minOf)
      else -> {}
    }
  }
  require(segmentIds <= StreamEvents.MAX_TASK_IDS) {
    "At most ${StreamEvents.MAX_TASK_IDS} task IDs are allowed"
  }
  require(torrents.size <= StreamEvents.MAX_TORRENTS) {
    "At most ${StreamEvents.MAX_TORRENTS} torrents can be followed"
  }
  return StreamPlan(
    progress = IntervalRules.everyTask(progress),
    segments = IntervalRules.byTask(segments),
    statsIntervalMs = stats,
    torrents = torrents,
    connectionsLimit = connectionsLimit,
    connectionsIntervalMs = connectionsInterval ?: StreamEvents.INTERVALS_MS.first(),
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
