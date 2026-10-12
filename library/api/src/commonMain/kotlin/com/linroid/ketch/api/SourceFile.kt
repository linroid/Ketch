package com.linroid.ketch.api

import kotlinx.serialization.Serializable

/**
 * One file or variant of a source with several ([ResolvedSource.files]).
 *
 * @property id unique within the source
 * @property size size in bytes, or -1 if unknown
 * @property metadata source-specific values, such as `path` or `quality`
 */
@Serializable
data class SourceFile(
  val id: String,
  val name: String,
  val size: Long = -1,
  val metadata: Map<String, String> = emptyMap(),
)
