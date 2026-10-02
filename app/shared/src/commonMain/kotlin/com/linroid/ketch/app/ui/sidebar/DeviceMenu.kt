package com.linroid.ketch.app.ui.sidebar

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.linroid.ketch.api.SpeedLimit
import com.linroid.ketch.app.components.KetchMenu
import com.linroid.ketch.app.components.KetchMenuScope
import com.linroid.ketch.app.components.SpeedLimitPickerPresets
import com.linroid.ketch.app.icons.KetchIcon
import com.linroid.ketch.app.instance.DevicePresence
import com.linroid.ketch.app.instance.RemoteInstance
import com.linroid.ketch.app.state.AppState
import com.linroid.ketch.app.state.formatSpeedLimit
import com.linroid.ketch.app.state.isSlowLane
import com.linroid.ketch.app.ui.devices.RemoveDeviceDialog
import com.linroid.ketch.app.ui.devices.RenameDeviceDialog
import com.linroid.ketch.app.ui.devices.openDeviceSettings
import com.linroid.ketch.app.ui.devices.setSpeedLimit
import com.linroid.ketch.app.ui.devices.speedModeOf
import com.linroid.ketch.app.ui.devices.switchSpeedMode
import com.linroid.ketch.app.ui.pulse.slowLaneLimit
import com.linroid.ketch.config.SpeedLimitMode

/**
 * The menu of [device], opened by a right click on its sidebar row or rail pennant: switching
 * to it, its queue commands, its speed, its settings, renaming and, for a remote device,
 * whether the app stays connected and removing it. Rename and Remove ask in a dialog.
 *
 * @param number the device's place in the list, whose `⌥⌘` digit switches to it.
 * @param shown whether the device shows alone already.
 */
@Composable
internal fun DeviceMenu(
  state: AppState,
  device: DevicePresence,
  number: Int,
  shown: Boolean,
  expanded: Boolean,
  onDismissRequest: () -> Unit,
) {
  var renaming by remember { mutableStateOf(false) }
  var removing by remember { mutableStateOf(false) }
  LaunchedEffect(expanded, device.entry) {
    // The speed items read the device's limit from its settings.
    if (expanded && state.speedModeOf(device) == null) {
      state.settingsFor(device.entry).loadDownload()
    }
  }
  KetchMenu(expanded = expanded, onDismissRequest = onDismissRequest, title = device.name) {
    deviceCommands(
      state = state,
      device = device,
      number = number,
      shown = shown,
      onRename = { renaming = true },
      onRemove = { removing = true },
    )
  }
  if (renaming) RenameDeviceDialog(state, device, onDismiss = { renaming = false })
  if (removing) RemoveDeviceDialog(state, device, onDismiss = { removing = false })
}

/** The entries of a [DeviceMenu]; see there. */
internal fun KetchMenuScope.deviceCommands(
  state: AppState,
  device: DevicePresence,
  number: Int,
  shown: Boolean,
  onRename: () -> Unit,
  onRemove: () -> Unit,
) {
  val entry = device.entry
  val online = device.connected && device.health.isOnline
  val counts = device.counts
  item(
    label = "Switch to ${device.name}",
    onClick = { state.switchInstance(entry) },
    icon = KetchIcon.Devices,
    shortcut = deviceShortcut(number),
    enabled = !shown,
  )
  divider()
  item(
    label = "Pause all here",
    onClick = { state.pauseAll(listOf(entry)) },
    icon = KetchIcon.Pause,
    enabled = online && counts.downloading + counts.waiting > 0,
  )
  item(
    label = "Resume all here",
    onClick = { state.resumeAll(listOf(entry)) },
    icon = KetchIcon.Play,
    enabled = online && counts.paused > 0,
  )
  item(
    label = "Retry failed here" + if (device.failures > 0) " (${device.failures})" else "",
    onClick = { state.retryFailed(listOf(entry)) },
    icon = KetchIcon.Retry,
    enabled = online && device.failures > 0,
  )
  submenu(label = "Speed", icon = KetchIcon.Speed, enabled = online) {
    speedEntries(state, device)
  }
  divider()
  item(
    label = "Settings for this device…",
    onClick = { state.openDeviceSettings(entry) },
    icon = KetchIcon.Settings,
  )
  item(label = "Rename…", onClick = onRename)
  val remote = entry as? RemoteInstance ?: return
  item(
    label = "Stay connected",
    onClick = { state.instanceManager.setWatched(remote, !device.watched) },
    checked = device.watched,
  )
  divider()
  item(label = "Remove…", onClick = onRemove, icon = KetchIcon.Trash, destructive = true)
}

/**
 * Full speed and the Slow lane for a device with a speed mode; the speed limits otherwise, as
 * remote devices only take a limit.
 */
private fun KetchMenuScope.speedEntries(state: AppState, device: DevicePresence) {
  val controller = state.speedModeOf(device)
  if (controller != null) {
    val slow = controller.mode.value.isSlowLane
    item(
      label = "Full speed",
      onClick = { state.switchSpeedMode(controller, SpeedLimitMode.Full) },
      checked = !slow,
    )
    item(
      label = "Slow lane · ${formatSpeedLimit(controller.slowLaneLimit)}",
      onClick = { state.switchSpeedMode(controller, SpeedLimitMode.SlowLane) },
      checked = slow,
    )
    return
  }
  val settings = state.settingsFor(device.entry)
  val current = settings.download?.speedLimit ?: device.cap
  for (limit in SpeedLimitPickerPresets) {
    item(
      label = speedLimitLabel(limit),
      onClick = { state.setSpeedLimit(device.entry, limit) },
      checked = limit == current,
      enabled = settings.download != null,
    )
  }
}

/** A speed limit as the Speed menu lists it: "No limit" or "2 MB/s". */
internal fun speedLimitLabel(limit: SpeedLimit): String =
  if (limit.isUnlimited) "No limit" else formatSpeedLimit(limit)
