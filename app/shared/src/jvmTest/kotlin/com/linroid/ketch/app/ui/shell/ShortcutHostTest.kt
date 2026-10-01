package com.linroid.ketch.app.ui.shell

import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.InternalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import com.linroid.ketch.app.input.KetchCommand
import com.linroid.ketch.app.input.KetchCommands
import com.linroid.ketch.app.input.KeyboardPlatform
import com.linroid.ketch.app.snapshot.SnapshotHarness
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals

class ShortcutHostTest {

  @Test
  fun shortcutHost_nothingFocused_takesTheFocusAndRunsPaste() {
    val window = ShortcutWindow(runs = true)

    window.show { ShortcutHost(onCommand = window::run) { Box(Modifier.size(Side)) } }
    window.press(KetchCommands.PasteLinks)

    assertEquals(listOf(KetchCommands.PasteLinks), window.close())
  }

  @Test
  fun shortcutHost_focusedControlKeepsTheKey_leavesPasteToIt() {
    val window = ShortcutWindow(runs = true)

    window.show { ShortcutHost(onCommand = window::run) { KeyKeepingBox() } }
    window.press(KetchCommands.PasteLinks)
    window.press(KetchCommands.Add)

    assertEquals(listOf(KetchCommands.Add), window.close())
  }

  @Test
  fun shortcutHost_hostOwnsTheChord_leavesItToTheHost() {
    val window = ShortcutWindow(runs = true)

    window.show {
      CompositionLocalProvider(LocalHostShortcuts provides setOf(KetchCommands.Add)) {
        ShortcutHost(onCommand = window::run) { Box(Modifier.size(Side)) }
      }
    }
    window.press(KetchCommands.Add)
    window.press(KetchCommands.Discover)

    assertEquals(listOf(KetchCommands.Discover), window.close())
  }

  @Test
  fun shortcutHost_commandNotRun_offersTheKeyAgainAfterTheFocusedControl() {
    val window = ShortcutWindow(runs = false)

    window.show { ShortcutHost(onCommand = window::run) { Box(Modifier.size(Side)) } }
    window.press(KetchCommands.Discover)

    assertEquals(listOf(KetchCommands.Discover, KetchCommands.Discover), window.close())
  }

  @Test
  fun shortcutHost_focusablePopupOpen_leavesItTheKeys() {
    val window = ShortcutWindow(runs = true)
    val popupKeys = mutableListOf<Key>()

    window.show {
      ShortcutHost(onCommand = window::run) {
        Box(Modifier.size(Side))
        Popup(properties = PopupProperties(focusable = true)) {
          val focus = remember { FocusRequester() }
          Box(
            Modifier
              .size(Side)
              .focusRequester(focus)
              .onKeyEvent {
                if (it.type == KeyEventType.KeyDown) popupKeys += it.key
                true
              }
              .focusable(),
          )
          LaunchedEffect(focus) { focus.requestFocus() }
        }
      }
    }
    window.press(KetchCommands.Add)

    assertEquals(listOf(Key.N), popupKeys)
    assertEquals(emptyList(), window.close())
  }

  /** A focused box that keeps every key, as a focused text field keeps ⌘V. */
  @Composable
  private fun KeyKeepingBox() {
    val focus = remember { FocusRequester() }
    Box(Modifier.size(Side).focusRequester(focus).onKeyEvent { true }.focusable())
    LaunchedEffect(focus) { focus.requestFocus() }
  }

  /** A window without a screen whose shortcut handler records what it is asked to run. */
  private class ShortcutWindow(private val runs: Boolean) {
    private val commands = mutableListOf<KetchCommand>()
    private lateinit var scene: ImageComposeScene

    fun run(command: KetchCommand): Boolean {
      commands += command
      return runs
    }

    fun show(content: @Composable () -> Unit) = runBlocking(SnapshotHarness.ui) {
      scene = ImageComposeScene(
        width = SIZE,
        height = SIZE,
        density = Density(1f),
        coroutineContext = SnapshotHarness.ui,
        content = content,
      )
      settle()
    }

    @OptIn(InternalComposeUiApi::class)
    fun press(command: KetchCommand) = runBlocking(SnapshotHarness.ui) {
      val platform = KeyboardPlatform.current
      val press = command.chords(platform).first().resolve(platform)
      for (type in listOf(KeyEventType.KeyDown, KeyEventType.KeyUp)) {
        val event = KeyEvent(
          key = press.key,
          type = type,
          isMetaPressed = press.meta,
          isCtrlPressed = press.ctrl,
          isAltPressed = press.alt,
          isShiftPressed = press.shift,
        )
        scene.sendKeyEvent(event)
      }
      settle()
    }

    /** Closes the window and returns the commands it was asked to run. */
    fun close(): List<KetchCommand> {
      runBlocking(SnapshotHarness.ui) { scene.close() }
      return commands
    }

    private suspend fun settle() {
      repeat(FRAMES) { frame ->
        scene.render(frame * FRAME_MILLIS * NANOS_PER_MILLI)
        delay(FRAME_MILLIS)
      }
    }
  }

  private companion object {
    const val SIZE = 200
    const val FRAMES = 6
    const val FRAME_MILLIS = 16L
    const val NANOS_PER_MILLI = 1_000_000L
    val Side = 80.dp
  }
}
