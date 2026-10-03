package com.linroid.ketch.app.ui.inspector.tabs

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.isSpecified
import com.linroid.ketch.api.DownloadState
import com.linroid.ketch.api.Segment
import com.linroid.ketch.app.components.KetchChip
import com.linroid.ketch.app.components.KetchFileTypeChip
import com.linroid.ketch.app.components.KetchFileTypeChipDefaults
import com.linroid.ketch.app.components.KetchMenu
import com.linroid.ketch.app.components.KetchTextField
import com.linroid.ketch.app.components.KetchTooltip
import com.linroid.ketch.app.components.LanePhase
import com.linroid.ketch.app.components.LaneStripCanvas
import com.linroid.ketch.app.components.LaneStripDefaults
import com.linroid.ketch.app.components.lanePhase
import com.linroid.ketch.app.i18n.UiText
import com.linroid.ketch.app.i18n.isEmpty
import com.linroid.ketch.app.i18n.joinText
import com.linroid.ketch.app.i18n.percentText
import com.linroid.ketch.app.i18n.resolve
import com.linroid.ketch.app.i18n.text
import com.linroid.ketch.app.i18n.verbatim
import com.linroid.ketch.app.icons.KetchIcon
import com.linroid.ketch.app.state.TaskRow
import com.linroid.ketch.app.theme.KetchDensity
import com.linroid.ketch.app.theme.KetchSpacing
import com.linroid.ketch.app.theme.KetchTheme
import ketch.app.shared.generated.resources.Res
import ketch.app.shared.generated.resources.inspector_file_description
import ketch.app.shared.generated.resources.inspector_file_description_no_share
import ketch.app.shared.generated.resources.inspector_file_unnamed
import ketch.app.shared.generated.resources.inspector_files_all_done
import ketch.app.shared.generated.resources.inspector_files_done_of
import ketch.app.shared.generated.resources.inspector_files_filter
import ketch.app.shared.generated.resources.inspector_files_map
import ketch.app.shared.generated.resources.inspector_files_no_match
import ketch.app.shared.generated.resources.inspector_files_not_selected
import ketch.app.shared.generated.resources.inspector_files_one_done
import ketch.app.shared.generated.resources.inspector_files_sort
import ketch.app.shared.generated.resources.inspector_files_waiting
import ketch.app.shared.generated.resources.inspector_percent_spoken
import ketch.app.shared.generated.resources.inspector_sort_incomplete
import ketch.app.shared.generated.resources.inspector_sort_name
import ketch.app.shared.generated.resources.inspector_sort_size
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource

/**
 * The Files tab of the inspector for a torrent: every file it downloads, with its size and
 * progress, under a map with one block per file sized by its bytes.
 *
 * Names come from the torrent's resolved file list, by id; a file it does not name is "File 12".
 * The list is virtualized and the whole tab is at most [maxHeight] tall, so a torrent of
 * thousands of files stays cheap; the summary, the sort and the map stay above it as it scrolls.
 * It sorts incomplete files first, by name or by size, and a filter field appears once there are
 * more than 20 files.
 *
 * @param maxHeight the tallest the tab may be, such as the inspector's height so the list fills
 *   it; 360 dp when unspecified.
 */
@Composable
fun FilesTab(row: TaskRow, modifier: Modifier = Modifier, maxHeight: Dp = Dp.Unspecified) {
  // The sort, the filter and the list's state never carry over from another torrent.
  key(row.key) { TorrentFiles(row, modifier, maxHeight) }
}

@Composable
private fun TorrentFiles(row: TaskRow, modifier: Modifier, maxHeight: Dp) {
  val colors = KetchTheme.colors
  val spacing = KetchTheme.spacing
  val type = KetchTheme.typography
  val density = KetchTheme.density
  val metrics = remember(spacing, density) { FileMetrics(spacing, density) }
  val files = remember(row.segments, row.request, row.state) { torrentFiles(row) }
  val listed = row.request.resolvedSource?.files?.size ?: 0
  var sort by remember { mutableStateOf(FileSort.IncompleteFirst) }
  var query by remember { mutableStateOf("") }
  val shown = remember(files, sort, query) { files.matching(query).sortedFor(sort) }
  val phase = row.state.lanePhase()

  Column(
    modifier = modifier
      .fillMaxWidth()
      .heightIn(max = if (maxHeight.isSpecified) maxHeight else metrics.maxHeight),
    verticalArrangement = Arrangement.spacedBy(spacing.s3),
  ) {
    if (files.isEmpty()) {
      Text(
        text = stringResource(Res.string.inspector_files_waiting),
        style = type.caption,
        color = colors.textTertiary,
      )
      return@Column
    }
    val (title, detail) = remember(files, listed) { filesSummary(files, listed) }
    val summary = @Composable { summaryModifier: Modifier ->
      Column(modifier = summaryModifier) {
        Text(
          text = title.resolve(),
          style = type.numeral,
          color = colors.textPrimary,
          maxLines = 1,
          overflow = TextOverflow.Ellipsis,
        )
        if (!detail.isEmpty()) {
          Text(
            text = detail.resolve(),
            style = type.caption,
            color = colors.textSecondary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
          )
        }
      }
    }
    if (files.size > FILTER_THRESHOLD) {
      summary(Modifier.fillMaxWidth())
      Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(spacing.s2),
        modifier = Modifier.fillMaxWidth(),
      ) {
        KetchTextField(
          value = query,
          onValueChange = { query = it },
          placeholder = stringResource(Res.string.inspector_files_filter),
          leadingIcon = KetchIcon.Search,
          modifier = Modifier.weight(1f),
        )
        SortChip(sort = sort, onSort = { sort = it })
      }
    } else {
      Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(spacing.s2),
        modifier = Modifier.fillMaxWidth(),
      ) {
        summary(Modifier.weight(1f))
        SortChip(sort = sort, onSort = { sort = it })
      }
    }
    val blocks = remember(files) { files.blocks() }
    val mapDescription = filesMapDescription(files).resolve()
    LaneStripCanvas(
      segments = blocks,
      phase = phase,
      progress = null,
      height = LaneStripDefaults.MapHeight,
      heads = false,
      modifier = Modifier.clearAndSetSemantics { contentDescription = mapDescription },
    )
    if (shown.isEmpty()) {
      Text(
        text = stringResource(Res.string.inspector_files_no_match, query.trim()),
        style = type.caption,
        color = colors.textTertiary,
        modifier = Modifier.padding(vertical = spacing.s2),
      )
    } else {
      BoxWithConstraints(Modifier.fillMaxWidth().weight(1f, fill = false)) {
        // Narrow lists give the chip's room to the names.
        val chips = maxWidth >= metrics.chipsFrom
        LazyColumn(modifier = Modifier.fillMaxWidth()) {
          items(shown, key = { it.id }) { file ->
            FileRow(file = file, phase = phase, metrics = metrics, chip = chips)
          }
        }
      }
    }
  }
}

@Composable
private fun SortChip(sort: FileSort, onSort: (FileSort) -> Unit) {
  var open by remember { mutableStateOf(false) }
  Box {
    KetchChip(
      label = sort.label.resolve(),
      selected = false,
      onClick = { open = true },
      trailingIcon = KetchIcon.ChevronDown,
    )
    val title = stringResource(Res.string.inspector_files_sort)
    KetchMenu(expanded = open, onDismissRequest = { open = false }, title = title) {
      for (option in FileSort.entries) {
        item(label = option.label, onClick = { onSort(option) }, checked = option == sort)
      }
    }
  }
}

@Composable
private fun FileRow(file: TorrentFile, phase: LanePhase, metrics: FileMetrics, chip: Boolean) {
  val colors = KetchTheme.colors
  val spacing = KetchTheme.spacing
  val type = KetchTheme.typography
  val size = (if (file.size >= 0) compactSizeText(file.size) else verbatim(UNKNOWN)).resolve()
  val percent = file.percent
  val name = file.label.resolve()
  val description = if (percent != null) {
    Res.string.inspector_file_description
      .text(name, size, Res.string.inspector_percent_spoken.text(percent))
  } else {
    Res.string.inspector_file_description_no_share.text(name, size)
  }.resolve()
  KetchTooltip(text = file.pathText.resolve()) {
    Row(
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(spacing.s2),
      modifier = Modifier
        .fillMaxWidth()
        .height(metrics.rowHeight)
        .clearAndSetSemantics { contentDescription = description },
    ) {
      if (chip) KetchFileTypeChip(fileName = name, size = KetchFileTypeChipDefaults.TableSize)
      FileName(name, Modifier.weight(1f))
      Text(
        text = size,
        style = type.numeral,
        color = colors.textSecondary,
        textAlign = TextAlign.End,
        maxLines = 1,
        modifier = Modifier.width(metrics.sizeWidth),
      )
      Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(spacing.s1, Alignment.End),
        modifier = Modifier.width(metrics.progressWidth),
      ) {
        if (!file.isDone && file.size > 0) {
          val own = remember(file) { listOf(Segment(0, 0, file.size - 1, file.downloaded)) }
          LaneStripCanvas(
            segments = own,
            phase = phase,
            progress = null,
            height = LaneStripDefaults.RowHeight,
            heads = false,
            // The row describes itself; a strip would read as a download's connections.
            modifier = Modifier.width(metrics.barWidth).clearAndSetSemantics {},
          )
        }
        Text(
          text = (percent?.let(::percentText) ?: verbatim(UNKNOWN)).resolve(),
          style = type.numeral,
          color = if (file.isDone) colors.textTertiary else colors.textPrimary,
          textAlign = TextAlign.End,
          maxLines = 1,
        )
      }
    }
  }
}

/**
 * A file name on one line that gives up its middle when it does not fit, so both its start and
 * its end, where episode numbers and extensions sit, stay readable.
 */
@Composable
private fun FileName(name: String, modifier: Modifier = Modifier) {
  val style = KetchTheme.typography.bodyS
  val measurer = rememberTextMeasurer()
  BoxWithConstraints(modifier) {
    val width = constraints.maxWidth
    val shown = remember(name, width, style) {
      middleEllipsis(name) { text ->
        measurer.measure(text, style, maxLines = 1, softWrap = false).size.width <= width
      }
    }
    Text(text = shown, style = style, color = KetchTheme.colors.textPrimary, maxLines = 1)
  }
}

/** Sizes of the Files tab, on the spacing scale and at the UI's [density]. */
private class FileMetrics(spacing: KetchSpacing, density: KetchDensity) {
  /** The whole tab when the caller gives no height: 360 dp. */
  val maxHeight: Dp = spacing.s16 * 5 + spacing.s10

  /** A file's row, as tall as a table row. */
  val rowHeight: Dp = density.tableRow

  /** Lists at least this wide (340 dp) show each file's type chip. */
  val chipsFrom: Dp = spacing.s16 * 5 + spacing.s5
  val sizeWidth: Dp = spacing.s12 + spacing.s2
  val progressWidth: Dp = spacing.s16
  val barWidth: Dp = spacing.s6
}

/** Orders of the Files tab. */
internal enum class FileSort(private val resource: StringResource) {
  /** Files still downloading first, then the torrent's order. */
  IncompleteFirst(Res.string.inspector_sort_incomplete),
  Name(Res.string.inspector_sort_name),

  /** Largest first. */
  Size(Res.string.inspector_sort_size);

  /** What the sort menu calls it. */
  val label: UiText get() = resource.text()
}

/**
 * One file of a torrent.
 *
 * @property id the file's id in the torrent's resolved file list.
 * @property path its path in the torrent, folders included; `null` when the list names it not.
 * @property start where it begins in the torrent's bytes.
 * @property size its size in bytes, `-1` when unknown.
 * @property downloaded bytes downloaded so far.
 * @property number its place in the torrent, counting from 1, which names an unnamed file.
 */
@Immutable
internal data class TorrentFile(
  val id: String,
  val path: String?,
  val start: Long,
  val size: Long,
  val downloaded: Long,
  val number: Int = 0,
) {
  /** The file's own name, without its folders; `null` when unnamed. */
  val name: String?
    get() = path?.substringAfterLast('/')

  /** [name] as the list shows it, or "File 12" when unnamed. */
  val label: UiText
    get() = name?.let(::verbatim) ?: Res.string.inspector_file_unnamed.text(number)

  /** [path] as its tooltip shows it, or "File 12" when unnamed. */
  val pathText: UiText
    get() = path?.let(::verbatim) ?: Res.string.inspector_file_unnamed.text(number)

  /** Whether all of a file of known size is downloaded. */
  val isDone: Boolean
    get() = size >= 0 && downloaded >= size

  /** Downloaded share in whole percent, `null` when the size is unknown. */
  val percent: Int?
    get() = when {
      size < 0 -> null
      size == 0L -> 100
      else -> (downloaded.coerceIn(0, size) * 100 / size).toInt()
    }
}

/**
 * The files [row]'s torrent downloads, in torrent order: from its segments, one per selected
 * file, once it runs, or else from its resolved file list and selection. Names come from the
 * resolved list by id.
 */
internal fun torrentFiles(row: TaskRow): List<TorrentFile> {
  val source = row.request.resolvedSource?.files.orEmpty()
  val names = source.associate { it.id to it.name }
  val completed = row.state is DownloadState.Completed
  if (row.segments.isNotEmpty()) {
    return row.segments.sortedBy { it.start }.map { segment ->
      val id = segment.index.toString()
      val size = segment.totalBytes
      TorrentFile(
        id = id,
        path = names[id],
        start = segment.start,
        size = size,
        downloaded = if (completed) size else segment.downloadedBytes.coerceIn(0, size),
        number = segment.index + 1,
      )
    }
  }
  val selected = row.request.selectedFileIds
  var offset = 0L
  return source.withIndex().filter { (_, file) -> selected.isEmpty() || file.id in selected }
    .map { (index, file) ->
      TorrentFile(
        id = file.id,
        path = file.name,
        start = offset,
        size = file.size,
        downloaded = if (completed && file.size >= 0) file.size else 0,
        number = index + 1,
      ).also { offset += file.size.coerceAtLeast(0) }
    }
}

/** How many files [torrentFiles] lists for [row], without building them. */
internal fun torrentFileCount(row: TaskRow): Int {
  if (row.segments.isNotEmpty()) return row.segments.size
  val files = row.request.resolvedSource?.files.orEmpty()
  val selected = row.request.selectedFileIds
  return if (selected.isEmpty()) files.size else files.count { it.id in selected }
}

/**
 * The two lines over the file list: how many files are done, such as "9 of 14 files done", and
 * their bytes, such as "3.2 of 7.9 GB", with the files the torrent [listed] but does not download.
 */
internal fun filesSummary(files: List<TorrentFile>, listed: Int): Pair<UiText, UiText> {
  val done = files.count { it.isDone }
  val count = files.size
  val title = when {
    done < count -> Res.plurals.inspector_files_done_of.text(count, done, count)
    count == 1 -> Res.string.inspector_files_one_done.text()
    else -> Res.plurals.inspector_files_all_done.text(count)
  }
  val known = files.filter { it.size >= 0 }
  val total = known.sumOf { it.size }
  val downloaded = known.sumOf { it.downloaded.coerceIn(0, it.size) }
  val detail = buildList {
    if (total > 0) {
      add(if (downloaded >= total) compactSizeText(total) else bytesOfText(downloaded, total))
    }
    val unselected = listed - count
    if (unselected > 0) add(Res.plurals.inspector_files_not_selected.text(unselected))
  }.joinText()
  return title to detail
}

/** What the file map says to a screen reader: "300 files, 100 done, 33 percent". */
private fun filesMapDescription(files: List<TorrentFile>): UiText {
  val known = files.filter { it.size > 0 }
  val total = known.sumOf { it.size }
  val downloaded = known.sumOf { it.downloaded.coerceIn(0, it.size) }
  val percent = if (total > 0) downloaded * 100 / total else 0
  val done = files.count { it.isDone }
  return Res.plurals.inspector_files_map.text(files.size, files.size, done, percent)
}

/** These files in [sort] order. */
internal fun List<TorrentFile>.sortedFor(sort: FileSort): List<TorrentFile> = when (sort) {
  FileSort.IncompleteFirst -> sortedWith(compareBy({ it.isDone }, { it.start }))
  FileSort.Name -> sortedWith(compareBy(nullsLast(String.CASE_INSENSITIVE_ORDER)) { it.path })
  FileSort.Size -> sortedWith(compareByDescending<TorrentFile> { it.size }.thenBy { it.start })
}

/** The files whose path contains [query], ignoring case; all of them for a blank query. */
internal fun List<TorrentFile>.matching(query: String): List<TorrentFile> {
  val text = query.trim()
  return if (text.isEmpty()) this else filter { it.path?.contains(text, ignoreCase = true) == true }
}

/**
 * One block per file of known, non-zero size, at its place in the torrent; neighbouring files
 * that are done share one block, which the map draws as one run anyway.
 */
internal fun List<TorrentFile>.blocks(): List<Segment> {
  val blocks = ArrayList<Segment>()
  for (file in sortedBy { it.start }) {
    if (file.size <= 0) continue
    val end = file.start + file.size - 1
    val last = blocks.lastOrNull()
    if (file.isDone && last != null && last.isComplete && last.end + 1 == file.start) {
      blocks[blocks.lastIndex] = Segment(last.index, last.start, end, end - last.start + 1)
    } else {
      val downloaded = file.downloaded.coerceIn(0, file.size)
      blocks += Segment(blocks.size, file.start, end, downloaded)
    }
  }
  return blocks
}

/** Torrents with more files than this get a filter field. */
internal const val FILTER_THRESHOLD: Int = 20

/** What a size or share reads while it is unknown. */
private const val UNKNOWN = "–"
