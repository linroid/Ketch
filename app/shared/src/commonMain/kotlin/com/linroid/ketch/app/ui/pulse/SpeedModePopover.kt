package com.linroid.ketch.app.ui.pulse

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.linroid.ketch.api.SpeedLimit
import com.linroid.ketch.app.components.KetchButton
import com.linroid.ketch.app.components.KetchButtonSize
import com.linroid.ketch.app.components.KetchButtonVariant
import com.linroid.ketch.app.components.KetchCheckbox
import com.linroid.ketch.app.components.KetchSegmented
import com.linroid.ketch.app.components.SpeedLimitPicker
import com.linroid.ketch.app.icons.KetchIcon
import com.linroid.ketch.app.instance.EmbeddedInstance
import com.linroid.ketch.app.platform.localDeviceNoun
import com.linroid.ketch.app.state.AppState
import com.linroid.ketch.app.state.LocalClock
import com.linroid.ketch.app.state.SettingsTarget
import com.linroid.ketch.app.state.SpeedMode
import com.linroid.ketch.app.state.deviceId
import com.linroid.ketch.app.state.formatSpeedLimit
import com.linroid.ketch.app.state.isSlowLane
import com.linroid.ketch.app.theme.KetchTheme
import com.linroid.ketch.app.theme.eyebrowText
import com.linroid.ketch.config.SpeedLimitMode
import kotlin.time.Instant
import kotlinx.datetime.TimeZone

/**
 * The speed mode popover of the Pulse bar, 280 dp wide above the [SpeedModePill], or a bottom
 * sheet on touch: Full speed, Slow lane or Auto, the speed limit, "Use as Slow lane speed" and
 * a link to the Speed settings of the active device.
 *
 * A device without a speed mode, such as a remote one, offers only its speed limit.
 */
@Composable
fun SpeedModePopover(
  state: AppState,
  expanded: Boolean,
  onDismissRequest: () -> Unit,
  modifier: Modifier = Modifier,
) {
  PulsePopover(
    expanded = expanded,
    onDismissRequest = onDismissRequest,
    width = PopoverWidth,
    modifier = modifier,
    title = "Speed",
  ) {
    SpeedModeOptions(state, onOpenSettings = onDismissRequest)
  }
}

/**
 * The controls of the [SpeedModePopover], also shown by the phone's Pulse sheet.
 *
 * @param fillModes whether the mode control takes the full width; a 280 dp popover is too narrow
 *   to share it equally between the three names.
 */
@Composable
internal fun ColumnScope.SpeedModeOptions(
  state: AppState,
  onOpenSettings: () -> Unit,
  fillModes: Boolean = false,
) {
  val colors = KetchTheme.colors
  val spacing = KetchTheme.spacing
  val view = rememberSpeedModeView(state)
  val active by state.activeInstance.collectAsState()
  val controller = view.controller
  val deviceName = when (val entry = active) {
    null -> "this device"
    is EmbeddedInstance -> localDeviceNoun()
    else -> entry.label
  }
  val command = rememberPendingJob()
  var asSlowLane by remember(controller) { mutableStateOf(view.mode.isSlowLane) }

  if (controller != null) {
    val settings by controller.settings.collectAsState()
    Eyebrow("Speed mode")
    KetchSegmented(
      options = SpeedLimitMode.entries,
      selected = settings.mode,
      onSelect = { mode ->
        asSlowLane = mode == SpeedLimitMode.SlowLane ||
          mode == SpeedLimitMode.Auto && asSlowLane
        command.track(state.switchSpeedMode(mode))
      },
      label = ::speedModeName,
      fill = fillModes,
    )
    Spacer(Modifier.height(spacing.s2))
    Text(
      text = modeCaption(view.mode, view.limit, settings.rules.isEmpty(), LocalClock.current.now()),
      style = KetchTheme.typography.caption,
      color = colors.textSecondary,
    )
    Spacer(Modifier.height(spacing.s4))
  }

  val limit = when {
    controller != null && asSlowLane -> controller.slowLaneSpeed
    controller != null && view.mode != SpeedMode.Full -> controller.settings.value.standard
    else -> state.instanceSettings.download?.speedLimit ?: view.limit
  }
  val error = state.instanceSettings.downloadError.takeIf { state.limitGoesToSettings(asSlowLane) }
  Eyebrow(if (asSlowLane) "Slow lane speed" else "Speed limit")
  SpeedLimitPicker(
    value = limit,
    onCommit = { command.track(state.setSpeedLimit(it, asSlowLane)) },
    caption = error?.let { "Couldn't update $deviceName · $it" }
      ?: limitCaption(view, asSlowLane, deviceName),
    enabled = controller != null || state.instanceSettings.download != null,
    pending = command.pending,
  )
  if (controller != null) {
    Spacer(Modifier.height(spacing.s2))
    KetchCheckbox(
      checked = asSlowLane,
      onCheckedChange = { asSlowLane = it },
      label = "Use as Slow lane speed",
    )
  }
  Spacer(Modifier.height(spacing.s3))
  Spacer(Modifier.fillMaxWidth().height(1.dp).background(colors.divider))
  Spacer(Modifier.height(spacing.s2))
  KetchButton(
    text = "Speed settings…",
    onClick = {
      onOpenSettings()
      state.openSettings(SettingsTarget(SettingsTarget.Page.Speed, active?.deviceId))
    },
    variant = KetchButtonVariant.Ghost,
    size = KetchButtonSize.Small,
    leadingIcon = KetchIcon.Settings,
  )
}

@Composable
private fun Eyebrow(text: String) {
  Text(
    text = eyebrowText(text),
    style = KetchTheme.typography.eyebrow,
    color = KetchTheme.colors.textTertiary,
    modifier = Modifier.padding(bottom = KetchTheme.spacing.s2),
  )
}

/** What the mode does now, under the mode control. */
internal fun modeCaption(
  mode: SpeedMode,
  limit: SpeedLimit,
  noRules: Boolean,
  now: Instant,
): String {
  val zone = TimeZone.currentSystemDefault()
  return when (mode) {
    SpeedMode.Full -> if (limit.isUnlimited) {
      "Downloads run as fast as the network allows."
    } else {
      "Downloads are capped at ${formatSpeedLimit(limit)}."
    }
    SpeedMode.SlowLane -> {
      "Downloads share ${formatSpeedLimit(limit)}, leaving bandwidth for everything else."
    }
    is SpeedMode.Auto -> when {
      noRules -> "No speed rules yet. Add them in Speed settings."
      mode.until == null -> if (mode.slowLane) {
        "A rule keeps the Slow lane on."
      } else {
        "Full speed until a rule turns on the Slow lane."
      }
      else -> {
        val phase = if (mode.slowLane) "Slow lane" else "Full speed"
        "$phase until ${clockLabel(mode.until, now, zone)}, then your rules decide."
      }
    }
  }
}

private fun limitCaption(view: SpeedModeView, asSlowLane: Boolean, deviceName: String): String =
  when {
    asSlowLane -> "Used whenever the Slow lane is on."
    view.controller == null || view.mode == SpeedMode.Full -> {
      "Applies to every download on $deviceName."
    }
    else -> "Applies once the Slow lane is off."
  }

private val PopoverWidth = 280.dp
