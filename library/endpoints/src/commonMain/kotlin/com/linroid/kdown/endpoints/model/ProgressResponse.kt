package com.linroid.kdown.endpoints.model

import kotlinx.serialization.Serializable

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
