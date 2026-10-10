package com.linroid.ketch.app.input

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.input.pointer.isPrimaryPressed
import androidx.compose.ui.input.pointer.pointerInput

/**
 * Runs [onClick] on a click or tap here that nothing inside takes, such as one on the empty space
 * between a header's buttons. Other mouse buttons are ignored.
 */
internal fun Modifier.onEmptyClick(onClick: () -> Unit): Modifier = composed {
  val current by rememberUpdatedState(onClick)
  pointerInput(Unit) {
    awaitEachGesture {
      val down = awaitFirstDown(requireUnconsumed = false)
      val mouse = down.type == PointerType.Mouse
      if (down.isConsumed || mouse && !currentEvent.buttons.isPrimaryPressed) {
        return@awaitEachGesture
      }
      // Null when a child consumed the release or the pointer left.
      if (waitForUpOrCancellation() != null) current()
    }
  }
}
