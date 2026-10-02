package com.linroid.ketch.app.ui.shell

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.linroid.ketch.api.DownloadState
import com.linroid.ketch.app.components.DevicePennant
import com.linroid.ketch.app.components.DevicePennantDefaults
import com.linroid.ketch.app.components.KetchButtonSize
import com.linroid.ketch.app.components.KetchIconButton
import com.linroid.ketch.app.components.KetchTooltip
import com.linroid.ketch.app.components.focusRing
import com.linroid.ketch.app.components.rememberFocusVisibility
import com.linroid.ketch.app.components.trackFocusVisibility
import com.linroid.ketch.app.icons.KetchIcon
import com.linroid.ketch.app.icons.KetchIconImage
import com.linroid.ketch.app.input.KetchCommands
import com.linroid.ketch.app.instance.DevicePresence
import com.linroid.ketch.app.instance.DeviceScope
import com.linroid.ketch.app.state.AppDestination
import com.linroid.ketch.app.state.AppState
import com.linroid.ketch.app.state.TaskRow
import com.linroid.ketch.app.state.deviceId
import com.linroid.ketch.app.theme.KetchTheme
import com.linroid.ketch.app.ui.sidebar.DeviceMenu
import com.linroid.ketch.app.ui.sidebar.PennantCluster
import com.linroid.ketch.app.ui.sidebar.allDevicesLine
import com.linroid.ketch.app.ui.sidebar.deviceLine
import com.linroid.ketch.app.ui.sidebar.deviceShortcut
import com.linroid.ketch.app.ui.sidebar.onMenuKey
import com.linroid.ketch.app.ui.sidebar.pennantHealth
import com.linroid.ketch.app.ui.sidebar.pennantName
import com.linroid.ketch.app.ui.sidebar.rememberAltHeld
import com.linroid.ketch.app.ui.sidebar.rememberDevices
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/**
 * The 72 dp rail of medium windows, and of wide ones with the sidebar collapsed: the ⊕ add
 * button, the destinations with Downloads' downloading count, the device stack, and Settings at
 * the bottom. On macOS it starts below the traffic lights.
 *
 * From two devices on, the stack starts with All devices, ringed by the progress of everything
 * downloading. Each device's pennant switches to it, opens its menu on a right click, and takes
 * links, files and rows dragged onto it.
 *
 * @param showSidebarToggle whether the window is wide enough to expand the sidebar again.
 */
@Composable
internal fun NavRail(
  state: AppState,
  destinations: List<AppDestination>,
  destination: AppDestination,
  settingsSelected: Boolean,
  showSidebarToggle: Boolean,
  onSelect: (AppDestination) -> Unit,
  onOpenSettings: () -> Unit,
  onToggleSidebar: () -> Unit,
  onAddClipboardLink: () -> Unit,
  modifier: Modifier = Modifier,
) {
  val spacing = KetchTheme.spacing
  val chrome = KetchTheme.windowChrome
  val pulse by state.pulse.state.collectAsState()
  val active by state.activeInstance.collectAsState()
  val scope by state.deviceScope.collectAsState()
  val devices = rememberDevices(state)
  Column(
    horizontalAlignment = Alignment.CenterHorizontally,
    modifier = modifier
      .width(spacing.railWidth)
      .fillMaxHeight()
      .padding(top = if (chrome.top > 0.dp) chrome.top + spacing.s3 else spacing.s3),
  ) {
    if (showSidebarToggle) {
      KetchIconButton(
        icon = KetchIcon.Sidebar,
        onClick = onToggleSidebar,
        size = KetchButtonSize.Small,
        contentDescription = "Show sidebar",
        shortcut = KetchCommands.ToggleSidebar.shortcutLabel(),
        modifier = Modifier.padding(bottom = spacing.s2),
      )
    }
    AddButton(
      size = AddButtonDefaults.Rail,
      onClick = { state.openIntake() },
      onAddClipboardLink = onAddClipboardLink,
      modifier = Modifier.padding(bottom = spacing.s4),
    )
    Column(
      horizontalAlignment = Alignment.CenterHorizontally,
      verticalArrangement = Arrangement.spacedBy(spacing.s1),
    ) {
      for (entry in destinations) {
        RailItem(
          label = entry.label,
          icon = entry.icon,
          shortcut = entry.command.shortcutLabel(),
          selected = entry == destination && !settingsSelected,
          badge = if (entry == AppDestination.Downloads) pulse.counts.downloading else 0,
          onClick = { onSelect(entry) },
        )
      }
    }
    Spacer(
      Modifier
        .padding(vertical = spacing.s3)
        .width(spacing.s8)
        .height(DividerWidth)
        .background(KetchTheme.colors.hairline),
    )
    // The devices take the room left, so Settings stays at the bottom.
    Column(
      horizontalAlignment = Alignment.CenterHorizontally,
      verticalArrangement = Arrangement.spacedBy(spacing.s1),
      modifier = Modifier.weight(1f).verticalScroll(rememberScrollState()),
    ) {
      val all = scope == DeviceScope.All
      if (devices.size >= DeviceScope.MIN_DEVICES) {
        AllDevicesCell(state, devices, selected = all, onClick = { state.showAllDevices() })
      }
      devices.forEachIndexed { index, device ->
        key(device.deviceId) {
          RailDeviceCell(
            state = state,
            device = device,
            number = index + 1,
            selected = !all && device.deviceId == active?.deviceId,
          )
        }
      }
    }
    RailItem(
      label = "Settings",
      icon = KetchIcon.Settings,
      shortcut = KetchCommands.Settings.shortcutLabel(),
      selected = settingsSelected,
      badge = 0,
      onClick = onOpenSettings,
      modifier = Modifier.padding(bottom = spacing.s3),
    )
  }
}

/**
 * A destination of the rail: its glyph on the selected pill, which lightens on hover, with the
 * label below.
 */
@Composable
private fun RailItem(
  label: String,
  icon: KetchIcon,
  shortcut: String?,
  selected: Boolean,
  badge: Int,
  onClick: () -> Unit,
  modifier: Modifier = Modifier,
) {
  val colors = KetchTheme.colors
  val interactions = remember { MutableInteractionSource() }
  val hovered by interactions.collectIsHoveredAsState()
  val focus = rememberFocusVisibility()
  val shape = KetchTheme.shapes.full
  val fill by animateColorAsState(
    targetValue = when {
      selected -> colors.sidebarItemSelected
      hovered -> colors.sidebarItemHover
      else -> Color.Transparent
    },
    animationSpec = tween(KetchTheme.motion.micro),
  )
  KetchTooltip(text = label, shortcut = shortcut, modifier = modifier) {
    Column(
      horizontalAlignment = Alignment.CenterHorizontally,
      modifier = Modifier
        .widthIn(min = CellWidth)
        .trackFocusVisibility(focus)
        .selectable(
          selected = selected,
          interactionSource = interactions,
          indication = null,
          role = Role.Tab,
          onClick = onClick,
        )
        // After selectable: a clear before it would drop its click and selected state too.
        .clearAndSetSemantics {
          contentDescription = if (badge > 0) "$label, $badge downloading" else label
        }
        .padding(vertical = KetchTheme.spacing.s1),
    ) {
      Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
          .focusRing(focus.visible, shape, colors.focusRing)
          .size(IndicatorWidth, IndicatorHeight)
          .background(fill, shape),
      ) {
        Box {
          KetchIconImage(
            icon = icon,
            size = RailGlyph,
            tint = if (selected) colors.accentText else colors.textSecondary,
          )
          if (badge > 0) {
            RailBadge(
              count = badge,
              modifier = Modifier.align(Alignment.TopEnd).offset(x = BadgeShift, y = -BadgeRise),
            )
          }
        }
      }
      Text(
        text = label,
        style = KetchTheme.typography.numeralS,
        color = if (selected) colors.textPrimary else colors.textSecondary,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.padding(top = KetchTheme.spacing.s1),
      )
    }
  }
}

/** The downloading count over Downloads' glyph. */
@Composable
private fun RailBadge(count: Int, modifier: Modifier = Modifier) {
  val colors = KetchTheme.colors
  Box(
    contentAlignment = Alignment.Center,
    modifier = modifier
      .heightIn(min = BadgeSize)
      .widthIn(min = BadgeSize)
      .background(colors.accent, KetchTheme.shapes.badge)
      .padding(horizontal = KetchTheme.spacing.s1),
  ) {
    Text(text = count.toString(), style = KetchTheme.typography.numeralS, color = colors.onAccent)
  }
}

/**
 * A device in the rail's stack: its pennant with the health ring and unseen failures. Tapping it
 * switches to the device, a right click opens its menu, and links, files and rows dragged onto
 * it are added or sent there.
 *
 * @param number the device's place in the list, whose `⌥⌘` digit switches to it.
 */
@Composable
private fun RailDeviceCell(
  state: AppState,
  device: DevicePresence,
  number: Int,
  selected: Boolean,
) {
  var drag by remember { mutableStateOf<DeviceDrag?>(null) }
  var menuOpen by remember { mutableStateOf(false) }
  val move = rememberAltHeld()
  val hint = drag?.let { dropHint(it, device, move) }
  val line = deviceLine(device)
  Box {
    RailCell(
      selected = selected,
      tooltip = "${device.name} · ${line.text}",
      shortcut = deviceShortcut(number),
      description = "${device.name}, ${line.text}",
      onClick = { state.switchInstance(device.entry) },
      onSecondaryClick = { menuOpen = true },
      dropping = hint != null,
      accepting = hint?.accepts == true,
      modifier = Modifier.deviceDropTarget(state, device, onHover = { drag = it }),
    ) {
      DevicePennant(
        deviceId = device.deviceId,
        name = device.pennantName,
        size = DevicePennantDefaults.XLarge,
        health = pennantHealth(device),
        failures = device.unseenFailures,
      )
    }
    DeviceMenu(
      state = state,
      device = device,
      number = number,
      shown = selected,
      expanded = menuOpen,
      onDismissRequest = { menuOpen = false },
    )
  }
}

/**
 * All devices at the top of the rail's stack: the devices' pennants clustered, ringed by the
 * progress of everything downloading on them. Tapping it shows every device's downloads.
 */
@Composable
private fun AllDevicesCell(
  state: AppState,
  devices: List<DevicePresence>,
  selected: Boolean,
  onClick: () -> Unit,
) {
  val progress by remember(state) {
    state.taskList.allRows.map(::aggregateProgress).distinctUntilChanged()
  }.collectAsState(null)
  val line = allDevicesLine(devices)
  val label = KetchCommands.AllDevices.label
  RailCell(
    selected = selected,
    tooltip = "$label · ${line.text}",
    shortcut = KetchCommands.AllDevices.shortcutLabel(),
    description = "$label, ${line.text}",
    onClick = onClick,
  ) {
    ProgressRing(progress) {
      Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
          .size(DevicePennantDefaults.XLarge)
          .background(KetchTheme.colors.surfaceSunken, KetchTheme.shapes.full),
      ) {
        PennantCluster(devices, ring = KetchTheme.colors.surfaceSunken)
      }
    }
  }
}

/**
 * [content] in a circle the size of a ringed rail pennant, with a ring around it filled to
 * [progress]; no ring while [progress] is `null`.
 */
@Composable
private fun ProgressRing(progress: Float?, content: @Composable () -> Unit) {
  val colors = KetchTheme.colors
  val track = colors.hairline
  val fill = colors.accent
  Box(
    contentAlignment = Alignment.Center,
    modifier = Modifier
      .size(DevicePennantDefaults.XLarge + (RingGap + RingWidth) * 2)
      .drawBehind {
        if (progress == null) return@drawBehind
        val stroke = RingWidth.toPx()
        val inset = stroke / 2
        val arcSize = Size(size.width - stroke, size.height - stroke)
        val topLeft = Offset(inset, inset)
        drawArc(track, 0f, FULL_TURN, false, topLeft, arcSize, style = Stroke(stroke))
        drawArc(
          color = fill,
          startAngle = -QUARTER_TURN,
          sweepAngle = FULL_TURN * progress.coerceIn(0f, 1f),
          useCenter = false,
          topLeft = topLeft,
          size = arcSize,
          style = Stroke(stroke, cap = StrokeCap.Round),
        )
      },
  ) {
    content()
  }
}

/**
 * How far everything downloading in [rows] has come: their downloaded bytes over their total
 * size, or `null` while nothing of known size downloads.
 */
internal fun aggregateProgress(rows: List<TaskRow>): Float? {
  var done = 0L
  var total = 0L
  for (row in rows) {
    val progress = (row.state as? DownloadState.Downloading)?.progress ?: continue
    if (progress.totalBytes <= 0) continue
    done += progress.downloadedBytes.coerceIn(0, progress.totalBytes)
    total += progress.totalBytes
  }
  return if (total == 0L) null else done.toFloat() / total
}

/**
 * A square cell of the rail around a device's pennant: it lightens on hover, sits on the
 * selected fill when [selected], and names itself in a tooltip after [tooltip] and [shortcut].
 * While [dropping], a drag is over it, on the soft accent with an accent outline while
 * [accepting] the drop.
 */
@Composable
private fun RailCell(
  selected: Boolean,
  tooltip: String,
  shortcut: String?,
  description: String,
  onClick: () -> Unit,
  modifier: Modifier = Modifier,
  onSecondaryClick: (() -> Unit)? = null,
  dropping: Boolean = false,
  accepting: Boolean = false,
  content: @Composable ColumnScope.() -> Unit,
) {
  val colors = KetchTheme.colors
  val shape = KetchTheme.shapes.md
  val interactions = remember { MutableInteractionSource() }
  val hovered by interactions.collectIsHoveredAsState()
  val focus = rememberFocusVisibility()
  val fill by animateColorAsState(
    targetValue = when {
      dropping && accepting -> colors.accentSoft
      selected -> colors.sidebarItemSelected
      hovered || dropping -> colors.sidebarItemHover
      else -> Color.Transparent
    },
    animationSpec = tween(KetchTheme.motion.micro),
  )
  val outline = if (dropping && accepting) {
    Modifier.border(DropOutline, colors.accent, shape)
  } else {
    Modifier
  }
  KetchTooltip(text = tooltip, shortcut = shortcut, enabled = !dropping, modifier = modifier) {
    Column(
      horizontalAlignment = Alignment.CenterHorizontally,
      verticalArrangement = Arrangement.Center,
      modifier = Modifier
        .focusRing(focus.visible, shape, colors.focusRing)
        .size(CellWidth)
        .background(fill, shape)
        .then(outline)
        .then(onSecondaryClick?.let { Modifier.onSecondaryPress(it).onMenuKey(it) } ?: Modifier)
        .semantics(mergeDescendants = true) { contentDescription = description }
        .trackFocusVisibility(focus)
        .selectable(
          selected = selected,
          interactionSource = interactions,
          indication = null,
          role = Role.Tab,
          onClick = onClick,
        ),
      content = content,
    )
  }
}

private val RailGlyph: Dp = 24.dp
private val CellWidth: Dp = 56.dp
private val IndicatorWidth: Dp = 56.dp
private val IndicatorHeight: Dp = 32.dp
private val BadgeSize: Dp = 16.dp
private val BadgeShift: Dp = 10.dp
private val BadgeRise: Dp = 4.dp
private val DividerWidth: Dp = 1.dp
private val DropOutline: Dp = 1.dp
private val RingGap: Dp = 2.dp
private val RingWidth: Dp = 2.dp
private const val FULL_TURN = 360f
private const val QUARTER_TURN = 90f
