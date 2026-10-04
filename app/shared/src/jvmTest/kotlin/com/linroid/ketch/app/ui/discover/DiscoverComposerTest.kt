package com.linroid.ketch.app.ui.discover

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import com.linroid.ketch.app.snapshot.frames
import com.linroid.ketch.app.snapshot.nodes
import com.linroid.ketch.app.snapshot.withScene
import com.linroid.ketch.app.state.DiscoverDraft
import com.linroid.ketch.app.theme.KetchTheme
import com.linroid.ketch.config.DensityMode
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Discover's composer on a phone: its website field as the on-screen keyboard comes and goes. */
class DiscoverComposerTest {
  @Test
  fun sitesChip_tappedWithTheKeyboardUp_opensTheFieldWithTheKeyboardInIt() {
    phoneComposer(keyboardUp = true) { _, focus ->
      assertFalse(HINT in texts())

      chip().click()
      frames(FRAMES)

      assertTrue(HINT in texts(), "The website field opens: ${texts()}")
      assertTrue(focus.sitesFocused, "The keyboard moves into it, so it stays open")
    }
  }

  @Test
  fun sitesField_keyboardOpensForTheMessage_foldsItUntilTheChipOpensItAgain() {
    phoneComposer(keyboardUp = false, showSites = true) { keyboard, focus ->
      assertTrue(HINT in texts())

      focus.message.requestFocus()
      keyboard.up = true
      // Long enough for the field to fold away.
      frames(FOLD_FRAMES)
      assertFalse(HINT in texts(), "The keyboard folds the field away")

      chip().click()
      frames(FRAMES)
      assertTrue(HINT in texts(), "The chip opens it again: ${texts()}")
      focus.message.requestFocus()
      frames(FRAMES)
      assertTrue(HINT in texts(), "It stays open while the keyboard does")
    }
  }

  /**
   * Shows the composer as a phone does, with the keyboard up when [keyboardUp] and the website
   * field asked for when [showSites], and runs [test] on it.
   */
  private fun phoneComposer(
    keyboardUp: Boolean,
    showSites: Boolean = false,
    test: suspend ImageComposeScene.(Keyboard, DiscoverFocus) -> Unit,
  ) {
    val draft = DiscoverDraft(showSites = showSites)
    val focus = DiscoverFocus()
    val keyboard = Keyboard(keyboardUp)
    withScene(
      width = WIDTH,
      height = HEIGHT,
      content = {
        KetchTheme(darkTheme = false, density = DensityMode.Comfortable, reduceMotion = true) {
          DiscoverComposer(
            draft = draft,
            firstMessage = true,
            running = false,
            touch = true,
            softKeyboard = true,
            keyboardVisible = keyboard.up,
            focus = focus,
            onSend = {},
            onStop = {},
          )
        }
      },
    ) {
      frames(FRAMES)
      test(keyboard, focus)
    }
  }

  /** Whether the on-screen keyboard shows. */
  private class Keyboard(up: Boolean) {
    var up by mutableStateOf(up)
  }

  private fun ImageComposeScene.chip(): SemanticsNode =
    nodes().first { it.ownText() == SITES_CHIP }

  private fun ImageComposeScene.texts(): Set<String> = nodes().mapNotNull { it.ownText() }.toSet()

  private fun SemanticsNode.ownText(): String? =
    config.getOrNull(SemanticsProperties.Text)?.joinToString("") { it.text }

  /** Taps [this] node, or the closest clickable one around it. */
  private fun SemanticsNode.click() {
    var node: SemanticsNode? = this
    while (node != null && SemanticsActions.OnClick !in node.config) node = node.parent
    val action = node?.config?.get(SemanticsActions.OnClick)?.action
    assertTrue(action != null, "Nothing to tap around $this")
    action()
  }

  private companion object {
    const val WIDTH = 400
    const val HEIGHT = 400
    const val FRAMES = 12
    const val FOLD_FRAMES = 60
    const val SITES_CHIP = "Limit to websites"
    const val HINT = "Searches only these websites and their subdomains. Separate them with commas."
  }
}
