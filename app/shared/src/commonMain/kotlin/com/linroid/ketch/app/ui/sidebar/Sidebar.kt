package com.linroid.ketch.app.ui.sidebar

import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.linroid.ketch.app.components.KetchButtonSize
import com.linroid.ketch.app.components.KetchCountBadge
import com.linroid.ketch.app.components.KetchDot
import com.linroid.ketch.app.components.KetchIconButton
import com.linroid.ketch.app.components.KetchLogoTile
import com.linroid.ketch.app.components.KetchLogoTileDefaults
import com.linroid.ketch.app.components.KetchSidebarItem
import com.linroid.ketch.app.components.KetchTooltip
import com.linroid.ketch.app.icons.KetchIcon
import com.linroid.ketch.app.input.KetchCommands
import com.linroid.ketch.app.input.KeyboardPlatform
import com.linroid.ketch.app.instance.DeviceScope
import com.linroid.ketch.app.state.AppDestination
import com.linroid.ketch.app.state.AppState
import com.linroid.ketch.app.state.deviceId
import com.linroid.ketch.app.theme.KetchTheme
import com.linroid.ketch.app.theme.eyebrowText
import com.linroid.ketch.app.ui.shell.AddButton
import com.linroid.ketch.app.ui.shell.AddButtonDefaults

/**
 * The sidebar of wide windows, transparent over the canvas wash: the title zone with the
 * sidebar toggle and the ⊕ add button (beside the traffic lights on macOS, after the Ketch mark
 * on the web), the destinations, the DEVICES (All devices from two on, then each device with its
 * health and live line, its menu and its drops), and Settings at the bottom.
 *
 * @param destination the destination shown, which sits on the selected pill.
 * @param settingsSelected whether Settings shows, which then takes the pill instead.
 * @param onToggleSidebar collapses the sidebar to the rail.
 * @param onAddClipboardLink adds the link on the clipboard at once.
 */
@Composable
internal fun Sidebar(
  state: AppState,
  destinations: List<AppDestination>,
  destination: AppDestination,
  settingsSelected: Boolean,
  onSelect: (AppDestination) -> Unit,
  onOpenSettings: () -> Unit,
  onToggleSidebar: () -> Unit,
  onAddClipboardLink: () -> Unit,
  modifier: Modifier = Modifier,
) {
  val spacing = KetchTheme.spacing
  val pulse by state.pulse.state.collectAsState()
  val active by state.activeInstance.collectAsState()
  val scope by state.deviceScope.collectAsState()
  val devices = rememberDevices(state)
  Column(modifier.width(spacing.sidebarWidth).fillMaxHeight()) {
    TitleZone(
      onToggleSidebar = onToggleSidebar,
      onAdd = { state.openIntake() },
      onAddClipboardLink = onAddClipboardLink,
    )
    for (entry in destinations) {
      val downloads = entry == AppDestination.Downloads
      KetchTooltip(text = entry.label, shortcut = entry.command.shortcutLabel()) {
        KetchSidebarItem(
          label = entry.label,
          icon = entry.icon,
          selected = entry == destination && !settingsSelected,
          onClick = { onSelect(entry) },
          trailing = if (downloads) {
            { DownloadsMarks(pulse.counts.downloading, pulse.failures > 0) }
          } else {
            null
          },
        )
      }
    }
    // The devices take the room left above Settings, and scroll in a short window.
    val devicesHover = remember { MutableInteractionSource() }
    val hovered by devicesHover.collectIsHoveredAsState()
    Column(Modifier.weight(1f).hoverable(devicesHover)) {
      DevicesEyebrow(showShortcut = hovered)
      Column(
        Modifier
          .weight(1f, fill = false)
          .heightIn(max = (KetchTheme.density.deviceRow + spacing.s1) * VISIBLE_DEVICE_ROWS)
          .verticalScroll(rememberScrollState()),
      ) {
        val all = scope == DeviceScope.All
        if (devices.size >= DeviceScope.MIN_DEVICES) {
          AllDevicesRow(devices = devices, selected = all, onClick = { state.showAllDevices() })
        }
        devices.forEachIndexed { index, device ->
          key(device.deviceId) {
            SidebarDeviceRow(
              state = state,
              device = device,
              number = index + 1,
              active = !all && device.deviceId == active?.deviceId,
            )
          }
        }
      }
      AddDeviceRow(onClick = { state.showAddRemoteDialog = true })
    }
    KetchTooltip(text = "Settings", shortcut = KetchCommands.Settings.shortcutLabel()) {
      KetchSidebarItem(
        label = "Settings",
        icon = KetchIcon.Settings,
        selected = settingsSelected,
        onClick = onOpenSettings,
        modifier = Modifier.padding(bottom = spacing.s2),
      )
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
    Text(
      text = eyebrowText("Devices"),
      style = KetchTheme.typography.eyebrow,
      color = colors.textSecondary,
      modifier = Modifier.weight(1f),
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

/**
 * The top of the sidebar, as tall as the content card's inset and page header. On macOS the
 * traffic lights sit at its start and the buttons follow them on their row; on the web the Ketch
 * mark leads; elsewhere the buttons line up with the page header. Its empty space is the title
 * bar the desktop app lets the window be dragged by.
 */
@Composable
private fun TitleZone(
  onToggleSidebar: () -> Unit,
  onAdd: () -> Unit,
  onAddClipboardLink: () -> Unit,
) {
  val spacing = KetchTheme.spacing
  val chrome = KetchTheme.windowChrome
  val buttons = @Composable {
    KetchIconButton(
      icon = KetchIcon.Sidebar,
      onClick = onToggleSidebar,
      size = KetchButtonSize.Small,
      contentDescription = "Hide sidebar",
      shortcut = KetchCommands.ToggleSidebar.shortcutLabel(),
    )
    AddButton(
      size = AddButtonDefaults.TitleZone,
      onClick = onAdd,
      onAddClipboardLink = onAddClipboardLink,
    )
  }
  Box(Modifier.fillMaxWidth().height(spacing.cardInset + spacing.pageHeaderHeight)) {
    when {
      chrome.top > 0.dp -> Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(spacing.s0_5),
        modifier = Modifier.height(chrome.top).padding(start = chrome.leading + spacing.s3),
      ) { buttons() }
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
        Row(horizontalArrangement = Arrangement.spacedBy(spacing.s0_5)) { buttons() }
      }
      else -> Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(spacing.s0_5),
        modifier = Modifier
          .padding(top = spacing.cardInset)
          .fillMaxHeight()
          .padding(start = spacing.s3),
      ) { buttons() }
    }
  }
}

/** Device rows shown before the list scrolls. */
private const val VISIBLE_DEVICE_ROWS = 6

private val FailedDotSize = 6.dp
