package com.linroid.ketch.app.ui.shell

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
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
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
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
import com.linroid.ketch.app.state.AppDestination
import com.linroid.ketch.app.state.AppState
import com.linroid.ketch.app.state.deviceId
import com.linroid.ketch.app.theme.KetchTheme
import com.linroid.ketch.app.ui.sidebar.deviceLine
import com.linroid.ketch.app.ui.sidebar.pennantHealth
import com.linroid.ketch.app.ui.sidebar.pennantName
import com.linroid.ketch.app.ui.sidebar.rememberDevices

/**
 * The 72 dp rail of medium windows, and of wide ones with the sidebar collapsed: the ⊕ add
 * button, the destinations with Downloads' downloading count, a pennant per device (tapping one
 * switches to it), and Settings at the bottom. On macOS it starts below the traffic lights.
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
      for (device in devices) {
        key(device.deviceId) {
          val line = deviceLine(device)
          RailCell(
            selected = device.deviceId == active?.deviceId,
            tooltip = device.name,
            shortcut = line.text,
            description = "${device.name}, ${line.text}",
            onClick = { state.switchInstance(device.entry) },
          ) {
            DevicePennant(
              deviceId = device.deviceId,
              name = device.pennantName,
              size = DevicePennantDefaults.XLarge,
              health = pennantHealth(device),
              failures = device.unseenFailures,
            )
          }
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
        .semantics(mergeDescendants = true) { contentDescription = label }
        .trackFocusVisibility(focus)
        .selectable(
          selected = selected,
          interactionSource = interactions,
          indication = null,
          role = Role.Tab,
          onClick = onClick,
        )
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
            RailBadge(badge, Modifier.align(Alignment.TopEnd).offset(x = BadgeShift, y = -BadgeRise))
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
 * A square cell of the rail around a device's pennant: it lightens on hover, sits on the
 * selected fill when [selected], and names itself in a tooltip after [tooltip] and [shortcut].
 */
@Composable
private fun RailCell(
  selected: Boolean,
  tooltip: String,
  shortcut: String?,
  description: String,
  onClick: () -> Unit,
  modifier: Modifier = Modifier,
  content: @Composable ColumnScope.() -> Unit,
) {
  val colors = KetchTheme.colors
  val shape = KetchTheme.shapes.md
  val interactions = remember { MutableInteractionSource() }
  val hovered by interactions.collectIsHoveredAsState()
  val focus = rememberFocusVisibility()
  val fill by animateColorAsState(
    targetValue = when {
      selected -> colors.sidebarItemSelected
      hovered -> colors.sidebarItemHover
      else -> Color.Transparent
    },
    animationSpec = tween(KetchTheme.motion.micro),
  )
  KetchTooltip(text = tooltip, shortcut = shortcut, modifier = modifier) {
    Column(
      horizontalAlignment = Alignment.CenterHorizontally,
      verticalArrangement = Arrangement.Center,
      modifier = Modifier
        .focusRing(focus.visible, shape, colors.focusRing)
        .size(CellWidth)
        .background(fill, shape)
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
