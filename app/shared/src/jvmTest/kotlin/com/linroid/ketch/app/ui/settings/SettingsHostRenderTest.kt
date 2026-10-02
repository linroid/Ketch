package com.linroid.ketch.app.ui.settings

import androidx.compose.runtime.Composable
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import com.linroid.ketch.app.input.KeyboardPlatform
import com.linroid.ketch.app.snapshot.SettingsEnvironment
import com.linroid.ketch.app.snapshot.SettingsFrame
import com.linroid.ketch.app.snapshot.SnapshotHarness
import com.linroid.ketch.app.snapshot.SnapshotTheme
import com.linroid.ketch.app.snapshot.frames
import com.linroid.ketch.app.snapshot.nodes
import com.linroid.ketch.app.snapshot.sendKey
import com.linroid.ketch.app.snapshot.withScene
import com.linroid.ketch.app.state.SettingsTarget
import com.linroid.ketch.app.theme.KetchDensity
import com.linroid.ketch.config.DensityMode
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Settings driven with the keyboard, as the desktop's Settings window shows it. */
class SettingsHostRenderTest {
  @Test
  fun settingsContent_primaryF_putsTheKeyboardInSearch() {
    var closed = 0
    runSettings(onClose = { closed++ }) { scene ->
      assertEquals(null, scene.focusedFieldText())

      scene.key(Key.F, primary = true)

      assertEquals("", scene.focusedFieldText())
      assertEquals(0, closed)
    }
  }

  @Test
  fun settingsContent_escape_clearsTheSearchThenCloses() {
    var closed = 0
    runSettings(query = "trackers", onClose = { closed++ }) { scene ->
      assertTrue("extra trackers" in scene.texts())

      scene.key(Key.Escape)

      assertEquals("", scene.focusedFieldText())
      assertTrue("extra trackers" !in scene.texts())
      assertEquals(0, closed)

      scene.key(Key.Escape)

      assertEquals(1, closed)
    }
  }

  @Test
  fun settingsContent_enterOnAResult_opensItsPage() {
    runSettings(query = "trackers") { scene ->
      scene.key(Key.Enter)

      assertTrue("add trackers" in scene.texts(), "The BitTorrent page shows: ${scene.texts()}")
    }
  }

  @Test
  fun settingsNav_pageItem_announcesItsSummaryAsItsState() {
    runSettings { scene ->
      val states = scene.stateDescriptions()
      assertTrue("Light · Signal" in states, "The General item's state: $states")
      assertTrue("light · signal" !in scene.texts(), "The summary shows: ${scene.texts()}")
    }
  }

  private fun runSettings(
    query: String = "",
    onClose: () -> Unit = {},
    test: suspend (ImageComposeScene) -> Unit,
  ) {
    val environment = runBlocking(SnapshotHarness.ui) {
      SettingsEnvironment(SnapshotTheme.Light, DensityMode.Compact)
    }
    try {
      withScene(WIDTH, HEIGHT, content = { Settings(environment, query, onClose) }) {
        frames(FRAMES)
        test(this)
      }
    } finally {
      runBlocking(SnapshotHarness.ui) { environment.close() }
    }
  }

  @Composable
  private fun Settings(environment: SettingsEnvironment, query: String, onClose: () -> Unit) {
    SettingsFrame(environment, SnapshotTheme.Light, KetchDensity.Compact, desktop = true) {
      SettingsContent(
        state = environment.controller.state,
        target = SettingsTarget(SettingsTarget.Page.General),
        onClose = onClose,
        initialQuery = query,
      )
    }
  }

  /** Presses [key], with the primary modifier (⌘ on Apple keyboards, Ctrl elsewhere). */
  private suspend fun ImageComposeScene.key(key: Key, primary: Boolean = false) {
    val apple = KeyboardPlatform.current.isApple
    sendKey(key, meta = primary && apple, ctrl = primary && !apple)
    frames(FRAMES)
  }

  /** Every text shown, in lower case. */
  private fun ImageComposeScene.texts(): Set<String> = nodes()
    .mapNotNull { it.config.getOrNull(SemanticsProperties.Text) }
    .flatten()
    .map { it.text.lowercase() }
    .toSet()

  /** Every state description screen readers announce. */
  private fun ImageComposeScene.stateDescriptions(): Set<String> = nodes()
    .mapNotNull { it.config.getOrNull(SemanticsProperties.StateDescription) }
    .toSet()

  /** The text of the field that has the keyboard, or `null` when no field has it. */
  private fun ImageComposeScene.focusedFieldText(): String? = nodes()
    .firstOrNull { it.config.getOrNull(SemanticsProperties.Focused) == true }
    ?.config?.getOrNull(SemanticsProperties.EditableText)
    ?.text

  private companion object {
    const val WIDTH = 860
    const val HEIGHT = 640
    const val FRAMES = 12
  }
}
