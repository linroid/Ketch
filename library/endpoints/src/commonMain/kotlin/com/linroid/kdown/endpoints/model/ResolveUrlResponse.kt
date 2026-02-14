package com.linroid.kdown.endpoints.model

import kotlinx.serialization.Serializable

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
