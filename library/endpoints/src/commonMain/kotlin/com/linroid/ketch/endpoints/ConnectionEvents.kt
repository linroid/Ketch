package com.linroid.ketch.endpoints

/**
 * The server-sent events and error codes of the live connections stream,
 * [Api.ActiveConnections.Events].
 */
object ConnectionEvents {
  /**
   * An `ActiveConnections` snapshot; its `id` counts the stream's events. Sent right away, then
   * each time the connections change, and again every 15 seconds while nothing does.
   */
  const val SNAPSHOT: String = "snapshot"

  /** An `ErrorResponse`; the stream closes after it. */
  const val ERROR: String = "error"

  /** Error code of a `limit` outside 1..1024 (`400`). */
  const val INVALID_LIMIT: String = "invalid_limit"

  /** Error code of a server whose engine does not report its connections (`501`). */
  const val UNSUPPORTED: String = "unsupported"

  /** Error code of a server already streaming to as many clients as it allows (`429`). */
  const val TOO_MANY_STREAMS: String = "too_many_streams"
}
