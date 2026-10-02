package com.linroid.ketch.app.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.linroid.ketch.app.theme.KetchTheme
import kotlinx.coroutines.delay
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/** Connection counts a task can ask for. */
val ConnectionRange: IntRange = 1..32

/** Peer limits a torrent can ask for, in steps of [PEER_LIMIT_STEP]. */
val PeerLimitRange: IntRange = 1..512

/** Step of the torrent peer limit. */
const val PEER_LIMIT_STEP: Int = 10

/**
 * The count after pressing − ([delta] = -1) or + ([delta] = 1) on [current], moving by [step]
 * and staying in [range]. A [current] of 0 means Auto and counts as [autoValue], or as the
 * start of the range when that is unknown.
 */
fun stepConnections(
  current: Int,
  delta: Int,
  range: IntRange = ConnectionRange,
  step: Int = 1,
  autoValue: Int? = null,
): Int {
  val base = if (current == 0) autoValue ?: range.first else current
  return (base + delta * step).coerceIn(range)
}

/** "8", or "Auto (4)" for a [value] of 0 with the [autoValue] it resolves to. */
fun connectionLabel(value: Int, autoValue: Int? = null): String = when {
  value != 0 -> value.toString()
  autoValue != null -> "Auto ($autoValue)"
  else -> "Auto"
}

/**
 * `[−] 8 [+]` stepper for a task's connections, or a torrent's peer limit.
 *
 * Presses change the number at once and commit 400 ms after the last one, so a run of presses
 * sends a single change. While [pending] it shows a spinner and keeps the number just chosen;
 * once the command ends it follows [value] again.
 *
 * @param value the requested count; 0 means Auto, shown as "Auto ([autoValue])".
 * @param onCommit applies a new count.
 * @param noun what is counted, for screen readers, such as "connections" or "peers".
 * @param disabledReason why it is disabled, shown as its tooltip, such as "This server allows
 *   1 connection".
 */
@Composable
fun ConnectionStepper(
  value: Int,
  onCommit: (Int) -> Unit,
  modifier: Modifier = Modifier,
  autoValue: Int? = null,
  range: IntRange = ConnectionRange,
  step: Int = 1,
  enabled: Boolean = true,
  pending: Boolean = false,
  noun: String = "connections",
  disabledReason: String? = null,
) {
  val colors = KetchTheme.colors
  val spacing = KetchTheme.spacing
  var requested by remember { mutableStateOf<Int?>(null) }
  LaunchedEffect(value, pending, requested) {
    val chosen = requested ?: return@LaunchedEffect
    if (value == chosen) {
      requested = null
    } else if (!pending) {
      delay(SettleTimeout)
      requested = null
    }
  }
  val shown = requested ?: value
  val currentOnCommit by rememberUpdatedState(onCommit)
  val currentValue by rememberUpdatedState(value)
  val scope = rememberCoroutineScope()
  val debounce = remember(scope) {
    DebouncedCommit<Int>(scope, StepperCommitDelay) { count ->
      if (count != currentValue) currentOnCommit(count)
    }
  }
  DisposableEffect(debounce) { onDispose { debounce.flush() } }
  fun press(delta: Int) {
    val next = stepConnections(shown, delta, range, step, autoValue)
    requested = next
    debounce.update(next)
  }
  val effective = if (shown == 0) autoValue ?: range.first else shown
  val stepper = @Composable { stepperModifier: Modifier ->
    Row(
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(spacing.s1),
      modifier = stepperModifier
        .graphicsLayer { alpha = if (enabled) 1f else DISABLED_ALPHA }
        .semantics { stateDescription = "${connectionLabel(shown, autoValue)} $noun" },
    ) {
      StepButton(
        plus = false,
        description = "Fewer $noun",
        enabled = enabled && effective > range.first,
        onClick = { press(-1) },
      )
      Text(
        text = connectionLabel(shown, autoValue),
        style = KetchTheme.typography.numeral,
        color = colors.textPrimary,
        textAlign = TextAlign.Center,
        maxLines = 1,
        modifier = Modifier.widthIn(min = ValueMinWidth),
      )
      StepButton(
        plus = true,
        description = "More $noun",
        enabled = enabled && (shown == 0 || effective < range.last),
        onClick = { press(1) },
      )
      if (pending) KetchSpinner()
    }
  }
  if (!enabled && disabledReason != null) {
    KetchTooltip(text = disabledReason, modifier = modifier) { stepper(Modifier) }
  } else {
    stepper(modifier)
  }
}

@Composable
private fun StepButton(plus: Boolean, description: String, enabled: Boolean, onClick: () -> Unit) {
  val colors = KetchTheme.colors
  val shape = KetchTheme.shapes.sm
  val side = KetchTheme.density.buttonSmall
  val interactions = remember { MutableInteractionSource() }
  val overlay = rememberInteractionOverlay(interactions, enabled)
  val focus = rememberFocusVisibility()
  Box(
    contentAlignment = Alignment.Center,
    modifier = Modifier
      .focusRing(focus.visible, shape, colors.focusRing)
      .graphicsLayer { alpha = if (enabled) 1f else DISABLED_ALPHA }
      .size(side)
      .background(colors.surface, shape)
      .background(overlay, shape)
      .border(1.dp, colors.borderStrong, shape)
      .semantics { contentDescription = description }
      .trackFocusVisibility(focus)
      .clickable(
        interactionSource = interactions,
        indication = null,
        enabled = enabled,
        role = Role.Button,
        onClick = onClick,
      ),
  ) {
    StepGlyph(plus, KetchTheme.density.controlGlyph, colors.textPrimary)
  }
}

/** A − or + drawn like the icon set: 1.7 units of a 20-unit grid, round caps. */
@Composable
private fun StepGlyph(plus: Boolean, size: Dp, color: Color) {
  Canvas(Modifier.size(size)) {
    val unit = this.size.width / GLYPH_GRID
    val stroke = GLYPH_STROKE * unit
    val from = 4 * unit
    val to = 16 * unit
    val mid = 10 * unit
    drawLine(color, Offset(from, mid), Offset(to, mid), stroke, StrokeCap.Round)
    if (plus) drawLine(color, Offset(mid, from), Offset(mid, to), stroke, StrokeCap.Round)
  }
}

private const val GLYPH_GRID = 20f
private const val GLYPH_STROKE = 1.7f
private val StepperCommitDelay = 400.milliseconds
private val SettleTimeout = 2.seconds
private val ValueMinWidth = 56.dp
