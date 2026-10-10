package com.linroid.ketch.api.torrent

import kotlinx.serialization.Serializable

/**
 * One content file of a torrent task, as [TorrentController.files] lists it.
 *
 * Named an entry because the files of a torrent's own metainfo are another type.
 *
 * @property id stable file ID, the value [com.linroid.ketch.api.DownloadTask.selectFiles] takes
 * @property path the file's path inside the torrent
 * @property size the file's size in bytes
 * @property selected whether the task downloads it
 */
@Serializable
data class TorrentFileEntry(
  val id: String,
  val path: String,
  val size: Long,
  val selected: Boolean,
) {
  init {
    require(id.length in 1..128 && path.length <= 4096 && size >= 0)
  }
}

/**
 * One page of a torrent's files, in the [TorrentFileOrder] it was asked for.
 *
 * @property revision the task revision the page was read at
 * @property selectionGeneration the selection the page's [TorrentFileEntry.selected] flags follow
 * @property totalFiles how many content files the torrent has
 * @property nextCursor the cursor of the next page, or `null` on the last one. It binds the
 *   task, the selection generation and the order, so a cursor used after the selection changed
 *   or with another order is refused.
 */
@Serializable
data class TorrentFilePage(
  val taskId: String,
  val revision: TorrentRevision,
  val selectionGeneration: Long,
  val totalFiles: Int,
  val files: List<TorrentFileEntry>,
  val nextCursor: String? = null,
) {
  init {
    require(files.size <= 1000 && totalFiles >= 0 && selectionGeneration >= 0)
    require(nextCursor == null || nextCursor.length in 1..4096)
  }
}

/**
 * How [TorrentController.files] orders a torrent's files. Ties break by [TORRENT] order, so every
 * order is total and stable.
 *
 * @property wireName the value of the REST `sort` query parameter
 */
enum class TorrentFileOrder(val wireName: String) {
  /** The order of the torrent's metainfo (file ID order). */
  TORRENT("torrent"),

  /** Case-insensitive natural order of the full path, so "Episode 2" comes before "Episode 10". */
  NAME("name"),

  /** File size, smallest first. */
  SIZE("size"),

  /** Lowercased file extension, then [NAME]. */
  EXTENSION("extension"),

  /** Selected files first, then [NAME]. */
  SELECTED("selected");

  companion object {
    /** The order named [name], or `null` for a name this version does not know. */
    fun fromWire(name: String?): TorrentFileOrder? = entries.firstOrNull { it.wireName == name }
  }
}
