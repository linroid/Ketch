package com.linroid.ketch.endpoints.stream

/**
 * The server-sent events, error codes and limits of the subscribed event stream, `GET
 * /api/stream`. The stream starts with [HELLO] and carries the task lifecycle; everything else
 * is sent once the client asks for it with a [StreamSubscription]: task progress and segments as
 * task events, and each interest as [INTEREST] events under the ID the client gave it.
 */
object StreamEvents {
  /**
   * The feature servers serving the stream list in `KetchStatus.features`; clients use the
   * older `/api/events` without it.
   */
  const val FEATURE: String = "events.stream"

  /** Version of the stream's protocol, in [StreamHello.protocol]. */
  const val PROTOCOL: Int = 1

  /** A [StreamHello]; always the first event. */
  const val HELLO: String = "hello"

  /**
   * A [StreamSubscribed], once a subscription applies. Events of the interests it adds follow it;
   * none follow for those it drops.
   */
  const val SUBSCRIBED: String = "subscribed"

  /** A `TasksResponse` with every task, sent once, right after the first [SUBSCRIBED]. */
  const val TASKS: String = "tasks"

  /** A `TaskSnapshot` of a task that was added. */
  const val TASK_ADDED: String = "task.added"

  /** A [StreamTaskRef] of a task that was removed. */
  const val TASK_REMOVED: String = "task.removed"

  /** A [TaskStateUpdate]: anything but a downloading task's progress and segments changed. */
  const val TASK_STATE: String = "task.state"

  /** A [TaskProgressUpdate] of a downloading task. */
  const val TASK_PROGRESS: String = "task.progress"

  /** A [TaskSegmentsUpdate]. */
  const val TASK_SEGMENTS: String = "task.segments"

  /** An [InterestUpdate]: the latest value of an interest the subscription names. */
  const val INTEREST: String = "interest"

  /**
   * An [InterestRef]: what an interest follows is gone, such as a removed torrent; nothing more
   * comes for it.
   */
  const val INTEREST_END: String = "interest.end"

  /** An [InterestError]: the interest cannot be served or failed; nothing more comes for it. */
  const val INTEREST_ERROR: String = "interest.error"

  /** A [StreamError]; the stream closes after it. */
  const val ERROR: String = "error"

  /** Error code of a subscription for a stream that is closed or not the caller's (`404`). */
  const val UNKNOWN_STREAM: String = "unknown_stream"

  /** Error code of a subscription beyond the limits below (`400`). */
  const val INVALID_SUBSCRIPTION: String = "invalid_subscription"

  /** Error code of a server already streaming to as many clients as it allows (`429`). */
  const val TOO_MANY_STREAMS: String = "too_many_streams"

  /**
   * Error code of an interest the server cannot serve: one it does not know, or torrents without
   * a torrent source.
   */
  const val UNSUPPORTED: String = "unsupported"

  /** Error code of an interest that failed on the server. */
  const val INTERNAL_ERROR: String = "internal_error"

  /** Most interests in a subscription, and most entries in its segments. */
  const val MAX_INTERESTS: Int = 64

  /** Most task IDs the segments of a subscription name together. */
  const val MAX_TASK_IDS: Int = 1024

  /**
   * The intervals the server sends at, in milliseconds. A requested interval is rounded up to
   * one of them by [intervalStep].
   */
  val INTERVALS_MS: List<Long> = listOf(200, 500, 1_000, 2_000, 5_000, 10_000, 30_000, 60_000)

  /** The step of [INTERVALS_MS] that [intervalMs] rounds up to, the longest at most. */
  fun intervalStep(intervalMs: Long): Long {
    require(intervalMs > 0) { "intervalMs must be positive" }
    return INTERVALS_MS.firstOrNull { it >= intervalMs } ?: INTERVALS_MS.last()
  }
}
