package com.linroid.ketch.app.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
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
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.linroid.ketch.app.i18n.UiText
import com.linroid.ketch.app.i18n.resolve
import com.linroid.ketch.app.i18n.text
import com.linroid.ketch.app.i18n.verbatim
import com.linroid.ketch.app.theme.KetchTheme
import ketch.app.shared.generated.resources.Res
import ketch.app.shared.generated.resources.component_stepper_auto
import ketch.app.shared.generated.resources.component_stepper_auto_count
import ketch.app.shared.generated.resources.component_stepper_connections
import ketch.app.shared.generated.resources.component_stepper_connections_auto
import ketch.app.shared.generated.resources.component_stepper_connections_auto_count
import ketch.app.shared.generated.resources.component_stepper_fewer_connections
import ketch.app.shared.generated.resources.component_stepper_fewer_peers
import ketch.app.shared.generated.resources.component_stepper_more_connections
import ketch.app.shared.generated.resources.component_stepper_more_peers
import ketch.app.shared.generated.resources.component_stepper_peers
import ketch.app.shared.generated.resources.component_stepper_peers_auto
import ketch.app.shared.generated.resources.component_stepper_peers_auto_count
import ketch.app.shared.generated.resources.component_stepper_use_auto
import org.jetbrains.compose.resources.PluralStringResource
import org.jetbrains.compose.resources.StringResource
import kotlin.time.Duration.Companion.milliseconds

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
fun connectionText(value: Int, autoValue: Int? = null): UiText = when {
  value != 0 -> verbatim(value.toString())
  autoValue != null -> Res.string.component_stepper_auto_count.text(autoValue)
  else -> Res.string.component_stepper_auto.text()
}

/** What a [ConnectionStepper] counts, which names its value and buttons for screen readers. */
enum class StepperCount(
  private val fewer: StringResource,
  private val more: StringResource,
  private val count: PluralStringResource,
  private val auto: StringResource,
  private val autoCount: StringResource,
) {
  /** A task's connections. */
  Connections(
    Res.string.component_stepper_fewer_connections,
    Res.string.component_stepper_more_connections,
    Res.plurals.component_stepper_connections,
    Res.string.component_stepper_connections_auto,
    Res.string.component_stepper_connections_auto_count,
  ),

  /** A torrent's peer limit. */
  Peers(
    Res.string.component_stepper_fewer_peers,
    Res.string.component_stepper_more_peers,
    Res.plurals.component_stepper_peers,
    Res.string.component_stepper_peers_auto,
    Res.string.component_stepper_peers_auto_count,
  );

  /** The − button: "Fewer connections". */
  val fewerLabel: UiText get() = fewer.text()

  /** The + button: "More connections". */
  val moreLabel: UiText get() = more.text()

  /** [value] as the stepper shows it, with what it counts: "8 connections", "Auto (4) peers". */
  fun valueLabel(value: Int, autoValue: Int? = null): UiText = when {
    value != 0 -> count.text(value)
    autoValue != null -> autoCount.text(autoValue)
    else -> auto.text()
  }
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
 * @param counts what is counted, which names the value and the buttons for screen readers.
 * @param disabledReason why it is disabled, shown as its tooltip, such as "This server allows
 *   1 connection".
 * @param allowAuto whether the task's device takes 0 (Auto); an Auto button then follows the
 *   stepper while the value is not Auto.
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
  counts: StepperCount = StepperCount.Connections,
  disabledReason: String? = null,
  allowAuto: Boolean = false,
) {
  val colors = KetchTheme.colors
  val spacing = KetchTheme.spacing
  var requested by rememberRequested(value, pending)
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
    val state = counts.valueLabel(shown, autoValue).resolve()
    Row(
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(spacing.s1),
      modifier = stepperModifier
        .graphicsLayer { alpha = if (enabled) 1f else DISABLED_ALPHA }
        .semantics { stateDescription = state },
    ) {
      StepButton(
        plus = false,
        description = counts.fewerLabel.resolve(),
        enabled = enabled && effective > range.first,
        onClick = { press(-1) },
      )
      Text(
        text = connectionText(shown, autoValue).resolve(),
        style = KetchTheme.typography.numeral,
        color = colors.textPrimary,
        textAlign = TextAlign.Center,
        maxLines = 1,
        modifier = Modifier.widthIn(min = ValueMinWidth),
      )
      StepButton(
        plus = true,
        description = counts.moreLabel.resolve(),
        enabled = enabled && (shown == 0 || effective < range.last),
        onClick = { press(1) },
      )
      if (allowAuto && shown != 0) {
        val useAuto = Res.string.component_stepper_use_auto.text().resolve()
        KetchButton(
          text = Res.string.component_stepper_auto.text().resolve(),
          onClick = {
            requested = 0
            debounce.update(0)
          },
          modifier = Modifier.semantics { contentDescription = useAuto },
          variant = KetchButtonVariant.Ghost,
          size = KetchButtonSize.Small,
          enabled = enabled,
          tooltip = useAuto,
        )
      }
      if (pending) KetchSpinner()
    }
  }
  OptionalTooltip(disabledReason.takeIf { !enabled }, modifier, content = stepper)
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
      .ketchClickable(interactions, focus, enabled = enabled, onClick = onClick),
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
private val ValueMinWidth = 56.dp
