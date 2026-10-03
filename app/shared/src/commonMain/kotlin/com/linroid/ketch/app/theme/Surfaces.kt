package com.linroid.ketch.app.theme

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.dropShadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp

/**
 * Draws a surface: the shadows of [level], then [fill] clipped to [shape], then a 1 dp
 * [border] over the content. In the dark theme, raised surfaces also get a faint white
 * highlight along their top edge.
 *
 * This is the only way feature code builds a surface; rows are never surfaces.
 */
@Composable
fun Modifier.ketchSurface(
  level: KetchElevationLevel,
  shape: Shape,
  fill: Color,
  border: Color? = null,
): Modifier {
  val elevation = KetchTheme.elevation
  val highlightFade = with(LocalDensity.current) { TopHighlightFade.toPx() }
  var modifier = this
  for (shadow in elevation.shadows(level)) modifier = modifier.dropShadow(shape, shadow)
  modifier = modifier.clip(shape).background(fill, shape)
  if (border != null) modifier = modifier.border(1.dp, border, shape)
  if (elevation.isDark && level != KetchElevationLevel.E0) {
    val highlight = Brush.verticalGradient(
      colors = listOf(TopHighlight, Color.Transparent),
      endY = highlightFade,
    )
    modifier = modifier.border(1.dp, highlight, shape)
  }
  return modifier
}

private val TopHighlight = Color.White.copy(alpha = 0.06f)
private val TopHighlightFade = 16.dp
