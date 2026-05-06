package com.linroid.ketch.app.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.linroid.ketch.app.icons.KetchIcon
import com.linroid.ketch.app.icons.KetchIconImage
import com.linroid.ketch.app.theme.KetchTheme

enum class KetchButtonVariant { Primary, Secondary, Ghost, Danger }
enum class KetchButtonSize { Small, Medium, Large }

/**
 * Ketch button.
 *
 *  - [Primary]   — filled accent, for the main action on a surface.
 *  - [Secondary] — outlined, for paired actions (Cancel next to Save).
 *  - [Ghost]     — no border, no fill; for toolbar rows.
 *  - [Danger]    — filled error color.
 *
 * Sizes:
 *  - [Small]  32dp tall, 12dp h-padding, 14dp icon.
 *  - [Medium] 36dp tall, 16dp h-padding, 16dp icon. Default.
 *  - [Large]  40dp tall, 20dp h-padding, 18dp icon.
 */
@Composable
fun KetchButton(
  text: String,
  onClick: () -> Unit,
  modifier: Modifier = Modifier,
  variant: KetchButtonVariant = KetchButtonVariant.Primary,
  size: KetchButtonSize = KetchButtonSize.Medium,
  leadingIcon: KetchIcon? = null,
  enabled: Boolean = true,
) {
  val colors = KetchTheme.colors
  val shape = RoundedCornerShape(8.dp)

  data class Style(val bg: Color, val fg: Color, val border: Color?)
  val style = when (variant) {
    KetchButtonVariant.Primary   -> Style(colors.primary, Color.White, null)
    KetchButtonVariant.Secondary -> Style(Color.Transparent, colors.onBackground, colors.outline)
    KetchButtonVariant.Ghost     -> Style(Color.Transparent, colors.onBackground, null)
    KetchButtonVariant.Danger    -> Style(colors.error, Color.White, null)
  }

  val (minH, padH, iconSize) = when (size) {
    KetchButtonSize.Small  -> Triple(32.dp, 12.dp, 14.dp)
    KetchButtonSize.Medium -> Triple(36.dp, 16.dp, 16.dp)
    KetchButtonSize.Large  -> Triple(40.dp, 20.dp, 18.dp)
  }

  Row(
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(7.dp, Alignment.CenterHorizontally),
    modifier = modifier
      .defaultMinSize(minHeight = minH)
      .clip(shape)
      .background(if (enabled) style.bg else style.bg.copy(alpha = 0.4f))
      .let { if (style.border != null) it.border(1.dp, style.border, shape) else it }
      .clickable(enabled = enabled, onClick = onClick)
      .padding(horizontal = padH, vertical = 0.dp),
  ) {
    if (leadingIcon != null) {
      KetchIconImage(leadingIcon, size = iconSize, tint = style.fg)
    }
    Text(text = text, color = style.fg, style = KetchTheme.typography.labelLarge)
  }
}

/** Square icon-only button — toolbar-style, transparent until hover. */
@Composable
fun KetchIconButton(
  icon: KetchIcon,
  onClick: () -> Unit,
  modifier: Modifier = Modifier,
  size: KetchButtonSize = KetchButtonSize.Medium,
  enabled: Boolean = true,
  tint: Color = KetchTheme.colors.onSurfaceVariant,
) {
  val side = when (size) {
    KetchButtonSize.Small  -> 28.dp
    KetchButtonSize.Medium -> 32.dp
    KetchButtonSize.Large  -> 36.dp
  }
  Row(
    horizontalArrangement = Arrangement.Center,
    verticalAlignment = Alignment.CenterVertically,
    modifier = modifier
      .size(side)
      .clip(RoundedCornerShape(7.dp))
      .clickable(enabled = enabled, onClick = onClick),
  ) {
    KetchIconImage(icon = icon, size = side - 12.dp, tint = tint)
  }
}
