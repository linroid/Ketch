package com.linroid.ketch.endpoints.stream

import com.linroid.ketch.api.DownloadProgress
import com.linroid.ketch.api.DownloadRequest
import com.linroid.ketch.api.DownloadState
import com.linroid.ketch.api.Segment
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

/** A task named by an event, such as [StreamEvents.TASK_REMOVED]. */
@Serializable
data class StreamTaskRef(val taskId: String)

/**
 * A task's state, queue position or request changed.
 *
 * @property queuePosition the task's place in the queue, `null` when it does not wait
 * @property request the request as views show it, or `null` when it did not change since this
 *   stream last sent it
 */
@Serializable
data class TaskStateUpdate(
  val taskId: String,
  val state: DownloadState,
  val queuePosition: Int? = null,
  val request: DownloadRequest? = null,
)

/** Progress of a task that is downloading: the client keeps it in `DownloadState.Downloading`. */
@Serializable
data class TaskProgressUpdate(
  val taskId: String,
  val progress: DownloadProgress,
)

/** A task's segments. */
@Serializable
data class TaskSegmentsUpdate(
  val taskId: String,
  val segments: List<Segment>,
)

/**
 * The latest value of the interest with [id], encoded with its `Interest.valueSerializer`.
 * Values come at most once per the interest's interval; a client that reads slowly gets the
 * latest, not each one.
 */
@Serializable
data class InterestUpdate(
  val id: Long,
  val value: JsonElement,
)

/** The interest with [id], named by [StreamEvents.INTEREST_END]. */
@Serializable
data class InterestRef(val id: Long)

/**
 * The interest with [id] cannot be served or failed; the stream goes on.
 *
 * @property error [StreamEvents.UNSUPPORTED], [StreamEvents.INTERNAL_ERROR], or a torrent command
 *   error's wire name
 * @property message a description in English
 */
@Serializable
data class InterestError(
  val id: Long,
  val error: String,
  val message: String,
)

/**
 * A failure that ends the stream.
 *
 * @property error a stable code from [StreamEvents]
 * @property message a description in English
 */
@Serializable
data class StreamError(
  val error: String,
  val message: String,
)
