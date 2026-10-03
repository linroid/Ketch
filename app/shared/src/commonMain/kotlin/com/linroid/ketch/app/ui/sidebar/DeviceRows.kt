package com.linroid.ketch.app.ui.sidebar

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.isAltPressed
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
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
import com.linroid.ketch.app.i18n.UiText
import com.linroid.ketch.app.i18n.joinText
import com.linroid.ketch.app.i18n.resolve
import com.linroid.ketch.app.i18n.speedText
import com.linroid.ketch.app.i18n.text
import com.linroid.ketch.app.i18n.verbatim
import com.linroid.ketch.app.icons.KetchIcon
import com.linroid.ketch.app.icons.KetchIconImage
import com.linroid.ketch.app.input.KetchCommands
import com.linroid.ketch.app.input.KeyboardPlatform
import com.linroid.ketch.app.input.altKeyName
import com.linroid.ketch.app.instance.DevicePresence
import com.linroid.ketch.app.state.AppState
import com.linroid.ketch.app.state.DeviceHealth
import com.linroid.ketch.app.state.isSlowLane
import com.linroid.ketch.app.theme.KetchDensity
import com.linroid.ketch.app.theme.KetchTheme
import com.linroid.ketch.app.ui.devices.systemName
import com.linroid.ketch.app.ui.shell.DeviceDrag
import com.linroid.ketch.app.ui.shell.DropHint
import com.linroid.ketch.app.ui.shell.deviceDropTarget
import com.linroid.ketch.app.ui.shell.dropHint
import com.linroid.ketch.app.ui.shell.onSecondaryPress
import ketch.app.shared.generated.resources.Res
import ketch.app.shared.generated.resources.shell_device_connecting
import ketch.app.shared.generated.resources.shell_device_idle
import ketch.app.shared.generated.resources.shell_device_needs_token
import ketch.app.shared.generated.resources.shell_device_off
import ketch.app.shared.generated.resources.shell_device_offline
import ketch.app.shared.generated.resources.shell_device_waiting
import ketch.app.shared.generated.resources.sidebar_add_device
import ketch.app.shared.generated.resources.sidebar_all_devices_tooltip
import ketch.app.shared.generated.resources.sidebar_device_menu
import org.jetbrains.compose.resources.stringResource

/**
 * What a device row says after the device's name.
 *
 * @property alert whether it reports a problem, which it shows in the failed color.
 * @property slowLane whether the device's downloads run in the Slow lane.
 */
internal data class DeviceLine(
  val text: UiText,
  val alert: Boolean = false,
  val slowLane: Boolean = false,
)

/**
 * The live line of [device]: its speed while it downloads, "2 waiting" while tasks only wait,
 * "Idle", or why it cannot be reached. A remote device the app does not keep connected reads
 * "Off", which is not a problem.
 */
internal fun deviceLine(device: DevicePresence): DeviceLine = when {
  device.health == DeviceHealth.Unauthorized ->
    DeviceLine(Res.string.shell_device_needs_token.text(), alert = true)
  !device.connected -> DeviceLine(Res.string.shell_device_off.text())
  device.health is DeviceHealth.Offline ->
    DeviceLine(Res.string.shell_device_offline.text(), alert = true)
  device.health == DeviceHealth.Connecting -> DeviceLine(Res.string.shell_device_connecting.text())
  device.counts.downloading > 0 -> DeviceLine(
    text = speedText(device.speed),
    slowLane = device.speedMode.isSlowLane,
  )
  device.counts.waiting > 0 ->
    DeviceLine(Res.plurals.shell_device_waiting.text(device.counts.waiting))
  else -> DeviceLine(Res.string.shell_device_idle.text())
}

/**
 * The live line of every device together: their total speed while any of them downloads, else
 * how many tasks wait, else "Idle". Devices that cannot be reached count for nothing.
 */
internal fun allDevicesLine(devices: List<DevicePresence>): DeviceLine {
  val online = devices.filter { it.connected && it.health.isOnline }
  val waiting = online.sumOf { it.counts.waiting }
  return when {
    online.any { it.counts.downloading > 0 } -> DeviceLine(speedText(online.sumOf { it.speed }))
    waiting > 0 -> DeviceLine(Res.plurals.shell_device_waiting.text(waiting))
    else -> DeviceLine(Res.string.shell_device_idle.text())
  }
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

/** Whether ⌥ (Alt) is held now, which makes a drop of rows on a device move them. */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
internal fun rememberAltHeld(): Boolean {
  val window = LocalWindowInfo.current
  val held by remember(window) { derivedStateOf { window.keyboardModifiers.isAltPressed } }
  return held
}

/**
 * The key that makes a drop of rows on a device move them, as this keyboard names it: ⌥ on
 * Apple keyboards, Alt elsewhere; `null` on touch screens.
 */
@Composable
internal fun moveKeyLabel(): String? =
  if (KetchTheme.density == KetchDensity.Comfortable) null else altKeyName(KeyboardPlatform.current)

/**
 * A device in the sidebar: its pennant with the health ring and unseen failures, its name and
 * its [live line][deviceLine]. Clicking it switches to the device, a right click or a long
 * press opens its [DeviceMenu], and links, files and rows dragged onto it are added or sent
 * there; the row then grows and says what a drop does. The [active] device's name is stronger.
 *
 * @param number the device's place in the list, whose `⌥⌘` digit switches to it.
 */
@Composable
internal fun SidebarDeviceRow(
  state: AppState,
  device: DevicePresence,
  number: Int,
  active: Boolean,
  modifier: Modifier = Modifier,
) {
  var drag by remember { mutableStateOf<DeviceDrag?>(null) }
  var menuOpen by remember { mutableStateOf(false) }
  val move = rememberAltHeld()
  val moveKey = moveKeyLabel()
  Box(modifier) {
    DeviceRowContent(
      device = device,
      active = active,
      hint = drag?.let { dropHint(it, device, move, moveKey) },
      shortcut = deviceShortcut(number),
      onClick = { state.switchInstance(device.entry) },
      onSecondaryClick = { menuOpen = true },
      modifier = Modifier.deviceDropTarget(state, device, onHover = { drag = it }),
    )
    DeviceMenu(
      state = state,
      device = device,
      number = number,
      shown = active,
      expanded = menuOpen,
      onDismissRequest = { menuOpen = false },
    )
  }
}

/**
 * The look of a [SidebarDeviceRow]. While [hint] is set a drag is over it: the row grows to
 * two lines, the name over what a drop does, on the soft accent when the drop is taken.
 *
 * @param shortcut chord that switches to the device, shown in the tooltip after its address.
 */
@Composable
internal fun DeviceRowContent(
  device: DevicePresence,
  active: Boolean,
  hint: DropHint?,
  onClick: () -> Unit,
  modifier: Modifier = Modifier,
  shortcut: String? = null,
  onSecondaryClick: () -> Unit = {},
) {
  val colors = KetchTheme.colors
  val line = deviceLine(device)
  SidebarRow(
    selected = active,
    onClick = onClick,
    onSecondaryClick = onSecondaryClick,
    tooltip = listOfNotNull(device.detail, device.systemName).map(::verbatim).joinText().resolve(),
    shortcut = shortcut,
    dropping = hint != null,
    accepting = hint?.accepts == true,
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
    Column(Modifier.weight(1f)) {
      Text(
        text = device.name.resolve(),
        style = KetchTheme.typography.label,
        fontWeight = if (active) FontWeight.SemiBold else null,
        color = if (active || hint != null) colors.textPrimary else colors.textSecondary,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
      )
      if (hint != null) {
        Text(
          text = hint.text.resolve(),
          style = KetchTheme.typography.caption,
          color = if (hint.accepts) colors.accentText else colors.textSecondary,
          maxLines = 1,
          overflow = TextOverflow.Ellipsis,
        )
      }
    }
    if (hint == null) {
      if (line.slowLane) {
        KetchIconImage(KetchIcon.SlowLane, size = LineGlyph, tint = colors.status.paused.color)
      }
      Text(
        text = line.text.resolve(),
        style = KetchTheme.typography.numeralS,
        color = if (line.alert) colors.status.failed.color else colors.textSecondary,
        maxLines = 1,
      )
    }
  }
}

/**
 * The "All devices" row above the devices, from two devices on: their pennants clustered, and
 * their total speed. Clicking it shows the downloads of every device at once.
 */
@Composable
internal fun AllDevicesRow(
  devices: List<DevicePresence>,
  selected: Boolean,
  onClick: () -> Unit,
  modifier: Modifier = Modifier,
) {
  val colors = KetchTheme.colors
  val line = allDevicesLine(devices)
  SidebarRow(
    selected = selected,
    onClick = onClick,
    tooltip = stringResource(Res.string.sidebar_all_devices_tooltip),
    shortcut = KetchCommands.AllDevices.shortcutLabel(),
    modifier = modifier,
    leading = { PennantCluster(devices) },
  ) {
    Text(
      text = KetchCommands.AllDevices.label.resolve(),
      style = KetchTheme.typography.label,
      fontWeight = if (selected) FontWeight.SemiBold else null,
      color = if (selected) colors.textPrimary else colors.textSecondary,
      maxLines = 1,
      overflow = TextOverflow.Ellipsis,
      modifier = Modifier.weight(1f),
    )
    Text(
      text = line.text.resolve(),
      style = KetchTheme.typography.numeralS,
      color = colors.textSecondary,
      maxLines = 1,
    )
  }
}

/**
 * Up to three of [devices]' smallest pennants in a 32 dp square: two corner to corner, three in
 * a triangle. A ring in [ring] sets each apart from the one it overlaps.
 */
@Composable
internal fun PennantCluster(
  devices: List<DevicePresence>,
  modifier: Modifier = Modifier,
  ring: Color = KetchTheme.colors.canvas,
) {
  val shown = devices.take(MAX_CLUSTERED)
  val places = when (shown.size) {
    1 -> listOf(ClusterCenter to ClusterCenter)
    2 -> listOf(0.dp to 0.dp, ClusterStep to ClusterStep)
    else -> listOf(ClusterCenter to 0.dp, 0.dp to ClusterStep, ClusterStep to ClusterStep)
  }
  Box(modifier.size(ClusterSize).clearAndSetSemantics {}) {
    // The first device stays in front, so its pennant reads whole.
    shown.zip(places).reversed().forEach { (device, place) ->
      DevicePennant(
        deviceId = device.deviceId,
        name = device.pennantName,
        size = DevicePennantDefaults.XSmall,
        modifier = Modifier
          .offset(x = place.first, y = place.second)
          .border(ClusterRing, ring, KetchTheme.shapes.full),
      )
    }
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
      text = stringResource(Res.string.sidebar_add_device),
      style = KetchTheme.typography.label,
      color = colors.textSecondary,
      maxLines = 1,
      modifier = Modifier.weight(1f),
    )
  }
}

/**
 * A device-sized sidebar row: [leading] centered in a pennant's room, then [content]. It
 * lightens on hover, like the destinations above it. While [dropping], a drag is over it: it
 * grows to two lines, on the soft accent with an accent outline while [accepting] the drop.
 */
@Composable
private fun SidebarRow(
  selected: Boolean,
  onClick: () -> Unit,
  modifier: Modifier = Modifier,
  onSecondaryClick: (() -> Unit)? = null,
  tooltip: String? = null,
  shortcut: String? = null,
  dropping: Boolean = false,
  accepting: Boolean = false,
  leading: @Composable () -> Unit,
  content: @Composable RowScope.() -> Unit,
) {
  val colors = KetchTheme.colors
  val spacing = KetchTheme.spacing
  val motion = KetchTheme.motion
  val shape = KetchTheme.shapes.sidebarItem
  val interactions = remember { MutableInteractionSource() }
  val hovered by interactions.collectIsHoveredAsState()
  val focus = rememberFocusVisibility()
  val fill by animateColorAsState(
    targetValue = when {
      dropping && accepting -> colors.accentSoft
      hovered || dropping -> colors.sidebarItemHover
      // Fades by alpha alone: Color.Transparent is transparent black, which flashes grey.
      else -> colors.sidebarItemHover.copy(alpha = 0f)
    },
    animationSpec = tween(motion.micro),
  )
  val outline = if (dropping && accepting) {
    Modifier.border(DropOutline, colors.accent, shape)
  } else {
    Modifier
  }
  val rowHeight = KetchTheme.density.deviceRow
  val menuLabel = stringResource(Res.string.sidebar_device_menu)
  val height by animateDpAsState(
    targetValue = if (dropping) maxOf(rowHeight, spacing.s12) else rowHeight,
    animationSpec = tween(motion.short, easing = motion.easeStandard),
  )
  KetchTooltip(
    text = tooltip.orEmpty(),
    shortcut = shortcut,
    enabled = tooltip != null && !dropping,
    modifier = modifier,
  ) {
    Row(
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(spacing.iconLabelGap),
      modifier = Modifier
        .fillMaxWidth()
        .padding(horizontal = spacing.s2, vertical = spacing.s0_5)
        .focusRing(focus.visible, shape, colors.focusRing)
        .height(height)
        .background(fill, shape)
        .then(outline)
        .then(onSecondaryClick?.let { Modifier.onSecondaryPress(it).onMenuKey(it) } ?: Modifier)
        .trackFocusVisibility(focus)
        .semantics { this.selected = selected }
        .combinedClickable(
          interactionSource = interactions,
          indication = null,
          role = Role.Tab,
          onLongClickLabel = onSecondaryClick?.let { menuLabel },
          // A long press opens the menu where there is no right click.
          onLongClick = onSecondaryClick,
          onClick = onClick,
        )
        .padding(start = spacing.s0_5, end = spacing.s2),
    ) {
      Box(Modifier.size(PennantRoom), contentAlignment = Alignment.Center) { leading() }
      content()
    }
  }
}

/** Runs [onMenu] for the keyboard's menu key or ⇧F10 while this element has the focus. */
internal fun Modifier.onMenuKey(onMenu: () -> Unit): Modifier = onKeyEvent { event ->
  val menu = event.key == Key.Menu || event.key == Key.F10 && event.isShiftPressed
  if (event.type == KeyEventType.KeyDown && menu) onMenu()
  menu
}

/** The chord that switches to the device listed [number]th, or `null` past the ninth. */
internal fun deviceShortcut(number: Int): String? =
  KetchCommands.deviceOrNull(number)?.shortcutLabel()

private const val MAX_CLUSTERED = 3

// A 24 dp pennant with its health ring.
private val PennantRoom: Dp = 32.dp
private val LineGlyph: Dp = 12.dp
private val DropOutline: Dp = 1.dp
private val ClusterSize: Dp = 32.dp
private val ClusterStep: Dp = 15.dp
private val ClusterCenter: Dp = 8.dp
private val ClusterRing: Dp = 1.5.dp
