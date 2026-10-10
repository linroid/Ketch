package com.linroid.ketch.app.state

import com.linroid.ketch.api.torrent.NaturalOrder
import com.linroid.ketch.app.i18n.UiText
import com.linroid.ketch.app.i18n.text
import com.linroid.ketch.app.util.FileKind
import ketch.app.shared.generated.resources.Res
import ketch.app.shared.generated.resources.sort_kind
import ketch.app.shared.generated.resources.sort_name
import ketch.app.shared.generated.resources.sort_progress
import ketch.app.shared.generated.resources.sort_selected_first
import ketch.app.shared.generated.resources.sort_size
import ketch.app.shared.generated.resources.sort_torrent_order
import org.jetbrains.compose.resources.StringResource

/**
 * How a torrent's file list is ordered, in the add sheet and the inspector's Files tab alike.
 * Every order breaks ties by [Torrent] order, so the result is total and stable.
 *
 * @property id what the order is saved as in [com.linroid.ketch.config.UiPreferences.sort].
 * @property descendingFirst whether the order starts descending, as Size does: largest first.
 */
internal enum class FileOrder(
  val id: String,
  val descendingFirst: Boolean,
  private val resource: StringResource,
) {
  /** Case-insensitive natural order of the full path, so "Episode 2" comes before "Episode 10". */
  Name("name", false, Res.string.sort_name),

  /** Largest first. */
  Size("size", true, Res.string.sort_size),

  /** Grouped by [FileKind] in [KIND_ORDER]: videos, audio, subtitles, images, ...; then by name. */
  Kind("kind", false, Res.string.sort_kind),

  /** Chosen files first, then by name. */
  Selected("selected", false, Res.string.sort_selected_first),

  /**
   * Files still downloading first, the furthest along on top; then finished ones, then the
   * files that are not chosen. The inspector's only.
   */
  Progress("progress", false, Res.string.sort_progress),

  /** The order of the torrent's metainfo. */
  Torrent("torrent", false, Res.string.sort_torrent_order);

  /** What the Sort menu calls it. */
  val label: UiText get() = resource.text()
}

/**
 * A [FileOrder] and its direction.
 *
 * @property descending whether the order is turned around from ascending; [FileOrder.Size]
 *   starts descending.
 */
internal data class FileSort(
  val order: FileOrder,
  val descending: Boolean = order.descendingFirst,
) {
  /** Whether the Sort menu's Reverse order is checked: the order runs against its default. */
  val reversed: Boolean get() = descending != order.descendingFirst

  /** This order turned around. */
  fun reverse(): FileSort = copy(descending = !descending)

  /** This sort as [com.linroid.ketch.config.UiPreferences.sort] keeps it, such as `size:desc`. */
  fun encode(): String = "${order.id}:${if (descending) DESC else ASC}"

  companion object {
    private const val ASC = "asc"
    private const val DESC = "desc"

    /**
     * Decodes [value] made by [encode]; a value that is missing, malformed or names an order
     * [surface] does not offer gives [surface]'s default.
     */
    fun decode(value: String?, surface: FileSortSurface): FileSort {
      val parts = value?.split(':') ?: return surface.default
      if (parts.size != 2) return surface.default
      val order = surface.orders.firstOrNull { it.id == parts[0] } ?: return surface.default
      val descending = when (parts[1]) {
        DESC -> true
        ASC -> false
        else -> return surface.default
      }
      return FileSort(order, descending)
    }
  }
}

/**
 * A list of torrent files that remembers its [FileSort] in
 * [com.linroid.ketch.config.UiPreferences.sort] under [key].
 *
 * @property orders the orders its Sort menu offers, in menu order.
 * @property default the sort it starts with, and falls back to.
 */
internal enum class FileSortSurface(
  val key: String,
  val orders: List<FileOrder>,
  val default: FileSort,
) {
  /** The add sheet's file picker. */
  Intake(
    key = "torrentFiles.intake",
    orders = listOf(
      FileOrder.Name,
      FileOrder.Size,
      FileOrder.Kind,
      FileOrder.Selected,
      FileOrder.Torrent,
    ),
    default = FileSort(FileOrder.Name),
  ),

  /** The inspector's Files tab. */
  Inspector(
    key = "torrentFiles.inspector",
    orders = listOf(
      FileOrder.Progress,
      FileOrder.Name,
      FileOrder.Size,
      FileOrder.Kind,
      FileOrder.Selected,
      FileOrder.Torrent,
    ),
    default = FileSort(FileOrder.Progress),
  ),
}

/**
 * What a [FileSort] orders one file, or one folder of the add sheet, by.
 *
 * @property path its path inside the torrent, or a folder's or file's name among its siblings;
 *   `null` for a file the torrent does not name, which Name and Kind put last.
 * @property size its size in bytes, a folder's total.
 * @property index its place in the torrent, counting from 0; a folder's first file's.
 * @property selected whether it is chosen; a folder is when any of its files is.
 * @property progress the share of it downloaded, from 0 to 1; `null` when unknown.
 * @property folder whether it is a folder, whose Kind is no kind: folders order by name.
 */
internal class FileSortKey(
  val path: String?,
  val size: Long,
  val index: Int,
  val selected: Boolean = true,
  val progress: Float? = null,
  val folder: Boolean = false,
) {
  // FileKind looks at the extension; sorting thousands of files asks for it many times.
  private var kindRank = -1

  /** Place of its kind in [KIND_ORDER]; folders and unnamed files share the first one. */
  fun kindRank(): Int {
    if (kindRank < 0) {
      kindRank = if (folder || path == null) {
        0
      } else {
        val kind = FileKind.of(path.substringAfterLast('/'))
        KIND_ORDER.indexOf(kind).let { if (it < 0) KIND_ORDER.size else it }
      }
    }
    return kindRank
  }

  /** Progress rank: 0 while downloading, 1 once done, 2 when not chosen. */
  fun progressRank(): Int = when {
    !selected -> 2
    (progress ?: 0f) >= 1f -> 1
    else -> 0
  }
}

/** The kinds of [FileOrder.Kind] in order; kinds not listed come last. */
internal val KIND_ORDER: List<FileKind> = listOf(
  FileKind.Video,
  FileKind.Audio,
  FileKind.Subtitle,
  FileKind.Image,
  FileKind.Pdf,
  FileKind.Ebook,
  FileKind.Document,
  FileKind.Text,
  FileKind.Spreadsheet,
  FileKind.Presentation,
  FileKind.Archive,
  FileKind.DiskImage,
  FileKind.App,
  FileKind.Design,
  FileKind.Model3d,
  FileKind.Font,
  FileKind.Code,
  FileKind.Data,
  FileKind.Database,
  FileKind.Web,
  FileKind.Key,
  FileKind.Email,
  FileKind.Torrent,
  FileKind.Unknown,
)

/** Orders [FileSortKey]s by [sort], ties by torrent order. */
internal fun fileComparator(sort: FileSort): Comparator<FileSortKey> {
  val byName = Comparator<FileSortKey> { a, b ->
    val pa = a.path
    val pb = b.path
    when {
      pa == null && pb == null -> 0
      pa == null -> 1
      pb == null -> -1
      else -> NaturalOrder.compare(pa, pb)
    }
  }
  val key: Comparator<FileSortKey> = when (sort.order) {
    FileOrder.Name -> byName
    FileOrder.Size -> compareBy { it.size }
    FileOrder.Kind -> compareBy<FileSortKey> { it.kindRank() }.then(byName)
    FileOrder.Selected -> compareBy<FileSortKey> { !it.selected }.then(byName)
    FileOrder.Progress ->
      compareBy<FileSortKey> { it.progressRank() }.thenByDescending { it.progress ?: 0f }
    FileOrder.Torrent -> compareBy { it.index }
  }
  val directed = if (sort.descending) key.reversed() else key
  return directed.thenBy { it.index }
}

/** These items in [sort] order, reading each one's [FileSortKey] once. */
internal fun <T> List<T>.sortedByFiles(sort: FileSort, key: (T) -> FileSortKey): List<T> {
  val comparator = fileComparator(sort)
  return map { it to key(it) }.sortedWith { a, b -> comparator.compare(a.second, b.second) }
    .map { it.first }
}
