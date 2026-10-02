package com.linroid.ketch.app.ui.devices

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.linroid.ketch.app.components.KetchEyebrow
import com.linroid.ketch.app.components.SpeedLimitPicker
import com.linroid.ketch.app.instance.DevicePresence
import com.linroid.ketch.app.state.AppState
import com.linroid.ketch.app.theme.KetchTheme
import com.linroid.ketch.app.ui.pulse.PopoverAlignment
import com.linroid.ketch.app.ui.pulse.PulsePopover
import com.linroid.ketch.app.ui.pulse.SpeedModeControl
import com.linroid.ketch.app.ui.pulse.SpeedModeOptions
import com.linroid.ketch.app.ui.pulse.SpeedModePillContent
import com.linroid.ketch.app.ui.pulse.SpeedModeView
import com.linroid.ketch.app.ui.pulse.SpeedPopoverWidth
import com.linroid.ketch.app.ui.pulse.SpeedSettingsButton
import com.linroid.ketch.app.ui.pulse.rememberPendingJob
import com.linroid.ketch.app.ui.pulse.speedModeView

/**
 * What [device]'s speed mode pill shows: its own mode and limit, whether or not it is the device
 * the app shows. Only the embedded device has a speed mode; others show their speed limit.
 */
@Composable
internal fun rememberDeviceSpeedView(state: AppState, device: DevicePresence): SpeedModeView {
  // The device's settings read the limit back after each change; its status only every 30 s.
  val cap = state.settingsFor(device.entry).download?.speedLimit ?: device.cap
  return speedModeView(state.speedModeOf(device), device.speedMode, cap)
}

/**
 * [device]'s speed mode pill on its card. Clicking it turns the Slow lane on or off where the
 * device has a speed mode, and otherwise opens its speed limit; the chevron opens the options.
 * The device the app shows gets the options of the Pulse bar's popover.
 */
@Composable
internal fun DeviceSpeedPill(
  state: AppState,
  device: DevicePresence,
  active: Boolean,
  modifier: Modifier = Modifier,
) {
  val view = rememberDeviceSpeedView(state, device)
  val switching = rememberPendingJob()
  var open by remember { mutableStateOf(false) }
  LaunchedEffect(device.entry) {
    if (view.controller == null) state.settingsFor(device.entry).loadDownload()
  }
  Box(modifier) {
    SpeedModePillContent(
      view = view,
      pending = switching.pending,
      onToggle = {
        if (view.controller == null) open = true else switching.track(state.toggleSlowLane(device))
      },
      onOptions = { open = !open },
    )
    // Lined up with the pill's end, which sits at the card's end, so it stays over the card.
    PulsePopover(
      expanded = open,
      onDismissRequest = { open = false },
      width = SpeedPopoverWidth,
      alignment = PopoverAlignment.End,
      title = "Speed of ${device.name}",
    ) {
      if (active) {
        SpeedModeOptions(state, onOpenSettings = { open = false })
      } else {
        DeviceSpeedOptions(state, device, view, onOpenSettings = { open = false })
      }
    }
  }
}

/**
 * The speed options of a device the app does not show: its mode where it has one, otherwise its
 * speed limit, and a link to its Speed settings for the rest.
 */
@Composable
private fun ColumnScope.DeviceSpeedOptions(
  state: AppState,
  device: DevicePresence,
  view: SpeedModeView,
  onOpenSettings: () -> Unit,
) {
  val spacing = KetchTheme.spacing
  val controller = view.controller
  val command = rememberPendingJob()
  if (controller != null) {
    SpeedModeControl(
      controller = controller,
      view = view,
      onSelect = { command.track(state.switchSpeedMode(controller, it)) },
    )
  } else {
    val settings = state.settingsFor(device.entry)
    KetchEyebrow("Speed limit", Modifier.padding(bottom = spacing.s2))
    SpeedLimitPicker(
      value = settings.download?.speedLimit ?: view.limit,
      onCommit = { state.setSpeedLimit(device.entry, it) },
      caption = settings.downloadError?.let { "Couldn't update ${device.name} · $it" }
        ?: "Applies to every download on ${device.name}.",
      enabled = settings.download != null,
    )
  }
  Spacer(Modifier.height(spacing.s3))
  SpeedSettingsButton(state, device.deviceId, onOpenSettings)
}
