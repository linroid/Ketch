package com.linroid.ketch.app.ui.inspector

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.Placeable
import androidx.compose.ui.layout.SubcomposeLayout
import androidx.compose.ui.layout.layout
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.offset
import com.linroid.ketch.app.components.focusRing
import com.linroid.ketch.app.components.rememberFocusVisibility
import com.linroid.ketch.app.components.trackFocusVisibility
import com.linroid.ketch.app.icons.KetchIcon
import com.linroid.ketch.app.icons.KetchIconImage
import com.linroid.ketch.app.theme.KetchTheme
import com.linroid.ketch.app.theme.eyebrowText
import com.linroid.ketch.app.ui.inspector.tabs.middleEllipsis
import ketch.app.shared.generated.resources.Res
import ketch.app.shared.generated.resources.action_show
import ketch.app.shared.generated.resources.inspector_hide
import kotlinx.coroutines.delay
import org.jetbrains.compose.resources.stringResource
import kotlin.time.Duration.Companion.seconds

/** A titled part of the inspector, such as CONTROLS or DETAILS. */
@Composable
internal fun InspectorSection(
  title: String,
  modifier: Modifier = Modifier,
  content: @Composable ColumnScope.() -> Unit,
) {
  val spacing = KetchTheme.spacing
  Column(
    modifier = modifier.fillMaxWidth(),
    verticalArrangement = Arrangement.spacedBy(spacing.s1),
  ) {
    Text(
      text = eyebrowText(title),
      style = KetchTheme.typography.eyebrow,
      color = KetchTheme.colors.textTertiary,
      modifier = Modifier.padding(bottom = spacing.s1),
    )
    content()
  }
}

/** Width of the label column of the Controls and Details rows. */
internal val InspectorLabelWidth: Dp
  @Composable get() = KetchTheme.spacing.s16 + KetchTheme.spacing.s8

/**
 * A row of the Controls: [label] in the label column, then the control. A [wide] control, such
 * as a segmented one, moves under its label unless [inline] says the inspector is wide enough
 * for it beside the label.
 */
@Composable
internal fun ControlRow(
  label: String,
  inline: Boolean,
  modifier: Modifier = Modifier,
  wide: Boolean = false,
  content: @Composable () -> Unit,
) {
  val spacing = KetchTheme.spacing
  val minHeight = KetchTheme.density.buttonLarge
  if (wide && !inline) {
    Column(
      verticalArrangement = Arrangement.spacedBy(spacing.s1),
      modifier = modifier.fillMaxWidth().padding(vertical = spacing.s1),
    ) {
      ControlLabel(label)
      content()
    }
  } else {
    Row(
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(spacing.s2),
      modifier = modifier.fillMaxWidth().heightIn(min = minHeight),
    ) {
      ControlLabel(label, Modifier.width(InspectorLabelWidth - spacing.s2))
      Box(Modifier.weight(1f)) { content() }
    }
  }
}

@Composable
private fun ControlLabel(text: String, modifier: Modifier = Modifier) {
  Text(
    text = text,
    style = KetchTheme.typography.caption,
    color = KetchTheme.colors.textTertiary,
    maxLines = 1,
    overflow = TextOverflow.Ellipsis,
    modifier = modifier,
  )
}

/** A small accent link, such as "Show full link" or "Copy details". */
@Composable
internal fun TextLink(text: String, onClick: () -> Unit) {
  val colors = KetchTheme.colors
  val shape = KetchTheme.shapes.xs
  val focus = rememberFocusVisibility()
  Text(
    text = text,
    style = KetchTheme.typography.labelS,
    color = colors.accentText,
    modifier = Modifier
      .focusRing(focus.visible, shape, colors.focusRing)
      .clip(shape)
      .trackFocusVisibility(focus)
      .clickable(role = Role.Button, onClick = onClick),
  )
}

/** A quiet toggle that folds [text]'s content away, with a chevron that turns when [open]. */
@Composable
internal fun Disclosure(text: String, open: Boolean, onToggle: () -> Unit) {
  val colors = KetchTheme.colors
  val shape = KetchTheme.shapes.xs
  val focus = rememberFocusVisibility()
  Row(
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(KetchTheme.spacing.s1),
    modifier = Modifier
      .focusRing(focus.visible, shape, colors.focusRing)
      .clip(shape)
      .trackFocusVisibility(focus)
      .clickable(
        role = Role.Button,
        onClickLabel = stringResource(
          if (open) Res.string.inspector_hide else Res.string.action_show,
        ),
        onClick = onToggle,
      )
      .padding(vertical = KetchTheme.spacing.s1),
  ) {
    KetchIconImage(
      icon = if (open) KetchIcon.ChevronDown else KetchIcon.Chevron,
      size = KetchTheme.spacing.s3,
      tint = colors.textTertiary,
    )
    Text(text = text, style = KetchTheme.typography.labelS, color = colors.textSecondary)
  }
}

/**
 * [text] on one line, shortened in the middle when it does not fit, so a file name keeps its
 * extension and a path its file.
 */
@Composable
internal fun MiddleText(
  text: String,
  style: TextStyle,
  color: Color,
  modifier: Modifier = Modifier,
) {
  val styled = style.copy(color = color)
  BoxWithConstraints(modifier) {
    val measurer = rememberTextMeasurer()
    val width = constraints.maxWidth
    val shown = remember(text, width, styled) {
      middleEllipsis(text) { measurer.measure(it, styled, maxLines = 1).size.width <= width }
    }
    Text(text = shown, style = styled, maxLines = 1, overflow = TextOverflow.Ellipsis)
  }
}

/**
 * Lets the content reach [horizontal] past both sides of its slot, so a hover background can
 * frame a row whose text lines up with its neighbours.
 */
internal fun Modifier.bleed(horizontal: Dp): Modifier = layout { measurable, constraints ->
  val extra = horizontal.roundToPx() * 2
  val placeable = measurable.measure(constraints.offset(horizontal = extra))
  layout((placeable.width - extra).coerceAtLeast(0), placeable.height) {
    placeable.placeRelative(-extra / 2, 0)
  }
}

/**
 * The first of [count] variants of [content], from the fullest to the most compact, whose
 * natural width fits the room; the last one when none does. Variants after the one that fits
 * are never composed.
 */
@Composable
internal fun FirstThatFits(
  count: Int,
  modifier: Modifier = Modifier,
  content: @Composable (variant: Int) -> Unit,
) {
  SubcomposeLayout(modifier) { constraints ->
    val loose = constraints.copy(minWidth = 0)
    var placeables: List<Placeable> = emptyList()
    for (variant in 0 until count) {
      val measurables = subcompose(variant) { content(variant) }
      val natural = measurables.maxOfOrNull { it.maxIntrinsicWidth(constraints.maxHeight) } ?: 0
      if (natural <= constraints.maxWidth || variant == count - 1) {
        placeables = measurables.map { it.measure(loose) }
        break
      }
    }
    val width = (placeables.maxOfOrNull { it.width } ?: 0).coerceIn(loose.minWidth, loose.maxWidth)
    val height = placeables.maxOfOrNull { it.height } ?: 0
    layout(width, height) {
      placeables.forEach { it.placeRelative(0, 0) }
    }
  }
}

/**
 * [value], or the value just chosen while the command that applies it runs: a control keeps
 * showing a choice until [value] catches up, or, once nothing is [pending], for at most 2 s
 * more, then shows [value] again, so a choice that failed springs back.
 *
 * @return the shown value and the function that records a choice.
 */
@Composable
internal fun <T> rememberChoice(value: T, pending: Boolean): Pair<T, (T) -> Unit> {
  var chosen by remember { mutableStateOf<Choice<T>?>(null) }
  LaunchedEffect(value, pending, chosen) {
    val current = chosen ?: return@LaunchedEffect
    if (current.value == value) {
      chosen = null
    } else if (!pending) {
      delay(CHOICE_SETTLE)
      chosen = null
    }
  }
  val shown = chosen?.value ?: value
  return shown to { choice: T -> chosen = Choice(choice) }
}

/** A choice wrapped, so a chosen `null` differs from no choice. */
private class Choice<T>(val value: T)

/**
 * Whether [trigger] changed from 0 within the last 1.5 s, such as a count of copies, so a value
 * can read "Copied" for a moment.
 */
@Composable
internal fun rememberFlash(trigger: Int): Boolean {
  var on by remember { mutableStateOf(false) }
  LaunchedEffect(trigger) {
    if (trigger == 0) return@LaunchedEffect
    on = true
    delay(FLASH)
    on = false
  }
  return on
}

private val CHOICE_SETTLE = 2.seconds
private val FLASH = 1.5.seconds
