package com.linroid.ketch.app.components

import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.PlainTooltip
import androidx.compose.material3.TooltipBox
import androidx.compose.material3.TooltipDefaults
import androidx.compose.material3.rememberTooltipState
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
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
  val shape = RoundedCornerShape(10.dp)
  val interactions = remember { MutableInteractionSource() }
  val hovered by interactions.collectIsHoveredAsState()
  val focused by interactions.collectIsFocusedAsState()

  data class Style(val bg: Color, val fg: Color, val border: Color?)
  val style = when (variant) {
    KetchButtonVariant.Primary   -> Style(colors.primary, if (colors.isDark) colors.background else Color.White, null)
    KetchButtonVariant.Secondary -> Style(Color.Transparent, colors.onBackground, colors.outline)
    KetchButtonVariant.Ghost     -> Style(Color.Transparent, colors.onBackground, null)
    KetchButtonVariant.Danger    -> Style(colors.error, Color.White, null)
  }

  val (minH, padH, iconSize) = when (size) {
    KetchButtonSize.Small  -> Triple(32.dp, 12.dp, 14.dp)
    KetchButtonSize.Medium -> Triple(40.dp, 16.dp, 16.dp)
    KetchButtonSize.Large  -> Triple(44.dp, 20.dp, 18.dp)
  }

  Row(
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(7.dp, Alignment.CenterHorizontally),
    modifier = modifier
      .defaultMinSize(minHeight = minH)
      .clip(shape)
      .background(if (!enabled) colors.surfaceHover else if (hovered && variant == KetchButtonVariant.Ghost) colors.surfaceHover else style.bg)
      .let { if (style.border != null) it.border(1.dp, style.border, shape) else it }
      .let { if (focused) it.border(2.dp, colors.primary, shape) else it }
      .hoverable(interactions)
      .clickable(interactionSource = interactions, indication = LocalIndication.current, enabled = enabled, role = Role.Button, onClick = onClick)
      .padding(horizontal = padH, vertical = 0.dp),
  ) {
    if (leadingIcon != null) {
      KetchIconImage(leadingIcon, size = iconSize, tint = if (enabled) style.fg else colors.onSurfaceDim)
    }
    Text(text = text, color = if (enabled) style.fg else colors.onSurfaceDim, style = KetchTheme.typography.labelLarge)
  }
}

/** Icon-only button with a tooltip and visible hover / keyboard focus. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun KetchIconButton(
  icon: KetchIcon,
  onClick: () -> Unit,
  modifier: Modifier = Modifier,
  size: KetchButtonSize = KetchButtonSize.Medium,
  enabled: Boolean = true,
  tint: Color = KetchTheme.colors.onSurfaceVariant,
  contentDescription: String = icon.name,
) {
  val interactions = remember { MutableInteractionSource() }
  val hovered by interactions.collectIsHoveredAsState()
  val focused by interactions.collectIsFocusedAsState()
  val colors = KetchTheme.colors
  val shape = RoundedCornerShape(10.dp)
  val side = when (size) {
    KetchButtonSize.Small  -> 40.dp
    KetchButtonSize.Medium -> 44.dp
    KetchButtonSize.Large  -> 48.dp
  }
  TooltipBox(
    positionProvider = TooltipDefaults.rememberPlainTooltipPositionProvider(),
    tooltip = { PlainTooltip { Text(contentDescription) } },
    state = rememberTooltipState(),
  ) {
  Row(
    horizontalArrangement = Arrangement.Center,
    verticalAlignment = Alignment.CenterVertically,
    modifier = modifier
      .semantics { this.contentDescription = contentDescription }
      .size(side)
      .clip(shape)
      .background(if (hovered || focused) colors.surfaceHover else Color.Transparent)
      .let { if (focused) it.border(2.dp, colors.primary, shape) else it }
      .hoverable(interactions)
      .clickable(interactionSource = interactions, indication = LocalIndication.current, enabled = enabled, role = Role.Button, onClick = onClick),
  ) {
    KetchIconImage(icon = icon, size = 20.dp, tint = if (enabled) tint else tint.copy(alpha = 0.38f))
  }
}
}
