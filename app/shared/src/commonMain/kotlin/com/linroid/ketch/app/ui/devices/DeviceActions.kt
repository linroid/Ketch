package com.linroid.ketch.app.ui.devices

import com.linroid.ketch.api.log.KetchLogger
import com.linroid.ketch.api.log.describeCauses
import com.linroid.ketch.app.feedback.MessageLevel
import com.linroid.ketch.app.instance.DevicePresence
import com.linroid.ketch.app.instance.InstanceEntry
import com.linroid.ketch.app.instance.RemoteInstance
import com.linroid.ketch.app.state.AppState
import com.linroid.ketch.app.state.LOCAL_DEVICE_ID
import com.linroid.ketch.app.state.SettingsTarget
import com.linroid.ketch.app.state.SpeedModeController
import com.linroid.ketch.app.state.StatusFilter
import com.linroid.ketch.app.state.catchingUnlessCancelled
import com.linroid.ketch.app.state.deviceId
import com.linroid.ketch.app.state.isSlowLane
import com.linroid.ketch.app.ui.pulse.speedModeFor
import com.linroid.ketch.app.ui.pulse.switchSpeedMode
import com.linroid.ketch.app.ui.pulse.toggleSlowLane
import com.linroid.ketch.config.SpeedLimitMode
import kotlinx.coroutines.Job

private val log = KetchLogger("DevicesPage")

/** Switches to [entry] and shows its downloads on the [filter] tab, in one step. */
internal fun AppState.showDeviceTab(entry: InstanceEntry, filter: StatusFilter = StatusFilter.All) {
  switchInstance(entry)
  showDownloads(filter)
}

/** Runs [action] on [entry] without switching to it. */
internal fun AppState.runNextAction(entry: InstanceEntry, action: NextAction): Job = when (action) {
  is NextAction.RetryFailed -> retryFailed(listOf(entry))
  NextAction.PauseAll -> pauseAll(listOf(entry))
  is NextAction.StartNow -> startNow(action.task)
}

/** Speed mode of [device], or `null` when it has none: only the embedded device can. */
internal fun AppState.speedModeOf(device: DevicePresence): SpeedModeController? =
  speedModeFor(device.entry)

/**
 * Turns [device]'s Slow lane off when it is in effect and on otherwise, like its pill in the
 * Pulse bar, with an Undo toast; whether or not the device shows.
 *
 * @return the change, or `null` when the device has no speed mode.
 */
internal fun AppState.toggleSlowLane(device: DevicePresence): Job? {
  val controller = speedModeOf(device) ?: return null
  val next = if (controller.mode.value.isSlowLane) SpeedLimitMode.Full else SpeedLimitMode.SlowLane
  return switchSpeedMode(controller, next)
}

/** Connects to [device] again now, rather than at its next scheduled attempt. */
internal fun AppState.retryNow(device: RemoteInstance): Job = launchCommand {
  catchingUnlessCancelled { instanceManager.reconnect(device) }.onFailure { e ->
    log.w { "Couldn't reconnect to ${device.deviceId}: ${e.describeCauses()}" }
    messages.post(
      level = MessageLevel.Error,
      title = "Couldn't reconnect to ${device.label}",
      cause = e,
      deviceId = device.deviceId,
    )
  }
}

/** Asks for a new access token for [device], which turned down the saved one. */
internal fun AppState.askForToken(device: RemoteInstance) {
  unauthorizedInstance = device
  showAddRemoteDialog = true
}

/**
 * Opens the Add device sheet, which takes a pairing link or an address and, where the platform
 * can, lists the devices it finds on the network.
 */
internal fun AppState.addDevice() {
  showAddRemoteDialog = true
}

/** Opens Settings › Sharing of this device, where another device can pair with it. */
internal fun AppState.pairDevice() {
  openSettings(SettingsTarget(SettingsTarget.Page.Sharing, LOCAL_DEVICE_ID))
}

/** Opens the settings of [entry]'s own pages, starting with Downloads. */
internal fun AppState.openDeviceSettings(entry: InstanceEntry) {
  openSettings(SettingsTarget(SettingsTarget.Page.Downloads, entry.deviceId))
}

/**
 * Names [entry] [name]: a remote device at once; the embedded one as the name other devices see
 * it by, which applies after a restart. A blank name goes back to the device's own.
 */
internal fun AppState.renameDevice(entry: InstanceEntry, name: String) {
  when (entry) {
    is RemoteInstance -> instanceManager.rename(entry, name)
    else -> appSettings.saveName(name)
  }
}

