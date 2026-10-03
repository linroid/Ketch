package com.linroid.ketch.app.ui.devices

import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.linroid.ketch.app.i18n.resolve
import com.linroid.ketch.app.instance.DevicePresence
import com.linroid.ketch.app.state.AppState
import com.linroid.ketch.app.ui.pulse.PopoverAlignment
import com.linroid.ketch.app.ui.pulse.PulsePopover
import com.linroid.ketch.app.ui.pulse.SpeedModeOptions
import com.linroid.ketch.app.ui.pulse.SpeedModePillContent
import com.linroid.ketch.app.ui.pulse.SpeedModeView
import com.linroid.ketch.app.ui.pulse.SpeedPopoverWidth
import com.linroid.ketch.app.ui.pulse.rememberPendingJob
import com.linroid.ketch.app.ui.pulse.speedModeView
import ketch.app.shared.generated.resources.Res
import ketch.app.shared.generated.resources.device_speed_of
import org.jetbrains.compose.resources.stringResource

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
 * device has a speed mode, and otherwise opens its speed limit; the chevron opens the options of
 * the Pulse bar's popover, for this device whether or not the app shows it.
 */
@Composable
internal fun DeviceSpeedPill(
  state: AppState,
  device: DevicePresence,
  modifier: Modifier = Modifier,
) {
  val view = rememberDeviceSpeedView(state, device)
  val switching = rememberPendingJob()
  var open by remember { mutableStateOf(false) }
  LaunchedEffect(device.entry) { state.settingsFor(device.entry).loadDownload() }
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
      title = stringResource(Res.string.device_speed_of, device.name.resolve()),
    ) {
      SpeedModeOptions(state, device.entry, view, onOpenSettings = { open = false })
    }
  }
}
