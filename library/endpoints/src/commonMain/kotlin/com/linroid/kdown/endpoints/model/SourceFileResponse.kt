package com.linroid.kdown.endpoints.model

import kotlinx.serialization.Serializable

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
