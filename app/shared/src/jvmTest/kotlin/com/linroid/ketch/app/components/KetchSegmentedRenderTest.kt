package com.linroid.ketch.app.components

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.text.TextLayoutResult
import com.linroid.ketch.app.snapshot.frames
import com.linroid.ketch.app.snapshot.nodes
import com.linroid.ketch.app.snapshot.withScene
import com.linroid.ketch.app.theme.KetchTheme
import com.linroid.ketch.config.DensityMode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class KetchSegmentedRenderTest {
  @Test
  fun longLabels_initialAndExternalSelections_scrollFullyIntoView() {
    val options = listOf("Volle Geschwindigkeit", "Drosselung", "Automatisch")
    val selected = mutableStateOf(options.last())
    val scroll = ScrollState(0)
    withScene(
      width = WIDTH,
      height = 80,
      content = {
        KetchTheme(darkTheme = false, density = DensityMode.Compact, reduceMotion = true) {
          KetchSegmented(
            options = options,
            selected = selected.value,
            onSelect = { selected.value = it },
            label = { it },
            modifier = Modifier.horizontalScroll(scroll),
            revealInitialSelection = true,
          )
        }
      },
    ) {
      frames(FRAMES)
      assertTrue(scroll.value > 0, "The initial selection must scroll into view")
      assertLabelVisible(options.last())

      selected.value = options.first()
      frames(FRAMES)
      assertLabelVisible(options.first())
    }
  }

  private fun ImageComposeScene.assertLabelVisible(text: String) {
    val node = nodes().single {
      it.config.getOrNull(SemanticsProperties.Text)?.singleOrNull()?.text == text
    }
    val layouts = mutableListOf<TextLayoutResult>()
    checkNotNull(node.config.getOrNull(SemanticsActions.GetTextLayoutResult)?.action)(layouts)
    val layout = layouts.single()
    assertFalse(layout.isLineEllipsized(0), "The label must not be ellipsized")
    assertEquals(layout.size.width.toFloat(), node.boundsInRoot.width, 1f)
    assertTrue(node.boundsInRoot.left >= 0 && node.boundsInRoot.right <= WIDTH)
  }

  private companion object {
    const val WIDTH = 240
    const val FRAMES = 30
  }
}
