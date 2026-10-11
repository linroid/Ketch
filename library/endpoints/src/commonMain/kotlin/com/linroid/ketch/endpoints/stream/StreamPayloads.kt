package com.linroid.ketch.endpoints.stream

import com.linroid.ketch.api.DownloadProgress
import com.linroid.ketch.api.DownloadRequest
import com.linroid.ketch.api.DownloadState
import com.linroid.ketch.api.Segment
import kotlinx.serialization.Serializable

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
 * Totals of the tasks that are downloading; counts per state follow from the task lifecycle.
 *
 * @property downloadBps the summed download speed, in bytes per second
 * @property downloadedBytes bytes downloaded by tasks of known size
 * @property totalBytes size of the tasks of known size
 * @property remainingBytes bytes the tasks of known size still have to download
 * @property downloading tasks that are downloading
 * @property unknownSizes tasks that are downloading content of unknown size
 */
@Serializable
data class StreamStats(
  val downloadBps: Long = 0,
  val downloadedBytes: Long = 0,
  val totalBytes: Long = 0,
  val remainingBytes: Long = 0,
  val downloading: Int = 0,
  val unknownSizes: Int = 0,
)

/**
 * A failure on the stream.
 *
 * @property error a stable code from [StreamEvents], or a torrent command error's wire name
 * @property message a description in English
 * @property topic the topic that failed, such as [StreamEvents.TOPIC_TORRENTS], or `null` when
 *   the whole stream ends
 * @property taskId the task the failed topic was about, if any
 */
@Serializable
data class StreamError(
  val error: String,
  val message: String,
  val topic: String? = null,
  val taskId: String? = null,
)
