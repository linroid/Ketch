package com.linroid.ketch.app.ui.pulse

import com.linroid.ketch.api.SpeedLimit
import com.linroid.ketch.api.log.KetchLogger
import com.linroid.ketch.api.log.describeCauses
import com.linroid.ketch.app.feedback.MessageAction
import com.linroid.ketch.app.feedback.MessageLevel
import com.linroid.ketch.app.instance.EmbeddedInstance
import com.linroid.ketch.app.state.AppState
import com.linroid.ketch.app.state.SpeedMode
import com.linroid.ketch.app.state.SpeedModeController
import com.linroid.ketch.app.state.formatSpeedLimit
import com.linroid.ketch.app.state.isSlowLane
import com.linroid.ketch.config.SpeedLimitMode
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job

private val log = KetchLogger("SpeedMode")

/**
 * Speed mode of the active device, or `null` when it has none: only the embedded device has
 * one, and only when the host passed it to the app.
 */
val AppState.activeSpeedMode: SpeedModeController?
  get() = speedMode?.takeIf { activeInstance.value is EmbeddedInstance }

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
fun AppState.switchSpeedMode(mode: SpeedLimitMode, undoable: Boolean = true): Job? {
  val controller = activeSpeedMode ?: return null
  val previous = controller.settings.value.mode
  if (mode == previous) return null
  return launchCommand {
    val applied = speedModeCommand("switch to ${speedModeName(mode)}") {
      controller.setMode(mode)
    }
    if (!applied) return@launchCommand
    val title = when (mode) {
      SpeedLimitMode.SlowLane -> "Slow lane on · ${formatSpeedLimit(controller.slowLaneLimit)}"
      SpeedLimitMode.Full -> "Slow lane off"
      SpeedLimitMode.Auto -> "Speed follows your rules"
    }
    val undo = MessageAction("Undo") { switchSpeedMode(previous, undoable = false) }
    messages.post(
      level = MessageLevel.Success,
      title = title,
      actions = if (undoable) listOf(undo) else emptyList(),
    )
  }
}

/**
 * Sets the speed limit the speed mode popover edits on the active device.
 *
 * As the slow lane's speed ([asSlowLane]) it is saved with the speed mode and turns the slow
 * lane on when the device runs at full speed. Otherwise it is the standing cap: at full speed,
 * or on a device without a speed mode, it becomes the device's download speed limit; while the
 * slow lane or Auto rules hold the limit, the speed mode keeps it for when they let go.
 *
 * @return the change, or `null` when it went to the device's download settings, which keep
 *   their own error.
 */
fun AppState.setSpeedLimit(limit: SpeedLimit, asSlowLane: Boolean): Job? {
  val controller = activeSpeedMode
  if (controller == null || limitGoesToSettings(asSlowLane)) {
    val settings = instanceSettings
    val download = settings.download ?: return null
    settings.updateDownload(download.copy(speedLimit = limit))
    return null
  }
  return launchCommand {
    speedModeCommand("set the speed limit") {
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
 * Whether [setSpeedLimit] hands the limit to the active device's download settings, which keep
 * their own error, rather than to its speed mode.
 */
internal fun AppState.limitGoesToSettings(asSlowLane: Boolean): Boolean {
  val controller = activeSpeedMode ?: return true
  return !asSlowLane && controller.settings.value.mode == SpeedLimitMode.Full
}

/** Limit the slow lane runs at: its speed, held to the standing cap. */
internal val SpeedModeController.slowLaneLimit: SpeedLimit
  get() = effectiveCap(
    mode = SpeedMode.SlowLane,
    cap = SpeedLimit.Unlimited,
    slowLane = slowLaneSpeed,
    standard = settings.value.standard,
  )

// Runs a speed mode change, reads the device's limit back for the Pulse bar and posts a failure
// with Try again. Returns whether the change applied.
private suspend fun AppState.speedModeCommand(label: String, block: suspend () -> Unit): Boolean {
  try {
    block()
  } catch (e: CancellationException) {
    throw e
  } catch (e: Exception) {
    log.w { "Couldn't $label: ${e.describeCauses()}" }
    messages.post(
      level = MessageLevel.Error,
      title = "Couldn't $label",
      actions = listOf(
        MessageAction("Try again") {
          launchCommand { speedModeCommand(label, block) }
        }
      ),
      cause = e,
    )
    return false
  }
  instanceSettings.loadDownload()
  return true
}
