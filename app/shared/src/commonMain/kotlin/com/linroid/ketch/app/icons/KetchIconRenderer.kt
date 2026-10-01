package com.linroid.ketch.app.icons

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.linroid.ketch.app.theme.KetchTheme

/**
 * Renders a [KetchIcon] at [size] in [tint].
 *
 * The icon's cached vector is drawn through a vector painter, so path data is parsed once per
 * icon rather than on every draw. Strokes scale with [size], keeping the design's optical
 * weight (1.7px at the 20×20 author viewport).
 */
@Composable
fun KetchIconImage(
  icon: KetchIcon,
  modifier: Modifier = Modifier,
  size: Dp = 20.dp,
  tint: Color = KetchTheme.colors.onBackground,
) {
  val colorFilter = remember(tint) { ColorFilter.tint(tint) }
  Image(
    painter = rememberVectorPainter(icon.imageVector),
    contentDescription = null,
    modifier = modifier.size(size),
    colorFilter = colorFilter,
  )
}
