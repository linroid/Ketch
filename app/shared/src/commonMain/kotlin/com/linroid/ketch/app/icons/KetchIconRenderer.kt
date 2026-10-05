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
 * Downloads, Discover, Devices and Search move briefly between rests. Set [animate] to false
 * for a still glyph; the theme's reduced-motion setting always disables animation.
 */
@Composable
fun KetchIconImage(
  icon: KetchIcon,
  modifier: Modifier = Modifier,
  size: Dp = 20.dp,
  tint: Color = KetchTheme.colors.textPrimary,
  animate: Boolean = true,
) {
  val colorFilter = remember(tint) { ColorFilter.tint(tint) }
  if (animate && !KetchTheme.reduceMotion) {
    when (icon) {
      KetchIcon.Discover -> {
        AnimatedDiscoverIcon(modifier.size(size), colorFilter)
        return
      }
      KetchIcon.Active, KetchIcon.Devices, KetchIcon.Search -> {
        AnimatedNavigationIcon(icon, modifier.size(size), colorFilter)
        return
      }
      else -> Unit
    }
  }
  Image(
    painter = rememberVectorPainter(icon.imageVector),
    contentDescription = null,
    modifier = modifier.size(size),
    colorFilter = colorFilter,
  )
}
