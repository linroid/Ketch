package com.linroid.ketch.app.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.linroid.ketch.app.icons.KetchIcon
import com.linroid.ketch.app.icons.KetchIconImage
import com.linroid.ketch.app.theme.KetchElevationLevel
import com.linroid.ketch.app.theme.KetchTheme
import com.linroid.ketch.app.theme.ketchSurface
import kotlinx.coroutines.launch

/**
 * Mutually exclusive [options] on a sunken track, with a raised thumb that slides to the
 * [selected] one. Used for the status tabs and for two to five short choices such as the theme.
 *
 * @param label text of each option.
 * @param count number after an option's label, such as tasks in a tab; `null` or 0 shows none.
 * @param alert whether an option's count is of failures, drawn white on `dangerFill`.
 * @param icon glyph before an option's label.
 * @param shortcut chord that selects an option, shown in its tooltip.
 * @param fill whether the control takes the full width, sharing it equally.
 * @param description what screen readers say for an option instead of its label, such as for
 *   one that shows only its [icon].
 * @param revealInitialSelection scroll to the initial selection when hosted in a scroller.
 */
@Composable
fun <T> KetchSegmented(
  options: List<T>,
  selected: T,
  onSelect: (T) -> Unit,
  label: @Composable (T) -> String,
  modifier: Modifier = Modifier,
  count: (T) -> Int? = { null },
  alert: (T) -> Boolean = { false },
  icon: (T) -> KetchIcon? = { null },
  shortcut: (T) -> String? = { null },
  fill: Boolean = false,
  description: @Composable (T) -> String? = { null },
  revealInitialSelection: Boolean = false,
) {
  val colors = KetchTheme.colors
  val motion = KetchTheme.motion
  val shape = KetchTheme.shapes.full
  val selectedIndex = options.indexOf(selected)
  val bounds = remember(options.size) { mutableStateListOf(*Array(options.size) { 0 to 0 }) }
  val thumbX = remember { Animatable(0f) }
  val thumbWidth = remember { Animatable(0f) }
  val target = bounds.getOrNull(selectedIndex)?.takeIf { it.second > 0 }
  LaunchedEffect(target) {
    if (target == null) return@LaunchedEffect
    val (x, width) = target
    if (thumbWidth.value == 0f) {
      thumbX.snapTo(x.toFloat())
      thumbWidth.snapTo(width.toFloat())
    } else {
      val spec = tween<Float>(motion.short, easing = motion.easeStandard)
      launch { thumbX.animateTo(x.toFloat(), spec) }
      thumbWidth.animateTo(width.toFloat(), spec)
    }
  }
  val requesters = remember(options.size) { List(options.size) { BringIntoViewRequester() } }
  var shownIndex by remember {
    mutableIntStateOf(if (revealInitialSelection) -1 else selectedIndex)
  }
  LaunchedEffect(selectedIndex, target) {
    // Show the initial selection and selections made elsewhere, such as by a shortcut.
    if (selectedIndex == shownIndex || target == null) return@LaunchedEffect
    shownIndex = selectedIndex
    requesters.getOrNull(selectedIndex)?.bringIntoView()
  }
  val density = LocalDensity.current
  Box(
    modifier = modifier
      .height(KetchTheme.density.chip + TrackGrowth)
      .then(if (fill) Modifier.fillMaxWidth() else Modifier)
      .background(colors.surfaceSunken, shape)
      .padding(TrackPadding),
  ) {
    if (target != null && thumbWidth.value > 0f) {
      Box(
        Modifier
          .offset { IntOffset(thumbX.value.toInt(), 0) }
          .width(with(density) { thumbWidth.value.toDp() })
          .fillMaxHeight()
          .ketchSurface(KetchElevationLevel.E2, shape, colors.surface),
      )
    }
    Row(
      verticalAlignment = Alignment.CenterVertically,
      modifier = Modifier
        .fillMaxHeight()
        .then(if (fill) Modifier.fillMaxWidth() else Modifier)
        .selectableGroup(),
    ) {
      options.forEachIndexed { index, option ->
        val segment = Modifier
          .fillMaxHeight()
          .bringIntoViewRequester(requesters[index])
          .onPlaced {
            val placed = it.positionInParent().x.toInt() to it.size.width
            if (bounds[index] != placed) bounds[index] = placed
          }
        Segment(
          text = label(option),
          selected = index == selectedIndex,
          count = count(option),
          alert = alert(option),
          icon = icon(option),
          shortcut = shortcut(option),
          description = description(option),
          onClick = { onSelect(option) },
          modifier = if (fill) segment.weight(1f) else segment,
        )
      }
    }
  }
}

@Composable
private fun Segment(
  text: String,
  selected: Boolean,
  count: Int?,
  alert: Boolean,
  icon: KetchIcon?,
  shortcut: String?,
  description: String?,
  onClick: () -> Unit,
  modifier: Modifier,
) {
  val colors = KetchTheme.colors
  val spacing = KetchTheme.spacing
  val shape = KetchTheme.shapes.full
  val interactions = remember { MutableInteractionSource() }
  val hovered by interactions.collectIsHoveredAsState()
  val focus = rememberFocusVisibility()
  val ink by animateColorAsState(
    targetValue = if (selected || hovered) colors.textPrimary else colors.textSecondary,
    animationSpec = tween(KetchTheme.motion.micro),
  )
  val body = @Composable { bodyModifier: Modifier ->
    Row(
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(spacing.s1, Alignment.CenterHorizontally),
      modifier = bodyModifier
        .focusRing(focus.visible, shape, colors.focusRing)
        .trackFocusVisibility(focus)
        .selectable(
          selected = selected,
          interactionSource = interactions,
          indication = null,
          role = Role.Tab,
          onClick = onClick,
        )
        .then(
          if (description != null) {
            Modifier.clearAndSetSemantics { contentDescription = description }
          } else {
            Modifier
          },
        )
        .padding(horizontal = spacing.s3),
    ) {
      if (icon != null) KetchIconImage(icon, size = KetchTheme.density.controlGlyph, tint = ink)
      Text(
        text = text,
        style = KetchTheme.typography.label,
        color = ink,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
      )
      if (count != null && count > 0) {
        if (alert) {
          KetchCountBadge(count = count, alert = true)
        } else {
          Text(
            text = count.toString(),
            style = KetchTheme.typography.numeralS,
            color = colors.textTertiary,
          )
        }
      }
    }
  }
  OptionalTooltip(text.takeIf { shortcut != null }, modifier, shortcut, body)
}

private val TrackGrowth = 4.dp
private val TrackPadding = 3.dp
