package com.linroid.ketch.endpoints.model

import com.linroid.ketch.api.DownloadRequest
import com.linroid.ketch.api.DownloadState
import com.linroid.ketch.api.Segment
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Server-Sent Event payload for real-time task updates.
 *
 * Each subclass carries only the data relevant to that event type.
 * The [eventType] property provides the SSE event name.
 */
@Serializable
sealed class TaskEvent {
  abstract val taskId: String

  /** The event type for SSE routing. */
  val eventType: TaskEventType get() = when (this) {
    is TaskAdded -> TaskEventType.TaskAdded
    is TaskRemoved -> TaskEventType.TaskRemoved
    is StateChanged -> TaskEventType.StateChanged
    is Progress -> TaskEventType.Progress
    is Error -> TaskEventType.Error
  }

  /** A new task was added. */
  @Serializable
  @SerialName("task_added")
  data class TaskAdded(
    override val taskId: String,
    val state: DownloadState,
  ) : TaskEvent()

  /** A task was removed. */
  @Serializable
  @SerialName("task_removed")
  data class TaskRemoved(
    override val taskId: String,
  ) : TaskEvent()

  /**
   * A task's state, settings, or segments changed (non-progress update).
   * Null request or segments indicate an older server that omitted those fields.
   */
  @Serializable
  @SerialName("state_changed")
  data class StateChanged(
    override val taskId: String,
    val state: DownloadState,
    val request: DownloadRequest? = null,
    val segments: List<Segment>? = null,
  ) : TaskEvent()

  /**
   * Download progress, settings, or segment update for an active download.
   * Null request or segments indicate an older server that omitted those fields.
   */
  @Serializable
  @SerialName("progress")
  data class Progress(
    override val taskId: String,
    val state: DownloadState,
    val request: DownloadRequest? = null,
    val segments: List<Segment>? = null,
  ) : TaskEvent()

  /** Server error for a task. */
  @Serializable
  @SerialName("error")
  data class Error(
    override val taskId: String,
  ) : TaskEvent()
}
