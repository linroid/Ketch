package com.linroid.ketch.app.ui.shell

import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import com.linroid.ketch.app.input.CommandScope
import com.linroid.ketch.app.input.KetchCommand
import com.linroid.ketch.app.input.KetchCommands
import com.linroid.ketch.app.input.KeyboardPlatform
import com.linroid.ketch.app.snapshot.frames
import com.linroid.ketch.app.snapshot.sendKey
import com.linroid.ketch.app.snapshot.withScene
import kotlin.test.Test
import kotlin.test.assertEquals

class ShortcutHostTest {

  @Test
  fun shortcutHost_nothingFocused_takesTheFocusAndRunsPaste() {
    val ran = shortcutTest(runs = true) { press(KetchCommands.PasteLinks) }

    assertEquals(listOf(KetchCommands.PasteLinks), ran)
  }

  @Test
  fun shortcutHost_focusedControlKeepsTheKey_leavesPasteToIt() {
    val ran = shortcutTest(
      runs = true,
      content = { run -> ShortcutHost(onCommand = run) { KeyKeepingBox() } },
    ) {
      press(KetchCommands.PasteLinks)
      press(KetchCommands.Add)
    }

    assertEquals(listOf(KetchCommands.Add), ran)
  }

  @Test
  fun shortcutHost_hostOwnsTheChord_leavesItToTheHost() {
    val ran = shortcutTest(
      runs = true,
      content = { run ->
        CompositionLocalProvider(LocalHostShortcuts provides setOf(KetchCommands.Add)) {
          ShortcutHost(onCommand = run) { Box(Modifier.size(Side)) }
        }
      },
    ) {
      press(KetchCommands.Add)
      press(KetchCommands.Discover)
    }

    assertEquals(listOf(KetchCommands.Discover), ran)
  }

  @Test
  fun shortcutHost_commandNotRun_offersTheKeyAgainAfterTheFocusedControl() {
    val ran = shortcutTest(runs = false) { press(KetchCommands.Discover) }

    assertEquals(listOf(KetchCommands.Discover, KetchCommands.Discover), ran)
  }

  @Test
  fun shortcutHost_pageKeyWithNothingOnThePageFocused_runsIt() {
    val ran = shortcutTest(
      runs = true,
      content = { run ->
        ShortcutHost(onCommand = run, page = CommandScope.Discover) { Box(Modifier.size(Side)) }
      },
    ) {
      press(KetchCommands.DiscoverHistory)
    }

    assertEquals(listOf(KetchCommands.DiscoverHistory), ran)
  }

  @Test
  fun shortcutHost_pageKeyWhileAnOverlayIsOpen_leavesIt() {
    val ran = shortcutTest(
      runs = true,
      content = { run ->
        ShortcutHost(
          onCommand = run,
          overlay = CommandScope.Palette,
          page = CommandScope.Discover,
        ) { Box(Modifier.size(Side)) }
      },
    ) {
      press(KetchCommands.DiscoverHistory)
    }

    assertEquals(emptyList(), ran)
  }

  @Test
  fun shortcutHost_pageKeyTheFocusedControlKeeps_leavesItToTheControl() {
    val ran = shortcutTest(
      runs = true,
      content = { run ->
        ShortcutHost(onCommand = run, page = CommandScope.Discover) { KeyKeepingBox() }
      },
    ) {
      press(KetchCommands.DiscoverHistory)
    }

    assertEquals(emptyList(), ran)
  }

  @Test
  fun shortcutHost_focusablePopupOpen_leavesItTheKeys() {
    val popupKeys = mutableListOf<Key>()

    val ran = shortcutTest(
      runs = true,
      content = { run ->
        ShortcutHost(onCommand = run) {
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
      },
    ) {
      press(KetchCommands.Add)
    }

    assertEquals(listOf(Key.N), popupKeys)
    assertEquals(emptyList(), ran)
  }

  /** A focused box that keeps every key, as a focused text field keeps ⌘V. */
  @Composable
  private fun KeyKeepingBox() {
    val focus = remember { FocusRequester() }
    Box(Modifier.size(Side).focusRequester(focus).onKeyEvent { true }.focusable())
    LaunchedEffect(focus) { focus.requestFocus() }
  }

  /**
   * Shows [content] in a window without a screen and runs [test] in it, handing [content] a
   * shortcut handler that records the commands it is asked to run and answers [runs]. Returns
   * those commands.
   */
  private fun shortcutTest(
    runs: Boolean,
    content: @Composable (run: (KetchCommand) -> Boolean) -> Unit = { run ->
      ShortcutHost(onCommand = run) { Box(Modifier.size(Side)) }
    },
    test: suspend ImageComposeScene.() -> Unit,
  ): List<KetchCommand> {
    val commands = mutableListOf<KetchCommand>()
    val run = { command: KetchCommand ->
      commands += command
      runs
    }
    withScene(SIZE, SIZE, content = { content(run) }) {
      settle()
      test()
    }
    return commands
  }

  private suspend fun ImageComposeScene.press(command: KetchCommand) {
    val platform = KeyboardPlatform.current
    val press = command.chords(platform).first().resolve(platform)
    sendKey(press.key, press.meta, press.ctrl, press.alt, press.shift)
    settle()
  }

  private suspend fun ImageComposeScene.settle() = frames(FRAMES)

  private companion object {
    const val SIZE = 200
    const val FRAMES = 6
    val Side = 80.dp
  }
}
