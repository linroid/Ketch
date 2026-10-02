package com.linroid.ketch.app.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.linroid.ketch.app.icons.KetchIcon
import com.linroid.ketch.app.icons.KetchIconImage
import com.linroid.ketch.app.theme.FileTypeHue
import com.linroid.ketch.app.theme.KetchTheme

/** Sizes of a [KetchHueTile]. */
object KetchHueTileDefaults {
  /** Items in a sidebar, such as the list of Settings pages beside the open one. */
  val XSmall: Dp = 24.dp

  /** Rows of a full-width list, such as the phone's list of Settings pages. */
  val Small: Dp = 32.dp

  /** Device types and setting group headers. */
  val Medium: Dp = 40.dp

  /** Discover results and empty states. */
  val Large: Dp = 48.dp
}

/**
 * A glyph on a soft tile of [hue], for settings categories, device types and Discover results.
 * The tile fades from 18% to 6% of the hue toward its bottom-end corner, and in the light theme
 * catches a thin white highlight along its top edge.
 *
 * @param size one of the [KetchHueTileDefaults] sizes; the corners round at 28% of it, and the
 *   glyph takes half of it, but never less than 14 dp, so it stays legible on small tiles.
 */
@Composable
fun KetchHueTile(
  icon: KetchIcon,
  hue: FileTypeHue,
  modifier: Modifier = Modifier,
  size: Dp = KetchHueTileDefaults.Medium,
) {
  val dark = KetchTheme.colors.isDark
  val tint = if (dark) hue.dark else hue.light
  val shape = rememberTileShape(size)
  val fill = remember(tint) {
    Brush.linearGradient(listOf(tint.copy(alpha = TOP_ALPHA), tint.copy(alpha = BOTTOM_ALPHA)))
  }
  val highlightHeight = with(LocalDensity.current) { (size * HIGHLIGHT_SHARE).toPx() }
  val highlight = remember(highlightHeight) {
    Brush.verticalGradient(listOf(TopHighlight, Color.Transparent), endY = highlightHeight)
  }
  Box(
    contentAlignment = Alignment.Center,
    modifier = modifier
      .size(size)
      .clip(shape)
      .background(fill)
      .then(if (dark) Modifier else Modifier.border(1.dp, highlight, shape)),
  ) {
    KetchIconImage(icon, size = (size * GLYPH_SHARE).coerceAtLeast(MinGlyph), tint = tint)
  }
}

/** The rounded square of a tile [size] wide, whose corners round at 28% of it. */
@Composable
internal fun rememberTileShape(size: Dp): RoundedCornerShape =
  remember(size) { RoundedCornerShape(size * 0.28f) }

private const val GLYPH_SHARE = 0.5f

/** Smallest glyph a tile draws, which its strokes stay legible at. */
private val MinGlyph = 14.dp
private const val TOP_ALPHA = 0.18f
private const val BOTTOM_ALPHA = 0.06f

/** How far down the top highlight fades out, as a share of the tile. */
private const val HIGHLIGHT_SHARE = 0.4f
private val TopHighlight = Color.White.copy(alpha = 0.4f)
