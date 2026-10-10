package com.linroid.ketch.app.ui.downloads.actions

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.input.pointer.SuspendingPointerInputModifierNode
import androidx.compose.ui.input.pointer.isCtrlPressed
import androidx.compose.ui.input.pointer.isMetaPressed
import androidx.compose.ui.input.pointer.isPrimaryPressed
import androidx.compose.ui.input.pointer.isShiftPressed
import androidx.compose.ui.node.DelegatingNode
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.platform.InspectorInfo
import com.linroid.ketch.app.input.KeyboardPlatform
import com.linroid.ketch.app.input.awaitAnyButtonDown
import com.linroid.ketch.app.input.isContextClick
import com.linroid.ketch.app.state.AppState
import com.linroid.ketch.app.state.LocalAppState
import com.linroid.ketch.app.state.SelectionState
import com.linroid.ketch.app.state.TaskKey
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * The selection of the Downloads list, kept in [AppState.selectedKeys] so the menu bar, the
 * tray and the inspector see it too, with the anchor and the focused row on top.
 *
 * Every change goes through a [SelectionState] operation; [visible] gives the keys of the rows
 * on screen in display order, which ranges, ⌘A and the arrow keys follow.
 */
@Stable
internal class ListSelection(private val state: AppState) {
  private var anchor: TaskKey? by mutableStateOf(null)
  private var focused: TaskKey? by mutableStateOf(null)

  /** The selection as it stands. */
  val current: SelectionState
    get() = SelectionState(state.selectedKeys, anchor, focused)

  /** Number of selected rows. */
  val count: Int get() = state.selectedKeys.size

  /**
   * The row a click selected while the inspector was closed, which [settle] shows once no second
   * click made the click a double-click; `null` otherwise.
   */
  var pendingInspect: TaskKey? = null
    private set

  /** The row with the keyboard focus, or `null`. */
  val focusedKey: TaskKey? get() = focused

  /** Whether [key] is selected. */
  operator fun contains(key: TaskKey): Boolean = key in state.selectedKeys

  /** Replaces the selection with [next]. */
  fun update(next: SelectionState) {
    if (next.selected != state.selectedKeys) state.selectedKeys = next.selected
    anchor = next.anchor
    focused = next.focused
  }

  /**
   * A click on [key]. A plain click selects the row and shows it in the inspector; when the
   * inspector is closed and [RowClick.awaitsDoubleClick], only once the click [settle]s. On touch
   * a tap only shows the row, unless something is selected: then it toggles the row, as in
   * selection mode.
   */
  fun click(key: TaskKey, visible: List<TaskKey>, click: RowClick) {
    pendingInspect = null
    if (click.touch && count == 0) {
      focused = key
      state.inspect(key)
      return
    }
    val toggle = click.toggle || click.touch
    val inspectorShown = state.inspectedTask != null || count >= 2
    if (!toggle && !click.range && click.awaitsDoubleClick && !inspectorShown) {
      pendingInspect = key
    }
    update(current.click(key, visible, toggle = toggle, range = click.range))
    if (!toggle && !click.range && pendingInspect == null) state.inspect(key)
  }

  /**
   * The click on [key] did not become a double-click: shows the row in the inspector if its click
   * is still [pendingInspect] and it is the only row selected.
   */
  fun settle(key: TaskKey) {
    if (pendingInspect != key) return
    pendingInspect = null
    if (state.selectedKeys == setOf(key)) state.inspect(key)
  }

  /** A double-click: the click before it shows nothing in the inspector. */
  fun doubleClicked() {
    pendingInspect = null
  }

  /** A long-press on touch: enters selection mode with [key] selected, or toggles it. */
  fun longPress(key: TaskKey) {
    update(current.toggle(key))
  }

  /**
   * A right-click on [key]: selects it unless it is selected, and returns the keys the menu acts
   * on, in [visible] order.
   */
  fun contextClick(key: TaskKey, visible: List<TaskKey>): List<TaskKey> {
    update(current.contextClick(key))
    return current.targets(key, visible)
  }

  /** Clears the selection, keeping the focus. */
  fun clear() {
    update(current.clear())
  }

  /** Drops removed tasks; [existing] holds the keys of every task that still exists. */
  fun prune(existing: Set<TaskKey>) {
    val pruned = current.prune(existing)
    if (pruned != current) update(pruned)
  }
}

/** The [ListSelection] of the app shown around this composition. */
@Composable
internal fun rememberListSelection(state: AppState = LocalAppState.current): ListSelection =
  remember(state) { ListSelection(state) }

/**
 * A click on a row, with the modifiers held.
 *
 * @property toggle whether ⌘ (Apple keyboards) or Ctrl (elsewhere) was held.
 * @property range whether ⇧ was held.
 * @property touch whether a finger tapped, rather than a mouse or pen clicking.
 * @property awaitsDoubleClick whether a second click may still make this a double-click, so a
 *   closed inspector waits for [ListSelection.settle] before showing the row.
 */
internal data class RowClick(
  val toggle: Boolean = false,
  val range: Boolean = false,
  val touch: Boolean = false,
  val awaitsDoubleClick: Boolean = false,
)

/**
 * Pointer input of a task row: [onClick] with the modifiers held, [onClickSettled] once a mouse
 * click had no second click in quick succession, [onDoubleClick] for one that did,
 * [onContextClick] for a right-click (or ⌃-click on Apple keyboards) at its position in the row,
 * and [onLongPress] for a finger held down. Presses that a child, such as a hover action,
 * handles never reach these, so they never change the selection.
 */
internal fun Modifier.taskRowPointer(
  onClick: (RowClick) -> Unit,
  onDoubleClick: () -> Unit,
  onContextClick: (Offset) -> Unit,
  onLongPress: () -> Unit = {},
  onClickSettled: () -> Unit = {},
): Modifier =
  this then RowPointerElement(onClick, onDoubleClick, onContextClick, onLongPress, onClickSettled)

private data class RowPointerElement(
  val onClick: (RowClick) -> Unit,
  val onDoubleClick: () -> Unit,
  val onContextClick: (Offset) -> Unit,
  val onLongPress: () -> Unit,
  val onClickSettled: () -> Unit,
) : ModifierNodeElement<RowPointerNode>() {
  override fun create(): RowPointerNode =
    RowPointerNode(onClick, onDoubleClick, onContextClick, onLongPress, onClickSettled)

  override fun update(node: RowPointerNode) {
    node.onClick = onClick
    node.onDoubleClick = onDoubleClick
    node.onContextClick = onContextClick
    node.onLongPress = onLongPress
    node.onClickSettled = onClickSettled
  }

  override fun InspectorInfo.inspectableProperties() {
    name = "taskRowPointer"
  }
}

private class RowPointerNode(
  var onClick: (RowClick) -> Unit,
  var onDoubleClick: () -> Unit,
  var onContextClick: (Offset) -> Unit,
  var onLongPress: () -> Unit,
  var onClickSettled: () -> Unit,
) : DelegatingNode() {
  private var lastClickAt = 0L
  private var lastClickPosition = Offset.Zero
  private var settling: Job? = null

  init {
    delegate(SuspendingPointerInputModifierNode { detect() })
  }

  private suspend fun PointerInputScope.detect() {
    val apple = KeyboardPlatform.current.isApple
    awaitEachGesture {
      val down = awaitAnyButtonDown()
      val event = currentEvent
      val keys = event.keyboardModifiers
      if (event.isContextClick) {
        down.consume()
        onContextClick(down.position)
        return@awaitEachGesture
      }
      if (down.isConsumed) return@awaitEachGesture
      val touch = down.type == PointerType.Touch
      if (down.type == PointerType.Mouse && !event.buttons.isPrimaryPressed) {
        return@awaitEachGesture
      }
      if (touch) {
        var cancelled = false
        val held = withTimeoutOrNull(viewConfiguration.longPressTimeoutMillis) {
          // Null once the finger scrolls, swipes or slides out of the row.
          waitForUpOrCancellation().also { cancelled = it == null }
        }
        val pressed = currentEvent.changes.any { it.pressed && !it.isConsumed }
        if (held == null && !cancelled && pressed) {
          onLongPress()
          // The finger lifts later; that release must not tap the row too.
          do {
            val next = awaitPointerEvent()
            next.changes.forEach { it.consume() }
          } while (next.changes.any { it.pressed })
          return@awaitEachGesture
        }
        if (held == null || held.isConsumed) return@awaitEachGesture
        held.consume()
        onClick(RowClick(touch = true))
        return@awaitEachGesture
      }
      // A second press may make the last click a double-click; it settles if this one does not.
      val unsettled = settling?.isActive == true
      settling?.cancel()
      val up = waitForUpOrCancellation()
      if (up == null) {
        if (unsettled) onClickSettled()
        return@awaitEachGesture
      }
      up.consume()
      val timeout = viewConfiguration.doubleTapTimeoutMillis
      val quick = up.uptimeMillis - lastClickAt <= timeout
      val near = (up.position - lastClickPosition).getDistance() <= viewConfiguration.touchSlop
      if (quick && near) {
        lastClickAt = 0L
        onDoubleClick()
      } else {
        lastClickAt = up.uptimeMillis
        lastClickPosition = up.position
        val primary = if (apple) keys.isMetaPressed else keys.isCtrlPressed
        onClick(RowClick(toggle = primary, range = keys.isShiftPressed, awaitsDoubleClick = true))
        settling = coroutineScope.launch {
          delay(timeout)
          onClickSettled()
        }
      }
    }
  }
}
