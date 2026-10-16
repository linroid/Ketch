package com.linroid.ketch.app.ui.downloads.actions

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerButton
import androidx.compose.ui.input.pointer.PointerButtons
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerKeyboardModifiers
import androidx.compose.ui.input.pointer.PointerType
import com.linroid.ketch.app.input.KeyboardPlatform
import com.linroid.ketch.app.snapshot.SnapshotClock
import com.linroid.ketch.app.snapshot.frames
import com.linroid.ketch.app.snapshot.withScene
import kotlin.test.Test
import kotlin.test.assertEquals

class TaskRowPointerTest {
  @Test
  fun rightClick_opensContextMenuAtPointer_withoutClickingRow() {
    pointerScene { events ->
      click(PointerButton.Secondary)
      assertEquals(listOf("context:$POSITION"), events)
    }
  }

  @Test
  fun rightClick_overChildAction_opensContextMenu_withoutRunningAction() {
    pointerScene(childAction = true) { events ->
      click(PointerButton.Secondary)
      assertEquals(listOf("context:$POSITION"), events)
    }
  }

  @Test
  fun primaryClick_selectsRow_butChildActionDoesNot() {
    pointerScene { events ->
      click(PointerButton.Primary)
      assertEquals(listOf("click"), events)
    }
    pointerScene(childAction = true) { events ->
      click(PointerButton.Primary)
      assertEquals(listOf("child"), events)
    }
  }

  @Test
  fun middleClick_doesNotSelectRowOrOpenMenu() {
    pointerScene { events ->
      click(PointerButton.Tertiary)
      assertEquals(emptyList(), events)
    }
  }

  @Test
  fun controlClick_onMac_opensContextMenu() {
    val previous = KeyboardPlatform.override
    KeyboardPlatform.override = KeyboardPlatform.Mac
    try {
      pointerScene { events ->
        click(PointerButton.Primary, ctrl = true)
        assertEquals(listOf("context:$POSITION"), events)
      }
    } finally {
      KeyboardPlatform.override = previous
    }
  }

  @Test
  fun touchTap_stillClicksRow() {
    pointerScene { events ->
      click(PointerButton.Primary, type = PointerType.Touch)
      assertEquals(listOf("click"), events)
    }
  }

  private fun pointerScene(
    childAction: Boolean = false,
    test: suspend ImageComposeScene.(List<String>) -> Unit,
  ) {
    val events = mutableListOf<String>()
    withScene(
      width = 200,
      height = 80,
      content = {
        Box(
          Modifier.fillMaxSize().taskRowPointer(
            onClick = { events += "click" },
            onDoubleClick = { events += "double" },
            onContextClick = { events += "context:$it" },
          )
        ) {
          if (childAction) {
            Box(Modifier.fillMaxSize().clickable { events += "child" })
          }
        }
      },
    ) {
      frames(2)
      test(events)
    }
  }

  private suspend fun ImageComposeScene.click(
    button: PointerButton,
    ctrl: Boolean = false,
    type: PointerType = PointerType.Mouse,
  ) {
    // Keep the first click beyond the double-click window, even in a fresh virtual clock.
    val time = SnapshotClock.millis + 1_000
    sendPointerEvent(PointerEventType.Move, POSITION, timeMillis = time)
    sendPointerEvent(
      eventType = PointerEventType.Press,
      position = POSITION,
      timeMillis = time,
      buttons = PointerButtons(
        isPrimaryPressed = button == PointerButton.Primary,
        isSecondaryPressed = button == PointerButton.Secondary,
        isTertiaryPressed = button == PointerButton.Tertiary,
      ),
      button = button,
      keyboardModifiers = PointerKeyboardModifiers(isCtrlPressed = ctrl),
      type = type,
    )
    sendPointerEvent(
      eventType = PointerEventType.Release,
      position = POSITION,
      timeMillis = time + 10,
      buttons = PointerButtons(),
      button = button,
      type = type,
    )
    frames(2)
  }

  private companion object {
    val POSITION = Offset(60f, 30f)
  }
}
