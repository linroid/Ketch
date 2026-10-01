package com.linroid.ketch.app.components

import androidx.compose.foundation.background
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
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.linroid.ketch.app.icons.KetchIcon
import com.linroid.ketch.app.icons.KetchIconImage
import com.linroid.ketch.app.theme.KetchTheme

/** Sizes of a [KetchLogoTile]. */
object KetchLogoTileDefaults {
  /** Next to "Ketch" in the web sidebar header. */
  val Sidebar: Dp = 20.dp

  /** Onboarding. */
  val Onboarding: Dp = 64.dp

  /** The About page. */
  val About: Dp = 96.dp
}

/**
 * The app mark: the white segmented sail on the ember gradient, as on the app icon.
 *
 * @param size one of the [KetchLogoTileDefaults] sizes; the corners round at 28% of it.
 * @param contentDescription what a screen reader says, or `null` when text next to it already
 *   names Ketch.
 */
@Composable
fun KetchLogoTile(
  modifier: Modifier = Modifier,
  size: Dp = KetchLogoTileDefaults.Onboarding,
  contentDescription: String? = null,
) {
  val ember = KetchTheme.colors.brandEmber
  val shape = remember(size) { RoundedCornerShape(size * CORNER_SHARE) }
  val gradient = remember(ember) { Brush.linearGradient(ember) }
  Box(
    contentAlignment = Alignment.Center,
    modifier = modifier
      .size(size)
      .clip(shape)
      .background(gradient)
      .then(
        if (contentDescription == null) {
          Modifier
        } else {
          Modifier.semantics {
            this.contentDescription = contentDescription
            role = Role.Image
          }
        }
      ),
  ) {
    KetchIconImage(KetchIcon.Sail, size = size * GLYPH_SHARE, tint = Color.White)
  }
}

private const val CORNER_SHARE = 0.28f
private const val GLYPH_SHARE = 0.72f
