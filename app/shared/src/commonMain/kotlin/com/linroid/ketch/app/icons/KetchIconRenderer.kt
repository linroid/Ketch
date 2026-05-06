package com.linroid.ketch.app.icons

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.linroid.ketch.app.theme.KetchTheme

/**
 * Renders a [KetchIcon] at [size] in [tint].
 *
 * Uses Compose's built-in [PathParser] so the JS mock's path strings are
 * reusable verbatim. Stroke width scales with the requested size to preserve
 * the design's optical weight (1.7px at the 20×20 author viewport).
 */
@Composable
fun KetchIconImage(
  icon: KetchIcon,
  size: Dp = 20.dp,
  tint: Color = KetchTheme.colors.onBackground,
) {
  val data = icon.data
  Canvas(modifier = Modifier.size(size)) {
    val s = this.size.width / 20f
    // Author stroke is 1.7px in the 20-unit viewport; we draw post-scale so
    // dividing by `s` keeps the effective stroke weight constant on screen.
    scale(scaleX = s, scaleY = s, pivot = Offset.Zero) {
      data.strokes.forEach { d ->
        val path = PathParser().parsePathString(d).toPath()
        drawPath(
          path = path,
          color = tint,
          style = Stroke(
            width = 1.7f,
            cap = StrokeCap.Round,
            join = StrokeJoin.Round,
          ),
        )
      }
      data.fills.forEach { d ->
        val path = PathParser().parsePathString(d).toPath()
        drawPath(path = path, color = tint)
      }
    }
  }
}
