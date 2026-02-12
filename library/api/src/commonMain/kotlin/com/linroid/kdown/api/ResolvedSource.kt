package com.linroid.kdown.api

import kotlinx.serialization.Serializable

/**
 * Pre-resolved metadata about a download URL.
 *
 * Returned by [KDownApi.resolve] after probing the URL without
 * downloading. When passed in [DownloadRequest.resolvedUrl], the
 * download engine skips its own HEAD/probe request and uses this
 * information directly.
 *
 * @property url the resolved download URL
 * @property sourceType identifier of the source that handled the
 *   URL (e.g., "http")
 * @property totalBytes total content size in bytes, or -1 if unknown
 * @property supportsResume whether the source supports resuming
 *   interrupted downloads
 * @property suggestedFileName file name suggested by the source
 *   (e.g., from Content-Disposition), or null
 * @property maxSegments maximum number of concurrent segments the
 *   source supports. 1 if no range/parallel support.
 * @property metadata source-specific key-value pairs
 * @property files list of selectable files within this source.
 *   Empty for single-file sources (e.g., HTTP). When non-empty,
 *   the UI should present a file selector before downloading.
 * @property selectionMode how the user selects from [files]:
 *   [FileSelectionMode.MULTIPLE] for subset selection (torrent),
 *   [FileSelectionMode.SINGLE] for single-variant selection (HLS).
 */
@Serializable
data class ResolvedSource(
  val url: String,
  val sourceType: String,
  val totalBytes: Long,
  val supportsResume: Boolean,
  val suggestedFileName: String?,
  val maxSegments: Int,
  val metadata: Map<String, String> = emptyMap(),
  val files: List<SourceFile> = emptyList(),
  val selectionMode: FileSelectionMode = FileSelectionMode.MULTIPLE,
)
