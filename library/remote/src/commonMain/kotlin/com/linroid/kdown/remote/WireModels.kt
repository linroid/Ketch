package com.linroid.kdown.remote

import kotlinx.serialization.Serializable

/** Wire DTO for creating a new download via the REST API. */
@Serializable
internal data class WireCreateDownloadRequest(
  val url: String,
  val directory: String,
  val fileName: String? = null,
  val connections: Int = 1,
  val headers: Map<String, String> = emptyMap(),
  val priority: String = "NORMAL",
  val speedLimitBytesPerSecond: Long = 0
)

/** Wire DTO for a download task returned by the REST API. */
@Serializable
internal data class WireTaskResponse(
  val taskId: String,
  val url: String,
  val directory: String,
  val fileName: String? = null,
  val state: String,
  val progress: WireProgressResponse? = null,
  val error: String? = null,
  val filePath: String? = null,
  val segments: List<WireSegmentResponse> = emptyList(),
  val createdAt: String,
  val priority: String,
  val speedLimitBytesPerSecond: Long = 0
)

/** Wire DTO for progress information. */
@Serializable
internal data class WireProgressResponse(
  val downloadedBytes: Long,
  val totalBytes: Long,
  val percent: Float,
  val bytesPerSecond: Long
)

/** Wire DTO for segment-level progress. */
@Serializable
internal data class WireSegmentResponse(
  val index: Int,
  val start: Long,
  val end: Long,
  val downloadedBytes: Long,
  val isComplete: Boolean
)

/** Wire DTO for SSE task events. */
@Serializable
internal data class WireTaskEvent(
  val taskId: String,
  val type: String,
  val state: String,
  val progress: WireProgressResponse? = null,
  val error: String? = null,
  val filePath: String? = null
)

/** Request body for speed limit endpoints. */
@Serializable
internal data class WireSpeedLimitBody(val bytesPerSecond: Long)

/** Request body for priority endpoint. */
@Serializable
internal data class WirePriorityBody(val priority: String)
