package com.linroid.kdown.endpoints.model

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
 * @property selectedFileIds IDs of files selected from a multi-file
 *   source. Empty means download all/default.
 */
@Serializable
data class CreateDownloadRequest(
  val url: String,
  val directory: String,
  val fileName: String? = null,
  val connections: Int = 1,
  val headers: Map<String, String> = emptyMap(),
  val priority: String = "NORMAL",
  val speedLimitBytesPerSecond: Long = 0,
  val selectedFileIds: Set<String> = emptySet(),
  val resolvedUrl: ResolveUrlResponse? = null,
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
  val speedLimitBytesPerSecond: Long = 0,
)

/**
 * Download progress information.
 */
@Serializable
data class ProgressResponse(
  val downloadedBytes: Long,
  val totalBytes: Long,
  val percent: Float,
  val bytesPerSecond: Long,
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
  val isComplete: Boolean,
)

/**
 * Request body for updating speed limits.
 *
 * @property bytesPerSecond the speed limit. 0 means unlimited.
 */
@Serializable
data class SpeedLimitRequest(
  val bytesPerSecond: Long,
)

/**
 * Request body for setting task priority.
 *
 * @property priority one of LOW, NORMAL, HIGH, URGENT
 */
@Serializable
data class PriorityRequest(
  val priority: String,
)

/**
 * Request body for resolving a URL without downloading.
 *
 * @property url the URL to resolve
 * @property headers optional HTTP headers to include in the probe
 */
@Serializable
data class ResolveUrlRequest(
  val url: String,
  val headers: Map<String, String> = emptyMap(),
)

/**
 * Response body for a resolved URL.
 *
 * @property url the resolved download URL
 * @property sourceType identifier of the source that handled it
 * @property totalBytes total content size in bytes, or -1 if unknown
 * @property supportsResume whether resume is supported
 * @property suggestedFileName file name suggested by the source
 * @property maxSegments maximum concurrent segments supported
 * @property metadata source-specific key-value pairs
 * @property files selectable files within this source
 * @property selectionMode how files should be selected
 */
@Serializable
data class ResolveUrlResponse(
  val url: String,
  val sourceType: String,
  val totalBytes: Long,
  val supportsResume: Boolean,
  val suggestedFileName: String? = null,
  val maxSegments: Int,
  val metadata: Map<String, String> = emptyMap(),
  val files: List<SourceFileResponse> = emptyList(),
  val selectionMode: String = "MULTIPLE",
)

/**
 * Wire representation of a selectable file within a source.
 *
 * @property id unique identifier for this file
 * @property name human-readable display name
 * @property size file size in bytes, or -1 if unknown
 * @property metadata source-specific key-value pairs
 */
@Serializable
data class SourceFileResponse(
  val id: String,
  val name: String,
  val size: Long = -1,
  val metadata: Map<String, String> = emptyMap(),
)

/**
 * Generic error response body.
 */
@Serializable
data class ErrorResponse(
  val error: String,
  val message: String,
)

/**
 * Server status information returned by the health endpoint.
 */
@Serializable
data class ServerStatus(
  val version: String,
  val activeTasks: Int,
  val totalTasks: Int,
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
  val filePath: String? = null,
)
