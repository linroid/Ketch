package com.linroid.ketch.app.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.selection.triStateToggleable
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.linroid.ketch.app.icons.KetchIcon
import com.linroid.ketch.app.icons.KetchIconImage
import com.linroid.ketch.app.theme.KetchDensity
import com.linroid.ketch.app.theme.KetchTheme

/**
 * A checkbox, with its [label] to the right when given. The whole row toggles it.
 *
 * @param onCheckedChange `null` makes it read-only, for a parent row that toggles it.
 */
@Composable
fun KetchCheckbox(
  checked: Boolean,
  onCheckedChange: ((Boolean) -> Unit)?,
  modifier: Modifier = Modifier,
  enabled: Boolean = true,
  label: String? = null,
) {
  val interactions = remember { MutableInteractionSource() }
  val focus = rememberFocusVisibility()
  val toggle = if (onCheckedChange != null) {
    Modifier
      .trackFocusVisibility(focus)
      .toggleable(
        value = checked,
        interactionSource = interactions,
        indication = null,
        enabled = enabled,
        role = Role.Checkbox,
        onValueChange = onCheckedChange,
      )
  } else {
    Modifier
  }
  ToggleRow(label, enabled, focus, toggle, modifier) {
    CheckBox(ToggleableState(checked), interactions, enabled, focus.visible && label == null)
  }
}

/**
 * A checkbox that can also be partly checked, such as "select all" over a partial selection.
 *
 * @param onClick `null` makes it read-only.
 */
@Composable
fun KetchTriStateCheckbox(
  state: ToggleableState,
  onClick: (() -> Unit)?,
  modifier: Modifier = Modifier,
  enabled: Boolean = true,
  label: String? = null,
) {
  val interactions = remember { MutableInteractionSource() }
  val focus = rememberFocusVisibility()
  val toggle = if (onClick != null) {
    Modifier
      .trackFocusVisibility(focus)
      .triStateToggleable(
        state = state,
        interactionSource = interactions,
        indication = null,
        enabled = enabled,
        role = Role.Checkbox,
        onClick = onClick,
      )
  } else {
    Modifier
  }
  ToggleRow(label, enabled, focus, toggle, modifier) {
    CheckBox(state, interactions, enabled, focus.visible && label == null)
  }
}

/**
 * An on/off switch, with its [label] to the left when given, as in settings rows.
 *
 * @param onCheckedChange `null` makes it read-only.
 */
@Composable
fun KetchSwitch(
  checked: Boolean,
  onCheckedChange: ((Boolean) -> Unit)?,
  modifier: Modifier = Modifier,
  enabled: Boolean = true,
  label: String? = null,
) {
  val colors = KetchTheme.colors
  val density = KetchTheme.density
  val motion = KetchTheme.motion
  val interactions = remember { MutableInteractionSource() }
  val hovered by interactions.collectIsHoveredAsState()
  val focus = rememberFocusVisibility()
  val track = density.switchTrack
  val thumb = track.height - SwitchInset * 2
  val thumbOffset by animateDpAsState(
    targetValue = if (checked) track.width - thumb - SwitchInset * 2 else 0.dp,
    animationSpec = tween(motion.short, easing = motion.easeStandard),
  )
  val fill by animateColorAsState(
    targetValue = when {
      checked && hovered && enabled -> colors.accentHover
      checked -> colors.accent
      else -> colors.borderStrong
    },
    animationSpec = tween(motion.short),
  )
  val toggle = if (onCheckedChange != null) {
    Modifier
      .trackFocusVisibility(focus)
      .toggleable(
        value = checked,
        interactionSource = interactions,
        indication = null,
        enabled = enabled,
        role = Role.Switch,
        onValueChange = onCheckedChange,
      )
  } else {
    Modifier
  }
  val shape = KetchTheme.shapes.full
  Row(
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(KetchTheme.spacing.s3),
    modifier = modifier
      .graphicsLayer { alpha = if (enabled) 1f else DISABLED_ALPHA }
      .heightIn(min = density.iconButtonTarget)
      .then(toggle),
  ) {
    if (label != null) {
      Text(
        text = label,
        style = KetchTheme.typography.body,
        color = colors.textPrimary,
        modifier = Modifier.weight(1f, fill = false),
      )
    }
    Box(
      contentAlignment = Alignment.CenterStart,
      modifier = Modifier
        .focusRing(focus.visible, shape, colors.focusRing)
        .size(track.width, track.height)
        .background(fill, shape)
        .padding(SwitchInset),
    ) {
      Box(
        Modifier
          .offset(x = thumbOffset)
          .size(thumb)
          .clip(shape)
          .background(Color.White),
      )
    }
  }
}

@Composable
private fun ToggleRow(
  label: String?,
  enabled: Boolean,
  focus: FocusVisibility,
  toggle: Modifier,
  modifier: Modifier,
  box: @Composable () -> Unit,
) {
  val spacing = KetchTheme.spacing
  val density = KetchTheme.density
  if (label == null) {
    Box(
      contentAlignment = Alignment.Center,
      modifier = modifier
        .graphicsLayer { alpha = if (enabled) 1f else DISABLED_ALPHA }
        .size(density.iconButtonTarget)
        .then(toggle),
    ) { box() }
    return
  }
  val shape = KetchTheme.shapes.xs
  Row(
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(spacing.s2),
    modifier = modifier
      .focusRing(focus.visible, shape, KetchTheme.colors.focusRing)
      .graphicsLayer { alpha = if (enabled) 1f else DISABLED_ALPHA }
      .heightIn(min = density.chip)
      .then(toggle),
  ) {
    box()
    Text(label, style = KetchTheme.typography.body, color = KetchTheme.colors.textPrimary)
  }
}

@Composable
private fun CheckBox(
  state: ToggleableState,
  interactions: MutableInteractionSource,
  enabled: Boolean,
  ring: Boolean,
) {
  val colors = KetchTheme.colors
  val shape = KetchTheme.shapes.xs
  val hovered by interactions.collectIsHoveredAsState()
  val on = state != ToggleableState.Off
  val fill by animateColorAsState(
    targetValue = when {
      on && hovered && enabled -> colors.accentHover
      on -> colors.accent
      else -> colors.surface
    },
    animationSpec = tween(KetchTheme.motion.micro),
  )
  val border = when {
    on -> Color.Transparent
    hovered && enabled -> colors.textTertiary
    else -> colors.borderStrong
  }
  val side = KetchTheme.density.checkbox
  Box(
    contentAlignment = Alignment.Center,
    modifier = Modifier
      .focusRing(ring, shape, colors.focusRing)
      .size(side)
      .background(fill, shape)
      .border(1.dp, border, shape),
  ) {
    when (state) {
      ToggleableState.On -> {
        KetchIconImage(KetchIcon.Check, size = side - CheckInset, tint = colors.onAccent)
      }
      ToggleableState.Indeterminate -> {
        Box(Modifier.size(side / 2, 2.dp).background(colors.onAccent, KetchTheme.shapes.full))
      }
      ToggleableState.Off -> Unit
    }
  }
}

private class TrackSize(val width: Dp, val height: Dp)

private val KetchDensity.switchTrack: TrackSize
  get() = if (this == KetchDensity.Comfortable) TrackSize(40.dp, 24.dp) else TrackSize(32.dp, 18.dp)

private val KetchDensity.checkbox: Dp
  get() = if (this == KetchDensity.Comfortable) 20.dp else 16.dp

private val SwitchInset = 2.dp
private val CheckInset = 4.dp
