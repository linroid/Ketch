package com.linroid.ketch.app.ui.intake

import androidx.compose.ui.state.ToggleableState
import com.linroid.ketch.api.SourceFile
import com.linroid.ketch.app.state.FileSort
import com.linroid.ketch.app.state.FileSortKey
import com.linroid.ketch.app.state.FileSortSurface
import com.linroid.ketch.app.state.isTorrentExtra
import com.linroid.ketch.app.state.sortedByFiles
import com.linroid.ketch.app.state.toggle
import com.linroid.ketch.app.state.toggleState
import com.linroid.ketch.app.util.FileKind
import ketch.app.shared.generated.resources.Res
import ketch.app.shared.generated.resources.file_type_audio
import ketch.app.shared.generated.resources.file_type_image
import ketch.app.shared.generated.resources.file_type_other
import ketch.app.shared.generated.resources.file_type_video
import ketch.app.shared.generated.resources.intake_kind_subtitles
import org.jetbrains.compose.resources.StringResource

/** Kinds of torrent files the picker selects with one chip. */
internal enum class TorrentFileKind(val label: StringResource) {
  Video(Res.string.file_type_video),
  Subtitles(Res.string.intake_kind_subtitles),
  Audio(Res.string.file_type_audio),
  Images(Res.string.file_type_image),
  Other(Res.string.file_type_other);

  companion object {
    /** The kind of a file named [name]. */
    fun of(name: String): TorrentFileKind = when (FileKind.of(name)) {
      FileKind.Video -> Video
      FileKind.Subtitle -> Subtitles
      FileKind.Audio -> Audio
      FileKind.Image -> Images
      else -> Other
    }
  }
}

/** One row of the torrent picker's tree. */
internal sealed interface TorrentNode {
  /** Path from the torrent's root, which identifies the row. */
  val path: String

  /** Last component of [path]. */
  val name: String

  /** How deep the row is nested; top-level rows are 0. */
  val depth: Int

  /** Size of the file, or of every file in the folder. */
  val size: Long

  /** Ids of the files the row stands for. */
  val fileIds: List<String>

  /** A folder of files. */
  class Folder(
    override val path: String,
    override val name: String,
    override val depth: Int,
    val children: List<TorrentNode>,
  ) : TorrentNode {
    override val size: Long = children.sumOf { it.size }
    override val fileIds: List<String> = children.flatMap { it.fileIds }

    /** Place of its first file in the torrent. */
    val index: Int = children.minOfOrNull {
      when (it) {
        is Folder -> it.index
        is File -> it.index
      }
    } ?: 0
  }

  /** A file. */
  class File(
    val file: SourceFile,
    override val name: String,
    override val depth: Int,
    val index: Int = 0,
  ) : TorrentNode {
    override val path: String get() = file.name
    override val size: Long get() = file.size.coerceAtLeast(0)
    override val fileIds: List<String> = listOf(file.id)
    val kind: TorrentFileKind = TorrentFileKind.of(name)
  }
}

/**
 * The files of a torrent as a folder tree, from the `/`-separated paths in their names.
 *
 * @param sort order of the rows within each folder; folders come first, ordered the same way
 *   (by total size, and by name for Kind).
 * @param selection the chosen files, which the Selected order puts first; a folder counts as
 *   chosen when any of its files is.
 */
internal class TorrentTree(
  val files: List<SourceFile>,
  sort: FileSort = FileSortSurface.Intake.default,
  selection: Set<String> = emptySet(),
) {
  /** Top-level rows. */
  val roots: List<TorrentNode> = build(
    entries = files.mapIndexed { index, file -> Entry(file, index, file.name.split('/')) },
    prefix = "",
    depth = 0,
    sort = sort,
    selection = selection,
  )

  /** Every file's kind, by file id. */
  val kinds: Map<String, TorrentFileKind> =
    files.associate { it.id to TorrentFileKind.of(it.name.substringAfterLast('/')) }

  /** How many files of each kind there are; kinds without files are left out. */
  val kindCounts: Map<TorrentFileKind, Int> =
    TorrentFileKind.entries.associateWith { kind -> kinds.values.count { it == kind } }
      .filterValues { it > 0 }

  /** Total size of every file. */
  val totalBytes: Long = files.sumOf { it.size.coerceAtLeast(0) }

  /**
   * The rows to show: folders not in [collapsed] are open. With a [filter], only files whose
   * path contains it are shown, inside their folders, which are all open.
   */
  fun visible(collapsed: Set<String>, filter: String = ""): List<TorrentNode> {
    val query = filter.trim().lowercase()
    val rows = mutableListOf<TorrentNode>()
    fun add(node: TorrentNode) {
      when (node) {
        is TorrentNode.File -> if (query.isEmpty() || query in node.path.lowercase()) rows += node
        is TorrentNode.Folder -> {
          val start = rows.size
          rows += node
          if (query.isNotEmpty() || node.path !in collapsed) node.children.forEach(::add)
          // A filtered folder without matches is dropped.
          if (query.isNotEmpty() && rows.size == start + 1) rows.removeAt(start)
        }
      }
    }
    roots.forEach(::add)
    return rows
  }

  /** Bytes of the files in [selection]. */
  fun bytesOf(selection: Set<String>): Long =
    files.filter { it.id in selection }.sumOf { it.size.coerceAtLeast(0) }

  /** The id of the largest file. */
  fun largest(): String? = files.maxByOrNull { it.size }?.id

  /** Ids of the extras: `.nfo` files, tiny text files and samples. */
  fun extras(): Set<String> = files.filter(::isTorrentExtra).mapTo(LinkedHashSet()) { it.id }

  /** Ids of the files of [kind]. */
  fun idsOf(kind: TorrentFileKind): Set<String> =
    kinds.filterValues { it == kind }.keys

  /** A file with its place in the torrent and the parts of its path left to place. */
  private class Entry(val file: SourceFile, val index: Int, val parts: List<String>)

  private fun build(
    entries: List<Entry>,
    prefix: String,
    depth: Int,
    sort: FileSort,
    selection: Set<String>,
  ): List<TorrentNode> {
    val (leaves, nested) = entries.partition { it.parts.size == 1 }
    val folders = nested.groupBy { it.parts.first() }.map { (name, children) ->
      val path = prefix + name
      TorrentNode.Folder(
        path = path,
        name = name,
        depth = depth,
        children = build(
          entries = children.map { Entry(it.file, it.index, it.parts.drop(1)) },
          prefix = "$path/",
          depth = depth + 1,
          sort = sort,
          selection = selection,
        ),
      )
    }
    val fileNodes = leaves.map { TorrentNode.File(it.file, it.parts.single(), depth, it.index) }
    val sortedFolders = folders.sortedByFiles(sort) { folder ->
      FileSortKey(
        path = folder.name,
        size = folder.size,
        index = folder.index,
        selected = folder.fileIds.any { it in selection },
        folder = true,
      )
    }
    val sortedFiles = fileNodes.sortedByFiles(sort) { node ->
      FileSortKey(
        path = node.name,
        size = node.size,
        index = node.index,
        selected = node.file.id in selection,
      )
    }
    return sortedFolders + sortedFiles
  }
}

/** Whether every, some or none of [node]'s files are in [selection]. */
internal fun toggleState(node: TorrentNode, selection: Set<String>): ToggleableState =
  toggleState(node.fileIds, selection)

/** [selection] after clicking [node]: a fully selected row clears, any other selects all. */
internal fun toggle(node: TorrentNode, selection: Set<String>): Set<String> =
  toggle(node.fileIds, selection)

/**
 * [selection] after a ⇧-click on [to] in [rows], the visible rows, whose last click was on
 * [from]: every file between them takes the state the click gives [to].
 */
internal fun toggleRange(
  rows: List<TorrentNode>,
  from: String,
  to: TorrentNode,
  selection: Set<String>,
): Set<String> {
  val start = rows.indexOfFirst { it.path == from }
  val end = rows.indexOf(to)
  if (start < 0 || end < 0) return toggle(to, selection)
  val select = toggleState(to, selection) != ToggleableState.On
  val ids = rows.subList(minOf(start, end), maxOf(start, end) + 1).flatMap { it.fileIds }
  return if (select) selection + ids else selection - ids.toSet()
}
