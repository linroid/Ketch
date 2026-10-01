package com.linroid.ketch.app.ui.pulse

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.linroid.ketch.api.SpeedLimit
import com.linroid.ketch.app.components.KetchSpinner
import com.linroid.ketch.app.components.KetchTooltip
import com.linroid.ketch.app.components.focusRing
import com.linroid.ketch.app.components.rememberFocusVisibility
import com.linroid.ketch.app.components.rememberInteractionOverlay
import com.linroid.ketch.app.components.trackFocusVisibility
import com.linroid.ketch.app.icons.KetchIcon
import com.linroid.ketch.app.icons.KetchIconImage
import com.linroid.ketch.app.input.KetchCommands
import com.linroid.ketch.app.instance.EmbeddedInstance
import com.linroid.ketch.app.state.AppState
import com.linroid.ketch.app.state.LocalClock
import com.linroid.ketch.app.state.SpeedMode
import com.linroid.ketch.app.state.SpeedModeController
import com.linroid.ketch.app.state.isSlowLane
import com.linroid.ketch.app.theme.KetchTheme
import kotlinx.coroutines.Job
import kotlinx.datetime.TimeZone

/**
 * What the speed mode pill and popover show for the active device.
 *
 * @property mode mode in effect.
 * @property limit limit downloads run at, from [effectiveCap].
 * @property label text of the pill, from [speedModeLabel].
 * @property controller the device's speed mode; `null` when it has none, which leaves only the
 *   speed limit to change.
 */
@Immutable
internal data class SpeedModeView(
  val mode: SpeedMode,
  val limit: SpeedLimit,
  val label: String,
  val controller: SpeedModeController?,
) {
  /** Whether any limit holds downloads back, which tints the pill amber. */
  val limited: Boolean get() = !limit.isUnlimited

  /** Glyph of the mode. */
  val icon: KetchIcon
    get() = when (mode) {
      SpeedMode.Full -> KetchIcon.Speed
      SpeedMode.SlowLane -> KetchIcon.SlowLane
      is SpeedMode.Auto -> KetchIcon.Auto
    }
}

/** The speed mode of the active device as the pill and popover show it, kept current. */
@Composable
internal fun rememberSpeedModeView(state: AppState): SpeedModeView {
  val pulse by state.pulse.state.collectAsState()
  val active by state.activeInstance.collectAsState()
  val controller = state.speedMode.takeIf { active is EmbeddedInstance }
  val settings = controller?.settings?.collectAsState()?.value
  val mode = controller?.mode?.collectAsState()?.value ?: pulse.mode
  // The suggested slow lane speed follows the observed peak.
  controller?.observedPeak?.collectAsState()?.value
  val limit = if (controller == null || settings == null) {
    pulse.cap
  } else {
    effectiveCap(mode, pulse.cap, controller.slowLaneSpeed, settings.standard)
  }
  val label = speedModeLabel(mode, limit, LocalClock.current.now(), TimeZone.currentSystemDefault())
  return SpeedModeView(mode, limit, label, controller)
}

/** The command a control shows a spinner for, until it ends. */
@Stable
internal class PendingJob {
  private var job by mutableStateOf<Job?>(null)

  /** Whether the tracked command still runs. */
  val pending: Boolean get() = job != null

  /** Tracks [next] in place of the last command; `null`, for no command, changes nothing. */
  fun track(next: Job?) {
    next ?: return
    job = next
    next.invokeOnCompletion { if (job === next) job = null }
  }
}

/** Remembers a [PendingJob] that tracks nothing yet. */
@Composable
internal fun rememberPendingJob(): PendingJob = remember { PendingJob() }

/**
 * The speed mode pill of the Pulse bar: "Full speed", "Capped · 20 MB/s", "Slow lane · 1 MB/s"
 * or "Auto · Slow lane until 18:00", amber whenever a limit holds downloads back.
 *
 * Clicking it turns the Slow lane on or off with an Undo toast, like `⇧⌘L`; its chevron opens
 * the [SpeedModePopover]. A device without a speed mode, such as a remote one, opens the
 * popover from both, to change its speed limit.
 */
@Composable
fun SpeedModePill(state: AppState, modifier: Modifier = Modifier) {
  val view = rememberSpeedModeView(state)
  var popoverOpen by remember { mutableStateOf(false) }
  val switching = rememberPendingJob()
  Box(modifier) {
    SpeedModePillContent(
      view = view,
      pending = switching.pending,
      onToggle = {
        if (view.controller == null) {
          popoverOpen = true
        } else {
          switching.track(state.toggleSlowLane())
        }
      },
      onOptions = { popoverOpen = !popoverOpen },
    )
    SpeedModePopover(
      state = state,
      expanded = popoverOpen,
      onDismissRequest = { popoverOpen = false },
    )
  }
}

/** The pill of [view], drawn without the state it reads; see [SpeedModePill]. */
@Composable
internal fun SpeedModePillContent(
  view: SpeedModeView,
  onToggle: () -> Unit,
  onOptions: () -> Unit,
  modifier: Modifier = Modifier,
  pending: Boolean = false,
) {
  val colors = KetchTheme.colors
  val spacing = KetchTheme.spacing
  val shape = KetchTheme.shapes.full
  val limitedInk = colors.status.paused.color
  val fill by animateColorAsState(
    targetValue = if (view.limited) colors.status.paused.soft else Color.Transparent,
    animationSpec = tween(KetchTheme.motion.short),
  )
  val ink = if (view.limited) limitedInk else colors.textSecondary
  val border = if (view.limited) limitedInk.copy(alpha = LIMITED_BORDER_ALPHA) else colors.hairline
  val toggles = view.controller != null
  val toggleTip = if (toggles) {
    if (view.mode.isSlowLane) "Turn off Slow lane" else "Turn on Slow lane"
  } else {
    "Speed limit"
  }
  Row(
    verticalAlignment = Alignment.CenterVertically,
    modifier = modifier
      .height(PillHeight)
      .widthIn(max = PillMaxWidth)
      .background(fill, shape)
      .border(1.dp, border, shape),
  ) {
    KetchTooltip(
      text = toggleTip,
      shortcut = if (toggles) KetchCommands.SlowLane.shortcutLabel() else null,
      modifier = Modifier.weight(1f, fill = false),
    ) {
      PillPart(
        onClick = onToggle,
        description = view.label,
        stateLabel = if (view.limited) "Limited" else "Not limited",
        startPadding = spacing.s2,
        endPadding = spacing.s1,
      ) {
        if (pending) {
          KetchSpinner(color = ink)
        } else {
          KetchIconImage(icon = view.icon, size = GlyphSize, tint = ink)
        }
        Text(
          text = view.label,
          style = KetchTheme.typography.labelS,
          color = if (view.limited) limitedInk else colors.textPrimary,
          maxLines = 1,
          overflow = TextOverflow.Ellipsis,
          modifier = Modifier.weight(1f, fill = false),
        )
      }
    }
    KetchTooltip(text = "Speed options") {
      PillPart(
        onClick = onOptions,
        description = "Speed options",
        stateLabel = null,
        startPadding = spacing.s0_5,
        endPadding = spacing.s2,
      ) {
        KetchIconImage(icon = KetchIcon.ChevronDown, size = ChevronSize, tint = ink)
      }
    }
  }
}

@Composable
private fun PillPart(
  onClick: () -> Unit,
  description: String,
  stateLabel: String?,
  startPadding: Dp,
  endPadding: Dp,
  content: @Composable RowScope.() -> Unit,
) {
  val colors = KetchTheme.colors
  val shape = KetchTheme.shapes.full
  val interactions = remember { MutableInteractionSource() }
  val overlay = rememberInteractionOverlay(interactions)
  val focus = rememberFocusVisibility()
  Row(
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(KetchTheme.spacing.s1),
    modifier = Modifier
      .fillMaxHeight()
      .focusRing(focus.visible, shape, colors.focusRing)
      .clip(shape)
      .background(overlay)
      .semantics {
        contentDescription = description
        if (stateLabel != null) stateDescription = stateLabel
      }
      .trackFocusVisibility(focus)
      .clickable(
        interactionSource = interactions,
        indication = null,
        role = Role.Button,
        onClick = onClick,
      )
      .padding(start = startPadding, end = endPadding),
    content = content,
  )
}

private const val LIMITED_BORDER_ALPHA = 0.32f
private val PillHeight = 24.dp
private val PillMaxWidth = 280.dp
private val GlyphSize = 16.dp
private val ChevronSize = 12.dp
