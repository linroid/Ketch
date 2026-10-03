package com.linroid.ketch.app.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.linroid.ketch.app.i18n.resolve
import com.linroid.ketch.app.icons.KetchIcon
import com.linroid.ketch.app.icons.KetchIconImage
import com.linroid.ketch.app.input.KetchCommand
import com.linroid.ketch.app.theme.KetchColors
import com.linroid.ketch.app.theme.KetchDensity
import com.linroid.ketch.app.theme.KetchTheme

/** Visual weight of a [KetchButton]. */
enum class KetchButtonVariant {
  /** Accent fill for the one main action of a surface. */
  Primary,

  /** Surface fill with a strong border, for actions next to a Primary one. */
  Secondary,

  /** Soft accent fill, for emphasis that does not compete with Primary. */
  Tonal,

  /** No fill until hovered, for toolbars and dense rows. */
  Ghost,

  /** Danger fill with white text, for every destructive confirmation. */
  Danger,
}

/** Height of a [KetchButton]; the actual values come from the current [KetchDensity]. */
enum class KetchButtonSize {
  /** 28 dp with a pointer, 36 dp on touch. */
  Small,

  /** 32 dp with a pointer, 44 dp on touch. The default. */
  Medium,

  /** 36 dp with a pointer, 48 dp on touch. */
  Large,
}

/**
 * A pill-shaped button.
 *
 * Hovering darkens it by 8% and pressing by 12% while it shrinks slightly; keyboard focus draws
 * an accent ring; a disabled button fades to 40%. Keep to one [KetchButtonVariant.Primary] per
 * surface.
 *
 * @param loading shows a spinner in place of [leadingIcon], keeping the width, and ignores
 *   clicks.
 * @param tooltip text shown after hovering; [text] is used when only [shortcut] is given.
 * @param shortcut chord of the same action, such as "⌘N", shown in the tooltip.
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
  loading: Boolean = false,
  tooltip: String? = null,
  shortcut: String? = null,
) {
  if (tooltip != null || shortcut != null) {
    KetchTooltip(text = tooltip ?: text, shortcut = shortcut, modifier = modifier) {
      ButtonBody(text, onClick, Modifier, variant, size, leadingIcon, enabled, loading)
    }
  } else {
    ButtonBody(text, onClick, modifier, variant, size, leadingIcon, enabled, loading)
  }
}

@Composable
private fun ButtonBody(
  text: String,
  onClick: () -> Unit,
  modifier: Modifier,
  variant: KetchButtonVariant,
  size: KetchButtonSize,
  leadingIcon: KetchIcon?,
  enabled: Boolean,
  loading: Boolean,
) {
  val colors = KetchTheme.colors
  val density = KetchTheme.density
  val shape = KetchTheme.shapes.button
  val style = buttonStyle(variant, colors)
  val interactions = remember { MutableInteractionSource() }
  val active = enabled && !loading
  val overlay = rememberInteractionOverlay(interactions, active)
  val scale = rememberPressScale(interactions)
  val focus = rememberFocusVisibility()
  val glyph = density.controlGlyph
  Box(
    contentAlignment = Alignment.Center,
    modifier = modifier
      .focusRing(focus.visible, shape, colors.focusRing)
      .graphicsLayer {
        scaleX = scale
        scaleY = scale
        alpha = if (enabled) 1f else DISABLED_ALPHA
      }
      .heightIn(min = size.height(density))
      .clip(shape)
      .background(style.fill)
      .background(overlay)
      .then(if (style.border != null) Modifier.border(1.dp, style.border, shape) else Modifier)
      .trackFocusVisibility(focus)
      .clickable(
        interactionSource = interactions,
        indication = null,
        enabled = active,
        role = Role.Button,
        onClick = onClick,
      )
      .padding(horizontal = size.padding(density)),
  ) {
    Row(
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(KetchTheme.spacing.iconLabelGap),
    ) {
      if (leadingIcon != null) {
        Box(Modifier.size(glyph), contentAlignment = Alignment.Center) {
          if (loading) {
            KetchSpinner(size = LoadingSpinnerSize, color = style.ink)
          } else {
            KetchIconImage(leadingIcon, size = glyph, tint = style.ink)
          }
        }
      }
      Text(
        text = text,
        style = KetchTheme.typography.label,
        color = style.ink,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.alpha(if (loading && leadingIcon == null) 0f else 1f),
      )
    }
    if (loading && leadingIcon == null) {
      KetchSpinner(size = LoadingSpinnerSize, color = style.ink)
    }
  }
}

/**
 * Icon-only button. Its tooltip names the action and its [shortcut], so screen readers and
 * pointer users both learn what it does.
 *
 * @param size [KetchButtonSize.Medium] is the density's icon button (28 dp in a 32 dp hit
 *   area with a pointer, 40 dp in a 48 dp target on touch); Small and Large step down or up.
 * @param contentDescription what the button does; also the tooltip.
 * @param shortcut chord of the same action, shown in the tooltip.
 * @param selected whether the button is toggled on, which tints it with the accent.
 */
@Composable
fun KetchIconButton(
  icon: KetchIcon,
  onClick: () -> Unit,
  modifier: Modifier = Modifier,
  size: KetchButtonSize = KetchButtonSize.Medium,
  enabled: Boolean = true,
  tint: Color = KetchTheme.colors.textSecondary,
  contentDescription: String = icon.name,
  shortcut: String? = null,
  selected: Boolean = false,
) {
  val colors = KetchTheme.colors
  val density = KetchTheme.density
  val shape = KetchTheme.shapes.sm
  val interactions = remember { MutableInteractionSource() }
  val overlay = rememberInteractionOverlay(interactions, enabled)
  val scale = rememberPressScale(interactions)
  val focus = rememberFocusVisibility()
  val visual = size.iconButton(density)
  KetchTooltip(text = contentDescription, shortcut = shortcut, modifier = modifier) {
    Box(
      contentAlignment = Alignment.Center,
      modifier = Modifier
        .size(maxOf(visual, density.iconButtonTarget))
        .semantics {
          this.contentDescription = contentDescription
          this.selected = selected
        }
        .trackFocusVisibility(focus)
        .clickable(
          interactionSource = interactions,
          indication = null,
          enabled = enabled,
          role = Role.Button,
          onClick = onClick,
        ),
    ) {
      Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
          .focusRing(focus.visible, shape, colors.focusRing)
          .graphicsLayer {
            scaleX = scale
            scaleY = scale
            alpha = if (enabled) 1f else DISABLED_ALPHA
          }
          .size(visual)
          .background(if (selected) colors.accentSoft else Color.Transparent, shape)
          .background(overlay, shape),
      ) {
        KetchIconImage(
          icon = icon,
          size = if (size == KetchButtonSize.Large) density.navGlyph else density.controlGlyph,
          tint = if (selected) colors.accentText else tint,
        )
      }
    }
  }
}

/** [KetchIconButton] for [command]: its icon, label and shortcut. */
@Composable
fun KetchIconButton(
  command: KetchCommand,
  onClick: () -> Unit,
  modifier: Modifier = Modifier,
  icon: KetchIcon = checkNotNull(command.icon) { "${command.id} has no icon" },
  size: KetchButtonSize = KetchButtonSize.Medium,
  enabled: Boolean = true,
  tint: Color = KetchTheme.colors.textSecondary,
  selected: Boolean = false,
) {
  KetchIconButton(
    icon = icon,
    onClick = onClick,
    modifier = modifier,
    size = size,
    enabled = enabled,
    tint = tint,
    contentDescription = command.label.resolve(),
    shortcut = command.shortcutLabel(),
    selected = selected,
  )
}

private class ButtonStyle(val fill: Color, val ink: Color, val border: Color?)

private fun buttonStyle(variant: KetchButtonVariant, colors: KetchColors): ButtonStyle =
  when (variant) {
    KetchButtonVariant.Primary -> ButtonStyle(colors.accent, colors.onAccent, null)
    KetchButtonVariant.Secondary -> {
      ButtonStyle(colors.surface, colors.textPrimary, colors.borderStrong)
    }
    KetchButtonVariant.Tonal -> ButtonStyle(colors.accentSoft, colors.accentText, null)
    KetchButtonVariant.Ghost -> ButtonStyle(Color.Transparent, colors.textPrimary, null)
    KetchButtonVariant.Danger -> ButtonStyle(colors.dangerFill, Color.White, null)
  }

private fun KetchButtonSize.height(density: KetchDensity): Dp = when (this) {
  KetchButtonSize.Small -> density.buttonSmall
  KetchButtonSize.Medium -> density.buttonMedium
  KetchButtonSize.Large -> density.buttonLarge
}

private fun KetchButtonSize.padding(density: KetchDensity): Dp {
  val compact = when (this) {
    KetchButtonSize.Small -> 12.dp
    KetchButtonSize.Medium -> 14.dp
    KetchButtonSize.Large -> 16.dp
  }
  return if (density == KetchDensity.Comfortable) compact + 4.dp else compact
}

private fun KetchButtonSize.iconButton(density: KetchDensity): Dp = when (this) {
  KetchButtonSize.Small -> density.iconButton - 4.dp
  KetchButtonSize.Medium -> density.iconButton
  KetchButtonSize.Large -> density.buttonLarge
}

private val LoadingSpinnerSize = 14.dp
