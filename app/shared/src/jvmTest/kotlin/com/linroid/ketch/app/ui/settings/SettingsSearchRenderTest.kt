package com.linroid.ketch.app.ui.settings

import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.unit.Density
import com.linroid.ketch.app.snapshot.SettingsEnvironment
import com.linroid.ketch.app.snapshot.SettingsFrame
import com.linroid.ketch.app.snapshot.SnapshotHarness
import com.linroid.ketch.app.snapshot.SnapshotTheme
import com.linroid.ketch.app.state.SettingsCategory
import com.linroid.ketch.app.state.SettingsTarget
import com.linroid.ketch.app.theme.KetchDensity
import com.linroid.ketch.config.DensityMode
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.milliseconds

/**
 * Search jumps to rows and groups by their titles, so each page is rendered as the desktop shows
 * it and every setting search can find must name one of them.
 */
class SettingsSearchRenderTest {
  @Test
  fun settingsIndex_everyEntry_namesARowOrGroupOnItsPage() {
    val missing = SettingsCategory.entries.flatMap { category ->
      val texts = renderedTexts(category)
      SettingsIndex
        .filter { it.category == category && it.needs in Shown && it.title !in Conditional }
        .filter { entry -> entry.anchors.none { it.lowercase() in texts } }
        .map { "${category.title}: ${it.title}" }
    }

    assertEquals(emptyList(), missing)
  }

  /** Every text on [category]'s page, in lower case, as group titles show in capitals. */
  private fun renderedTexts(category: SettingsCategory): Set<String> {
    val environment = runBlocking(SnapshotHarness.ui) {
      SettingsEnvironment(SnapshotTheme.Light, DensityMode.Compact)
    }
    try {
      return runBlocking(SnapshotHarness.ui) {
        val scene = ImageComposeScene(
          width = WIDTH,
          height = HEIGHT,
          density = Density(1f),
          coroutineContext = SnapshotHarness.ui,
        ) {
          SettingsFrame(environment, SnapshotTheme.Light, KetchDensity.Compact, desktop = true) {
            SettingsContent(
              state = environment.controller.state,
              target = SettingsTarget(category.page),
              onClose = {},
            )
          }
        }
        try {
          repeat(FRAMES) {
            scene.render(System.nanoTime())
            delay(FRAME)
          }
          scene.semanticsOwners
            .flatMap { it.unmergedRootSemanticsNode.all() }
            .mapNotNull { it.config.getOrNull(SemanticsProperties.Text) }
            .flatten()
            .map { it.text.lowercase() }
            .toSet()
        } finally {
          scene.close()
        }
      }
    } finally {
      runBlocking(SnapshotHarness.ui) { environment.close() }
    }
  }

  private fun SemanticsNode.all(): List<SemanticsNode> =
    listOf(this) + children.flatMap { it.all() }

  private companion object {
    const val WIDTH = 860
    const val HEIGHT = 4000
    const val FRAMES = 30
    val FRAME = 16.milliseconds

    /** What the desktop app offers; the web's and phones' entries are not on its pages. */
    val Shown = setOf(null, SettingsFeature.Desktop, SettingsFeature.SetupChecklist)

    /**
     * Settings that only show sometimes: the add sheet's folders once there are any, and the
     * Sharing rows under its collapsed Advanced section.
     */
    val Conditional = setOf(
      "Folders in the add sheet",
      "Reachable from other devices",
      "Port",
      "Access code",
      "Discoverable on the local network",
      "Websites allowed to connect",
      "Start sharing when Ketch opens",
    )
  }
}
