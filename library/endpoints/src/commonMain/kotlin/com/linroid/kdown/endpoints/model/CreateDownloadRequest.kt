package com.linroid.kdown.endpoints.model

import kotlinx.serialization.Serializable

/**
 * Request body for creating a new download task.
 *
 * @property url the URL to download
 * @property directory the target directory path
 * @property fileName optional explicit file name
 * @property connections number of concurrent segments (0 = use config default)
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
  val connections: Int = 0,
  val headers: Map<String, String> = emptyMap(),
  val priority: String = "NORMAL",
  val speedLimitBytesPerSecond: Long = 0,
  val selectedFileIds: Set<String> = emptySet(),
  val resolvedUrl: ResolveUrlResponse? = null,
)
