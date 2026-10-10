package com.linroid.ketch.api

import kotlinx.serialization.Serializable

/**
 * Pre-resolved metadata about a download URL.
 *
 * Returned by [KetchApi.resolve] after probing the URL without
 * downloading. When passed in [DownloadRequest.resolvedSource], the
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
 * @property contentType media type of the content as the source reports it, such as an HTTP
 *   `Content-Type` of `video/mp4`, or `null` when unknown. [DownloadCategory.mimeTypes] match it.
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
  val contentType: String? = null,
) {
  companion object {
    /**
     * [metadata] key holding a source's whole content description, such as a torrent's base64
     * metainfo. [withoutBulkMetadata] always leaves it out, whatever its size.
     */
    const val METAINFO_KEY: String = "metainfo"

    /** [metadata] values longer than this are left out of task views and events. */
    const val MAX_VIEW_METADATA_CHARS: Int = 4096
  }
}

/**
 * This source without [ResolvedSource.METAINFO_KEY] and without metadata values longer than
 * [ResolvedSource.MAX_VIEW_METADATA_CHARS], as tasks keep and show it; [ResolvedSource.files]
 * are kept. Results of [KetchApi.resolve] keep everything.
 */
fun ResolvedSource.withoutBulkMetadata(): ResolvedSource {
  val kept = metadata.filter { (key, value) ->
    key != ResolvedSource.METAINFO_KEY && value.length <= ResolvedSource.MAX_VIEW_METADATA_CHARS
  }
  return if (kept.size == metadata.size) this else copy(metadata = kept)
}
