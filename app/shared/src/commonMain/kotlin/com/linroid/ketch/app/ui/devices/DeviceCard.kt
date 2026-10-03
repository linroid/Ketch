package com.linroid.ketch.app.ui.devices

import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.linroid.ketch.app.components.DevicePennant
import com.linroid.ketch.app.components.DevicePennantDefaults
import com.linroid.ketch.app.components.KetchButton
import com.linroid.ketch.app.components.KetchButtonSize
import com.linroid.ketch.app.components.KetchButtonVariant
import com.linroid.ketch.app.components.KetchDot
import com.linroid.ketch.app.components.KetchSpeedChart
import com.linroid.ketch.app.components.KetchSpinner
import com.linroid.ketch.app.components.KetchTooltip
import com.linroid.ketch.app.components.SpeedBand
import com.linroid.ketch.app.components.focusRing
import com.linroid.ketch.app.components.healthColor
import com.linroid.ketch.app.components.ketchClickable
import com.linroid.ketch.app.components.rememberFocusVisibility
import com.linroid.ketch.app.icons.KetchIcon
import com.linroid.ketch.app.icons.KetchIconImage
import com.linroid.ketch.app.instance.DevicePresence
import com.linroid.ketch.app.instance.RemoteInstance
import com.linroid.ketch.app.platform.localDeviceNoun
import com.linroid.ketch.app.state.AppState
import com.linroid.ketch.app.state.DeviceHealth
import com.linroid.ketch.app.state.LocalClock
import com.linroid.ketch.app.theme.KetchElevationLevel
import com.linroid.ketch.app.theme.KetchTheme
import com.linroid.ketch.app.theme.ketchSurface
import com.linroid.ketch.app.ui.pulse.healthLabel
import com.linroid.ketch.app.ui.pulse.sparklineSamples
import com.linroid.ketch.app.ui.pulse.speedText

/**
 * A device on the Devices page: who it is, how fast it downloads and in which speed mode, its
 * lane of downloads, its tasks per tab (each opens that tab on the device), its free space,
 * networks and sharing, what to do next and its last minute of speed. A device that is not
 * online says why, with a way to fix it. Links and files dropped on the card are added there.
 *
 * @param work what the device's tasks add up to; see [rememberDeviceWork].
 * @param active whether it is the device the app shows.
 * @param marked whether its outline marks it as that device, which only matters among several.
 */
@Composable
internal fun DeviceCard(
  state: AppState,
  device: DevicePresence,
  work: DeviceWork,
  active: Boolean,
  marked: Boolean,
  onRename: () -> Unit,
  onRemove: () -> Unit,
  modifier: Modifier = Modifier,
) {
  val colors = KetchTheme.colors
  val shape = KetchTheme.shapes.card
  val problem = deviceProblem(device, LocalClock.current.now())
  DeviceDropTarget(
    label = "Drop to download on ${device.name}",
    enabled = problem == null,
    shape = shape,
    onDropFiles = { state.dropFiles(device.entry, it) },
    onDropText = { state.dropText(device.entry, it) },
    modifier = modifier,
  ) {
    Column(
      Modifier
        .fillMaxSize()
        .ketchSurface(
          level = KetchElevationLevel.E2,
          shape = shape,
          fill = colors.surface,
          border = if (marked) colors.accent.copy(alpha = MARKED_BORDER_ALPHA) else colors.hairline,
        )
    ) {
      Column(Modifier.padding(KetchTheme.spacing.s5).weight(1f)) {
        CardHeader(state, device, dimmed = problem != null, onRename, onRemove)
        if (problem == null) {
          DeviceDetails(state, device, work, active)
        } else {
          ProblemDetails(state, device, problem, onRemove)
        }
      }
      if (problem == null) Sparkline(device.history)
    }
  }
}

/**
 * The pennant and name of a device, which open its downloads, its health and ⋯, then a line
 * about its version, system and uptime.
 */
@Composable
private fun CardHeader(
  state: AppState,
  device: DevicePresence,
  dimmed: Boolean,
  onRename: () -> Unit,
  onRemove: () -> Unit,
) {
  val colors = KetchTheme.colors
  val spacing = KetchTheme.spacing
  val interactions = remember { MutableInteractionSource() }
  val hovered by interactions.collectIsHoveredAsState()
  val focus = rememberFocusVisibility()
  val alpha = if (dimmed) DIMMED_ALPHA else 1f
  Row(
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(spacing.s2),
  ) {
    Row(
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(spacing.s3),
      modifier = Modifier
        .weight(1f)
        .focusRing(focus.visible, KetchTheme.shapes.md, colors.focusRing)
        .ketchClickable(
          interactions = interactions,
          focus = focus,
          onClickLabel = "Show downloads",
          onClick = { state.showDeviceTab(device.entry) },
        )
        .alpha(alpha),
    ) {
      DevicePennant(
        deviceId = device.deviceId,
        name = device.entry.label,
        size = DevicePennantDefaults.Large,
        icon = deviceIcon(device, localDeviceNoun()),
      )
      Text(
        text = device.name,
        style = KetchTheme.typography.titleM,
        color = if (hovered) colors.accentText else colors.textPrimary,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.weight(1f),
      )
    }
    HealthDot(device)
    DeviceMenuButton(state, device, onRename = onRename, onRemove = onRemove)
  }
  val meta = deviceMeta(device, LocalClock.current.now())
  if (meta.isNotEmpty()) {
    Text(
      text = meta,
      style = KetchTheme.typography.caption,
      color = colors.textTertiary,
      maxLines = 1,
      overflow = TextOverflow.Ellipsis,
      modifier = Modifier.padding(top = spacing.s1).alpha(alpha),
    )
  }
}

/** The connection's health; a device the app does not keep connected has none to show. */
@Composable
private fun HealthDot(device: DevicePresence) {
  val colors = KetchTheme.colors
  val label = if (device.connected) healthLabel(device.health) else "Not connected"
  KetchTooltip(text = label) {
    KetchDot(
      color = if (device.connected) colors.healthColor(device.health) else colors.textDisabled,
      pulse = device.connected && device.health == DeviceHealth.Connecting,
      modifier = Modifier
        .padding(KetchTheme.spacing.s1)
        .semantics { contentDescription = label },
    )
  }
}

/** Speed, lane, counts, storage, networks and next actions of a device that is online. */
@Composable
private fun ColumnScope.DeviceDetails(
  state: AppState,
  device: DevicePresence,
  work: DeviceWork,
  active: Boolean,
) {
  val spacing = KetchTheme.spacing
  Spacer(Modifier.height(spacing.s4))
  SpeedRow(state, device, active)
  Spacer(Modifier.height(spacing.s3))
  DeviceLane(work.blocks)
  Spacer(Modifier.height(spacing.s4))
  CountsRow(state, device)
  Spacer(Modifier.height(spacing.s4))
  Storage(device, work)
  val chips = deviceChips(state, device)
  if (chips.isNotEmpty()) {
    Spacer(Modifier.height(spacing.s3))
    ChipsRow(chips)
  }
  val actions = nextActions(device, work)
  if (actions.isNotEmpty()) {
    Spacer(Modifier.height(spacing.s4))
    ActionsRow(actions) { state.runNextAction(device.entry, it) }
  }
}

/** The device's total speed in large numerals, or "Idle", and its speed mode pill. */
@Composable
private fun SpeedRow(state: AppState, device: DevicePresence, active: Boolean) {
  val colors = KetchTheme.colors
  val type = KetchTheme.typography
  val downloading = device.counts.downloading > 0
  val speed = speedText(device.speed)
  Row(verticalAlignment = Alignment.CenterVertically) {
    Row(
      horizontalArrangement = Arrangement.spacedBy(KetchTheme.spacing.s1),
      modifier = Modifier.semantics(mergeDescendants = true) {},
    ) {
      Text(
        text = if (downloading) speed.amount else "Idle",
        style = type.numeralXL,
        color = if (downloading) colors.textPrimary else colors.textTertiary,
        maxLines = 1,
        modifier = Modifier.alignByBaseline(),
      )
      if (downloading) {
        Text(
          text = speed.unit,
          style = type.caption,
          color = colors.textTertiary,
          maxLines = 1,
          modifier = Modifier.alignByBaseline(),
        )
      }
    }
    Spacer(Modifier.width(KetchTheme.spacing.s3))
    Box(Modifier.weight(1f), contentAlignment = Alignment.CenterEnd) {
      DeviceSpeedPill(state, device, active = active)
    }
  }
}

/** What is wrong with a device that is not online, under its header, and how to fix it. */
@Composable
private fun ProblemDetails(
  state: AppState,
  device: DevicePresence,
  problem: DeviceProblem,
  onRemove: () -> Unit,
) {
  val colors = KetchTheme.colors
  val spacing = KetchTheme.spacing
  val remote = device.entry as? RemoteInstance
  Column(
    verticalArrangement = Arrangement.spacedBy(spacing.s1),
    modifier = Modifier.fillMaxWidth().padding(top = spacing.s4),
  ) {
    Row(
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(spacing.s2),
    ) {
      when (problem.kind) {
        ProblemKind.Connecting -> KetchSpinner(color = colors.textSecondary)
        ProblemKind.NotConnected -> KetchIconImage(
          icon = KetchIcon.Info,
          size = KetchTheme.density.controlGlyph,
          tint = colors.textTertiary,
        )
        else -> KetchIconImage(
          icon = KetchIcon.Warning,
          size = KetchTheme.density.controlGlyph,
          tint = colors.status.failed.color,
        )
      }
      Text(
        text = problem.title,
        style = KetchTheme.typography.bodyStrong,
        color = colors.textPrimary,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
      )
    }
    problem.detail?.let { detail ->
      Text(
        text = detail,
        style = KetchTheme.typography.caption,
        color = colors.textSecondary,
        maxLines = 2,
        overflow = TextOverflow.Ellipsis,
      )
    }
    if (remote != null) {
      Spacer(Modifier.height(spacing.s2))
      Row(horizontalArrangement = Arrangement.spacedBy(spacing.s2)) {
        problemFix(device, problem)?.let { fix ->
          KetchButton(
            text = fix.label,
            onClick = {
              when (fix) {
                ProblemFix.RetryNow -> state.retryNow(remote)
                ProblemFix.EnterToken -> state.askForToken(remote)
                ProblemFix.Connect -> state.instanceManager.setWatched(remote, true)
                ProblemFix.Show -> state.showDeviceTab(remote)
              }
            },
            variant = KetchButtonVariant.Secondary,
            size = KetchButtonSize.Small,
          )
        }
        KetchButton(
          text = "Remove",
          onClick = onRemove,
          variant = KetchButtonVariant.Ghost,
          size = KetchButtonSize.Small,
        )
      }
    }
  }
}

/**
 * The device's recent speed along the card's bottom edge, stretched across it like the Pulse bar
 * sparkline; empty while idle.
 */
@Composable
private fun Sparkline(history: List<Long>) {
  val samples = sparklineSamples(history)
  Box(Modifier.fillMaxWidth().height(SparklineHeight)) {
    if (samples != null) {
      KetchSpeedChart(
        bands = listOf(SpeedBand(samples, KetchTheme.colors.accent)),
        showAxis = false,
        modifier = Modifier.fillMaxSize().clearAndSetSemantics {},
      )
    }
  }
}

private const val MARKED_BORDER_ALPHA = 0.4f
private const val DIMMED_ALPHA = 0.6f
private val SparklineHeight: Dp = 40.dp
