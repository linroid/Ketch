package com.linroid.ketch.app.ui.sidebar

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.linroid.ketch.app.components.DevicePennant
import com.linroid.ketch.app.components.DevicePennantDefaults
import com.linroid.ketch.app.components.KetchTooltip
import com.linroid.ketch.app.components.focusRing
import com.linroid.ketch.app.components.rememberFocusVisibility
import com.linroid.ketch.app.components.trackFocusVisibility
import com.linroid.ketch.app.icons.KetchIcon
import com.linroid.ketch.app.icons.KetchIconImage
import com.linroid.ketch.app.instance.DevicePresence
import com.linroid.ketch.app.state.AppState
import com.linroid.ketch.app.state.DeviceHealth
import com.linroid.ketch.app.state.isSlowLane
import com.linroid.ketch.app.theme.KetchTheme
import com.linroid.ketch.app.ui.pulse.speedText

/**
 * What a device row says after the device's name.
 *
 * @property alert whether it reports a problem, which it shows in the failed color.
 * @property slowLane whether the device's downloads run in the Slow lane.
 */
internal data class DeviceLine(
  val text: String,
  val alert: Boolean = false,
  val slowLane: Boolean = false,
)

/**
 * The live line of [device]: its speed while it downloads, "2 waiting" while tasks only wait,
 * "Idle", or why it cannot be reached. A remote device the app does not keep connected reads
 * "Off", which is not a problem.
 */
internal fun deviceLine(device: DevicePresence): DeviceLine = when {
  device.health == DeviceHealth.Unauthorized -> DeviceLine("Needs token", alert = true)
  !device.connected -> DeviceLine("Off")
  device.health is DeviceHealth.Offline -> DeviceLine("Offline", alert = true)
  device.health == DeviceHealth.Connecting -> DeviceLine("Connecting")
  device.counts.downloading > 0 -> DeviceLine(
    text = speedText(device.speed).toString(),
    slowLane = device.speedMode.isSlowLane,
  )
  device.counts.waiting > 0 -> DeviceLine("${device.counts.waiting} waiting")
  else -> DeviceLine("Idle")
}

/**
 * The health ring of [device]'s pennant, or `null` for none: a remote device the app does not
 * keep connected has no health to show.
 */
internal fun pennantHealth(device: DevicePresence): DeviceHealth? =
  device.health.takeIf { device.connected }

/** What a pennant's monogram is made from: the host name of the embedded device, not "This Mac". */
internal val DevicePresence.pennantName: String get() = entry.label

/** Every configured device and what it is doing, in the order of the app's devices. */
@Composable
internal fun rememberDevices(state: AppState): List<DevicePresence> {
  val presence = remember(state) { state.instanceManager.presence }
  return presence.collectAsState().value
}

/**
 * A device in the sidebar: its pennant with the health ring and unseen failures, its name and
 * its [live line][deviceLine]. Clicking it switches to the device; the [active] one's name is
 * stronger.
 */
@Composable
internal fun SidebarDeviceRow(
  device: DevicePresence,
  active: Boolean,
  onClick: () -> Unit,
  modifier: Modifier = Modifier,
) {
  val line = deviceLine(device)
  SidebarRow(
    selected = active,
    onClick = onClick,
    tooltip = device.detail,
    modifier = modifier,
    leading = {
      DevicePennant(
        deviceId = device.deviceId,
        name = device.pennantName,
        size = DevicePennantDefaults.Medium,
        health = pennantHealth(device),
        failures = device.unseenFailures,
      )
    },
  ) {
    val colors = KetchTheme.colors
    Text(
      text = device.name,
      style = KetchTheme.typography.label,
      fontWeight = if (active) FontWeight.SemiBold else null,
      color = if (active) colors.textPrimary else colors.textSecondary,
      maxLines = 1,
      overflow = TextOverflow.Ellipsis,
      modifier = Modifier.weight(1f),
    )
    if (line.slowLane) {
      KetchIconImage(KetchIcon.SlowLane, size = LineGlyph, tint = colors.status.paused.color)
    }
    Text(
      text = line.text,
      style = KetchTheme.typography.numeralS,
      color = if (line.alert) colors.status.failed.color else colors.textSecondary,
      maxLines = 1,
    )
  }
}

/** The "Add device" row under the devices, which asks for a device to connect to. */
@Composable
internal fun AddDeviceRow(onClick: () -> Unit, modifier: Modifier = Modifier) {
  val colors = KetchTheme.colors
  SidebarRow(
    selected = false,
    onClick = onClick,
    modifier = modifier,
    leading = {
      KetchIconImage(
        icon = KetchIcon.Plus,
        size = KetchTheme.density.controlGlyph,
        tint = colors.textSecondary,
      )
    },
  ) {
    Text(
      text = "Add device",
      style = KetchTheme.typography.label,
      color = colors.textSecondary,
      maxLines = 1,
      modifier = Modifier.weight(1f),
    )
  }
}

/**
 * A device-sized sidebar row: [leading] centered in a pennant's room, then [content]. It
 * lightens on hover, like the destinations above it.
 */
@Composable
private fun SidebarRow(
  selected: Boolean,
  onClick: () -> Unit,
  modifier: Modifier = Modifier,
  tooltip: String? = null,
  leading: @Composable () -> Unit,
  content: @Composable RowScope.() -> Unit,
) {
  val colors = KetchTheme.colors
  val spacing = KetchTheme.spacing
  val shape = KetchTheme.shapes.sidebarItem
  val interactions = remember { MutableInteractionSource() }
  val hovered by interactions.collectIsHoveredAsState()
  val focus = rememberFocusVisibility()
  val fill by animateColorAsState(
    targetValue = if (hovered) colors.sidebarItemHover else Color.Transparent,
    animationSpec = tween(KetchTheme.motion.micro),
  )
  KetchTooltip(text = tooltip.orEmpty(), enabled = tooltip != null, modifier = modifier) {
    Row(
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(spacing.iconLabelGap),
      modifier = Modifier
        .fillMaxWidth()
        .padding(horizontal = spacing.s2, vertical = spacing.s0_5)
        .focusRing(focus.visible, shape, colors.focusRing)
        .height(KetchTheme.density.deviceRow)
        .background(fill, shape)
        .trackFocusVisibility(focus)
        .selectable(
          selected = selected,
          interactionSource = interactions,
          indication = null,
          role = Role.Tab,
          onClick = onClick,
        )
        .padding(start = spacing.s0_5, end = spacing.s2),
    ) {
      Box(Modifier.size(PennantRoom), contentAlignment = Alignment.Center) { leading() }
      content()
    }
  }
}

// A 24 dp pennant with its health ring.
private val PennantRoom: Dp = 32.dp
private val LineGlyph: Dp = 12.dp
