package com.linroid.ketch.app.input

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.AwaitPointerEventScope
import androidx.compose.ui.input.pointer.PointerEvent
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.changedToDownIgnoreConsumed
import androidx.compose.ui.input.pointer.isCtrlPressed
import androidx.compose.ui.input.pointer.isPrimaryPressed
import androidx.compose.ui.input.pointer.isSecondaryPressed
import androidx.compose.ui.input.pointer.pointerInput

/** Like `awaitFirstDown`, but also accepts non-primary mouse buttons on desktop. */
internal suspend fun AwaitPointerEventScope.awaitAnyButtonDown(
  pass: PointerEventPass = PointerEventPass.Main,
): PointerInputChange {
  while (true) {
    val event = awaitPointerEvent(pass)
    if (event.changes.all { it.changedToDownIgnoreConsumed() }) return event.changes.first()
  }
}

/** A right click, or Control-click with an Apple keyboard. */
internal val PointerEvent.isContextClick: Boolean
  get() = buttons.isSecondaryPressed ||
    KeyboardPlatform.current.isApple && keyboardModifiers.isCtrlPressed && buttons.isPrimaryPressed

/** Opens a menu at the pointer unless a child already handled the press. */
internal fun Modifier.onContextClick(
  pass: PointerEventPass = PointerEventPass.Main,
  onClick: (Offset) -> Unit,
): Modifier = composed {
  val current by rememberUpdatedState(onClick)
  pointerInput(pass) {
    awaitEachGesture {
      val down = awaitAnyButtonDown(pass)
      if (!down.isConsumed && currentEvent.isContextClick) {
        down.consume()
        current(down.position)
      }
    }
  }
}
