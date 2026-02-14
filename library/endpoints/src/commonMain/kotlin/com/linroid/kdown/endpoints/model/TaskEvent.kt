package com.linroid.kdown.endpoints.model

import kotlinx.serialization.Serializable

/**
 * Server-Sent Event payload for real-time task updates.
 */
@Serializable
data class TaskEvent(
  val taskId: String,
  val type: String,
  val state: String,
  val progress: ProgressResponse? = null,
  val error: String? = null,
  val filePath: String? = null,
)
