package com.linroid.ketch.app.ui.list

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import com.linroid.ketch.api.DownloadProgress
import com.linroid.ketch.api.DownloadState
import com.linroid.ketch.app.components.KetchFileTypeChip
import com.linroid.ketch.app.components.KetchFileTypeChipDefaults
import com.linroid.ketch.app.components.KetchIconButton
import com.linroid.ketch.app.components.KetchSpinner
import com.linroid.ketch.app.components.LaneStrip
import com.linroid.ketch.app.components.LaneStripDefaults
import com.linroid.ketch.app.components.PriorityGlyph
import com.linroid.ketch.app.components.StatusDot
import com.linroid.ketch.app.icons.KetchIcon
import com.linroid.ketch.app.icons.KetchIconImage
import com.linroid.ketch.app.state.RowAction
import com.linroid.ketch.app.state.TaskRow
import com.linroid.ketch.app.state.formatSpeedLimit
import com.linroid.ketch.app.theme.KetchColors
import com.linroid.ketch.app.theme.KetchDensity
import com.linroid.ketch.app.theme.KetchTheme
import com.linroid.ketch.app.ui.downloads.LocalShownDevices
import com.linroid.ketch.app.ui.downloads.RowPennant
import com.linroid.ketch.app.ui.downloads.actions.HoverActions
import com.linroid.ketch.app.ui.downloads.actions.ListActions
import com.linroid.ketch.app.ui.downloads.actions.RowFrameState
import com.linroid.ketch.app.ui.downloads.actions.SelectionCheckbox
import com.linroid.ketch.app.ui.downloads.actions.TaskRowFrame
import com.linroid.ketch.app.ui.downloads.actions.icon
import com.linroid.ketch.app.ui.downloads.actions.rowActionLabel
import com.linroid.ketch.app.ui.downloads.addedRow
import com.linroid.ketch.app.util.ErrorCopy
import com.linroid.ketch.app.util.RowStatus
import com.linroid.ketch.app.util.formatSizeOf
import kotlinx.coroutines.launch

/**
 * A task as a two-line row, for narrow cards and touch: the file chip, the name with its
 * priority, a second line saying where the download stands or why it waits or failed, and a
 * lane strip while it runs.
 *
 * With a pointer the line holds a metric at its end, such as the speed and time left, and the
 * row's hover actions fade in over it. On touch the metric joins the second line, the row's
 * primary action sits at its end, and swiping pauses or resumes it (start to end) or removes it
 * with Undo (end to start).
 */
@Composable
internal fun DownloadListRow(
  row: TaskRow,
  actions: ListActions,
  modifier: Modifier = Modifier,
) {
  val touch = KetchTheme.density == KetchDensity.Comfortable
  if (touch) {
    SwipeableRow(row, actions, modifier) { RowBody(row, actions, touch = true) }
  } else {
    RowBody(row, actions, touch = false, modifier = modifier)
  }
}

@Composable
private fun RowBody(
  task: TaskRow,
  actions: ListActions,
  touch: Boolean,
  modifier: Modifier = Modifier,
) {
  val row = withMissingFile(task, actions.runner.isFileMissing(task))
  val colors = KetchTheme.colors
  val spacing = KetchTheme.spacing
  val dims = KetchTheme.density
  val completion = rememberRowCompletion(row)
  val lanes = showsLanes(row, completion)
  val chip = if (touch) KetchFileTypeChipDefaults.TouchSize else KetchFileTypeChipDefaults.ListSize
  TaskRowFrame(
    row = row,
    actions = actions,
    shape = KetchTheme.shapes.md,
    modifier = modifier
      .fillMaxWidth()
      .padding(horizontal = spacing.s1)
      .addedRow(task.key, KetchTheme.shapes.md)
      .rowDivider(colors.divider, spacing.s3 + chip + spacing.s3),
  ) { frame ->
    Row(
      verticalAlignment = Alignment.CenterVertically,
      modifier = Modifier
        .fillMaxWidth()
        .heightIn(min = if (lanes) dims.listRowWithLanes else dims.listRow)
        .padding(horizontal = spacing.s3, vertical = spacing.s2),
    ) {
      Box(Modifier.size(chip), contentAlignment = Alignment.Center) {
        if (frame.selecting) {
          SelectionCheckbox(row, actions)
        } else {
          KetchFileTypeChip(
            fileName = row.name,
            sourceUrl = row.request.url,
            size = chip,
            showCheck = completion.showsCheck,
          )
        }
      }
      Column(
        verticalArrangement = Arrangement.spacedBy(spacing.s0_5),
        modifier = Modifier.weight(1f).padding(start = spacing.s3),
      ) {
        FirstLine(row, metric = !touch, hovered = frame.hovered)
        Text(
          text = secondLine(row, touch, colors),
          style = KetchTheme.typography.caption,
          color = colors.textSecondary,
          maxLines = 2,
          overflow = TextOverflow.Ellipsis,
        )
        if (lanes) {
          LaneStrip(
            state = row.state,
            segments = row.segments,
            stalled = rememberStalledLanes(row),
            onCompletionShown = completion::onSheenShown,
            modifier = Modifier.fillMaxWidth().padding(top = spacing.s1),
          )
        }
      }
      if (touch) TrailingAction(row, actions)
    }
    if (!touch) HoverOverlay(row, actions, frame, aboveLanes = lanes)
  }
}

/**
 * The name, its priority and, with a pointer, the metric at the end, which gives way to the
 * hover actions while [hovered]. Under All devices the device's pennant leads the name.
 */
@Composable
private fun FirstLine(row: TaskRow, metric: Boolean, hovered: Boolean) {
  val colors = KetchTheme.colors
  val spacing = KetchTheme.spacing
  Row(verticalAlignment = Alignment.CenterVertically) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
      if (LocalShownDevices.current.several) {
        RowPennant(row, Modifier.padding(end = spacing.s2))
      }
      FileNameText(
        text = row.name,
        style = KetchTheme.typography.bodyStrong,
        color = colors.textPrimary,
        modifier = Modifier.weight(1f, fill = false),
      )
      PriorityGlyph(row.request.priority, Modifier.padding(start = spacing.s1))
    }
    if (metric) {
      Box(Modifier.padding(start = spacing.s3).alpha(if (hovered) 0f else 1f)) { Metric(row) }
    }
  }
}

/**
 * What a pointer row shows at the end of its first line: the speed and time left while it
 * downloads, the date it was added once finished, otherwise its status.
 */
@Composable
private fun Metric(row: TaskRow) {
  val colors = KetchTheme.colors
  val content = row.content
  when {
    content.status == RowStatus.Downloading -> Text(
      text = listOf(content.speed, content.time).filter { it.isNotEmpty() }.joinToString(" · "),
      style = KetchTheme.typography.numeral,
      color = colors.textSecondary,
      maxLines = 1,
    )
    row.state is DownloadState.Completed && content.status == RowStatus.Completed -> Text(
      text = content.added,
      style = KetchTheme.typography.numeral,
      color = colors.textTertiary,
      maxLines = 1,
    )
    else -> StatusDot(content.status, label = content.statusText)
  }
}

/**
 * The second line: the size so far and where the download stands, the reason it waits, how
 * long it took, or a failure's title in the failed color and its hint. On [touch] a running
 * download shows its speed and time left here too.
 */
internal fun secondLine(row: TaskRow, touch: Boolean, colors: KetchColors): AnnotatedString {
  val content = row.content
  val state = row.state
  val error = content.error
  val size = when (state) {
    is DownloadState.Downloading -> sizeOf(state.progress)
    is DownloadState.Paused -> sizeOf(state.progress)
    is DownloadState.Completed -> content.size.takeIf { it != UNKNOWN }
    else -> null
  }
  // The line wraps between its parts, never inside a size, speed or time.
  val parts = buildList {
    size?.let { add(it.unbroken()) }
    if (touch && content.status == RowStatus.Downloading) {
      add(content.speed.unbroken())
      content.time.takeIf { it.isNotEmpty() && it != UNKNOWN }?.let { add(it.unbroken()) }
    } else if (error == null) {
      // A queue reason is a sentence and may wrap; the other details are short facts.
      if (state is DownloadState.Queued) {
        add(content.detail)
      } else {
        content.detail.split(SEPARATOR).forEach { add(it.unbroken()) }
      }
    }
    val limit = row.request.speedLimit
    if (state is DownloadState.Downloading && !limit.isUnlimited) {
      add("limit ${formatSpeedLimit(limit)}".unbroken())
    }
  }
  return buildAnnotatedString {
    if (error != null) {
      appendError(error, colors)
      if (parts.isNotEmpty()) append(SEPARATOR)
    }
    append(parts.joinToString(SEPARATOR))
  }
}

/** Appends [error]'s title in the failed color, then its short hint. */
internal fun AnnotatedString.Builder.appendError(error: ErrorCopy, colors: KetchColors) {
  withStyle(SpanStyle(color = colors.status.failed.color)) { append(error.title) }
  error.shortHint?.let { hint ->
    append(SEPARATOR)
    append(hint)
  }
}

/** This text with no-break spaces, so a line never wraps inside it. */
private fun String.unbroken(): String = replace(' ', NO_BREAK_SPACE)

private fun sizeOf(progress: DownloadProgress): String? =
  progress.totalBytes.takeIf { it > 0 }
    ?.let { formatSizeOf(progress.downloadedBytes, it, separator = " of ") }

/** A touch row's primary action, such as Pause or Retry, in a 44 dp button. */
@Composable
private fun TrailingAction(row: TaskRow, actions: ListActions) {
  val runner = actions.runner
  val pending by runner.state.pending.collectAsState()
  val action = runner.primary(row) ?: return
  val spacing = KetchTheme.spacing
  Box(
    contentAlignment = Alignment.Center,
    modifier = Modifier.padding(start = spacing.s1).size(KetchTheme.density.iconButtonTarget),
  ) {
    if (RowCommands.isBusy(row, pending) && action != RowAction.Open) {
      KetchSpinner()
    } else {
      KetchIconButton(
        icon = action.icon,
        contentDescription = rowActionLabel(action, runner.files?.revealLabel),
        onClick = { runner.run(action, listOf(row)) },
        tint = if (action == RowAction.Open) KetchTheme.colors.textSecondary else {
          KetchTheme.colors.accentText
        },
      )
    }
  }
}

/**
 * A pointer row's hover actions at its end, over a fade into the row's hover fill so the metric
 * below never shows through. With [aboveLanes] they stop above the row's lane strip, which stays
 * in view. [placement] moves and widens them, such as over a table row's Left and Added cells.
 */
@Composable
internal fun BoxScope.HoverOverlay(
  row: TaskRow,
  actions: ListActions,
  frame: RowFrameState,
  aboveLanes: Boolean = false,
  placement: Modifier = Modifier,
) {
  val colors = KetchTheme.colors
  val spacing = KetchTheme.spacing
  val fill = rowFill(colors, frame, actions.keyboard.hasFocus)
  // The strip, the write heads reaching above it and the row's bottom padding.
  val lanes = LaneStripDefaults.RowHeight + spacing.s0_5 + spacing.s2
  val fade = spacing.s6
  Box(
    contentAlignment = Alignment.CenterEnd,
    modifier = Modifier
      .matchParentSize()
      .then(if (aboveLanes) Modifier.padding(bottom = lanes) else Modifier),
  ) {
    Row(
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.End,
      modifier = placement
        .fillMaxHeight()
        .then(
          if (frame.hovered) {
            Modifier.drawBehind {
              val stop = (fade.toPx() / size.width).coerceIn(0f, 1f)
              drawRect(Brush.horizontalGradient(0f to fill.copy(alpha = 0f), stop to fill))
            }
          } else {
            Modifier
          }
        )
        .padding(start = fade, end = spacing.s2),
    ) {
      HoverActions(row, frame.hovered, actions.runner, menu = actions.menu)
    }
  }
}

/** The fill [TaskRowFrame] gives a row in [frame], which the hover overlay fades into. */
internal fun rowFill(colors: KetchColors, frame: RowFrameState, listFocused: Boolean): Color =
  when {
    frame.selected && listFocused -> colors.rowSelectedFocused
    frame.selected -> colors.rowSelected
    else -> colors.surfaceHover
  }

/**
 * Lets a touch row be swiped: start to end pauses or resumes it, end to start removes it with
 * Undo. The row springs back after a pause or resume.
 */
@Composable
private fun SwipeableRow(
  row: TaskRow,
  actions: ListActions,
  modifier: Modifier,
  content: @Composable () -> Unit,
) {
  val runner = actions.runner
  val menu = runner.menu(row)
  val toggle = listOf(RowAction.Pause, RowAction.Resume).firstOrNull { it in menu }
  val removable = RowAction.Remove in menu
  val swipe = rememberSwipeToDismissBoxState()
  val scope = rememberCoroutineScope()
  val colors = KetchTheme.colors
  val spacing = KetchTheme.spacing
  SwipeToDismissBox(
    state = swipe,
    enableDismissFromStartToEnd = toggle != null,
    enableDismissFromEndToStart = removable,
    gesturesEnabled = actions.selection.count == 0,
    onDismiss = { direction ->
      when (direction) {
        SwipeToDismissBoxValue.StartToEnd -> toggle?.let { runner.run(it, listOf(row)) }
        SwipeToDismissBoxValue.EndToStart -> runner.run(RowAction.Remove, listOf(row))
        SwipeToDismissBoxValue.Settled -> Unit
      }
      scope.launch { swipe.reset() }
    },
    backgroundContent = {
      val direction = swipe.dismissDirection
      val removing = direction == SwipeToDismissBoxValue.EndToStart
      val fill = when (direction) {
        SwipeToDismissBoxValue.StartToEnd -> colors.accentSoft
        SwipeToDismissBoxValue.EndToStart -> colors.status.failed.soft
        SwipeToDismissBoxValue.Settled -> Color.Transparent
      }
      Box(
        contentAlignment = if (removing) Alignment.CenterEnd else Alignment.CenterStart,
        modifier = Modifier.fillMaxSize().background(fill).padding(horizontal = spacing.s6),
      ) {
        val icon = if (removing) KetchIcon.Trash else toggle?.icon
        if (icon != null && direction != SwipeToDismissBoxValue.Settled) {
          KetchIconImage(
            icon = icon,
            size = KetchTheme.density.controlGlyph,
            tint = if (removing) colors.status.failed.color else colors.accentText,
          )
        }
      }
    },
    modifier = modifier,
  ) {
    Box(Modifier.background(colors.surface)) { content() }
  }
}

private const val SEPARATOR = " · "
private const val NO_BREAK_SPACE = '\u00A0'
private const val UNKNOWN = "–"
