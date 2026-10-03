package com.linroid.ketch.app.ui.downloads.actions

import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.onKeyEvent
import com.linroid.ketch.api.DownloadPriority
import com.linroid.ketch.api.DownloadState
import com.linroid.ketch.app.components.ConnectionRange
import com.linroid.ketch.app.components.DebouncedCommit
import com.linroid.ketch.app.components.PEER_LIMIT_STEP
import com.linroid.ketch.app.components.PeerLimitRange
import com.linroid.ketch.app.components.stepConnections
import com.linroid.ketch.app.input.KetchCommand
import com.linroid.ketch.app.input.KetchCommands
import com.linroid.ketch.app.input.ShortcutContext
import com.linroid.ketch.app.input.ShortcutMatcher
import com.linroid.ketch.app.state.RowAction
import com.linroid.ketch.app.state.TaskKey
import com.linroid.ketch.app.state.TaskRow
import com.linroid.ketch.app.ui.inspector.autoConnectionsOf
import kotlinx.coroutines.CoroutineScope
import kotlin.time.Duration.Companion.milliseconds

/** What a list key does, before it is applied to rows. */
internal sealed interface ListKey {
  /**
   * Moves the focus [offset] rows, selecting the row reached; with [extend] the selection runs
   * from the anchor. [Int.MIN_VALUE] and [Int.MAX_VALUE] jump to the ends.
   */
  data class Move(val offset: Int, val extend: Boolean = false) : ListKey

  /** Moves the focus a page up (-1) or down (1). */
  data class Page(val direction: Int) : ListKey

  /** Selects every visible row. */
  data object SelectAll : ListKey

  /** Clears the selection, or moves to the search field when nothing is selected. */
  data object Escape : ListKey

  /** Adds [delta] connections to the target rows, debounced. */
  data class Connections(val delta: Int) : ListKey

  /** Raises (1) or lowers (-1) the priority of the target rows between Low and High. */
  data class Priority(val delta: Int) : ListKey

  /** Shows the focused row in the inspector. */
  data object Inspect : ListKey

  /** Runs on the target rows the action [command] maps each one to; see [keyAction]. */
  data class Rows(val command: KetchCommand) : ListKey
}

/** What [command] does in the list, or `null` when it is not a list command. */
internal fun listKey(command: KetchCommand): ListKey? = when (command) {
  KetchCommands.ListUp -> ListKey.Move(-1)
  KetchCommands.ListDown -> ListKey.Move(1)
  KetchCommands.ExtendSelectionUp -> ListKey.Move(-1, extend = true)
  KetchCommands.ExtendSelectionDown -> ListKey.Move(1, extend = true)
  KetchCommands.ListFirst -> ListKey.Move(Int.MIN_VALUE)
  KetchCommands.ListLast -> ListKey.Move(Int.MAX_VALUE)
  KetchCommands.ListPageUp -> ListKey.Page(-1)
  KetchCommands.ListPageDown -> ListKey.Page(1)
  KetchCommands.SelectAll -> ListKey.SelectAll
  KetchCommands.ClearSelection -> ListKey.Escape
  KetchCommands.MoreConnections -> ListKey.Connections(1)
  KetchCommands.FewerConnections -> ListKey.Connections(-1)
  KetchCommands.RaisePriority -> ListKey.Priority(1)
  KetchCommands.LowerPriority -> ListKey.Priority(-1)
  KetchCommands.FocusInspector -> ListKey.Inspect
  KetchCommands.TogglePause,
  KetchCommands.Open,
  KetchCommands.Reveal,
  KetchCommands.Remove,
  KetchCommands.RemoveAndTrash,
  KetchCommands.CopyLink,
  KetchCommands.CopyPath,
  KetchCommands.Retry -> ListKey.Rows(command)
  else -> null
}

/**
 * The action the row key [command] runs on [row], whose menu offers [menu]; `null` when the key
 * does nothing to it.
 *
 * - Space pauses a running or queued task and resumes a paused one; a failed task retries, or
 *   downloads again when its progress cannot be reused, and a canceled one downloads again.
 * - ↩ opens a finished file and shows any other task in the inspector.
 * - ⌘↩ shows the file in its folder, ⌫ removes the task with Undo and ⇧⌫ asks to remove it with
 *   its file, finished or partial.
 * - ⌘C and ⌥⌘C copy the link and the file path.
 * - ⌘R retries a failed or canceled task and resumes a paused one.
 */
internal fun keyAction(command: KetchCommand, row: TaskRow, menu: List<RowAction>): RowAction? {
  fun offered(action: RowAction) = action.takeIf { it in menu }
  val restart = offered(RowAction.Retry) ?: offered(RowAction.DownloadAgain)
  return when (command) {
    KetchCommands.TogglePause -> when (row.state) {
      is DownloadState.Downloading, DownloadState.Queued -> offered(RowAction.Pause)
      is DownloadState.Paused -> offered(RowAction.Resume)
      is DownloadState.Failed, DownloadState.Canceled -> restart
      else -> null
    }
    KetchCommands.Open -> offered(RowAction.Open) ?: RowAction.Details
    KetchCommands.Reveal -> offered(RowAction.ShowInFolder)
    KetchCommands.Remove -> offered(RowAction.Remove)
    KetchCommands.RemoveAndTrash -> RowAction.RemoveAndDelete
    KetchCommands.CopyLink -> offered(RowAction.CopyLink)
    KetchCommands.CopyPath -> offered(RowAction.CopyPath)
    KetchCommands.Retry -> when (row.state) {
      is DownloadState.Paused -> offered(RowAction.Resume)
      is DownloadState.Failed, DownloadState.Canceled -> restart
      else -> null
    }
    else -> null
  }
}

/**
 * [current] one step up ([delta] 1) or down (-1) between Low, Normal and High. Urgent is never
 * reached from the keyboard, since it may pause another download; from Urgent a step down is
 * High.
 */
internal fun stepPriority(current: DownloadPriority, delta: Int): DownloadPriority {
  val steps = listOf(DownloadPriority.LOW, DownloadPriority.NORMAL, DownloadPriority.HIGH)
  if (current == DownloadPriority.URGENT) {
    return if (delta < 0) DownloadPriority.HIGH else current
  }
  return steps[(steps.indexOf(current) + delta).coerceIn(0, steps.lastIndex)]
}

/**
 * The keyboard of the Downloads list: it turns list keys into selection moves and row actions,
 * while the list has the focus and no text field, composition or menu is active.
 *
 * Row keys act on the selection when the focused row is in it, otherwise on the focused row.
 */
@Stable
internal class ListKeyboard(
  private val selection: ListSelection,
  private val runner: RowActionRunner,
  private val menu: RowMenuState,
  scope: CoroutineScope,
  private val matcher: ShortcutMatcher = ShortcutMatcher(),
) {
  /** Gives the list the keyboard focus, as a click on a row does. */
  val focusRequester: FocusRequester = FocusRequester()

  /** Whether the list has the keyboard focus, which tints selected rows more strongly. */
  var hasFocus: Boolean by mutableStateOf(false)
    internal set

  /**
   * Whether the focused row shows a focus ring: after a list key moved it, and until the
   * pointer selects again, like `:focus-visible`.
   */
  var focusVisible: Boolean by mutableStateOf(false)

  private var connectionDelta = 0
  private val connections = DebouncedCommit<List<TaskRow>>(scope, CONNECTIONS_DELAY) { rows ->
    val delta = connectionDelta
    connectionDelta = 0
    for (row in rows) {
      val torrent = row.isTorrent
      val current = row.request.connections
      // Auto (0) steps from what it resolved to; a torrent's peer default is the device's own.
      val auto = if (torrent) null else autoConnectionsOf(runner.state, listOf(row))
      if (current == 0 && auto == null) continue
      val count = stepConnections(
        current = current,
        delta = delta,
        range = if (torrent) PeerLimitRange else ConnectionRange,
        step = if (torrent) PEER_LIMIT_STEP else 1,
        autoValue = auto,
      )
      if (count != current) runner.setConnections(listOf(row), count)
    }
  }

  /** Moves the focus into the list. */
  fun focus() {
    runCatching { focusRequester.requestFocus() }
  }

  /**
   * Handles [event] for a list showing [rows] in display order, [pageSize] rows to a page.
   * Returns whether the key was used.
   */
  fun handle(event: KeyEvent, rows: List<TaskRow>, pageSize: Int): Boolean {
    val context = ShortcutContext(listFocused = true, menuOpen = menu.isOpen)
    val command = matcher.match(event, context) ?: return false
    val key = listKey(command) ?: return false
    val used = apply(key, rows, pageSize)
    if (used && (key is ListKey.Move || key is ListKey.Page)) focusVisible = true
    return used
  }

  /** Applies [key] to a list showing [rows]; returns whether it did anything. */
  fun apply(key: ListKey, rows: List<TaskRow>, pageSize: Int = 1): Boolean {
    val visible = rows.map { it.key }
    val current = selection.current
    when (key) {
      is ListKey.Move -> selection.update(current.moveFocus(visible, key.offset, key.extend))
      is ListKey.Page -> {
        val step = pageSize.coerceAtLeast(1) * key.direction
        selection.update(current.moveFocus(visible, step))
      }
      ListKey.SelectAll -> selection.update(current.selectAllVisible(visible))
      ListKey.Escape -> if (current.count > 0) {
        selection.clear()
      } else {
        runner.state.requestSearchFocus()
      }
      is ListKey.Connections -> {
        val targets = targets(rows).filter { RowAction.Connections in runner.menu(it) }
        if (targets.isEmpty()) return false
        connectionDelta += key.delta
        connections.update(targets)
      }
      is ListKey.Priority -> {
        val targets = targets(rows).filter { RowAction.Priority in runner.menu(it) }
        if (targets.isEmpty()) return false
        targets.groupBy { stepPriority(it.request.priority, key.delta) }
          .filter { (priority, group) -> group.any { it.request.priority != priority } }
          .forEach { (priority, group) -> runner.setPriority(group, priority) }
      }
      ListKey.Inspect -> {
        val focused = current.focused ?: return false
        runner.inspect(focused)
      }
      is ListKey.Rows -> return runRows(key.command, targets(rows))
    }
    return true
  }

  private fun runRows(command: KetchCommand, targets: List<TaskRow>): Boolean {
    if (targets.isEmpty()) return false
    val actions = targets.associateWith { keyAction(command, it, runner.menu(it)) }
    if (actions.values.all { it == null }) return false
    when {
      command == KetchCommands.RemoveAndTrash -> runner.requestRemove(targets, withFiles = true)
      // Space pauses the whole selection while any of it runs, as the menu bar does.
      RowAction.Pause in actions.values -> runner.run(RowAction.Pause, targets)
      command == KetchCommands.Open && RowAction.Open !in actions.values -> {
        val focused = selection.focusedKey ?: targets.first().key
        runner.inspect(focused)
      }
      else -> actions.entries.mapNotNull { (row, action) -> action?.let { it to row } }
        .groupBy({ it.first }, { it.second })
        .filterKeys { it != RowAction.Details }
        .forEach { (action, group) -> runner.run(action, group) }
    }
    return true
  }

  /** The rows row keys act on: the selection holding the focused row, or the focused row. */
  private fun targets(rows: List<TaskRow>): List<TaskRow> {
    val visible = rows.map { it.key }
    val current = selection.current
    val keys = current.focused?.takeIf { it in visible }?.let { current.targets(it, visible) }
      ?: current.visibleSelection(visible)
    val byKey = rows.associateBy { it.key }
    return keys.mapNotNull(byKey::get)
  }

  private companion object {
    val CONNECTIONS_DELAY = 400.milliseconds
  }
}

/** A [ListKeyboard] for the list of [selection], remembered in this composition. */
@Composable
internal fun rememberListKeyboard(
  selection: ListSelection,
  runner: RowActionRunner,
  menu: RowMenuState,
): ListKeyboard {
  val scope = rememberCoroutineScope()
  return remember(selection, runner, menu, scope) { ListKeyboard(selection, runner, menu, scope) }
}

/**
 * Makes this the focusable Downloads list whose keys [keyboard] handles. Keys go to focused
 * children first, so a focused button still takes Space and ↩.
 *
 * @param rows the rows on screen in display order, read when a key is pressed.
 * @param pageSize how many rows a page key moves, read when one is pressed.
 */
internal fun Modifier.listKeyboard(
  keyboard: ListKeyboard,
  rows: () -> List<TaskRow>,
  pageSize: () -> Int = { 1 },
): Modifier = this
  .focusRequester(keyboard.focusRequester)
  .onFocusChanged { keyboard.hasFocus = it.hasFocus }
  .onKeyEvent { keyboard.handle(it, rows(), pageSize()) }
  .focusable()

/**
 * Scrolls [listState] just enough to show the row of [focused] after the keyboard moves the
 * focus; [indexOf] gives a key's item index in the list, or -1.
 */
@Composable
internal fun FollowFocusedRow(
  listState: LazyListState,
  focused: TaskKey?,
  indexOf: (TaskKey) -> Int,
) {
  val currentIndexOf by rememberUpdatedState(indexOf)
  LaunchedEffect(listState, focused) {
    val key = focused ?: return@LaunchedEffect
    val index = currentIndexOf(key).takeIf { it >= 0 } ?: return@LaunchedEffect
    val info = listState.layoutInfo
    val item = info.visibleItemsInfo.firstOrNull { it.index == index }
    val start = info.viewportStartOffset
    val end = info.viewportEndOffset
    when {
      item == null -> listState.scrollToItem(index)
      item.offset < start -> listState.scrollBy((item.offset - start).toFloat())
      item.offset + item.size > end -> listState.scrollBy((item.offset + item.size - end).toFloat())
    }
  }
}

/** Rows a page key moves in [listState]: the rows fully on screen, less one for context. */
internal fun pageSizeOf(listState: LazyListState): Int =
  (listState.layoutInfo.visibleItemsInfo.size - 1).coerceAtLeast(1)
