package com.linroid.ketch.app.ui.devices

import com.linroid.ketch.api.SpeedLimit
import com.linroid.ketch.api.log.KetchLogger
import com.linroid.ketch.api.log.describeCauses
import com.linroid.ketch.app.feedback.MessageAction
import com.linroid.ketch.app.feedback.MessageLevel
import com.linroid.ketch.app.i18n.text
import com.linroid.ketch.app.i18n.verbatim
import com.linroid.ketch.app.instance.DevicePresence
import com.linroid.ketch.app.instance.EmbeddedInstance
import com.linroid.ketch.app.instance.InstanceEntry
import com.linroid.ketch.app.instance.RemoteInstance
import com.linroid.ketch.app.instance.displayName
import com.linroid.ketch.app.platform.DroppedFile
import com.linroid.ketch.app.state.AppState
import com.linroid.ketch.app.state.IntakeRequest
import com.linroid.ketch.app.state.LOCAL_DEVICE_ID
import com.linroid.ketch.app.state.SettingsTarget
import com.linroid.ketch.app.state.SpeedModeController
import com.linroid.ketch.app.state.StatusFilter
import com.linroid.ketch.app.state.catchingUnlessCancelled
import com.linroid.ketch.app.state.deviceId
import com.linroid.ketch.app.state.isSlowLane
import com.linroid.ketch.app.state.speedLimitText
import com.linroid.ketch.app.ui.pulse.activeSpeedMode
import com.linroid.ketch.app.ui.pulse.slowLaneLimit
import com.linroid.ketch.app.ui.pulse.speedModeText
import com.linroid.ketch.app.ui.pulse.toggleSlowLane
import com.linroid.ketch.app.util.LinkParser
import com.linroid.ketch.config.SpeedLimitMode
import ketch.app.shared.generated.resources.Res
import ketch.app.shared.generated.resources.action_try_again
import ketch.app.shared.generated.resources.action_undo
import ketch.app.shared.generated.resources.device_drop_read_failed
import ketch.app.shared.generated.resources.device_drop_unsupported
import ketch.app.shared.generated.resources.device_reconnect_failed
import ketch.app.shared.generated.resources.device_slow_lane_off
import ketch.app.shared.generated.resources.device_slow_lane_on
import ketch.app.shared.generated.resources.device_speed_follows_rules
import ketch.app.shared.generated.resources.device_speed_mode_failed
import kotlinx.coroutines.CancellationException
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
  speedMode?.takeIf { device.entry is EmbeddedInstance }

/**
 * Turns [device]'s Slow lane off when it is in effect and on otherwise, like its pill in the
 * Pulse bar, with an Undo toast; whether or not the device shows.
 *
 * @return the change, or `null` when the device has no speed mode.
 */
internal fun AppState.toggleSlowLane(device: DevicePresence): Job? {
  val controller = speedModeOf(device) ?: return null
  if (activeSpeedMode === controller) return toggleSlowLane()
  val next = if (controller.mode.value.isSlowLane) SpeedLimitMode.Full else SpeedLimitMode.SlowLane
  return switchSpeedMode(controller, next)
}

/**
 * Switches [controller]'s device to [mode] and posts the result, with Undo when [undoable].
 *
 * @return the change, or `null` when the device is already in [mode].
 */
internal fun AppState.switchSpeedMode(
  controller: SpeedModeController,
  mode: SpeedLimitMode,
  undoable: Boolean = true,
): Job? {
  val previous = controller.settings.value.mode
  if (mode == previous) return null
  return launchCommand {
    val name = speedModeText(mode)
    try {
      controller.setMode(mode)
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      log.w { "Couldn't switch to $mode: ${e.describeCauses()}" }
      messages.post(
        level = MessageLevel.Error,
        title = Res.string.device_speed_mode_failed.text(name),
        actions = listOf(
          MessageAction(Res.string.action_try_again.text()) {
            switchSpeedMode(controller, mode, undoable)
          },
        ),
        cause = e,
      )
      return@launchCommand
    }
    val title = when (mode) {
      SpeedLimitMode.SlowLane ->
        Res.string.device_slow_lane_on.text(speedLimitText(controller.slowLaneLimit))
      SpeedLimitMode.Full -> Res.string.device_slow_lane_off.text()
      SpeedLimitMode.Auto -> Res.string.device_speed_follows_rules.text()
    }
    val undo = MessageAction(Res.string.action_undo.text()) {
      switchSpeedMode(controller, previous, undoable = false)
    }
    messages.post(
      level = MessageLevel.Success,
      title = title,
      actions = if (undoable) listOf(undo) else emptyList(),
    )
  }
}

/**
 * Sets [entry]'s global speed limit through its download settings, whose controller keeps any
 * error; does nothing until they have loaded.
 */
internal fun AppState.setSpeedLimit(entry: InstanceEntry, limit: SpeedLimit) {
  val settings = settingsFor(entry)
  val download = settings.download ?: return
  settings.updateDownload(download.copy(speedLimit = limit))
}

/** Connects to [device] again now, rather than at its next scheduled attempt. */
internal fun AppState.retryNow(device: RemoteInstance): Job = launchCommand {
  catchingUnlessCancelled { instanceManager.reconnect(device) }.onFailure { e ->
    log.w { "Couldn't reconnect to ${device.deviceId}: ${e.describeCauses()}" }
    messages.post(
      level = MessageLevel.Error,
      title = Res.string.device_reconnect_failed.text(device.displayName),
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

/** Opens the add sheet with [text], such as links dropped on [entry]'s card, adding there. */
internal fun AppState.dropText(entry: InstanceEntry, text: String) {
  val trimmed = text.trim()
  if (trimmed.isEmpty()) return
  openIntake(IntakeRequest(text = trimmed, targetDeviceId = entry.deviceId))
}

/**
 * Adds [files] dropped on [entry]'s card there: the first `.torrent` file joins the add sheet,
 * and lists of links fill it.
 */
internal fun AppState.dropFiles(entry: InstanceEntry, files: List<DroppedFile>) {
  val torrent = files.firstOrNull { it.name.endsWith(".torrent", ignoreCase = true) }
  if (torrent != null) {
    openIntake(IntakeRequest(targetDeviceId = entry.deviceId))
    if (showAddDialog) resolveDroppedFile(torrent, entry)
    return
  }
  val lists = files.filter { LinkParser.isLinkList(it.name) }
  if (lists.isEmpty()) {
    messages.post(
      level = MessageLevel.Error,
      title = Res.string.device_drop_unsupported.text(),
    )
    return
  }
  launchCommand {
    val text = lists.mapNotNull { file ->
      catchingUnlessCancelled { file.readBytes(MAX_LINK_LIST_BYTES).decodeToString() }
        .onFailure { e ->
          log.w { "Couldn't read a dropped link list: ${e.describeCauses()}" }
          messages.post(
            level = MessageLevel.Error,
            title = Res.string.device_drop_read_failed.text(file.name),
            detail = e.message?.let(::verbatim),
            cause = e,
          )
        }
        .getOrNull()
    }.joinToString("\n")
    dropText(entry, text)
  }
}

private const val MAX_LINK_LIST_BYTES = 1L * 1024 * 1024
