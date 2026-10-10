package com.linroid.ketch.app.ui.inspector.tabs

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.isSpecified
import com.linroid.ketch.api.DownloadState
import com.linroid.ketch.api.KetchFeatures
import com.linroid.ketch.api.Segment
import com.linroid.ketch.api.SourceFile
import com.linroid.ketch.app.components.KetchButton
import com.linroid.ketch.app.components.KetchButtonSize
import com.linroid.ketch.app.components.KetchButtonVariant
import com.linroid.ketch.app.components.KetchCheckbox
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
import com.linroid.ketch.app.feedback.MessageLevel
import com.linroid.ketch.app.i18n.UiText
import com.linroid.ketch.app.i18n.isEmpty
import com.linroid.ketch.app.i18n.joinText
import com.linroid.ketch.app.i18n.percentText
import com.linroid.ketch.app.i18n.resolve
import com.linroid.ketch.app.i18n.text
import com.linroid.ketch.app.i18n.verbatim
import com.linroid.ketch.app.icons.KetchIcon
import com.linroid.ketch.app.state.AppState
import com.linroid.ketch.app.state.FileOrder
import com.linroid.ketch.app.state.FileSort
import com.linroid.ketch.app.state.FileSortKey
import com.linroid.ketch.app.state.FileSortSurface
import com.linroid.ketch.app.state.TaskRow
import com.linroid.ketch.app.state.TorrentFilesModel
import com.linroid.ketch.app.state.awaitsFileSelection
import com.linroid.ketch.app.state.sortedByFiles
import com.linroid.ketch.app.theme.KetchDensity
import com.linroid.ketch.app.theme.KetchSpacing
import com.linroid.ketch.app.theme.KetchTheme
import com.linroid.ketch.app.ui.files.fileSortItems
import com.linroid.ketch.app.ui.list.TaskCommand
import ketch.app.shared.generated.resources.Res
import ketch.app.shared.generated.resources.inspector_file_description
import ketch.app.shared.generated.resources.inspector_file_description_no_share
import ketch.app.shared.generated.resources.inspector_file_unnamed
import ketch.app.shared.generated.resources.inspector_files_all_done
import ketch.app.shared.generated.resources.inspector_files_apply
import ketch.app.shared.generated.resources.inspector_files_choose
import ketch.app.shared.generated.resources.inspector_files_done_of
import ketch.app.shared.generated.resources.inspector_files_filter
import ketch.app.shared.generated.resources.inspector_files_hint
import ketch.app.shared.generated.resources.inspector_files_keep_one
import ketch.app.shared.generated.resources.inspector_files_map
import ketch.app.shared.generated.resources.inspector_files_no_match
import ketch.app.shared.generated.resources.inspector_files_not_selected
import ketch.app.shared.generated.resources.inspector_files_one_done
import ketch.app.shared.generated.resources.inspector_files_pending
import ketch.app.shared.generated.resources.inspector_files_reset
import ketch.app.shared.generated.resources.inspector_files_sort
import ketch.app.shared.generated.resources.inspector_files_start
import ketch.app.shared.generated.resources.inspector_files_waiting
import ketch.app.shared.generated.resources.inspector_percent_spoken
import org.jetbrains.compose.resources.stringResource

/**
 * The Files tab of the inspector for a torrent: its files, with their size and progress, under a
 * map with one block per file sized by its bytes.
 *
 * Names come from the torrent's resolved file list, by id; a device listing
 * [KetchFeatures.TORRENT_CONTROL] is asked for them when the task has none
 * ([TorrentFilesModel.load]), and only a file neither names is "File 12". On a device listing
 * [KetchFeatures.TORRENT_FILE_SELECTION] every file of a task that is not canceled is listed with
 * a checkbox: changes wait in a bar with Apply and Reset (Download for a task that waits for its
 * files), and a failure is posted and undone.
 *
 * The list is virtualized and the whole tab is at most [maxHeight] tall, so a torrent of
 * thousands of files stays cheap; the summary, the sort and the map stay above it as it scrolls.
 * Its order is remembered ([FileSortSurface.Inspector]), and a filter field appears once there
 * are more than 20 files.
 *
 * @param maxHeight the tallest the tab may be, such as the inspector's height so the list fills
 *   it; 360 dp when unspecified.
 */
@Composable
fun FilesTab(
  state: AppState,
  row: TaskRow,
  modifier: Modifier = Modifier,
  maxHeight: Dp = Dp.Unspecified,
) {
  // Collected so the tab follows what a remote device reports it supports.
  val presence by state.instanceManager.presence.collectAsState()
  val deviceId = row.key.deviceId
  val features = remember(presence, deviceId) { state.featuresOf(deviceId) }
  val controller = remember(presence, deviceId) { state.torrentControllerOf(deviceId) }
  val selectable = filesSelectable(row, features)
  val current by rememberUpdatedState(row)
  // The checks, the filter and the list's state never carry over from another torrent.
  key(row.key) {
    val model = remember(controller) {
      TorrentFilesModel(row.task, controller, state::launchCommand) { e ->
        val shown = current
        state.messages.post(
          level = MessageLevel.Error,
          title = TaskCommand.ChooseFiles.failure(verbatim(shown.name), shown.device.name),
          detail = e.message?.let(::verbatim),
          taskKey = shown.key,
          deviceId = shown.key.deviceId,
          cause = e,
        )
      }
    }
    val needsNames = model.needsNames(row.request)
    // Asked again as the task moves on, such as once a magnet's metadata arrives.
    LaunchedEffect(needsNames, row.state::class, row.segments.isEmpty()) {
      if (needsNames) model.load()
    }
    FilesTabContent(
      row = row,
      modifier = modifier,
      maxHeight = maxHeight,
      list = model.filesOf(row.request),
      model = model.takeIf { selectable },
      sort = state.appSettings.fileSort(FileSortSurface.Inspector),
      onSort = { state.appSettings.saveFileSort(FileSortSurface.Inspector, it) },
    )
  }
}

/**
 * [FilesTab]'s content for [row]: the files of [list] (else of the row's resolved file list) in
 * [sort], each with a checkbox when [model] edits the choice.
 */
@Composable
internal fun FilesTabContent(
  row: TaskRow,
  modifier: Modifier = Modifier,
  maxHeight: Dp = Dp.Unspecified,
  list: List<SourceFile>? = null,
  model: TorrentFilesModel? = null,
  sort: FileSort = FileSortSurface.Inspector.default,
  onSort: (FileSort) -> Unit = {},
) {
  val colors = KetchTheme.colors
  val spacing = KetchTheme.spacing
  val type = KetchTheme.typography
  val density = KetchTheme.density
  val metrics = remember(spacing, density) { FileMetrics(spacing, density) }
  val selectable = model != null
  val files = remember(row.segments, row.request, row.state, list, selectable) {
    torrentFiles(row, list, selectable)
  }
  val waiting = row.state.awaitsFileSelection
  val all = remember(files) { files.map { it.id } }
  val applied = remember(files, waiting) {
    if (waiting) all.toSet() else files.filter { it.selected }.mapTo(HashSet()) { it.id }
  }
  if (model != null) LaunchedEffect(model, applied) { model.sync(applied) }
  val checked = model?.checked(applied) ?: applied
  val chosen = remember(files) { files.filter { it.selected } }
  val listed = list?.size ?: row.request.resolvedSource?.files?.size ?: 0
  var query by remember { mutableStateOf("") }
  val shown = remember(files, sort, query) { files.matching(query).sortedFor(sort) }
  val phase = row.state.lanePhase()
  val orders = remember(selectable) {
    FileSortSurface.Inspector.orders.filter { selectable || it != FileOrder.Selected }
  }

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
    val (title, detail) = remember(chosen, listed, waiting, files) {
      if (waiting) {
        val total = files.sumOf { it.size.coerceAtLeast(0) }
        Res.string.inspector_files_choose.text() to compactSizeText(total)
      } else {
        filesSummary(chosen, listed)
      }
    }
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
        SortChip(sort = sort, orders = orders, onSort = onSort)
      }
    } else {
      Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(spacing.s2),
        modifier = Modifier.fillMaxWidth(),
      ) {
        summary(Modifier.weight(1f))
        SortChip(sort = sort, orders = orders, onSort = onSort)
      }
    }
    if (!waiting && chosen.isNotEmpty()) {
      val blocks = remember(chosen) { chosen.blocks() }
      val mapDescription = filesMapDescription(chosen).resolve()
      LaneStripCanvas(
        segments = blocks,
        phase = phase,
        progress = null,
        height = LaneStripDefaults.MapHeight,
        heads = false,
        modifier = Modifier.clearAndSetSemantics { contentDescription = mapDescription },
      )
    }
    if (model != null) {
      ChoiceBar(
        model = model,
        files = files,
        checked = checked,
        applied = applied,
        waiting = waiting,
      )
    }
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
            FileRow(
              file = file,
              phase = phase,
              metrics = metrics,
              chip = chips,
              // Nothing downloads before the files are chosen.
              progress = !waiting,
              check = model?.let { m ->
                FileCheck(checked = file.id in checked) { m.toggle(listOf(file.id), applied) }
              },
            )
          }
        }
      }
    }
  }
}

/**
 * Under the map while the checks differ from the files that download, or always for a task that
 * waits for its files: how many files are checked and their size, with Reset and Apply
 * (Download); then why a click changed nothing, or what Apply does.
 */
@Composable
private fun ChoiceBar(
  model: TorrentFilesModel,
  files: List<TorrentFile>,
  checked: Set<String>,
  applied: Set<String>,
  waiting: Boolean,
) {
  val colors = KetchTheme.colors
  val spacing = KetchTheme.spacing
  val type = KetchTheme.typography
  val dirty = model.isDirty(applied, waiting)
  if (dirty) {
    val bytes = remember(files, checked) {
      files.filter { it.id in checked }.sumOf { it.size.coerceAtLeast(0) }
    }
    Row(
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(spacing.s2),
      modifier = Modifier
        .fillMaxWidth()
        .background(colors.surfaceSunken, KetchTheme.shapes.sm)
        .padding(horizontal = spacing.s3, vertical = spacing.s2),
    ) {
      Text(
        text = Res.plurals.inspector_files_pending
          .text(checked.size, checked.size, compactSizeText(bytes)).resolve(),
        style = type.numeral,
        color = colors.textPrimary,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.weight(1f),
      )
      if (model.pending != null) {
        KetchButton(
          text = stringResource(Res.string.inspector_files_reset),
          onClick = model::reset,
          variant = KetchButtonVariant.Ghost,
          size = KetchButtonSize.Small,
          enabled = !model.applying,
        )
      }
      KetchButton(
        text = stringResource(
          if (waiting) Res.string.inspector_files_start else Res.string.inspector_files_apply,
        ),
        onClick = { model.apply(applied) },
        variant = KetchButtonVariant.Primary,
        size = KetchButtonSize.Small,
        loading = model.applying,
      )
    }
  }
  val note = when {
    model.keepOne -> Res.string.inspector_files_keep_one
    dirty && !waiting -> Res.string.inspector_files_hint
    else -> null
  }
  if (note != null) {
    Text(
      text = stringResource(note),
      style = type.caption,
      color = if (model.keepOne) colors.status.paused.color else colors.textSecondary,
    )
  }
}

@Composable
private fun SortChip(sort: FileSort, orders: List<FileOrder>, onSort: (FileSort) -> Unit) {
  var open by remember { mutableStateOf(false) }
  Box {
    KetchChip(
      label = sort.order.label.resolve(),
      selected = false,
      onClick = { open = true },
      trailingIcon = KetchIcon.ChevronDown,
    )
    val title = stringResource(Res.string.inspector_files_sort)
    KetchMenu(expanded = open, onDismissRequest = { open = false }, title = title) {
      fileSortItems(sort, orders, onSort)
    }
  }
}

/** A file's checkbox: whether it is [checked], and what clicking it does. */
private class FileCheck(val checked: Boolean, val onToggle: () -> Unit)

@Composable
private fun FileRow(
  file: TorrentFile,
  phase: LanePhase,
  metrics: FileMetrics,
  chip: Boolean,
  progress: Boolean,
  check: FileCheck?,
) {
  val colors = KetchTheme.colors
  val spacing = KetchTheme.spacing
  val type = KetchTheme.typography
  val size = (if (file.size >= 0) compactSizeText(file.size) else verbatim(UNKNOWN)).resolve()
  val shows = progress && file.selected
  val percent = file.percent.takeIf { shows }
  val name = file.label.resolve()
  val description = if (percent != null) {
    Res.string.inspector_file_description
      .text(name, size, Res.string.inspector_percent_spoken.text(percent))
  } else {
    Res.string.inspector_file_description_no_share.text(name, size)
  }.resolve()
  val toggle = if (check != null) {
    Modifier
      .clip(KetchTheme.shapes.sm)
      .toggleable(
        value = check.checked,
        role = Role.Checkbox,
        onValueChange = { check.onToggle() },
      )
  } else {
    Modifier
  }
  KetchTooltip(text = file.pathText.resolve()) {
    Row(
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(spacing.s2),
      modifier = Modifier
        .fillMaxWidth()
        .height(metrics.rowHeight)
        .then(toggle)
        .clearAndSetSemantics { contentDescription = description },
    ) {
      if (check != null) {
        KetchCheckbox(
          checked = check.checked,
          onCheckedChange = null,
          modifier = Modifier.size(spacing.s5),
        )
      }
      if (chip) KetchFileTypeChip(fileName = name, size = KetchFileTypeChipDefaults.TableSize)
      FileName(name, Modifier.weight(1f), dimmed = !file.selected)
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
        if (shows && !file.isDone && file.size > 0) {
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
        if (progress) {
          Text(
            text = (percent?.let(::percentText) ?: verbatim(UNKNOWN)).resolve(),
            style = type.numeral,
            color = if (file.isDone || !shows) colors.textTertiary else colors.textPrimary,
            textAlign = TextAlign.End,
            maxLines = 1,
          )
        }
      }
    }
  }
}

/**
 * A file name on one line that gives up its middle when it does not fit, so both its start and
 * its end, where episode numbers and extensions sit, stay readable.
 */
@Composable
private fun FileName(name: String, modifier: Modifier = Modifier, dimmed: Boolean = false) {
  val style = KetchTheme.typography.bodyS
  val measurer = rememberTextMeasurer()
  BoxWithConstraints(modifier) {
    val width = constraints.maxWidth
    val shown = remember(name, width, style) {
      middleEllipsis(name) { text ->
        measurer.measure(text, style, maxLines = 1, softWrap = false).size.width <= width
      }
    }
    val colors = KetchTheme.colors
    Text(
      text = shown,
      style = style,
      color = if (dimmed) colors.textSecondary else colors.textPrimary,
      maxLines = 1,
    )
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

/**
 * One file of a torrent.
 *
 * @property id the file's id in the torrent's resolved file list.
 * @property path its path in the torrent, folders included; `null` when the list names it not.
 * @property start where it begins in the torrent's bytes.
 * @property size its size in bytes, `-1` when unknown.
 * @property downloaded bytes downloaded so far.
 * @property number its place in the torrent, counting from 1, which names an unnamed file.
 * @property selected whether the task downloads it.
 */
@Immutable
internal data class TorrentFile(
  val id: String,
  val path: String?,
  val start: Long,
  val size: Long,
  val downloaded: Long,
  val number: Int = 0,
  val selected: Boolean = true,
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
 * Whether the Files tab of [row] lets the user choose its files: its device, which reports
 * [features], changes a torrent's files and the task is not canceled.
 */
internal fun filesSelectable(row: TaskRow, features: Set<String>): Boolean =
  row.isTorrent && KetchFeatures.TORRENT_FILE_SELECTION in features &&
    row.state !is DownloadState.Canceled

/**
 * The files [row]'s torrent downloads, in torrent order: from its segments, one per selected
 * file, once it runs, or else from its resolved file list and selection. Names come from [list],
 * else the resolved list, by id. When [selectable], every file of the list is listed, those it
 * does not download too, with the progress of its segment.
 */
internal fun torrentFiles(
  row: TaskRow,
  list: List<SourceFile>? = null,
  selectable: Boolean = false,
): List<TorrentFile> {
  val source = list ?: row.request.resolvedSource?.files.orEmpty()
  val completed = row.state is DownloadState.Completed
  if (selectable && source.isNotEmpty()) {
    val bySegment = row.segments.associateBy { it.index.toString() }
    val selected = row.request.selectedFileIds
    val waiting = row.state.awaitsFileSelection
    var offset = 0L
    return source.mapIndexed { index, file ->
      val chosen = waiting || selected.isEmpty() || file.id in selected
      val segment = bySegment[file.id]
      val size = segment?.totalBytes ?: file.size
      val downloaded = when {
        !chosen -> 0
        completed && size >= 0 -> size
        else -> segment?.downloadedBytes?.coerceIn(0, size.coerceAtLeast(0)) ?: 0
      }
      TorrentFile(
        id = file.id,
        path = file.name,
        start = segment?.start ?: offset,
        size = size,
        downloaded = downloaded,
        number = index + 1,
        selected = chosen,
      ).also { offset += file.size.coerceAtLeast(0) }
    }
  }
  val names = source.associate { it.id to it.name }
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

/**
 * How many files [torrentFiles] lists for [row], without building them: every file of its
 * resolved list when [selectable].
 */
internal fun torrentFileCount(row: TaskRow, selectable: Boolean = false): Int {
  val files = row.request.resolvedSource?.files.orEmpty()
  if (selectable && files.isNotEmpty()) return files.size
  if (row.segments.isNotEmpty()) return row.segments.size
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

/** These files in [sort] order; Selected first follows the files the task downloads. */
internal fun List<TorrentFile>.sortedFor(sort: FileSort): List<TorrentFile> =
  sortedByFiles(sort) { file ->
    FileSortKey(
      path = file.path,
      size = file.size,
      index = file.number - 1,
      selected = file.selected,
      progress = file.percent?.let { it / 100f },
    )
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
