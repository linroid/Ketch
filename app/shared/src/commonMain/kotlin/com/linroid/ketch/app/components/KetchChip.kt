package com.linroid.ketch.app.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.linroid.ketch.app.icons.KetchIcon
import com.linroid.ketch.app.icons.KetchIconImage
import com.linroid.ketch.app.theme.KetchTheme
import ketch.app.shared.generated.resources.Res
import ketch.app.shared.generated.resources.component_chip_remove
import org.jetbrains.compose.resources.stringResource

/**
 * Pill-shaped choice, filter or token.
 *
 * A [selected] chip turns soft accent with a check in front. Chips with [onRemove] carry a ✕
 * that removes them, as search tokens do.
 *
 * @param count number shown after the label, such as matching downloads.
 * @param leadingIcon glyph before the label; the check replaces it while selected.
 * @param trailingIcon glyph after the label, such as a chevron on a chip that opens a menu.
 * @param onRemove shows the ✕ and runs when it is clicked.
 */
@Composable
fun KetchChip(
  label: String,
  selected: Boolean,
  onClick: () -> Unit,
  modifier: Modifier = Modifier,
  enabled: Boolean = true,
  count: Int? = null,
  leadingIcon: KetchIcon? = null,
  trailingIcon: KetchIcon? = null,
  onRemove: (() -> Unit)? = null,
) {
  val colors = KetchTheme.colors
  val density = KetchTheme.density
  val spacing = KetchTheme.spacing
  val shape = KetchTheme.shapes.full
  val interactions = remember { MutableInteractionSource() }
  val overlay = rememberInteractionOverlay(interactions, enabled)
  val focus = rememberFocusVisibility()
  val ink = if (selected) colors.accentText else colors.textPrimary
  val glyph = if (selected) KetchIcon.Check else leadingIcon
  Row(
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(spacing.s1),
    modifier = modifier
      .focusRing(focus.visible, shape, colors.focusRing)
      .graphicsLayer { alpha = if (enabled) 1f else DISABLED_ALPHA }
      .height(density.chip)
      .clip(shape)
      .background(if (selected) colors.accentSoft else colors.surface)
      .background(overlay)
      .border(1.dp, if (selected) colors.accentSoft else colors.borderStrong, shape)
      .trackFocusVisibility(focus)
      .toggleable(
        value = selected,
        interactionSource = interactions,
        indication = null,
        enabled = enabled,
        role = Role.Checkbox,
        onValueChange = { onClick() },
      )
      .padding(start = spacing.s3, end = if (onRemove != null) spacing.s1 else spacing.s3),
  ) {
    if (glyph != null) {
      KetchIconImage(
        icon = glyph,
        size = if (selected) CheckSize else density.controlGlyph,
        tint = if (selected) colors.accentText else colors.textSecondary,
      )
    }
    Text(
      text = label,
      style = KetchTheme.typography.labelS,
      color = ink,
      maxLines = 1,
      overflow = TextOverflow.Ellipsis,
    )
    if (count != null) {
      Text(
        text = count.toString(),
        style = KetchTheme.typography.numeralS,
        color = if (selected) colors.accentText else colors.textTertiary,
      )
    }
    if (trailingIcon != null) {
      KetchIconImage(trailingIcon, size = CheckSize, tint = colors.textSecondary)
    }
    if (onRemove != null) RemoveButton(label, onRemove, enabled)
  }
}

@Composable
private fun RemoveButton(label: String, onRemove: () -> Unit, enabled: Boolean) {
  val colors = KetchTheme.colors
  val interactions = remember { MutableInteractionSource() }
  val overlay = rememberInteractionOverlay(interactions, enabled)
  val description = stringResource(Res.string.component_chip_remove, label)
  Box(
    contentAlignment = Alignment.Center,
    modifier = Modifier
      .size(RemoveTarget)
      .clip(KetchTheme.shapes.full)
      .background(overlay)
      .semantics { contentDescription = description }
      .ketchClickable(interactions, enabled = enabled, onClick = onRemove),
  ) {
    KetchIconImage(KetchIcon.Close, size = RemoveGlyph, tint = colors.textSecondary)
  }
}

private val CheckSize = 12.dp
private val RemoveGlyph = 14.dp
private val RemoveTarget = 20.dp
