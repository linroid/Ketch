package com.linroid.ketch.app.icons

import androidx.compose.foundation.layout.Box
import androidx.compose.ui.unit.dp
import com.linroid.ketch.app.snapshot.withScene
import com.linroid.ketch.app.theme.KetchTheme
import kotlinx.coroutines.delay
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class KetchIconMotionTest {
  @Test
  fun animatedIcons_motionEnabled_changeAcrossFrames() {
    for (icon in AnimatedIcons) {
      assertTrue(frames(icon).distinct().size > 1, "${icon.name} should move")
    }
  }

  @Test
  fun animatedIcons_reducedMotion_matchesDisabledAnimationAndStaysStill() {
    for (icon in AnimatedIcons) {
      val reduced = frames(icon, reduced = true)
      val disabled = frames(icon, animate = false)
      assertEquals(1, reduced.distinct().size, "${icon.name} must respect reduced motion")
      assertEquals(1, disabled.distinct().size, "${icon.name} must respect animate=false")
      assertEquals(disabled, reduced, "${icon.name} must use the same static silhouette")
    }
  }

  @Test
  fun otherIcons_remainStill() {
    assertEquals(1, frames(KetchIcon.Settings).distinct().size)
  }

  private fun frames(
    icon: KetchIcon,
    reduced: Boolean = false,
    animate: Boolean = true,
  ): List<Int> = withScene(80, 80, content = {
    KetchTheme(darkTheme = false, reduceMotion = reduced) {
      Box { KetchIconImage(icon, size = 64.dp, animate = animate) }
    }
  }) {
    buildList {
      for (frame in 0..28) {
        val image = render(frame * 100_000_000L)
        try {
          // Skip initial composition; compare the rendered icon through its entire gesture.
          if (frame > 1) {
            val data = checkNotNull(image.encodeToData())
            try {
              add(data.bytes.contentHashCode())
            } finally {
              data.close()
            }
          }
        } finally {
          image.close()
        }
        delay(2)
      }
    }
  }
}

private val AnimatedIcons = listOf(
  KetchIcon.Active, KetchIcon.Discover, KetchIcon.Devices, KetchIcon.Search
)
