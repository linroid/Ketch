package com.linroid.ketch.app.ui.settings

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import com.linroid.ketch.app.snapshot.SettingsFrame
import com.linroid.ketch.app.snapshot.SnapshotTheme
import com.linroid.ketch.app.snapshot.frames
import com.linroid.ketch.app.snapshot.nodes
import com.linroid.ketch.app.snapshot.withScene
import com.linroid.ketch.app.snapshot.withSettings
import com.linroid.ketch.app.state.SettingsCategory
import com.linroid.ketch.app.state.SettingsTarget
import com.linroid.ketch.app.theme.KetchDensity
import ketch.app.shared.generated.resources.Res
import ketch.app.shared.generated.resources.settings_downloads_folders
import kotlinx.coroutines.runBlocking
import org.jetbrains.compose.resources.getString
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Search jumps to rows and groups by their titles, so each page is rendered as the desktop shows
 * it and every setting search can find must name one of them.
 */
class SettingsSearchRenderTest {
  @Test
  fun settingsIndex_everyEntry_namesARowOrGroupOnItsPage() {
    val index = runBlocking { loadSettingsSearchIndex() }
    val missing = SettingsCategory.entries.flatMap { category ->
      val texts = renderedTexts(category)
      index.entries
        .filter { it.category == category && it.needs in Shown && it.title !in Conditional }
        .filter { entry -> entry.anchors.none { it.lowercase() in texts } }
        .map { "${it.page}: ${it.title}" }
    }

    assertEquals(emptyList(), missing)
  }

  /** Every text on [category]'s page, in lower case, as group titles show in capitals. */
  private fun renderedTexts(category: SettingsCategory): Set<String> =
    withSettings(SnapshotTheme.Light, KetchDensity.Compact) { environment ->
      withScene(
        width = WIDTH,
        height = HEIGHT,
        content = {
          SettingsFrame(environment, SnapshotTheme.Light, KetchDensity.Compact, desktop = true) {
            SettingsContent(
              state = environment.controller.state,
              target = SettingsTarget(category.page),
              onClose = {},
            )
          }
        },
      ) {
        frames(FRAMES)
        nodes()
          .mapNotNull { it.config.getOrNull(SemanticsProperties.Text) }
          .flatten()
          .map { it.text.lowercase() }
          .toSet()
      }
    }

  private companion object {
    const val WIDTH = 860
    const val HEIGHT = 4000
    const val FRAMES = 30

    /** What the desktop app offers; the web's and phones' entries are not on its pages. */
    val Shown = setOf(
      null,
      SettingsFeature.Desktop,
      SettingsFeature.SetupChecklist,
      SettingsFeature.KeepAwake
    )

    /**
     * Settings that only show sometimes: the add sheet's folders once there are any, and the
     * Sharing rows under its collapsed Advanced section.
     */
    val Conditional: Set<String> = runBlocking {
      (AdvancedRows + Res.string.settings_downloads_folders).map { getString(it) }.toSet()
    }
  }
}
