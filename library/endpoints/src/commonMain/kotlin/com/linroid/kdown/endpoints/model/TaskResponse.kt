package com.linroid.kdown.endpoints.model

import kotlinx.serialization.Serializable

/**
 * Response for a single download task.
 */
@Serializable
data class TaskResponse(
  val taskId: String,
  val url: String,
  val directory: String,
  val fileName: String? = null,
  val state: String,
  val progress: ProgressResponse? = null,
  val error: String? = null,
  val filePath: String? = null,
  val segments: List<SegmentResponse> = emptyList(),
  val createdAt: String,
  val priority: String,
  val speedLimitBytesPerSecond: Long = 0,
  val connections: Int = 0,
)
