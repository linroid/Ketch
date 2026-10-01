package com.linroid.ketch.app.ui.downloads.actions

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.input.pointer.SuspendingPointerInputModifierNode
import androidx.compose.ui.input.pointer.isCtrlPressed
import androidx.compose.ui.input.pointer.isMetaPressed
import androidx.compose.ui.input.pointer.isPrimaryPressed
import androidx.compose.ui.input.pointer.isSecondaryPressed
import androidx.compose.ui.input.pointer.isShiftPressed
import androidx.compose.ui.node.DelegatingNode
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.platform.InspectorInfo
import com.linroid.ketch.app.input.KeyboardPlatform
import com.linroid.ketch.app.state.AppState
import com.linroid.ketch.app.state.LocalAppState
import com.linroid.ketch.app.state.SelectionState
import com.linroid.ketch.app.state.TaskKey
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
   * A click on [key]. A plain click selects the row and shows it in the inspector. On touch a
   * tap only shows the row, unless something is selected: then it toggles the row, as in
   * selection mode.
   */
  fun click(key: TaskKey, visible: List<TaskKey>, click: RowClick) {
    if (click.touch && count == 0) {
      focused = key
      state.inspect(key)
      return
    }
    val toggle = click.toggle || click.touch
    update(current.click(key, visible, toggle = toggle, range = click.range))
    if (!toggle && !click.range) state.inspect(key)
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

  /** The selected keys among [visible], in display order. */
  fun visibleSelection(visible: List<TaskKey>): List<TaskKey> = current.visibleSelection(visible)

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
 */
internal data class RowClick(
  val toggle: Boolean = false,
  val range: Boolean = false,
  val touch: Boolean = false,
)

/**
 * Pointer input of a task row: [onClick] with the modifiers held, [onDoubleClick] for a second
 * click in quick succession, [onContextClick] for a right-click (or ⌃-click on Apple keyboards)
 * at its position in the row, and [onLongPress] for a finger held down. Presses that a child,
 * such as a hover action, handles never reach these, so they never change the selection.
 */
internal fun Modifier.taskRowPointer(
  onClick: (RowClick) -> Unit,
  onDoubleClick: () -> Unit,
  onContextClick: (Offset) -> Unit,
  onLongPress: () -> Unit = {},
): Modifier = this then RowPointerElement(onClick, onDoubleClick, onContextClick, onLongPress)

private data class RowPointerElement(
  val onClick: (RowClick) -> Unit,
  val onDoubleClick: () -> Unit,
  val onContextClick: (Offset) -> Unit,
  val onLongPress: () -> Unit,
) : ModifierNodeElement<RowPointerNode>() {
  override fun create(): RowPointerNode =
    RowPointerNode(onClick, onDoubleClick, onContextClick, onLongPress)

  override fun update(node: RowPointerNode) {
    node.onClick = onClick
    node.onDoubleClick = onDoubleClick
    node.onContextClick = onContextClick
    node.onLongPress = onLongPress
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
) : DelegatingNode() {
  private var lastClickAt = 0L
  private var lastClickPosition = Offset.Zero

  init {
    delegate(SuspendingPointerInputModifierNode { detect() })
  }

  private suspend fun PointerInputScope.detect() {
    val apple = KeyboardPlatform.current.isApple
    awaitEachGesture {
      val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Main)
      val event = currentEvent
      val keys = event.keyboardModifiers
      val secondary = event.buttons.isSecondaryPressed ||
        apple && keys.isCtrlPressed && event.buttons.isPrimaryPressed
      if (secondary) {
        down.consume()
        onContextClick(down.position)
        return@awaitEachGesture
      }
      if (down.isConsumed) return@awaitEachGesture
      val touch = down.type == PointerType.Touch
      if (touch) {
        val held = withTimeoutOrNull(viewConfiguration.longPressTimeoutMillis) {
          waitForUpOrCancellation()
        }
        if (held == null && currentEvent.changes.any { it.pressed && !it.isConsumed }) {
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
      val up = waitForUpOrCancellation() ?: return@awaitEachGesture
      up.consume()
      val quick = up.uptimeMillis - lastClickAt <= viewConfiguration.doubleTapTimeoutMillis
      val near = (up.position - lastClickPosition).getDistance() <= viewConfiguration.touchSlop
      if (quick && near) {
        lastClickAt = 0L
        onDoubleClick()
      } else {
        lastClickAt = up.uptimeMillis
        lastClickPosition = up.position
        val primary = if (apple) keys.isMetaPressed else keys.isCtrlPressed
        onClick(RowClick(toggle = primary, range = keys.isShiftPressed))
      }
    }
  }
}
