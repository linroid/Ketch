package com.linroid.ketch.app.ui.palette

import com.linroid.ketch.api.SpeedLimit
import com.linroid.ketch.api.log.KetchLogger
import com.linroid.ketch.app.feedback.MessageLevel
import com.linroid.ketch.app.i18n.text
import com.linroid.ketch.app.input.KetchCommand
import com.linroid.ketch.app.instance.InstanceEntry
import com.linroid.ketch.app.state.AppState
import com.linroid.ketch.app.state.DiscoverRequest
import com.linroid.ketch.app.state.IntakeRequest
import com.linroid.ketch.app.state.RowAction
import com.linroid.ketch.app.state.SettingsTarget
import com.linroid.ketch.app.state.StatusFilter
import com.linroid.ketch.app.state.deviceId
import com.linroid.ketch.app.ui.list.RowCommands
import com.linroid.ketch.app.ui.pulse.activeSpeedMode
import com.linroid.ketch.app.ui.pulse.setSpeedLimit
import com.linroid.ketch.app.ui.pulse.switchSpeedMode
import com.linroid.ketch.config.SpeedLimitMode
import ketch.app.shared.generated.resources.Res
import ketch.app.shared.generated.resources.palette_full_speed_already
import ketch.app.shared.generated.resources.palette_speed_not_loaded

/**
 * Carries out the [PaletteAction] of a palette row on [state].
 *
 * @param commands runs task actions, as the row's own buttons do.
 * @param onCommand runs a global command, as its shortcut does; returns whether it ran.
 */
internal class PaletteRunner(
  private val state: AppState,
  private val commands: RowCommands,
  private val onCommand: (KetchCommand) -> Boolean,
) {
  private val log = KetchLogger("CommandPalette")

  /** Runs [action]. */
  fun run(action: PaletteAction) {
    log.d { "Running ${action::class.simpleName}" }
    when (action) {
      is PaletteAction.Command -> onCommand(action.command)
      is PaletteAction.Download -> download(action)
      is PaletteAction.AddWithOptions -> {
        state.openIntake(IntakeRequest(text = action.text, targetDeviceId = action.deviceId))
      }
      is PaletteAction.SlowLane -> state.setSpeedLimit(action.limit, asSlowLane = true)
      is PaletteAction.SpeedCap -> capSpeed(action.limit)
      PaletteAction.FullSpeed -> fullSpeed()
      is PaletteAction.DeviceBatch -> deviceBatch(action)
      is PaletteAction.SwitchDevice -> device(action.deviceId)?.let(state::switchInstance)
      is PaletteAction.Task -> task(action)
      is PaletteAction.Settings -> state.openSettings(SettingsTarget(action.page))
      is PaletteAction.Search -> {
        state.searchQuery = action.query
        state.showDownloads(StatusFilter.All)
      }
      is PaletteAction.Discover -> state.openDiscover(DiscoverRequest(action.query))
    }
  }

  private fun download(action: PaletteAction.Download) {
    val target = device(action.deviceId)
    if (!action.now || target == null) {
      state.openIntake(IntakeRequest(text = action.text, targetDeviceId = action.deviceId))
      return
    }
    // The new rows show in the list of the device the app shows; another device's stay there.
    if (target == state.activeInstance.value) state.showDownloads(StatusFilter.All)
    state.quickAdd(action.urls, target)
  }

  private fun capSpeed(limit: SpeedLimit) {
    if (state.activeSpeedMode == null && state.instanceSettings.download == null) {
      state.messages.post(MessageLevel.Warning, Res.string.palette_speed_not_loaded.text())
      return
    }
    state.setSpeedLimit(limit, asSlowLane = false)
  }

  private fun fullSpeed() {
    val controller = state.activeSpeedMode
    if (controller != null && controller.settings.value.mode != SpeedLimitMode.Full) {
      state.switchSpeedMode(SpeedLimitMode.Full)
      return
    }
    // At full speed the device's own limit is the only cap left.
    val cap = state.instanceSettings.download?.speedLimit
    if (cap == null || cap.isUnlimited) {
      state.messages.postFeedback(MessageLevel.Info, Res.string.palette_full_speed_already.text())
      return
    }
    state.setSpeedLimit(SpeedLimit.Unlimited, asSlowLane = false)
  }

  private fun deviceBatch(action: PaletteAction.DeviceBatch) {
    val target = listOfNotNull(device(action.deviceId))
    if (target.isEmpty()) return
    when (action.verb) {
      BatchVerb.Pause -> state.pauseAll(target)
      BatchVerb.Resume -> state.resumeAll(target)
      BatchVerb.Retry -> state.retryFailed(target)
    }
  }

  private fun task(action: PaletteAction.Task) {
    val row = state.taskList.row(action.key) ?: return
    if (action.action != RowAction.Details) {
      commands.run(action.action, row)
      return
    }
    // Shows the task on a tab that lists it.
    val filter = state.statusFilter.takeIf { it.matches(row) } ?: StatusFilter.All
    state.showDownloads(filter)
    state.inspect(action.key)
  }

  private fun device(deviceId: String): InstanceEntry? =
    state.instances.value.firstOrNull { it.deviceId == deviceId }
}
