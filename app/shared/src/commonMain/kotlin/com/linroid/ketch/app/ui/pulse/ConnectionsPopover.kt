package com.linroid.ketch.app.ui.pulse

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.linroid.ketch.app.components.KetchButton
import com.linroid.ketch.app.components.KetchButtonSize
import com.linroid.ketch.app.components.KetchButtonVariant
import com.linroid.ketch.app.components.KetchCell
import com.linroid.ketch.app.components.KetchCellGrid
import com.linroid.ketch.app.components.KetchEyebrow
import com.linroid.ketch.app.i18n.resolve
import com.linroid.ketch.app.i18n.text
import com.linroid.ketch.app.instance.DeviceScope
import com.linroid.ketch.app.state.AppState
import com.linroid.ketch.app.state.ConnectionCell
import com.linroid.ketch.app.state.ConnectionCellKey
import com.linroid.ketch.app.state.ConnectionGridState
import com.linroid.ketch.app.state.TaskConnections
import com.linroid.ketch.app.state.TrafficDirection
import com.linroid.ketch.app.theme.KetchTheme
import com.linroid.ketch.app.theme.KetchTrafficColors
import ketch.app.shared.generated.resources.Res
import ketch.app.shared.generated.resources.pulse_connection_show_task
import ketch.app.shared.generated.resources.pulse_connections_more_long
import ketch.app.shared.generated.resources.pulse_connections_none
import ketch.app.shared.generated.resources.pulse_connections_title
import ketch.app.shared.generated.resources.pulse_connections_unsupported

/**
 * The live connections of the shown devices (the Pulse bar's strip): a header with their count
 * and speeds, a legend, the grid grouped by task, and the details of the cell pointed at or
 * picked. It opens 360 dp wide above its anchor, lined up with its end, or as a bottom sheet on
 * touch.
 */
@Composable
internal fun ConnectionsPopover(
  state: AppState,
  expanded: Boolean,
  onDismissRequest: () -> Unit,
) {
  PulsePopover(
    expanded = expanded,
    onDismissRequest = onDismissRequest,
    width = PopoverWidth,
    alignment = PopoverAlignment.End,
  ) {
    ConnectionsHost(state, onShown = onDismissRequest, gridHeight = PopoverGridHeight)
  }
}

/**
 * The phone's Pulse sheet section of the live connections, with the popover's content; nothing
 * while no shown device reports its connections.
 *
 * @param onShown called once Show task showed a connection's download, to close the sheet.
 */
@Composable
internal fun ConnectionsSection(state: AppState, onShown: () -> Unit) {
  ConnectionsHost(state, onShown = onShown, gridHeight = SheetGridHeight, hideUnsupported = true)
}

@Composable
private fun ConnectionsHost(
  state: AppState,
  onShown: () -> Unit,
  gridHeight: Dp,
  hideUnsupported: Boolean = false,
) {
  val grid by state.connectionGrid.state(POPOVER_LIMIT).collectAsStateWithLifecycle()
  val scope by state.deviceScope.collectAsState()
  if (hideUnsupported && !grid.supported) return
  ConnectionsContent(
    grid = grid,
    showDevices = scope == DeviceScope.All,
    gridHeight = gridHeight,
    onShowTask = { cell ->
      state.showConnections(cell.task, segments = cell.connection.source in SEGMENTED_SOURCES)
      onShown()
    },
  )
}

/**
 * The body of the [ConnectionsPopover], drawn from [grid].
 *
 * @param showDevices whether each device's connections follow its name, as under All devices.
 * @param gridHeight the most the grid takes before it scrolls.
 * @param initialSelection the cell whose details show until another is pointed at or picked.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ConnectionsContent(
  grid: ConnectionGridState,
  showDevices: Boolean,
  onShowTask: (ConnectionCell) -> Unit,
  gridHeight: Dp = PopoverGridHeight,
  initialSelection: ConnectionCellKey? = null,
) {
  val colors = KetchTheme.colors
  val spacing = KetchTheme.spacing
  val type = KetchTheme.typography
  var hovered by remember { mutableStateOf<ConnectionCellKey?>(null) }
  var selected by remember { mutableStateOf(initialSelection) }
  Column(verticalArrangement = Arrangement.spacedBy(spacing.s3)) {
    Column(verticalArrangement = Arrangement.spacedBy(spacing.s0_5)) {
      Text(
        text = Res.string.pulse_connections_title.text().resolve(),
        style = type.titleM,
        color = colors.textPrimary,
      )
      Text(
        text = grid.summaryText().resolve(),
        style = type.caption,
        color = colors.textSecondary,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
      )
    }
    FlowRow(
      horizontalArrangement = Arrangement.spacedBy(spacing.s3),
      verticalArrangement = Arrangement.spacedBy(spacing.s1),
    ) {
      TrafficDirection.entries.forEach { direction ->
        Row(
          verticalAlignment = Alignment.CenterVertically,
          horizontalArrangement = Arrangement.spacedBy(spacing.s1),
        ) {
          val level = if (direction == TrafficDirection.Idle) 0 else KetchTrafficColors.LEVELS
          KetchCell(trafficPaint(direction, level, colors), LegendCell)
          Text(
            text = trafficLabel(direction).resolve(),
            style = type.caption,
            color = colors.textSecondary,
            maxLines = 1,
          )
        }
      }
    }
    val tasks = remember(grid) { grid.devices.flatMap { it.tasks } }
    if (tasks.isEmpty()) {
      Text(
        text = Res.string.pulse_connections_none.text().resolve(),
        style = type.body,
        color = colors.textSecondary,
        modifier = Modifier.padding(vertical = spacing.s2),
      )
    } else {
      Column(
        verticalArrangement = Arrangement.spacedBy(spacing.s2),
        modifier = Modifier
          .fillMaxWidth()
          .heightIn(max = gridHeight)
          .verticalScroll(rememberScrollState()),
      ) {
        for (device in grid.devices) {
          if (showDevices) {
            val hue = colors.deviceHue(device.deviceId)
            Text(
              text = device.name.resolve(),
              style = type.label,
              color = if (colors.isDark) hue.dark else hue.light,
              maxLines = 1,
              overflow = TextOverflow.Ellipsis,
            )
          }
          for (task in device.tasks) {
            TaskCells(
              task = task,
              shown = hovered ?: selected,
              onHover = { key ->
                // Leaving one task's cells must not clear a cell another task's grid reported.
                if (key != null || hovered in task.cells.map { it.key }) hovered = key
              },
              onSelect = { selected = it },
            )
          }
        }
        if (grid.overflow > 0) {
          Text(
            text = Res.plurals.pulse_connections_more_long.text(grid.overflow).resolve(),
            style = type.caption,
            color = colors.textTertiary,
          )
        }
      }
      val key = hovered ?: selected
      val shown = tasks.firstNotNullOfOrNull { task ->
        task.cells.firstOrNull { it.key == key }?.let { it to task }
      } ?: tasks.first().let { it.cells.first() to it }
      ConnectionDetail(shown.first, shown.second.name, onShowTask)
    }
    if (grid.unsupported.isNotEmpty()) {
      Text(
        text = Res.plurals.pulse_connections_unsupported.text(grid.unsupported.size).resolve(),
        style = type.caption,
        color = colors.textTertiary,
      )
    }
  }
}

/** A task's name and count over its cells, 12 dp each, wrapping row by row. */
@Composable
private fun TaskCells(
  task: TaskConnections,
  shown: ConnectionCellKey?,
  onHover: (ConnectionCellKey?) -> Unit,
  onSelect: (ConnectionCellKey) -> Unit,
) {
  val colors = KetchTheme.colors
  val spacing = KetchTheme.spacing
  val type = KetchTheme.typography
  val paints = remember(task.cells, colors) { task.cells.map { it.paint(colors) } }
  val index = task.cells.indexOfFirst { it.key == shown }.takeIf { it >= 0 }
  Column(verticalArrangement = Arrangement.spacedBy(spacing.s1)) {
    Row(
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(spacing.s2),
    ) {
      Text(
        text = task.name ?: task.key.taskId,
        style = type.caption,
        color = colors.textSecondary,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.weight(1f, fill = false),
      )
      Text(
        text = task.cells.size.toString(),
        style = type.numeralS,
        color = colors.textTertiary,
      )
    }
    KetchCellGrid(
      cells = paints,
      cellSize = PopoverCell,
      gap = PopoverGap,
      selected = index,
      onHover = { at -> onHover(at?.let { task.cells.getOrNull(it)?.key }) },
      onSelect = { at -> task.cells.getOrNull(at)?.let { onSelect(it.key) } },
      description = gridDescription(task.cells).resolve(),
      selectedDescription = index?.let { task.cells[it].description(task.name).resolve() },
      modifier = Modifier.fillMaxWidth(),
    )
  }
}

/** The details of [cell], of the task named [taskName], with Show task. */
@Composable
private fun ConnectionDetail(
  cell: ConnectionCell,
  taskName: String?,
  onShowTask: (ConnectionCell) -> Unit,
) {
  val colors = KetchTheme.colors
  val spacing = KetchTheme.spacing
  val type = KetchTheme.typography
  val details = remember(cell) { cell.details() }
  val shape = KetchTheme.shapes.md
  Column(
    verticalArrangement = Arrangement.spacedBy(spacing.s0_5),
    modifier = Modifier
      .fillMaxWidth()
      .clip(shape)
      .background(colors.surfaceSunken)
      .padding(spacing.s3),
  ) {
    Row(
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(spacing.s2),
    ) {
      KetchCell(cell.paint(colors), LegendCell)
      Text(
        text = taskName ?: cell.task.taskId,
        style = type.bodyStrong,
        color = colors.textPrimary,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
      )
    }
    Text(
      text = details.endpoint,
      style = type.mono,
      color = colors.textPrimary,
      maxLines = 1,
      overflow = TextOverflow.Ellipsis,
    )
    DetailLine(details.transport.resolve())
    DetailLine(details.route.resolve())
    details.peer?.let { DetailLine(it.resolve()) }
    Text(text = details.rates.resolve(), style = type.numeral, color = colors.textPrimary)
    Text(
      text = details.totals.resolve(),
      style = type.caption,
      color = colors.textTertiary,
    )
    Row(Modifier.fillMaxWidth().padding(top = spacing.s1)) {
      KetchEyebrow(
        text = trafficLabel(cell.direction).resolve(),
        modifier = Modifier.weight(1f).align(Alignment.CenterVertically),
      )
      KetchButton(
        text = Res.string.pulse_connection_show_task.text().resolve(),
        onClick = { onShowTask(cell) },
        variant = KetchButtonVariant.Ghost,
        size = KetchButtonSize.Small,
      )
    }
  }
}

@Composable
private fun DetailLine(text: String) {
  Text(
    text = text,
    style = KetchTheme.typography.caption,
    color = KetchTheme.colors.textSecondary,
    maxLines = 2,
    overflow = TextOverflow.Ellipsis,
  )
}

/** Sources whose downloads list their connections on the inspector's Connections tab. */
private val SEGMENTED_SOURCES = setOf("http", "ftp")

private val PopoverWidth = 360.dp
private val PopoverGridHeight = 300.dp
private val SheetGridHeight = 200.dp
private val PopoverCell = 12.dp
private val PopoverGap = 3.dp
private val LegendCell = 10.dp
