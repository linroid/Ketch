package com.linroid.ketch.endpoints.model

import com.linroid.ketch.api.DownloadState
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

  /** A task's state changed (non-progress update). */
  @Serializable
  @SerialName("state_changed")
  data class StateChanged(
    override val taskId: String,
    val state: DownloadState,
  ) : TaskEvent()

  /** Download progress update. */
  @Serializable
  @SerialName("progress")
  data class Progress(
    override val taskId: String,
    val state: DownloadState,
  ) : TaskEvent()

  /** Server error for a task. */
  @Serializable
  @SerialName("error")
  data class Error(
    override val taskId: String,
  ) : TaskEvent()
}
