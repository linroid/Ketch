package com.linroid.kdown.server.model

import kotlinx.serialization.Serializable

/**
 * Request body for creating a new download task.
 *
 * @property url the URL to download
 * @property directory the target directory path
 * @property fileName optional explicit file name
 * @property connections number of concurrent segments
 * @property headers custom HTTP headers
 * @property priority task priority level
 * @property speedLimitBytesPerSecond per-task speed limit (0 = unlimited)
 */
@Serializable
data class CreateDownloadRequest(
  val url: String,
  val directory: String,
  val fileName: String? = null,
  val connections: Int = 1,
  val headers: Map<String, String> = emptyMap(),
  val priority: String = "NORMAL",
  val speedLimitBytesPerSecond: Long = 0
)

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
  val speedLimitBytesPerSecond: Long = 0
)

/**
 * Download progress information.
 */
@Serializable
data class ProgressResponse(
  val downloadedBytes: Long,
  val totalBytes: Long,
  val percent: Float,
  val bytesPerSecond: Long
)

/**
 * Segment-level progress information.
 */
@Serializable
data class SegmentResponse(
  val index: Int,
  val start: Long,
  val end: Long,
  val downloadedBytes: Long,
  val isComplete: Boolean
)

/**
 * Request body for updating the global speed limit.
 *
 * @property bytesPerSecond the speed limit. 0 means unlimited.
 */
@Serializable
data class SpeedLimitRequest(
  val bytesPerSecond: Long
)

/**
 * Request body for setting task priority.
 *
 * @property priority one of LOW, NORMAL, HIGH, URGENT
 */
@Serializable
data class PriorityRequest(
  val priority: String
)

/**
 * Generic error response body.
 */
@Serializable
data class ErrorResponse(
  val error: String,
  val message: String
)

/**
 * Server status information returned by the health endpoint.
 */
@Serializable
data class ServerStatus(
  val version: String,
  val activeTasks: Int,
  val totalTasks: Int
)

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
  val filePath: String? = null
)
