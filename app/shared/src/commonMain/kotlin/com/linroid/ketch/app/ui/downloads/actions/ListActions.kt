package com.linroid.ketch.app.ui.downloads.actions

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.onLongClick
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.LayoutDirection
import com.linroid.ketch.api.DownloadState
import com.linroid.ketch.app.components.KetchCheckbox
import com.linroid.ketch.app.components.focusRing
import com.linroid.ketch.app.state.AppState
import com.linroid.ketch.app.state.LocalAppState
import com.linroid.ketch.app.state.RowAction
import com.linroid.ketch.app.state.TaskKey
import com.linroid.ketch.app.state.TaskRow
import com.linroid.ketch.app.state.isPausedUntilResumed
import com.linroid.ketch.app.theme.KetchDensity
import com.linroid.ketch.app.theme.KetchTheme
import ketch.app.shared.generated.resources.Res
import ketch.app.shared.generated.resources.downloads_row_select
import ketch.app.shared.generated.resources.downloads_row_show_actions
import org.jetbrains.compose.resources.stringResource

/**
 * Everything the Downloads list needs to make its rows selectable and actionable, in one place
 * for the table and the list rows to plug in: the [selection], the [runner] of row actions, the
 * context [menu] and the [keyboard].
 *
 * The list keeps [rows] current with the rows it shows, in display order; ranges, ⌘A, the arrow
 * keys and the selection bar follow them.
 */
@Stable
internal class ListActions(
  val selection: ListSelection,
  val runner: RowActionRunner,
  val menu: RowMenuState,
  val keyboard: ListKeyboard,
) {
  /** The rows on screen, in display order, those of collapsed groups included. */
  var rows: List<TaskRow> by mutableStateOf(emptyList())

  /**
   * What a drag of the selection shows after the first row's name, such as "3 files"; kept
   * current by [TrackDragCount].
   */
  var dragCount: String? by mutableStateOf(null)

  /** Keys of [rows]. */
  val visibleKeys: List<TaskKey> get() = rows.map { it.key }

  /** The selected rows on screen, in display order. */
  val selectedRows: List<TaskRow>
    get() {
      val selected = selection.current.selected
      return rows.filter { it.key in selected }
    }

  /** Whether [key] is selected. */
  fun isSelected(key: TaskKey): Boolean = key in selection

  /** A click on [row]: selects or toggles it, and gives the list the keyboard focus. */
  fun click(row: TaskRow, click: RowClick) {
    keyboard.focusVisible = false
    selection.click(row.key, visibleKeys, click)
    if (!click.touch) keyboard.focus()
  }

  /**
   * A double-click on [row]: pauses a downloading or starting task and resumes a paused one, and
   * opens a finished file. It never shows the row in the inspector, nor lets the click before it.
   */
  fun doubleClick(row: TaskRow) {
    selection.doubleClicked()
    val action = when (val state = row.state) {
      is DownloadState.Downloading -> RowAction.Pause
      is DownloadState.Queued -> RowAction.Pause.takeIf { row.isStarting }
      // Paused for an urgent download, it still waits in the queue: Resume would do nothing.
      is DownloadState.Paused -> RowAction.Resume.takeIf { state.isPausedUntilResumed }
      is DownloadState.Completed -> RowAction.Open
      else -> null
    }?.takeIf { it in runner.menu(row) }
    if (action != null) runner.run(action, listOf(row))
  }

  /** A right-click on [row] at [position] in it: opens the menu of the rows it acts on. */
  fun contextClick(row: TaskRow, position: Offset) {
    keyboard.focusVisible = false
    openMenu(row, selection.contextClick(row.key, visibleKeys), position)
  }

  /**
   * Opens the menu of [row] without changing the selection, as a touch row's "⋯" or a screen
   * reader's long-press does: it acts on the selection when [row] is in it, else on [row] alone.
   */
  fun showMenu(row: TaskRow) {
    openMenu(row, selection.current.targets(row.key, visibleKeys), Offset.Zero)
  }

  // Opens the menu on row for the rows of keys, or for row alone when none of them is listed.
  private fun openMenu(row: TaskRow, keys: List<TaskKey>, position: Offset) {
    val byKey = rows.associateBy { it.key }
    menu.open(row.key, keys.mapNotNull(byKey::get).ifEmpty { listOf(row) }, position)
  }

  /** A long-press on [row]: enters selection mode with it, or toggles it. */
  fun longPress(row: TaskRow) {
    selection.longPress(row.key)
  }

  /** A click on [row]'s checkbox: toggles it, keeping the rest of the selection. */
  fun toggle(row: TaskRow) {
    selection.update(selection.current.toggle(row.key))
  }

  /** The rows a drag starting on [row] carries: the selection when it holds [row], else [row]. */
  fun dragRows(row: TaskRow): List<TaskRow> =
    if (isSelected(row.key)) selectedRows.ifEmpty { listOf(row) } else listOf(row)

  /** Selects every row on screen. */
  fun selectAll() {
    selection.update(selection.current.selectAllVisible(visibleKeys))
  }
}

/**
 * The [ListActions] of the Downloads list showing [rows], remembered in this composition.
 *
 * @param runner runs the row actions; the platform's files and clipboard by default.
 */
@Composable
internal fun rememberListActions(
  rows: List<TaskRow>,
  state: AppState = LocalAppState.current,
  runner: RowActionRunner = rememberRowActionRunner(),
): ListActions {
  val selection = rememberListSelection(state)
  val menu = remember { RowMenuState() }
  val keyboard = rememberListKeyboard(selection, runner, menu)
  val actions = remember(selection, runner, menu, keyboard) {
    ListActions(selection, runner, menu, keyboard)
  }
  SideEffect { actions.rows = rows }
  return actions
}

/**
 * Keeps [ListActions.dragCount] current with the selection, in a scope of its own so a new
 * selection recomposes nothing else. Place it once next to the list.
 */
@Composable
internal fun TrackDragCount(actions: ListActions) {
  val count = dragCount(actions.selectedRows)
  SideEffect { actions.dragCount = count }
}

/**
 * How a row looks while it is used, for its content.
 *
 * @property hovered whether the pointer is over the row, or a menu of the row is open, which
 *   keeps its hover actions in place while the pointer is in the menu.
 * @property selected whether the row is selected.
 * @property selecting whether the list is in selection mode, so checkboxes take the place of
 *   status dots; see [isSelectionMode].
 */
@Immutable
internal data class RowFrameState(
  val hovered: Boolean,
  val selected: Boolean,
  val selecting: Boolean,
)

/**
 * The interactive frame of one task row, shared by the table and the list rows.
 *
 * It draws the row's hover, selected and keyboard-focused states (`surfaceHover`; `rowSelected`
 * with a 2 dp accent bar, `rowSelectedFocused` while the list has the focus) and wires the
 * pointer through [actions]: a click selects and inspects, ⌘-click toggles, ⇧-click selects a
 * range, a double-click pauses, resumes or opens (see [ListActions.doubleClick]), a right-click
 * opens the menu at the pointer, a long-press enters selection mode on touch, and with a pointer
 * the row can be dragged out. [content] gets the row's [RowFrameState], for [HoverActions] and
 * [SelectionCheckbox].
 *
 * @param shape the row's shape: square in the table, rounded and inset in the list.
 */
@Composable
internal fun TaskRowFrame(
  row: TaskRow,
  actions: ListActions,
  modifier: Modifier = Modifier,
  shape: Shape = RectangleShape,
  content: @Composable BoxScope.(RowFrameState) -> Unit,
) {
  val colors = KetchTheme.colors
  val spacing = KetchTheme.spacing
  val motion = KetchTheme.motion
  val interactions = remember { MutableInteractionSource() }
  val pointerOver by interactions.collectIsHoveredAsState()
  val hovered = pointerOver || actions.menu.request?.anchor == row.key
  val selected = actions.isSelected(row.key)
  val keyboard = actions.keyboard
  val focused = keyboard.hasFocus && actions.selection.focusedKey == row.key
  val fill by animateColorAsState(
    targetValue = when {
      selected && keyboard.hasFocus -> colors.rowSelectedFocused
      selected -> colors.rowSelected
      hovered -> colors.surfaceHover
      // Fades by alpha alone: Color.Transparent is transparent black, which flashes grey.
      else -> colors.surfaceHover.copy(alpha = 0f)
    },
    animationSpec = tween(motion.micro),
  )
  val pointer = KetchTheme.density == KetchDensity.Compact
  val state = RowFrameState(hovered, selected, isSelectionMode(actions.selection.count, pointer))
  val selectLabel = stringResource(Res.string.downloads_row_select)
  val actionsLabel = stringResource(Res.string.downloads_row_show_actions)
  Box(
    modifier = modifier
      // Inside the row, so neighbors and the card edge never cut the ring off.
      .focusRing(focused && keyboard.focusVisible, shape, colors.focusRing, gap = -spacing.s1)
      .clip(shape)
      .background(fill)
      .drawWithContent {
        drawContent()
        if (selected) {
          val bar = spacing.s0_5.toPx()
          val start = if (layoutDirection == LayoutDirection.Ltr) 0f else size.width - bar
          drawRect(colors.accent, Offset(start, 0f), Size(bar, size.height))
        }
      }
      .hoverable(interactions)
      .semantics(mergeDescendants = true) {
        this.selected = selected
        onClick(label = selectLabel) {
          actions.click(row, RowClick())
          true
        }
        onLongClick(label = actionsLabel) {
          if (pointer) actions.contextClick(row, Offset.Zero) else actions.showMenu(row)
          true
        }
      }
      .then(
        if (pointer) {
          Modifier.taskDragSource(
            rows = { actions.dragRows(row) },
            count = { actions.dragCount },
          )
        } else {
          Modifier
        }
      )
      .taskRowPointer(
        onClick = { actions.click(row, it) },
        onDoubleClick = { actions.doubleClick(row) },
        onContextClick = { actions.contextClick(row, it) },
        onLongPress = { actions.longPress(row) },
        onClickSettled = { actions.selection.settle(row.key) },
      ),
  ) {
    content(state)
    RowMenuAnchor(actions.menu, row.key, actions.runner)
  }
}

/**
 * Whether a list with [count] selected rows is in selection mode, where checkboxes take the place
 * of status dots: with a [pointer] from two rows on, since a click selects the row it inspects,
 * and on touch from the first row a long-press selects.
 */
internal fun isSelectionMode(count: Int, pointer: Boolean): Boolean =
  count >= if (pointer) 2 else 1

/**
 * The checkbox that takes the place of a row's status dot or file chip in selection mode or
 * while the pointer is over the row. Clicking it toggles the row and keeps the rest selected.
 */
@Composable
internal fun SelectionCheckbox(row: TaskRow, actions: ListActions, modifier: Modifier = Modifier) {
  KetchCheckbox(
    checked = actions.isSelected(row.key),
    onCheckedChange = { actions.toggle(row) },
    modifier = modifier,
  )
}
