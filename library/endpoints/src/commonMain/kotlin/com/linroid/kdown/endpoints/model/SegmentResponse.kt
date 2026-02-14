package com.linroid.kdown.endpoints.model

import kotlinx.serialization.Serializable

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
