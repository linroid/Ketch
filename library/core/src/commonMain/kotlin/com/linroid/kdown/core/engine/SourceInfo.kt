package com.linroid.kdown.core.engine

/**
 * Metadata about a download source, returned by
 * [DownloadSource.resolve].
 *
 * @property totalBytes total content size in bytes, or -1 if unknown
 * @property supportsResume whether the source supports resuming
 *   interrupted downloads
 * @property suggestedFileName file name suggested by the source
 *   (e.g., from Content-Disposition), or null
 * @property maxSegments maximum number of concurrent segments the
 *   source supports. 1 means no parallel downloading.
 * @property metadata source-specific key-value pairs
 */
data class SourceInfo(
  val totalBytes: Long,
  val supportsResume: Boolean,
  val suggestedFileName: String?,
  val maxSegments: Int,
  val metadata: Map<String, String> = emptyMap(),
)
