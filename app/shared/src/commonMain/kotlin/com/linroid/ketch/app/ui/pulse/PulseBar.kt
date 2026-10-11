package com.linroid.ketch.app.ui.pulse

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.Placeable
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.constrainHeight
import androidx.compose.ui.unit.constrainWidth
import androidx.compose.ui.unit.dp
import com.linroid.ketch.app.components.KetchDot
import com.linroid.ketch.app.components.KetchSpeedChart
import com.linroid.ketch.app.components.KetchTooltip
import com.linroid.ketch.app.components.SpeedBand
import com.linroid.ketch.app.components.focusRing
import com.linroid.ketch.app.components.healthColor
import com.linroid.ketch.app.components.ketchClickable
import com.linroid.ketch.app.components.rememberFocusVisibility
import com.linroid.ketch.app.components.rememberInteractionOverlay
import com.linroid.ketch.app.i18n.UiText
import com.linroid.ketch.app.i18n.joinText
import com.linroid.ketch.app.i18n.resolve
import com.linroid.ketch.app.i18n.speedText
import com.linroid.ketch.app.i18n.text
import com.linroid.ketch.app.i18n.verbatim
import com.linroid.ketch.app.icons.KetchIcon
import com.linroid.ketch.app.icons.KetchIconImage
import com.linroid.ketch.app.input.KetchCommands
import com.linroid.ketch.app.state.AppState
import com.linroid.ketch.app.state.DeviceHealth
import com.linroid.ketch.app.state.PulseState
import com.linroid.ketch.app.state.SettingsTarget
import com.linroid.ketch.app.state.StatusFilter
import com.linroid.ketch.app.theme.KetchColors
import com.linroid.ketch.app.theme.KetchTheme
import com.linroid.ketch.app.ui.feedback.ActivityPopover
import ketch.app.shared.generated.resources.Res
import ketch.app.shared.generated.resources.command_activity
import ketch.app.shared.generated.resources.pulse_activity_unread
import ketch.app.shared.generated.resources.pulse_count_description
import ketch.app.shared.generated.resources.pulse_disk_short
import ketch.app.shared.generated.resources.pulse_disk_tooltip
import ketch.app.shared.generated.resources.pulse_health_description
import ketch.app.shared.generated.resources.pulse_idle
import ketch.app.shared.generated.resources.pulse_speed_downloading
import ketch.app.shared.generated.resources.pulse_speed_idle
import ketch.app.shared.generated.resources.pulse_speed_offline
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource

/**
 * Which of the Pulse bar's popovers are open, hoisted so a shortcut can open one: `⌘J` calls
 * [toggleActivity].
 */
@Stable
class PulseBarState {
  /** Whether the Activity popover is open. */
  var activityOpen: Boolean by mutableStateOf(false)

  /** Whether the speed history popover is open. */
  var speedOpen: Boolean by mutableStateOf(false)

  /** Whether the live connections popover is open. */
  var connectionsOpen: Boolean by mutableStateOf(false)

  /** Opens the Activity popover, or closes it when it is open. */
  fun toggleActivity() {
    activityOpen = !activityOpen
  }
}

/** Remembers a [PulseBarState] with every popover closed. */
@Composable
fun rememberPulseBarState(): PulseBarState = remember { PulseBarState() }

/**
 * The Pulse bar, 32 dp at the bottom of the content card and the one place for global status:
 * the speed mode pill, the active device's speed with its last minute as a sparkline, the
 * downloading, waiting and failed counts (each opens its tab), free space, the device's
 * connection and the Activity bell with its unread count.
 *
 * While rows are selected, the connection gives way to a summary of the selection. With
 * [showConnections], a grid of the live connections of the shown devices that report them sits
 * left of the connection, from 540 dp. As the card narrows, free space goes below 720 dp and the
 * counts below 600 dp, and any part that still does not fit is left out from the end. Metrics of
 * a device that is not online are dimmed.
 *
 * @param barState which popovers are open; hoist it to open Activity from a shortcut.
 * @param showConnections whether the connections grid shows, as it does on the Downloads page;
 *   the devices are asked for their connections only while it does.
 */
@Composable
fun PulseBar(
  state: AppState,
  modifier: Modifier = Modifier,
  barState: PulseBarState = rememberPulseBarState(),
  showConnections: Boolean = false,
) {
  val pulse by state.pulse.state.collectAsState()
  val unread by state.messages.unreadCount.collectAsState()
  val selected = state.selectedKeys
  // Rows change several times a second; they are only read while something is selected.
  val selection = if (selected.isEmpty()) {
    null
  } else {
    val rows by state.taskList.rows.collectAsState()
    remember(rows, selected) { selectionSummary(rows, selected) }
  }
  val local = pulse.devices.firstOrNull()?.health is DeviceHealth.Local
  PulseBarContent(
    pulse = pulse,
    unread = unread,
    selection = selection,
    onShowTab = { state.showDownloads(it) },
    onSpeedClick = { barState.speedOpen = !barState.speedOpen },
    onHealthClick = {
      if (local) {
        state.openSettings(SettingsTarget(SettingsTarget.Page.Sharing))
      } else {
        state.showInstanceSelector = true
      }
    },
    onActivityClick = { barState.toggleActivity() },
    modifier = modifier,
    pill = { SpeedModePill(state) },
    connections = if (showConnections) {
      { ConnectionStripHost(state, barState) }
    } else {
      null
    },
    speedPopover = {
      SpeedHistoryPopover(
        state = state,
        expanded = barState.speedOpen,
        onDismissRequest = { barState.speedOpen = false },
      )
    },
    activityPopover = {
      ActivityPopover(
        state = state,
        expanded = barState.activityOpen,
        onDismissRequest = { barState.activityOpen = false },
      )
    },
  )
}

/**
 * The Pulse bar drawn from [pulse], without the state it reads; see [PulseBar].
 *
 * @param selection summary of the selected rows, shown in place of the connection; `null` when
 *   nothing is selected.
 * @param pill the speed mode pill.
 * @param connections the live connections grid with its popover, shown from 540 dp; `null` for
 *   none.
 * @param speedPopover popover anchored to the speed, such as the speed history.
 * @param activityPopover popover anchored to the bell.
 */
@Composable
internal fun PulseBarContent(
  pulse: PulseState,
  unread: Int,
  selection: UiText?,
  onShowTab: (StatusFilter) -> Unit,
  onSpeedClick: () -> Unit,
  onHealthClick: () -> Unit,
  onActivityClick: () -> Unit,
  modifier: Modifier = Modifier,
  pill: @Composable () -> Unit,
  connections: (@Composable () -> Unit)? = null,
  speedPopover: @Composable () -> Unit = {},
  activityPopover: @Composable () -> Unit = {},
) {
  val colors = KetchTheme.colors
  val spacing = KetchTheme.spacing
  val device = pulse.devices.firstOrNull()
  val online = device?.health?.isOnline ?: true
  BoxWithConstraints(
    modifier = modifier
      .fillMaxWidth()
      .height(spacing.pulseBarHeight)
      .background(colors.surfaceSunken),
  ) {
    val showDisk = maxWidth >= DiskMinWidth
    val showCounts = maxWidth >= CountsMinWidth
    val showSparkline = maxWidth >= SparklineMinWidth
    val showHealthLabel = maxWidth >= HealthLabelMinWidth
    val showConnections = maxWidth >= ConnectionsMinWidth
    Spacer(Modifier.fillMaxWidth().height(1.dp).background(colors.hairline))
    Row(
      verticalAlignment = Alignment.CenterVertically,
      modifier = Modifier.fillMaxSize().padding(horizontal = spacing.rowPadding),
    ) {
      pill()
      Spacer(Modifier.width(spacing.s2))
      // Whatever does not fit is left out from the end, so no part is ever cut off.
      FittingRow(Modifier.weight(1f).alpha(if (online) 1f else OFFLINE_ALPHA)) {
        Box {
          SpeedReadout(pulse, online, showSparkline, onSpeedClick)
          speedPopover()
        }
        if (showCounts) {
          countParts(pulse.counts, pulse.failures).forEach { part ->
            Row(verticalAlignment = Alignment.CenterVertically) {
              Separator()
              BarLink(
                text = part.text.resolve(),
                description = Res.string.pulse_count_description
                  .text(part.description, part.filter.label).resolve(),
                color = if (part.alert) colors.status.failed.color else colors.textSecondary,
                onClick = { onShowTab(part.filter) },
              )
            }
          }
        }
        val disk = pulse.diskDevice
        if (showDisk && disk?.disk != null) {
          Row(verticalAlignment = Alignment.CenterVertically) {
            Separator()
            DiskReadout(
              label = diskLabel(disk.disk).resolve(),
              used = diskUsed(disk.disk),
              short = pulse.isDiskShort,
              tooltip = Res.string.pulse_disk_tooltip
                .text(diskLabel(disk.disk), disk.name, verbatim(disk.disk.directory)),
            )
          }
        }
      }
      if (connections != null && showConnections) {
        connections()
        Spacer(Modifier.width(spacing.s1))
      }
      if (selection != null) {
        Text(
          text = selection.resolve(),
          style = KetchTheme.typography.caption,
          color = colors.textPrimary,
          maxLines = 1,
          overflow = TextOverflow.Ellipsis,
          modifier = Modifier.padding(horizontal = spacing.s2),
        )
      } else if (device != null) {
        HealthBadge(device.health, device.name, showHealthLabel, onHealthClick)
      }
      Spacer(Modifier.width(spacing.s1))
      Box {
        ActivityBell(unread, onActivityClick)
        activityPopover()
      }
    }
  }
}

@Composable
private fun SpeedReadout(
  pulse: PulseState,
  online: Boolean,
  showSparkline: Boolean,
  onClick: () -> Unit,
) {
  val colors = KetchTheme.colors
  val type = KetchTheme.typography
  val downloading = pulse.counts.downloading > 0
  val speedLine = speedText(pulse.totalSpeed).resolve()
  val speed = splitSpeed(speedLine)
  val description = when {
    !online -> stringResource(Res.string.pulse_speed_offline)
    downloading -> stringResource(Res.string.pulse_speed_downloading, speedLine)
    else -> stringResource(Res.string.pulse_speed_idle)
  }
  BarButton(onClick = onClick, description = description) {
    when {
      !online -> Text("↓ —", style = type.numeral, color = colors.textSecondary)
      downloading -> Text(
        text = buildAnnotatedString {
          append("↓ ${speed.amount}")
          withStyle(type.caption.toSpanStyle().copy(color = colors.textTertiary)) {
            append(" ${speed.unit}")
          }
        },
        style = type.numeral,
        color = colors.textPrimary,
        maxLines = 1,
        softWrap = false,
      )
      else -> Text(
        text = stringResource(Res.string.pulse_idle),
        style = type.caption,
        color = colors.textSecondary,
        maxLines = 1,
      )
    }
    val bands = remember(pulse, colors) { sparklineBands(pulse, colors) }
    if (showSparkline && online && bands != null) {
      Spacer(Modifier.width(KetchTheme.spacing.s2))
      KetchSpeedChart(
        bands = bands,
        showAxis = false,
        modifier = Modifier.size(SparklineWidth, SparklineHeight).clearAndSetSemantics {},
      )
    }
  }
}

@Composable
private fun DiskReadout(label: String, used: Float, short: Boolean, tooltip: UiText) {
  val colors = KetchTheme.colors
  val ink = if (short) colors.status.paused.color else colors.textSecondary
  val tip = if (short) listOf(tooltip, Res.string.pulse_disk_short.text()).joinText() else tooltip
  KetchTooltip(text = tip.resolve()) {
    Row(
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(KetchTheme.spacing.s2),
      modifier = Modifier.padding(horizontal = KetchTheme.spacing.s1),
    ) {
      Text(label, style = KetchTheme.typography.caption, color = ink, maxLines = 1)
      DiskBar(used, short, colors.borderStrong, Modifier.size(DiskBarWidth, DiskBarHeight))
    }
  }
}

/**
 * The [used] share of a disk on a [track] sized by [modifier], amber while the space is [short];
 * an unknown share shows the track alone.
 */
@Composable
internal fun DiskBar(used: Float?, short: Boolean, track: Color, modifier: Modifier) {
  val colors = KetchTheme.colors
  Box(modifier.clip(KetchTheme.shapes.full).background(track)) {
    if (used != null) {
      Box(
        Modifier
          .fillMaxHeight()
          .fillMaxWidth(used)
          .background(if (short) colors.status.paused.color else colors.textTertiary)
      )
    }
  }
}

@Composable
private fun HealthBadge(
  health: DeviceHealth,
  name: UiText,
  showLabel: Boolean,
  onClick: () -> Unit,
) {
  val colors = KetchTheme.colors
  val label = healthText(health)
  val hollow = health is DeviceHealth.Local && health.sharingPort == null
  val description = Res.string.pulse_health_description.text(name, label).resolve()
  BarButton(onClick = onClick, description = description) {
    if (hollow) {
      Spacer(
        Modifier
          .size(DotSize)
          .border(RingStroke, colors.textTertiary, KetchTheme.shapes.full)
      )
    } else {
      KetchDot(
        color = colors.healthColor(health),
        size = DotSize,
        pulse = health == DeviceHealth.Connecting,
      )
    }
    if (showLabel) {
      Spacer(Modifier.width(KetchTheme.spacing.s1))
      Text(
        text = label.resolve(),
        style = KetchTheme.typography.caption,
        color = if (health.isOnline) colors.textSecondary else colors.healthColor(health),
        maxLines = 1,
      )
    }
  }
}

@Composable
private fun ActivityBell(unread: Int, onClick: () -> Unit) {
  val colors = KetchTheme.colors
  val description = if (unread > 0) {
    pluralStringResource(Res.plurals.pulse_activity_unread, unread, unread)
  } else {
    stringResource(Res.string.command_activity)
  }
  val command = KetchCommands.Activity
  KetchTooltip(text = command.label.resolve(), shortcut = command.shortcutLabel()) {
    BarButton(onClick = onClick, description = description) {
      KetchIconImage(
        icon = KetchIcon.Bell,
        size = BellSize,
        tint = if (unread > 0) colors.accentText else colors.textSecondary,
      )
      if (unread > 0) {
        Spacer(Modifier.width(KetchTheme.spacing.s1))
        Text(
          text = if (unread > MAX_UNREAD) "$MAX_UNREAD+" else unread.toString(),
          style = KetchTheme.typography.numeralS,
          color = colors.accentText,
        )
      }
    }
  }
}

@Composable
private fun Separator() {
  Text(
    text = "·",
    style = KetchTheme.typography.caption,
    color = KetchTheme.colors.textTertiary,
    modifier = Modifier.padding(horizontal = KetchTheme.spacing.s0_5),
  )
}

@Composable
private fun BarLink(text: String, description: String, color: Color, onClick: () -> Unit) {
  BarButton(onClick = onClick, description = description) {
    Text(text = text, style = KetchTheme.typography.caption, color = color, maxLines = 1)
  }
}

/** A clickable stretch of the bar, 24 dp tall, with the hover overlay and focus ring. */
@Composable
internal fun BarButton(
  onClick: () -> Unit,
  description: String,
  modifier: Modifier = Modifier,
  content: @Composable RowScope.() -> Unit,
) {
  val colors = KetchTheme.colors
  val shape = KetchTheme.shapes.full
  val interactions = remember { MutableInteractionSource() }
  val overlay = rememberInteractionOverlay(interactions)
  val focus = rememberFocusVisibility()
  Row(
    verticalAlignment = Alignment.CenterVertically,
    modifier = modifier
      .height(ButtonHeight)
      .focusRing(focus.visible, shape, colors.focusRing)
      .clip(shape)
      .background(overlay)
      .semantics(mergeDescendants = true) { contentDescription = description }
      .ketchClickable(interactions, focus, onClick = onClick)
      .padding(horizontal = KetchTheme.spacing.s1),
    content = content,
  )
}

/**
 * Lays [content] out in a row, centered vertically, and leaves out the children from the first
 * one that does not fit to the end.
 */
@Composable
private fun FittingRow(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
  Layout(content, modifier) { measurables, constraints ->
    val loose = Constraints(maxHeight = constraints.maxHeight)
    val placed = ArrayList<Placeable>(measurables.size)
    var width = 0
    for (measurable in measurables) {
      val placeable = measurable.measure(loose)
      if (width + placeable.width > constraints.maxWidth) break
      placed += placeable
      width += placeable.width
    }
    val height = constraints.constrainHeight(placed.maxOfOrNull { it.height } ?: 0)
    layout(constraints.constrainWidth(width), height) {
      var x = 0
      for (placeable in placed) {
        placeable.placeRelative(x, (height - placeable.height) / 2)
        x += placeable.width
      }
    }
  }
}

/**
 * The sparkline's bands: the scope's total speed in the accent color, or under All devices one
 * band per device in its pennant hue, stacked and scaled together; `null` when there is nothing
 * to draw.
 */
internal fun sparklineBands(pulse: PulseState, colors: KetchColors): List<SpeedBand>? {
  val total = sparklineSamples(pulse.history) ?: return null
  val moving = pulse.devices.filter { device -> device.history.any { it > 0 } }
  if (!pulse.allDevices || moving.size < 2) return listOf(SpeedBand(total, colors.accent))
  val peak = pulse.history.max()
  return moving.map { device ->
    val hue = colors.deviceHue(device.deviceId)
    SpeedBand(
      samples = device.history.map { it * SPARKLINE_TOP / peak },
      color = if (colors.isDark) hue.dark else hue.light,
    )
  }
}

/**
 * [history] scaled so its peak reaches about four fifths of the sparkline, whose chart rounds
 * its top up from there; `null` while there are fewer than two samples or all are zero.
 */
internal fun sparklineSamples(history: List<Long>): List<Long>? {
  val peak = history.maxOrNull() ?: return null
  if (history.size < 2 || peak <= 0) return null
  return history.map { it * SPARKLINE_TOP / peak }
}

private const val OFFLINE_ALPHA = 0.5f
private const val SPARKLINE_TOP = 800L * 1024
private const val MAX_UNREAD = 99
private val DiskMinWidth = 720.dp
private val CountsMinWidth = 600.dp
private val SparklineMinWidth = 480.dp
private val HealthLabelMinWidth = 420.dp
private val ConnectionsMinWidth = 540.dp
private val SparklineWidth = 64.dp
private val SparklineHeight = 14.dp
private val DiskBarWidth = 24.dp
private val DiskBarHeight = 3.dp
private val ButtonHeight = 24.dp
private val BellSize = 16.dp
private val DotSize: Dp = 8.dp
private val RingStroke = 1.5.dp
