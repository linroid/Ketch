package com.linroid.ketch.app.icons

import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.keyframes
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import com.linroid.ketch.app.theme.KetchTheme

internal const val DOWNLOAD_ARROW = "M10 2.5v10M6.5 9L10 12.5L13.5 9"
internal const val DOWNLOAD_TRAY =
  "M3.5 13.5v2.5a1.5 1.5 0 0 0 1.5 1.5h10a1.5 1.5 0 0 0 1.5-1.5v-2.5"
internal const val DEVICES_DISPLAY =
  "M15 5.5V5A1.5 1.5 0 0 0 13.5 3.5h-10A1.5 1.5 0 0 0 2 5v6.5A1.5 1.5 0 0 0 3.5 13H10" +
    "M7 13v3.5M5 16.5h4"
internal const val DEVICES_PHONE =
  "M13.7 8h3.1A1.2 1.2 0 0 1 18 9.2v7.1a1.2 1.2 0 0 1 -1.2 1.2h-3.1a1.2 1.2 0 0 1 -1.2-1.2" +
    "V9.2A1.2 1.2 0 0 1 13.7 8z"

private val DownloadArrow by lazy { IconData.strokes(DOWNLOAD_ARROW).toImageVector("DownloadArrow") }
private val DownloadTray by lazy { IconData.strokes(DOWNLOAD_TRAY).toImageVector("DownloadTray") }
private val DevicesDisplay by lazy { IconData.strokes(DEVICES_DISPLAY).toImageVector("DevicesDisplay") }
private val DevicesPhone by lazy { IconData.strokes(DEVICES_PHONE).toImageVector("DevicesPhone") }

/** A delivery bounce, a phone greeting or a searching sweep, followed by a long rest. */
@Composable
internal fun AnimatedNavigationIcon(
  icon: KetchIcon,
  modifier: Modifier,
  colorFilter: ColorFilter,
) {
  val easing = KetchTheme.motion.easeStandard
  val movement = rememberInfiniteTransition(label = "navigationIcon").animateFloat(
    initialValue = 0f,
    targetValue = 0f,
    animationSpec = infiniteRepeatable(keyframes {
      durationMillis = LOOP_MILLIS
      0f at 0 using easing
      1f at 700 using easing
      -0.35f at 1200 using easing
      0f at 1800
      0f at LOOP_MILLIS
    }),
    label = "navigationGesture",
  )
  val stationary = when (icon) {
    KetchIcon.Active -> DownloadTray
    KetchIcon.Devices -> DevicesDisplay
    else -> null
  }
  val moving = when (icon) {
    KetchIcon.Active -> DownloadArrow
    KetchIcon.Devices -> DevicesPhone
    else -> icon.imageVector
  }
  Box(modifier) {
    if (stationary != null) {
      Image(
        painter = rememberVectorPainter(stationary),
        contentDescription = null,
        colorFilter = colorFilter,
        modifier = Modifier.matchParentSize(),
      )
    }
    Image(
      painter = rememberVectorPainter(moving),
      contentDescription = null,
      colorFilter = colorFilter,
      modifier = Modifier.matchParentSize().graphicsLayer {
        val amount = movement.value
        val unit = size.width / ICON_VIEWPORT
        when (icon) {
          KetchIcon.Active -> translationY = 1.5f * unit * amount
          KetchIcon.Devices -> {
            transformOrigin = TransformOrigin(15.25f / ICON_VIEWPORT, 16.3f / ICON_VIEWPORT)
            rotationZ = -5f * amount
            translationY = -unit * amount
          }
          KetchIcon.Search -> {
            transformOrigin = TransformOrigin(9f / ICON_VIEWPORT, 9f / ICON_VIEWPORT)
            rotationZ = 8f * amount
            translationX = -0.65f * unit * amount
          }
          else -> Unit
        }
      },
    )
  }
}

private const val LOOP_MILLIS = 7000
