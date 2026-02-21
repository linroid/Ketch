package com.linroid.ketch.endpoints.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Identifies the type of [TaskEvent]. */
@Serializable
enum class TaskEventType(val value: String) {
  @SerialName("task_added")
  TaskAdded("task_added"),

  @SerialName("task_removed")
  TaskRemoved("task_removed"),

  @SerialName("state_changed")
  StateChanged("state_changed"),

  @SerialName("progress")
  Progress("progress"),

  @SerialName("error")
  Error("error"),
}
