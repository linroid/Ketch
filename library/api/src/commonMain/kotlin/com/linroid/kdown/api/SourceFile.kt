package com.linroid.kdown.api

import kotlinx.serialization.Serializable

/**
 * Describes a single file within a multi-file download source.
 *
 * Sources like torrent, HLS, or media extractors may resolve a URL
 * to multiple files or quality variants. Each [SourceFile] represents
 * one selectable item.
 *
 * @property id unique identifier for this file within the source
 * @property name human-readable display name
 * @property size file size in bytes, or -1 if unknown
 * @property metadata source-specific key-value pairs (e.g.,
 *   `"quality"` to `"1080p"`, `"path"` to `"Pack/Movie.mkv"`)
 */
@Serializable
data class SourceFile(
  val id: String,
  val name: String,
  val size: Long = -1,
  val metadata: Map<String, String> = emptyMap(),
)
