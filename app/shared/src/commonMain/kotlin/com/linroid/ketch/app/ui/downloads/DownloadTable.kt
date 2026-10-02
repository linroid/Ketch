package com.linroid.ketch.app.ui.downloads

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.absoluteOffset
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.isAltPressed
import androidx.compose.ui.input.pointer.isSecondaryPressed
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.linroid.ketch.api.DownloadState
import com.linroid.ketch.app.components.KetchEyebrow
import com.linroid.ketch.app.components.KetchFileTypeChip
import com.linroid.ketch.app.components.KetchFileTypeChipDefaults
import com.linroid.ketch.app.components.KetchMenu
import com.linroid.ketch.app.components.KetchMenuScope
import com.linroid.ketch.app.components.KetchTooltip
import com.linroid.ketch.app.components.KetchTriStateCheckbox
import com.linroid.ketch.app.components.LaneStrip
import com.linroid.ketch.app.components.LaneStripDefaults
import com.linroid.ketch.app.components.PriorityGlyph
import com.linroid.ketch.app.components.StatusDot
import com.linroid.ketch.app.components.StatusDotDefaults
import com.linroid.ketch.app.components.focusRing
import com.linroid.ketch.app.components.ketchClickable
import com.linroid.ketch.app.components.rememberFocusVisibility
import com.linroid.ketch.app.icons.KetchIcon
import com.linroid.ketch.app.icons.KetchIconImage
import com.linroid.ketch.app.platform.HorizontalResizePointerIcon
import com.linroid.ketch.app.state.ListArrangement
import com.linroid.ketch.app.state.RowGroup
import com.linroid.ketch.app.state.SortKey
import com.linroid.ketch.app.state.TaskListView
import com.linroid.ketch.app.state.TaskRow
import com.linroid.ketch.app.state.formatSpeedLimit
import com.linroid.ketch.app.theme.KetchColors
import com.linroid.ketch.app.theme.KetchTheme
import com.linroid.ketch.app.ui.downloads.actions.ListActions
import com.linroid.ketch.app.ui.downloads.actions.RowFrameState
import com.linroid.ketch.app.ui.downloads.actions.SelectionCheckbox
import com.linroid.ketch.app.ui.downloads.actions.TaskRowFrame
import com.linroid.ketch.app.ui.list.FileNameText
import com.linroid.ketch.app.ui.list.GroupCollapse
import com.linroid.ketch.app.ui.list.HoverOverlay
import com.linroid.ketch.app.ui.list.TaskLazyList
import com.linroid.ketch.app.ui.list.appendError
import com.linroid.ketch.app.ui.list.placement
import com.linroid.ketch.app.ui.list.rememberRowCompletion
import com.linroid.ketch.app.ui.list.rememberStalledLanes
import com.linroid.ketch.app.ui.list.rowDivider
import com.linroid.ketch.app.ui.list.showsLanes
import com.linroid.ketch.app.ui.list.withMissingFile
import com.linroid.ketch.app.util.RowStatus
import com.linroid.ketch.app.util.SearchToken
import com.linroid.ketch.app.util.priorityLabel
import kotlin.math.roundToInt

/**
 * The dense Downloads table: a sticky header whose labels sort the rows and whose right-click
 * chooses the columns, then 36 dp rows under sticky group headers.
 *
 * Columns that do not fit hide (see [TableLayout.fit]); Name takes the room left. The reason a
 * waiting, failed or finished row is where it is spans its Progress, Speed and Left cells.
 * ⌥-clicking a row's site, origin or device adds it to the search.
 *
 * @param layout the tab's columns.
 * @param onLayoutChange saves a change to the columns, such as a width dragged or a column shown.
 * @param autoColumns columns shown whatever [layout] says, such as Device while every device
 *   shows; when one has no room, rows put the device's pennant before their name instead.
 * @param onSort sorts by a column's key; see [nextArrangement].
 * @param onAddToken adds a token to the search.
 * @param rowHeight height of a row, from the row density.
 * @param groupAction a group header's own action, such as Retry all.
 */
@Composable
internal fun DownloadTable(
  view: TaskListView,
  actions: ListActions,
  collapse: GroupCollapse,
  listState: LazyListState,
  layout: TableLayout,
  onLayoutChange: (TableLayout) -> Unit,
  onSort: (SortKey) -> Unit,
  onAddToken: (SearchToken) -> Unit,
  rowHeight: Dp,
  modifier: Modifier = Modifier,
  autoColumns: Set<TableColumn> = emptySet(),
  groupAction: @Composable RowScope.(RowGroup) -> Unit = {},
) {
  val spacing = KetchTheme.spacing
  // A width being dragged shows at once and is saved when the drag ends.
  var dragged by remember(layout) { mutableStateOf(layout) }
  BoxWithConstraints(modifier) {
    val shown = autoColumns.fold(dragged) { shown, column -> shown.withVisible(column, true) }
    val columns = shown.fit(maxWidth, TablePadding)
    val namePennant = TableColumn.Device in autoColumns &&
      columns.none { it.column == TableColumn.Device }
    Column(Modifier.fillMaxSize()) {
      TableHeader(
        view = view,
        actions = actions,
        columns = columns,
        layout = dragged,
        autoColumns = autoColumns,
        onResize = { column, width -> dragged = dragged.withWidth(column, width) },
        onResizeEnd = { onLayoutChange(dragged) },
        onLayoutChange = onLayoutChange,
        onSort = onSort,
      )
      TaskLazyList(
        groups = view.groups,
        actions = actions,
        collapse = collapse,
        listState = listState,
        headerHeight = spacing.tableGroupHeaderHeight,
        headerPadding = TablePadding + spacing.s2,
        modifier = Modifier.weight(1f).fillMaxWidth(),
        groupAction = groupAction,
        row = { row ->
          TableRow(
            task = row,
            actions = actions,
            columns = columns,
            height = rowHeight,
            onAddToken = onAddToken,
            pennant = namePennant,
            modifier = placement().addedRow(row.key),
          )
        },
      )
    }
  }
}

/**
 * The arrangement after clicking the [key] column's header: the first click sorts by it in its
 * usual direction, the second reverses it, and the third goes back to Smart order.
 */
internal fun nextArrangement(current: ListArrangement, key: SortKey): ListArrangement = when {
  current.sort != key -> current.copy(sort = key, descending = key.descendingFirst)
  current.descending == key.descendingFirst -> current.copy(descending = !current.descending)
  else -> current.copy(sort = SortKey.Smart, descending = false)
}

@Composable
private fun TableHeader(
  view: TaskListView,
  actions: ListActions,
  columns: List<ColumnSetting>,
  layout: TableLayout,
  autoColumns: Set<TableColumn>,
  onResize: (TableColumn, Dp) -> Unit,
  onResizeEnd: () -> Unit,
  onLayoutChange: (TableLayout) -> Unit,
  onSort: (SortKey) -> Unit,
) {
  val colors = KetchTheme.colors
  val spacing = KetchTheme.spacing
  var chooserAt by remember { mutableStateOf<Offset?>(null) }
  Box {
    Row(
      verticalAlignment = Alignment.CenterVertically,
      modifier = Modifier
        .fillMaxWidth()
        .height(spacing.tableHeaderHeight)
        .background(colors.surfaceSunken)
        .rowDivider(colors.hairline)
        .pointerInput(Unit) {
          awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
            if (currentEvent.buttons.isSecondaryPressed) {
              down.consume()
              chooserAt = down.position
            }
          }
        }
        .padding(horizontal = TablePadding),
    ) {
      Box(Modifier.width(TableColumn.StatusDotWidth), contentAlignment = Alignment.Center) {
        SelectAllBox(view, actions)
      }
      HeaderCell(
        label = "Name",
        sort = SortKey.Name,
        arrangement = view.arrangement,
        numeric = false,
        onSort = onSort,
        modifier = Modifier.weight(1f),
      )
      columns.forEachIndexed { index, setting ->
        val column = setting.column
        Box(Modifier.width(setting.width).fillMaxHeight()) {
          HeaderCell(
            label = column.label,
            sort = column.sort,
            arrangement = view.arrangement,
            numeric = column.numeric,
            onSort = onSort,
            modifier = Modifier.fillMaxSize().then(columnLead(columns, index)),
          )
          ResizeHandle(
            width = setting.width,
            onResize = { onResize(column, it) },
            onResizeEnd = onResizeEnd,
            modifier = Modifier.align(Alignment.CenterStart),
          )
        }
      }
    }
    val at = chooserAt
    if (at != null) {
      Box(Modifier.absoluteOffset { IntOffset(at.x.roundToInt(), at.y.roundToInt()) }) {
        KetchMenu(
          expanded = true,
          onDismissRequest = { chooserAt = null },
          offset = DpOffset(0.dp, -spacing.s1),
        ) {
          columnChooser(layout, autoColumns, onLayoutChange)
        }
      }
    }
  }
}

/**
 * The column chooser: every column that can hide, checked when shown, and Reset columns.
 * Opened by right-clicking the table header and from "⋯ › Columns". [autoColumns] show
 * whatever the layout says, so they are checked and cannot be picked.
 */
internal fun KetchMenuScope.columnChooser(
  layout: TableLayout,
  autoColumns: Set<TableColumn> = emptySet(),
  onLayoutChange: (TableLayout) -> Unit,
) {
  header("Columns")
  for (setting in layout.columns.filterNot { it.column.fixed }) {
    val auto = setting.column in autoColumns
    item(
      label = setting.column.title,
      caption = if (auto) "Shown for All devices" else null,
      checked = setting.visible || auto,
      enabled = !auto,
      keepOpen = true,
      onClick = { onLayoutChange(layout.withVisible(setting.column, !setting.visible)) },
    )
  }
  divider()
  item(
    label = "Reset columns",
    enabled = layout != TableLayout(),
    onClick = { onLayoutChange(TableLayout()) },
  )
}

/** The column's name in menus, spelled out where the header abbreviates it. */
internal val TableColumn.title: String
  get() = if (this == TableColumn.Connections) "Connections" else label

/** Selects every row on screen, or clears the selection when all of them are selected. */
@Composable
private fun SelectAllBox(view: TaskListView, actions: ListActions) {
  val keys = view.keys
  val selected = keys.count { actions.isSelected(it) }
  val state = when {
    selected == 0 -> ToggleableState.Off
    selected == keys.size -> ToggleableState.On
    else -> ToggleableState.Indeterminate
  }
  KetchTooltip(text = if (state == ToggleableState.On) "Clear selection" else "Select all") {
    KetchTriStateCheckbox(
      state = state,
      onClick = {
        if (state == ToggleableState.On) actions.selection.clear() else actions.selectAll()
      },
      modifier = Modifier.semantics {
        contentDescription = if (state == ToggleableState.On) "Clear selection" else "Select all"
      },
    )
  }
}

@Composable
private fun HeaderCell(
  label: String,
  sort: SortKey,
  arrangement: ListArrangement,
  numeric: Boolean,
  onSort: (SortKey) -> Unit,
  modifier: Modifier = Modifier,
) {
  val colors = KetchTheme.colors
  val spacing = KetchTheme.spacing
  val active = arrangement.sort == sort
  val interactions = remember { MutableInteractionSource() }
  val hovered by interactions.collectIsHoveredAsState()
  val focus = rememberFocusVisibility()
  val ink = if (active || hovered) colors.textPrimary else colors.textTertiary
  val direction = if (arrangement.descending) "descending" else "ascending"
  Row(
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(
      spacing.s1,
      if (numeric) Alignment.End else Alignment.Start
    ),
    modifier = modifier
      .fillMaxHeight()
      .focusRing(focus.visible, KetchTheme.shapes.xs, colors.focusRing, gap = -spacing.s0_5)
      .hoverable(interactions)
      .ketchClickable(
        interactions = interactions,
        focus = focus,
        onClickLabel = "Sort by $label",
        onClick = { onSort(sort) },
      )
      .semantics {
        if (active) contentDescription = "$label, sorted $direction"
      }
      .padding(horizontal = CellPadding),
  ) {
    if (numeric && active) SortChevron(arrangement.descending, ink)
    KetchEyebrow(label, color = ink, maxLines = 1)
    if (!numeric && active) SortChevron(arrangement.descending, ink)
  }
}

@Composable
private fun SortChevron(descending: Boolean, tint: Color) {
  KetchIconImage(
    icon = if (descending) KetchIcon.ChevronDown else KetchIcon.ChevronUp,
    size = KetchTheme.spacing.s3,
    tint = tint,
  )
}

/**
 * The 6 dp handle on the boundary before a column, drawn as a short separator. The columns after
 * Name keep to the table's end, so dragging the boundary toward Name widens the column and the
 * boundary follows the pointer.
 */
@Composable
private fun ResizeHandle(
  width: Dp,
  onResize: (Dp) -> Unit,
  onResizeEnd: () -> Unit,
  modifier: Modifier = Modifier,
) {
  val colors = KetchTheme.colors
  val pixels = LocalDensity.current
  val interactions = remember { MutableInteractionSource() }
  val hovered by interactions.collectIsHoveredAsState()
  var start by remember { mutableStateOf(width) }
  var moved by remember { mutableStateOf(0f) }
  val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
  val line = if (hovered) colors.borderStrong else colors.hairline
  Box(
    modifier = modifier
      .width(ResizeHandleWidth)
      .fillMaxHeight()
      .pointerHoverIcon(HorizontalResizePointerIcon)
      .hoverable(interactions)
      .draggable(
        state = rememberDraggableState { delta ->
          moved += if (rtl) delta else -delta
          onResize(start + with(pixels) { moved.toDp() })
        },
        orientation = Orientation.Horizontal,
        onDragStarted = {
          start = width
          moved = 0f
        },
        onDragStopped = { onResizeEnd() },
      )
      .drawBehind {
        // On the boundary itself, as far from the labels on either side.
        val half = density / 2
        val x = if (layoutDirection == LayoutDirection.Ltr) half else size.width - half
        val inset = size.height / 4
        drawLine(line, Offset(x, inset), Offset(x, size.height - inset), strokeWidth = density)
      },
  )
}

@Composable
private fun TableRow(
  task: TaskRow,
  actions: ListActions,
  columns: List<ColumnSetting>,
  height: Dp,
  onAddToken: (SearchToken) -> Unit,
  pennant: Boolean,
  modifier: Modifier = Modifier,
) {
  val colors = KetchTheme.colors
  val row = withMissingFile(task, actions.runner.isFileMissing(task))
  val completion = rememberRowCompletion(row)
  val lanes = showsLanes(row, completion)
  TaskRowFrame(
    row = row,
    actions = actions,
    modifier = modifier
      .fillMaxWidth()
      .height(height)
      .rowDivider(colors.divider, TablePadding + TableColumn.StatusDotWidth + CellPadding),
  ) { frame ->
    Row(
      verticalAlignment = Alignment.CenterVertically,
      modifier = Modifier.fillMaxSize().padding(horizontal = TablePadding),
    ) {
      Box(Modifier.width(TableColumn.StatusDotWidth), contentAlignment = Alignment.Center) {
        if (frame.selecting || frame.hovered) {
          SelectionCheckbox(row, actions)
        } else {
          StatusDot(row.content.status, size = StatusDotDefaults.TableSize)
        }
      }
      NameCell(row, completion.showsCheck, pennant, onAddToken, Modifier.weight(1f))
      var index = 0
      while (index < columns.size) {
        val setting = columns[index]
        if (setting.column.inReasonSpan && spansReason(row, lanes)) {
          val start = index
          var width = 0.dp
          while (index < columns.size && columns[index].column.inReasonSpan) {
            width += columns[index].width
            index++
          }
          ReasonCell(row, Modifier.width(width).then(columnLead(columns, start)))
          continue
        }
        Cell(
          row = row,
          setting = setting,
          lanes = lanes,
          onSheenShown = completion::onSheenShown,
          onAddToken = onAddToken,
          frame = frame,
          modifier = columnLead(columns, index),
        )
        index++
      }
    }
    val placement = remember(columns) { hoverPlacement(columns) }
    HoverOverlay(row, actions, frame, placement = placement)
  }
}

/**
 * Where a row's hover actions go: over its Left and Added cells, so the name, speed, status and
 * device stay readable; at the row's end when neither shows.
 */
private fun hoverPlacement(columns: List<ColumnSetting>): Modifier {
  val spanned = columns.indices.filter { columns[it].column in HoverColumns }
  if (spanned.isEmpty()) return Modifier
  val after = columns.drop(spanned.last() + 1).fold(TablePadding) { sum, it -> sum + it.width }
  val span = columns.slice(spanned.first()..spanned.last()).map { it.width }.reduce(Dp::plus)
  return Modifier.padding(end = after).widthIn(min = span)
}

/**
 * Extra space before a left-aligned column that follows a right-aligned one, such as Progress
 * after Size, so a number and the text after it don't read as one.
 */
@Composable
private fun columnLead(columns: List<ColumnSetting>, index: Int): Modifier {
  val follows = index > 0 && columns[index - 1].column.numeric && !columns[index].column.numeric
  return if (follows) Modifier.padding(start = KetchTheme.spacing.s2) else Modifier
}

/** Whether [row] shows its reason across Progress, Speed and Left instead of numbers. */
private fun spansReason(row: TaskRow, lanes: Boolean): Boolean = when (row.state) {
  is DownloadState.Downloading, is DownloadState.Paused -> false
  is DownloadState.Completed -> !lanes
  else -> true
}

@Composable
private fun NameCell(
  row: TaskRow,
  showCheck: Boolean,
  pennant: Boolean,
  onAddToken: (SearchToken) -> Unit,
  modifier: Modifier = Modifier,
) {
  val colors = KetchTheme.colors
  val spacing = KetchTheme.spacing
  Row(
    verticalAlignment = Alignment.CenterVertically,
    modifier = modifier.padding(horizontal = CellPadding),
  ) {
    if (pennant) {
      RowPennant(
        row = row,
        modifier = Modifier
          .padding(end = spacing.s2)
          .altClick { onAddToken(SearchToken.Device(row.device.name)) },
      )
    }
    KetchFileTypeChip(
      fileName = row.name,
      sourceUrl = row.request.url,
      size = KetchFileTypeChipDefaults.TableSize,
      showCheck = showCheck,
      modifier = Modifier.altClick { onAddToken(SearchToken.Type(row.fileType)) },
    )
    FileNameText(
      text = row.name,
      style = KetchTheme.typography.cellStrong,
      color = colors.textPrimary,
      modifier = Modifier.weight(1f, fill = false).padding(start = spacing.s2),
    )
    PriorityGlyph(row.request.priority, Modifier.padding(start = spacing.s1))
    val limit = row.request.speedLimit
    if (!limit.isUnlimited && row.state.isLive) {
      CapPill(formatSpeedLimit(limit), Modifier.padding(start = spacing.s1))
    }
  }
}

/** Whether a task in this state can still download, so a speed cap still applies to it. */
private val DownloadState.isLive: Boolean
  get() = this is DownloadState.Downloading || this is DownloadState.Paused ||
    this is DownloadState.Queued || this is DownloadState.Scheduled

/** A per-task speed cap after the name, such as "2 MB/s". */
@Composable
private fun CapPill(text: String, modifier: Modifier = Modifier) {
  val colors = KetchTheme.colors
  val spacing = KetchTheme.spacing
  KetchTooltip(text = "Capped at $text", modifier = modifier) {
    Row(
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(spacing.s0_5),
      modifier = Modifier
        .height(spacing.s4)
        .background(colors.surfaceSunken, KetchTheme.shapes.full)
        .padding(horizontal = spacing.s1),
    ) {
      KetchIconImage(KetchIcon.SlowLane, size = spacing.s3, tint = colors.textSecondary)
      Text(text, style = KetchTheme.typography.numeralS, color = colors.textSecondary, maxLines = 1)
    }
  }
}

@Composable
private fun Cell(
  row: TaskRow,
  setting: ColumnSetting,
  lanes: Boolean,
  onSheenShown: () -> Unit,
  onAddToken: (SearchToken) -> Unit,
  frame: RowFrameState,
  modifier: Modifier = Modifier,
) {
  val colors = KetchTheme.colors
  val spacing = KetchTheme.spacing
  val content = row.content
  val cell = Modifier.width(setting.width).then(modifier).padding(horizontal = CellPadding)
  val muted = if (frame.selected) colors.textSecondary else colors.textTertiary
  when (setting.column) {
    TableColumn.Size -> NumberCell(content.size, cell, color = colors.textSecondary)
    TableColumn.Progress -> Row(
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(spacing.s2),
      modifier = cell,
    ) {
      if (lanes) {
        LaneStrip(
          state = row.state,
          segments = row.segments,
          height = LaneStripDefaults.CellHeight,
          stalled = rememberStalledLanes(row),
          onCompletionShown = onSheenShown,
          modifier = Modifier.weight(1f),
        )
        val progress = content.progress
        if (progress != null && row.state !is DownloadState.Completed) {
          Text(
            text = "${(progress * 100).toInt()}%",
            style = KetchTheme.typography.numeralS,
            color = colors.textSecondary,
            textAlign = TextAlign.End,
            maxLines = 1,
            modifier = Modifier.width(PercentWidth),
          )
        }
      }
    }
    TableColumn.Speed -> SpeedCell(row, cell)
    TableColumn.Left -> NumberCell(content.time, cell, color = colors.textSecondary)
    TableColumn.Added -> TextCell(content.added, cell, color = colors.textSecondary)
    TableColumn.Status -> TextCell(
      text = content.statusText,
      modifier = cell,
      color = if (content.status == RowStatus.Failed) colors.status.failed.color else {
        colors.textSecondary
      },
    )
    TableColumn.Connections -> Row(
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(spacing.s1, Alignment.End),
      modifier = cell,
    ) {
      val connections = row.connections
      if (connections != null && connections > 0) {
        Text(
          text = connections.toString(),
          style = KetchTheme.typography.numeral,
          color = colors.textSecondary,
          maxLines = 1,
        )
        LaneStrip(
          state = row.state,
          segments = row.segments,
          height = LaneStripDefaults.LaneHeight,
          modifier = Modifier.width(MiniStripWidth),
        )
      }
    }
    TableColumn.Source -> {
      val host = row.sourceHost
      TextCell(
        text = host ?: if (row.isTorrent) "Magnet" else "–",
        modifier = cell.altClick { host?.let { onAddToken(SearchToken.Host(it)) } },
        color = if (host == null) muted else colors.textSecondary,
      )
    }
    TableColumn.Origin -> {
      val origin = row.origin
      TextCell(
        text = origin?.label ?: "Unknown",
        modifier = cell.altClick { origin?.let { onAddToken(SearchToken.Origin(it)) } },
        color = if (origin == null) muted else colors.textSecondary,
      )
    }
    TableColumn.Priority -> Row(
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(spacing.s1),
      modifier = cell,
    ) {
      PriorityGlyph(row.request.priority)
      Text(
        text = priorityLabel(row.request.priority),
        style = KetchTheme.typography.cell,
        color = colors.textSecondary,
        maxLines = 1,
      )
    }
    TableColumn.Device -> Row(
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(spacing.s2),
      modifier = cell.altClick { onAddToken(SearchToken.Device(row.device.name)) },
    ) {
      RowPennant(row)
      Text(
        text = row.device.name,
        style = KetchTheme.typography.cell,
        color = colors.textSecondary,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
      )
    }
  }
}

/** The speed while downloading, marked when a limit caps it; blank in other states. */
@Composable
private fun SpeedCell(row: TaskRow, modifier: Modifier) {
  val colors = KetchTheme.colors
  val spacing = KetchTheme.spacing
  val content = row.content
  Row(
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(spacing.s0_5, Alignment.End),
    modifier = modifier,
  ) {
    if (row.state !is DownloadState.Downloading) return@Row
    val stalled = content.status == RowStatus.Stalled
    Text(
      text = content.speed.ifEmpty { "–" },
      style = KetchTheme.typography.numeral,
      color = if (stalled) colors.status.paused.color else colors.textPrimary,
      maxLines = 1,
    )
    if (content.limited) {
      KetchTooltip(text = "A speed limit caps this download") {
        KetchIconImage(KetchIcon.SlowLane, size = spacing.s3, tint = colors.status.paused.color)
      }
    }
  }
}

/** Why the row waits, failed or how its download went, across the cells it spans. */
@Composable
private fun ReasonCell(row: TaskRow, modifier: Modifier) {
  val colors = KetchTheme.colors
  Text(
    text = reasonText(row, colors),
    style = KetchTheme.typography.caption,
    color = colors.textSecondary,
    maxLines = 1,
    overflow = TextOverflow.Ellipsis,
    modifier = modifier.padding(horizontal = CellPadding),
  )
}

/** A row's reason: a failure's title in the failed color and its hint, else its detail. */
internal fun reasonText(row: TaskRow, colors: KetchColors): AnnotatedString {
  val error = row.content.error ?: return AnnotatedString(row.content.detail)
  return buildAnnotatedString { appendError(error, colors) }
}

@Composable
private fun NumberCell(text: String, modifier: Modifier, color: Color) {
  Text(
    text = text,
    style = KetchTheme.typography.numeral,
    color = if (text == "–") KetchTheme.colors.textTertiary else color,
    textAlign = TextAlign.End,
    maxLines = 1,
    overflow = TextOverflow.Clip,
    modifier = modifier,
  )
}

@Composable
private fun TextCell(text: String, modifier: Modifier, color: Color) {
  Text(
    text = text,
    style = KetchTheme.typography.cell,
    color = color,
    maxLines = 1,
    overflow = TextOverflow.Ellipsis,
    modifier = modifier,
  )
}

/**
 * Runs [onAltClick] when the pointer presses here with ⌥ (Alt) held, and keeps that press from
 * selecting the row.
 */
@Composable
private fun Modifier.altClick(onAltClick: () -> Unit): Modifier {
  val action by rememberUpdatedState(onAltClick)
  return pointerInput(Unit) {
    awaitEachGesture {
      val down = awaitFirstDown(requireUnconsumed = false)
      if (currentEvent.keyboardModifiers.isAltPressed) {
        down.consume()
        action()
      }
    }
  }
}

/** Space between the table's edge and its first and last cells. */
internal val TablePadding: Dp = 8.dp

/** The cells a row's hover actions stand in for while the pointer is over it. */
private val HoverColumns = setOf(TableColumn.Left, TableColumn.Added)

/** Horizontal padding inside each cell, so neighbouring cells are 8 dp apart. */
private val CellPadding: Dp = 4.dp
private val ResizeHandleWidth: Dp = 6.dp
private val PercentWidth: Dp = 32.dp
private val MiniStripWidth: Dp = 24.dp
