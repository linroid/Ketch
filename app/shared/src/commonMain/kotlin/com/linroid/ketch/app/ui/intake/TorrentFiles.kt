package com.linroid.ketch.app.ui.intake

import androidx.compose.ui.state.ToggleableState
import com.linroid.ketch.api.SourceFile
import com.linroid.ketch.app.state.isTorrentExtra
import com.linroid.ketch.app.util.FileKind
import ketch.app.shared.generated.resources.Res
import ketch.app.shared.generated.resources.file_type_audio
import ketch.app.shared.generated.resources.file_type_image
import ketch.app.shared.generated.resources.file_type_other
import ketch.app.shared.generated.resources.file_type_video
import ketch.app.shared.generated.resources.intake_kind_subtitles
import ketch.app.shared.generated.resources.sort_name
import ketch.app.shared.generated.resources.sort_size
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

/** How the picker orders the files of a folder. */
internal enum class TorrentSort(val label: StringResource) {
  Name(Res.string.sort_name),
  Size(Res.string.sort_size),
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
  }

  /** A file. */
  class File(
    val file: SourceFile,
    override val name: String,
    override val depth: Int,
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
 * @param sort order of the rows within each folder; folders come first.
 */
internal class TorrentTree(val files: List<SourceFile>, sort: TorrentSort = TorrentSort.Name) {
  /** Top-level rows. */
  val roots: List<TorrentNode> = build(files.map { it to it.name.split('/') }, "", 0, sort)

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

  private fun build(
    entries: List<Pair<SourceFile, List<String>>>,
    prefix: String,
    depth: Int,
    sort: TorrentSort,
  ): List<TorrentNode> {
    val (leaves, nested) = entries.partition { it.second.size == 1 }
    val folders = nested.groupBy { it.second.first() }.map { (name, children) ->
      val path = prefix + name
      TorrentNode.Folder(
        path = path,
        name = name,
        depth = depth,
        children = build(children.map { it.first to it.second.drop(1) }, "$path/", depth + 1, sort),
      )
    }
    val fileNodes = leaves.map { (file, parts) -> TorrentNode.File(file, parts.single(), depth) }
    val comparator: Comparator<TorrentNode> = when (sort) {
      TorrentSort.Name -> compareBy(String.CASE_INSENSITIVE_ORDER) { it.name }
      TorrentSort.Size -> compareByDescending { it.size }
    }
    return folders.sortedWith(comparator) + fileNodes.sortedWith(comparator)
  }
}

/** Whether every, some or none of [node]'s files are in [selection]. */
internal fun toggleState(node: TorrentNode, selection: Set<String>): ToggleableState {
  val selected = node.fileIds.count { it in selection }
  return when (selected) {
    0 -> ToggleableState.Off
    node.fileIds.size -> ToggleableState.On
    else -> ToggleableState.Indeterminate
  }
}

/** [selection] after clicking [node]: a fully selected row clears, any other selects all. */
internal fun toggle(node: TorrentNode, selection: Set<String>): Set<String> =
  if (toggleState(node, selection) == ToggleableState.On) {
    selection - node.fileIds.toSet()
  } else {
    selection + node.fileIds
  }

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
