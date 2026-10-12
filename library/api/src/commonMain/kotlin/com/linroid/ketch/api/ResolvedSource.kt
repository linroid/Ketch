package com.linroid.ketch.api

import kotlinx.serialization.Serializable

/**
 * What [KetchApi.resolve] learned about a URL; pass it in [DownloadRequest.resolvedSource] to
 * skip probing again.
 *
 * @property sourceType the source that handles the URL, such as `http`
 * @property totalBytes the content size, or -1 if unknown
 * @property suggestedFileName a name from the source, such as `Content-Disposition`
 * @property maxSegments the most segments it can download at once; 1 without ranges
 * @property metadata source-specific values
 * @property files the files or variants to choose from; empty for one file
 * @property selectionMode how [files] are chosen: any subset ([FileSelectionMode.MULTIPLE]) or
 *   one ([FileSelectionMode.SINGLE], default first; see [defaultSelection])
 * @property contentType the media type the source reports, such as `video/mp4`
 * @property checksum the digest the source publishes, which downloads without their own
 *   [DownloadRequest.checksum] are verified against
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
  val checksum: Checksum? = null,
) {
  companion object {
    /** [metadata] key of the whole content description, such as a torrent's metainfo. */
    const val METAINFO_KEY: String = "metainfo"

    /** The longest [metadata] value task views keep. */
    const val MAX_VIEW_METADATA_CHARS: Int = 4096
  }
}

/**
 * This source without [ResolvedSource.METAINFO_KEY] or metadata longer than
 * [ResolvedSource.MAX_VIEW_METADATA_CHARS], as tasks keep it.
 */
fun ResolvedSource.withoutBulkMetadata(): ResolvedSource {
  val kept = metadata.filter { (key, value) ->
    key != ResolvedSource.METAINFO_KEY && value.length <= ResolvedSource.MAX_VIEW_METADATA_CHARS
  }
  return if (kept.size == metadata.size) this else copy(metadata = kept)
}

/** The files downloaded when none are chosen: all of them, or the first for a single choice. */
fun ResolvedSource.defaultSelection(): Set<String> = when (selectionMode) {
  FileSelectionMode.MULTIPLE -> files.mapTo(LinkedHashSet()) { it.id }
  FileSelectionMode.SINGLE -> files.firstOrNull()?.let { setOf(it.id) }.orEmpty()
}
