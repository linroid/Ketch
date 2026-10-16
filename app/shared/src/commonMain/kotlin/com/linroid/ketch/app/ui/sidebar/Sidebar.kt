package com.linroid.ketch.app.ui.sidebar

import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.linroid.ketch.app.components.KetchBadge
import com.linroid.ketch.app.components.KetchBadgeTone
import com.linroid.ketch.app.components.KetchButtonSize
import com.linroid.ketch.app.components.KetchCountBadge
import com.linroid.ketch.app.components.KetchDot
import com.linroid.ketch.app.components.KetchEyebrow
import com.linroid.ketch.app.components.KetchIconButton
import com.linroid.ketch.app.components.KetchLogoTile
import com.linroid.ketch.app.components.KetchLogoTileDefaults
import com.linroid.ketch.app.components.KetchSidebarItem
import com.linroid.ketch.app.components.KetchTooltip
import com.linroid.ketch.app.i18n.resolve
import com.linroid.ketch.app.icons.KetchIcon
import com.linroid.ketch.app.input.KetchCommands
import com.linroid.ketch.app.input.KeyboardPlatform
import com.linroid.ketch.app.instance.DevicePresence
import com.linroid.ketch.app.instance.DeviceScope
import com.linroid.ketch.app.state.AppDestination
import com.linroid.ketch.app.state.AppState
import com.linroid.ketch.app.state.deviceId
import com.linroid.ketch.app.theme.KetchTheme
import com.linroid.ketch.app.ui.shell.AppearanceToggle
import com.linroid.ketch.app.ui.shell.NavBadge
import com.linroid.ketch.app.ui.shell.ShellState
import com.linroid.ketch.app.ui.shell.navBadges
import ketch.app.shared.generated.resources.Res
import ketch.app.shared.generated.resources.shell_devices
import ketch.app.shared.generated.resources.shell_hide_sidebar
import ketch.app.shared.generated.resources.shell_settings
import org.jetbrains.compose.resources.stringResource

/**
 * The sidebar of wide windows, transparent over the canvas wash: the title zone with the
 * sidebar toggle and the [AppearanceToggle] (beside the traffic lights on macOS, after the Ketch
 * mark on the web), the destinations, the DEVICES from two devices on (All devices, then each
 * device with its health and live line, its menu and its drops), and Settings at the bottom.
 *
 * The destination [shell] shows sits on the selected pill, or Settings while it shows; the toggle
 * collapses the sidebar to the rail.
 */
@Composable
internal fun Sidebar(
  shell: ShellState,
  destinations: List<AppDestination>,
  modifier: Modifier = Modifier,
) {
  val state = shell.app
  val spacing = KetchTheme.spacing
  val pulse by state.pulse.state.collectAsState()
  val active by state.activeInstance.collectAsState()
  val scope by state.deviceScope.collectAsState()
  val devices = rememberDevices(state)
  val badges = navBadges(state)
  Column(modifier.width(spacing.sidebarWidth).fillMaxHeight()) {
    TitleZone(state, onToggleSidebar = { shell.toggleSidebar() })
    for (entry in destinations) {
      val downloads = entry == AppDestination.Downloads
      val badge = badges[entry]
      val label = entry.label.resolve()
      KetchTooltip(text = label, shortcut = entry.command.shortcutLabel()) {
        KetchSidebarItem(
          label = label,
          icon = entry.icon,
          selected = entry == shell.destination && !shell.settingsOpen,
          onClick = { shell.show(entry) },
          trailing = when {
            downloads -> {
              { DownloadsMarks(pulse.counts.downloading, pulse.failures > 0) }
            }
            badge != null -> {
              { WaitingMark(badge) }
            }
            else -> null
          },
        )
      }
    }
    // The devices take the room left above Settings, and scroll in a short window. A lone
    // device needs no list; devices are added from the Devices page.
    if (devices.size < DeviceScope.MIN_DEVICES) {
      Spacer(Modifier.weight(1f))
    } else {
      DeviceSection(state, devices, scope, active?.deviceId, Modifier.weight(1f))
    }
    val settings = stringResource(Res.string.shell_settings)
    KetchTooltip(text = settings, shortcut = KetchCommands.Settings.shortcutLabel()) {
      KetchSidebarItem(
        label = settings,
        icon = KetchIcon.Settings,
        selected = shell.settingsOpen,
        onClick = { state.openSettings() },
        modifier = Modifier.padding(bottom = spacing.s2),
      )
    }
  }
}

/**
 * The DEVICES of the sidebar, shown from two devices on: All devices, then each device with its
 * health and live line, its menu and its drops.
 */
@Composable
private fun DeviceSection(
  state: AppState,
  devices: List<DevicePresence>,
  scope: DeviceScope,
  activeId: String?,
  modifier: Modifier = Modifier,
) {
  val spacing = KetchTheme.spacing
  val devicesHover = remember { MutableInteractionSource() }
  val hovered by devicesHover.collectIsHoveredAsState()
  Column(modifier.hoverable(devicesHover)) {
    DevicesEyebrow(showShortcut = hovered)
    Column(
      Modifier
        .weight(1f, fill = false)
        .heightIn(max = (KetchTheme.density.deviceRow + spacing.s1) * VISIBLE_DEVICE_ROWS)
        .verticalScroll(rememberScrollState()),
    ) {
      val all = scope == DeviceScope.All
      AllDevicesRow(devices = devices, selected = all, onClick = { state.showAllDevices() })
      devices.forEachIndexed { index, device ->
        key(device.deviceId) {
          SidebarDeviceRow(
            state = state,
            device = device,
            number = index + 1,
            active = !all && device.deviceId == activeId,
          )
        }
      }
    }
  }
}

/** The DEVICES label over the device rows, naming the switcher's chord while [showShortcut]. */
@Composable
private fun DevicesEyebrow(showShortcut: Boolean) {
  val spacing = KetchTheme.spacing
  val colors = KetchTheme.colors
  Row(
    verticalAlignment = Alignment.Bottom,
    modifier = Modifier
      .fillMaxWidth()
      .padding(start = spacing.s4, end = spacing.s4, top = spacing.s6, bottom = spacing.s1),
  ) {
    KetchEyebrow(
      text = stringResource(Res.string.shell_devices),
      modifier = Modifier.weight(1f),
      color = colors.textSecondary,
    )
    val shortcut = KetchCommands.SwitchDevice.shortcutLabel()
    if (shortcut != null) {
      Text(
        text = shortcut,
        style = KetchTheme.typography.numeralS,
        color = colors.textTertiary,
        modifier = Modifier.alpha(if (showShortcut) 1f else 0f).clearAndSetSemantics {},
      )
    }
  }
}

/** The downloading count of the Downloads item, with a failed dot when anything failed. */
@Composable
private fun DownloadsMarks(downloading: Int, failed: Boolean) {
  Row(
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(KetchTheme.spacing.s2),
  ) {
    if (failed) KetchDot(KetchTheme.colors.status.failed.color, size = FailedDotSize)
    if (downloading > 0) KetchCountBadge(downloading)
  }
}

/** The count of a destination's requests that wait for the user, such as Discover's. */
@Composable
private fun WaitingMark(badge: NavBadge) {
  KetchBadge(
    text = badge.count.toString(),
    tone = KetchBadgeTone.Accent,
    modifier = Modifier.clearAndSetSemantics { contentDescription = badge.countDescription },
  )
}

/**
 * The top of the sidebar, as tall as the content card's inset and page header. On macOS the
 * traffic lights sit at its start, the sidebar toggle follows them on their row and the
 * appearance toggle ends it; on the web the Ketch mark leads; elsewhere the buttons line up with
 * the page header. Its empty space is the title bar the desktop app lets the window be dragged by.
 */
@Composable
private fun TitleZone(state: AppState, onToggleSidebar: () -> Unit) {
  val spacing = KetchTheme.spacing
  val chrome = KetchTheme.windowChrome
  val toggle = @Composable {
    KetchIconButton(
      icon = KetchIcon.Sidebar,
      onClick = onToggleSidebar,
      size = KetchButtonSize.Small,
      contentDescription = stringResource(Res.string.shell_hide_sidebar),
      shortcut = KetchCommands.ToggleSidebar.shortcutLabel(),
    )
  }
  val appearance = @Composable { AppearanceToggle(state.appSettings) }
  Box(Modifier.fillMaxWidth().height(spacing.cardInset + spacing.pageHeaderHeight)) {
    when {
      chrome.top > 0.dp -> Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
          .fillMaxWidth()
          .height(chrome.top)
          .padding(start = chrome.leading, end = spacing.s2),
      ) {
        toggle()
        Spacer(Modifier.weight(1f))
        appearance()
      }
      KeyboardPlatform.current.isWeb -> Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
          .fillMaxWidth()
          .padding(top = spacing.cardInset)
          .fillMaxHeight()
          .padding(start = spacing.s4, end = spacing.s3),
      ) {
        KetchLogoTile(size = KetchLogoTileDefaults.Sidebar)
        Text(
          text = "Ketch",
          style = KetchTheme.typography.label,
          fontWeight = FontWeight.SemiBold,
          color = KetchTheme.colors.textPrimary,
          modifier = Modifier.padding(start = spacing.s2).weight(1f),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(spacing.s0_5)) {
          toggle()
          appearance()
        }
      }
      else -> Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
          .fillMaxWidth()
          .padding(top = spacing.cardInset)
          .fillMaxHeight()
          .padding(start = spacing.s2, end = spacing.s2),
      ) {
        toggle()
        Spacer(Modifier.weight(1f))
        appearance()
      }
    }
  }
}

/** Device rows shown before the list scrolls. */
private const val VISIBLE_DEVICE_ROWS = 6

private val FailedDotSize = 6.dp
