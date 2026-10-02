package com.linroid.ketch.app.ui.settings

import androidx.compose.runtime.Composable
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.InternalComposeUiApi
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.unit.Density
import com.linroid.ketch.app.input.KeyboardPlatform
import com.linroid.ketch.app.snapshot.SettingsEnvironment
import com.linroid.ketch.app.snapshot.SettingsFrame
import com.linroid.ketch.app.snapshot.SnapshotHarness
import com.linroid.ketch.app.snapshot.SnapshotTheme
import com.linroid.ketch.app.state.SettingsTarget
import com.linroid.ketch.app.theme.KetchDensity
import com.linroid.ketch.config.DensityMode
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds

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
      runBlocking(SnapshotHarness.ui) {
        val scene = ImageComposeScene(
          width = WIDTH,
          height = HEIGHT,
          density = Density(1f),
          coroutineContext = SnapshotHarness.ui,
        ) {
          Settings(environment, query, onClose)
        }
        try {
          scene.frames()
          test(scene)
        } finally {
          scene.close()
        }
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

  private suspend fun ImageComposeScene.frames() {
    repeat(FRAMES) {
      render(System.nanoTime())
      delay(FRAME)
    }
  }

  /** Presses [key], with the primary modifier (⌘ on Apple keyboards, Ctrl elsewhere). */
  @OptIn(InternalComposeUiApi::class)
  private suspend fun ImageComposeScene.key(key: Key, primary: Boolean = false) {
    val apple = KeyboardPlatform.current.isApple
    for (type in listOf(KeyEventType.KeyDown, KeyEventType.KeyUp)) {
      sendKeyEvent(
        KeyEvent(
          key = key,
          type = type,
          isMetaPressed = primary && apple,
          isCtrlPressed = primary && !apple,
        ),
      )
    }
    frames()
  }

  private fun ImageComposeScene.nodes(): List<SemanticsNode> =
    semanticsOwners.flatMap { it.unmergedRootSemanticsNode.all() }

  private fun SemanticsNode.all(): List<SemanticsNode> =
    listOf(this) + children.flatMap { it.all() }

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
    val FRAME = 16.milliseconds
  }
}
