package com.linroid.ketch.app.icons

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.keyframes
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import com.linroid.ketch.app.theme.KetchTheme
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

internal const val DISCOVER_STAND = "M9.5 12.6V14M6.5 17.5l3-3.5 3 3.5"
internal const val DISCOVER_BARREL = "M4 9.5L11 5l3 5-8 3z"
internal const val DISCOVER_EYEPIECE = "M3 11l1.5 2.5 2-1.2"
internal const val DISCOVER_SPARK =
  "M16.5 1C16.8 2.8 17.2 3.2 19 3.5C17.2 3.8 16.8 4.2 16.5 6" +
    "C16.2 4.2 15.8 3.8 14 3.5C15.8 3.2 16.2 2.8 16.5 1z"

private val Stand by lazy { IconData.strokes(DISCOVER_STAND).toImageVector("discoverStand") }
private val Barrel by lazy {
  IconData.strokes(DISCOVER_BARREL, DISCOVER_EYEPIECE).toImageVector("discoverBarrel")
}
private val Spark by lazy { IconData.fills(DISCOVER_SPARK).toImageVector("discoverSpark") }

/** Scans once, catches a star, then rests. Reduced motion uses the complete static vector. */
@Composable
internal fun AnimatedDiscoverIcon(modifier: Modifier, colorFilter: ColorFilter) {
  val easing = KetchTheme.motion.easeStandard
  val angle = remember { Animatable(0f) }
  val twinkle = remember { Animatable(1f) }
  LaunchedEffect(easing) {
    while (isActive) {
      coroutineScope {
        launch {
          angle.animateTo(0f, keyframes {
            durationMillis = SCAN_MILLIS
            0f at 0 using easing
            7f at 900 using easing
            -5f at 1900 using easing
            0f at SCAN_MILLIS
          })
        }
        launch {
          twinkle.animateTo(1f, keyframes {
            durationMillis = TWINKLE_MILLIS
            1f at 0 using easing
            0.65f at 1900 using easing
            1.15f at 2700 using easing
            1f at TWINKLE_MILLIS
          })
        }
      }
      // Both gestures have finished: request no frames until the next seven-second cycle.
      delay(REST_MILLIS)
    }
  }
  Box(modifier) {
    Image(
      painter = rememberVectorPainter(Stand),
      contentDescription = null,
      colorFilter = colorFilter,
      modifier = Modifier.matchParentSize(),
    )
    Image(
      painter = rememberVectorPainter(Barrel),
      contentDescription = null,
      colorFilter = colorFilter,
      modifier = Modifier.matchParentSize().graphicsLayer {
        transformOrigin = TransformOrigin(9.5f / ICON_VIEWPORT, 12.6f / ICON_VIEWPORT)
        rotationZ = angle.value
      },
    )
    Image(
      painter = rememberVectorPainter(Spark),
      contentDescription = null,
      colorFilter = colorFilter,
      modifier = Modifier.matchParentSize().graphicsLayer {
        transformOrigin = TransformOrigin(16.5f / ICON_VIEWPORT, 3.5f / ICON_VIEWPORT)
        scaleX = twinkle.value
        scaleY = twinkle.value
      },
    )
  }
}

private const val SCAN_MILLIS = 2600
private const val TWINKLE_MILLIS = 3200
private const val REST_MILLIS = 3800L
