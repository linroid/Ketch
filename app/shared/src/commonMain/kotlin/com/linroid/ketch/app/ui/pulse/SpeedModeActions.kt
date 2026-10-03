package com.linroid.ketch.app.ui.pulse

import com.linroid.ketch.api.SpeedLimit
import com.linroid.ketch.api.log.KetchLogger
import com.linroid.ketch.api.log.describeCauses
import com.linroid.ketch.app.feedback.MessageAction
import com.linroid.ketch.app.feedback.MessageLevel
import com.linroid.ketch.app.i18n.UiText
import com.linroid.ketch.app.i18n.text
import com.linroid.ketch.app.instance.EmbeddedInstance
import com.linroid.ketch.app.instance.InstanceEntry
import com.linroid.ketch.app.state.AppState
import com.linroid.ketch.app.state.SpeedMode
import com.linroid.ketch.app.state.SpeedModeController
import com.linroid.ketch.app.state.isSlowLane
import com.linroid.ketch.app.state.speedLimitText
import com.linroid.ketch.config.SpeedLimitMode
import ketch.app.shared.generated.resources.Res
import ketch.app.shared.generated.resources.action_try_again
import ketch.app.shared.generated.resources.action_undo
import ketch.app.shared.generated.resources.pulse_limit_failed
import ketch.app.shared.generated.resources.pulse_slow_lane_off
import ketch.app.shared.generated.resources.pulse_slow_lane_on
import ketch.app.shared.generated.resources.pulse_speed_follows_rules
import ketch.app.shared.generated.resources.pulse_switch_failed
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job

private val log = KetchLogger("SpeedMode")

/**
 * Speed mode of [entry], or `null` when it has none: only the embedded device has one, and only
 * when the host passed it to the app.
 */
internal fun AppState.speedModeFor(entry: InstanceEntry?): SpeedModeController? =
  speedMode?.takeIf { entry is EmbeddedInstance }

/** Speed mode of the active device, or `null` when it has none (see [speedModeFor]). */
val AppState.activeSpeedMode: SpeedModeController?
  get() = speedModeFor(activeInstance.value)

/**
 * Turns the Slow lane off on the active device when it is in effect, by hand or by a rule, and
 * on otherwise, then posts "Slow lane on · 1 MB/s" or "Slow lane off" with Undo. This is the
 * Pulse bar pill's click and `⇧⌘L`.
 *
 * Like every speed mode change it runs in the app scope, so closing the control that started it
 * cancels neither the change nor its Undo; a failure is posted as an error with Try again.
 *
 * @return the change, or `null` when the active device has no speed mode.
 */
fun AppState.toggleSlowLane(): Job? {
  val controller = activeSpeedMode ?: return null
  val next = if (controller.mode.value.isSlowLane) SpeedLimitMode.Full else SpeedLimitMode.SlowLane
  return switchSpeedMode(next)
}

/**
 * Switches the active device to [mode] and posts the result, with Undo when [undoable].
 *
 * @return the change, or `null` when the device has no speed mode or is already in [mode].
 */
fun AppState.switchSpeedMode(mode: SpeedLimitMode, undoable: Boolean = true): Job? =
  activeSpeedMode?.let { switchSpeedMode(it, mode, undoable) }

/**
 * Switches [controller]'s device to [mode], whether or not it is the one shown, and posts the
 * result, with Undo when [undoable].
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
    val failure = Res.string.pulse_switch_failed.text(speedModeName(mode))
    val applied = speedModeCommand("setMode(${mode.name})", failure) {
      controller.setMode(mode)
    }
    if (!applied) return@launchCommand
    val title = when (mode) {
      SpeedLimitMode.SlowLane ->
        Res.string.pulse_slow_lane_on.text(speedLimitText(controller.slowLaneLimit))
      SpeedLimitMode.Full -> Res.string.pulse_slow_lane_off.text()
      SpeedLimitMode.Auto -> Res.string.pulse_speed_follows_rules.text()
    }
    val undo = MessageAction(Res.string.action_undo.text()) {
      switchSpeedMode(controller, previous, undoable = false)
    }
    messages.postFeedback(
      level = MessageLevel.Success,
      title = title,
      actions = if (undoable) listOf(undo) else emptyList(),
    )
  }
}

/** Sets the speed limit the speed mode popover edits on the active device, as on any other. */
fun AppState.setSpeedLimit(limit: SpeedLimit, asSlowLane: Boolean): Job? =
  activeInstance.value?.let { setSpeedLimit(it, limit, asSlowLane) }

/**
 * Sets the speed limit the speed mode options edit on [entry], whether or not it is shown.
 *
 * As the slow lane's speed ([asSlowLane]) it is saved with the speed mode and turns the slow
 * lane on when the device runs at full speed. Otherwise it is the standing cap: at full speed,
 * or on a device without a speed mode, it becomes the device's download speed limit; while the
 * slow lane or Auto rules hold the limit, the speed mode keeps it for when they let go.
 *
 * @return the change, or `null` when it went to the device's download settings, which keep
 *   their own error.
 */
internal fun AppState.setSpeedLimit(
  entry: InstanceEntry,
  limit: SpeedLimit,
  asSlowLane: Boolean = false,
): Job? {
  val controller = speedModeFor(entry)
  if (controller == null || limitGoesToSettings(controller, asSlowLane)) {
    val settings = settingsFor(entry)
    val download = settings.download ?: return null
    settings.updateDownload(download.copy(speedLimit = limit))
    return null
  }
  return launchCommand {
    speedModeCommand("setSpeedLimit", Res.string.pulse_limit_failed.text()) {
      if (asSlowLane) {
        controller.setSlowLane(limit)
        if (controller.settings.value.mode == SpeedLimitMode.Full) {
          controller.setMode(SpeedLimitMode.SlowLane)
        }
      } else {
        controller.setStandard(limit)
      }
    }
  }
}

/**
 * Whether [setSpeedLimit] hands the limit to the device's download settings, which keep their
 * own error, rather than to its speed mode [controller].
 */
internal fun limitGoesToSettings(controller: SpeedModeController?, asSlowLane: Boolean): Boolean =
  controller == null || !asSlowLane && controller.settings.value.mode == SpeedLimitMode.Full

/** Limit the slow lane runs at: its speed, held to the standing cap. */
internal val SpeedModeController.slowLaneLimit: SpeedLimit
  get() = effectiveCap(
    mode = SpeedMode.SlowLane,
    cap = SpeedLimit.Unlimited,
    slowLane = slowLaneSpeed,
    standard = settings.value.standard,
  )

// Runs a speed mode change, reads the device's limit back for the Pulse bar and posts a failure
// titled failure with Try again. command names the change in the log. Returns whether the
// change applied.
private suspend fun AppState.speedModeCommand(
  command: String,
  failure: UiText,
  block: suspend () -> Unit,
): Boolean {
  try {
    block()
  } catch (e: CancellationException) {
    throw e
  } catch (e: Exception) {
    log.w { "Speed mode command $command failed: ${e.describeCauses()}" }
    messages.post(
      level = MessageLevel.Error,
      title = failure,
      actions = listOf(
        MessageAction(Res.string.action_try_again.text()) {
          launchCommand { speedModeCommand(command, failure, block) }
        }
      ),
      cause = e,
    )
    return false
  }
  // Only the embedded device has a speed mode.
  instances.value.firstOrNull { it is EmbeddedInstance }?.let(::settingsFor)?.loadDownload()
  return true
}
