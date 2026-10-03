package com.linroid.ketch.app.ui.pulse

import androidx.compose.foundation.background
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
import com.linroid.ketch.app.i18n.UiText
import com.linroid.ketch.app.i18n.resolve
import com.linroid.ketch.app.i18n.text
import com.linroid.ketch.app.icons.KetchIcon
import com.linroid.ketch.app.instance.displayName
import com.linroid.ketch.app.state.AppState
import com.linroid.ketch.app.state.LocalClock
import com.linroid.ketch.app.state.SettingsTarget
import com.linroid.ketch.app.state.SpeedMode
import com.linroid.ketch.app.state.clockLabel
import com.linroid.ketch.app.state.deviceId
import com.linroid.ketch.app.state.isSlowLane
import com.linroid.ketch.app.state.speedLimitText
import com.linroid.ketch.app.theme.KetchTheme
import com.linroid.ketch.app.theme.eyebrowText
import com.linroid.ketch.config.SpeedLimitMode
import ketch.app.shared.generated.resources.Res
import ketch.app.shared.generated.resources.device_any_in_sentence
import ketch.app.shared.generated.resources.pulse_caption_capped
import ketch.app.shared.generated.resources.pulse_caption_full
import ketch.app.shared.generated.resources.pulse_caption_full_until
import ketch.app.shared.generated.resources.pulse_caption_no_rules
import ketch.app.shared.generated.resources.pulse_caption_rule_full
import ketch.app.shared.generated.resources.pulse_caption_rule_slow_lane
import ketch.app.shared.generated.resources.pulse_caption_slow_lane
import ketch.app.shared.generated.resources.pulse_caption_slow_lane_until
import ketch.app.shared.generated.resources.pulse_limit_after_slow_lane
import ketch.app.shared.generated.resources.pulse_limit_for_device
import ketch.app.shared.generated.resources.pulse_limit_for_slow_lane
import ketch.app.shared.generated.resources.pulse_slow_lane_speed
import ketch.app.shared.generated.resources.pulse_speed
import ketch.app.shared.generated.resources.pulse_speed_limit
import ketch.app.shared.generated.resources.pulse_speed_mode
import ketch.app.shared.generated.resources.pulse_speed_settings
import ketch.app.shared.generated.resources.pulse_update_failed
import ketch.app.shared.generated.resources.pulse_use_as_slow_lane
import kotlinx.datetime.TimeZone
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource
import kotlin.time.Instant

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
    title = stringResource(Res.string.pulse_speed),
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
  val deviceName = active?.displayName ?: Res.string.device_any_in_sentence.text()
  val command = rememberPendingJob()
  var asSlowLane by remember(controller) { mutableStateOf(view.mode.isSlowLane) }

  if (controller != null) {
    val settings by controller.settings.collectAsState()
    Eyebrow(Res.string.pulse_speed_mode)
    KetchSegmented(
      options = SpeedLimitMode.entries,
      selected = settings.mode,
      onSelect = { mode ->
        asSlowLane = mode == SpeedLimitMode.SlowLane ||
          mode == SpeedLimitMode.Auto && asSlowLane
        command.track(state.switchSpeedMode(mode))
      },
      label = { speedModeText(it).resolve() },
      fill = fillModes,
    )
    Spacer(Modifier.height(spacing.s2))
    val now = LocalClock.current.now()
    Text(
      text = modeCaptionText(view.mode, view.limit, settings.rules.isEmpty(), now).resolve(),
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
  val caption = error?.let { Res.string.pulse_update_failed.text(deviceName, it) }
    ?: limitCaption(view, asSlowLane, deviceName)
  Eyebrow(if (asSlowLane) Res.string.pulse_slow_lane_speed else Res.string.pulse_speed_limit)
  SpeedLimitPicker(
    value = limit,
    onCommit = { command.track(state.setSpeedLimit(it, asSlowLane)) },
    caption = caption.resolve(),
    enabled = controller != null || state.instanceSettings.download != null,
    pending = command.pending,
  )
  if (controller != null) {
    Spacer(Modifier.height(spacing.s2))
    KetchCheckbox(
      checked = asSlowLane,
      onCheckedChange = { asSlowLane = it },
      label = stringResource(Res.string.pulse_use_as_slow_lane),
    )
  }
  Spacer(Modifier.height(spacing.s3))
  Spacer(Modifier.fillMaxWidth().height(1.dp).background(colors.divider))
  Spacer(Modifier.height(spacing.s2))
  KetchButton(
    text = stringResource(Res.string.pulse_speed_settings),
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
private fun Eyebrow(text: StringResource) {
  Text(
    text = eyebrowText(stringResource(text)),
    style = KetchTheme.typography.eyebrow,
    color = KetchTheme.colors.textTertiary,
    modifier = Modifier.padding(bottom = KetchTheme.spacing.s2),
  )
}

/** What the mode does now, under the mode control, with clock times local to [timeZone]. */
internal fun modeCaptionText(
  mode: SpeedMode,
  limit: SpeedLimit,
  noRules: Boolean,
  now: Instant,
  timeZone: TimeZone = TimeZone.currentSystemDefault(),
): UiText = when (mode) {
  SpeedMode.Full -> if (limit.isUnlimited) {
    Res.string.pulse_caption_full.text()
  } else {
    Res.string.pulse_caption_capped.text(speedLimitText(limit))
  }
  SpeedMode.SlowLane -> Res.string.pulse_caption_slow_lane.text(speedLimitText(limit))
  is SpeedMode.Auto -> when {
    noRules -> Res.string.pulse_caption_no_rules.text()
    mode.until == null -> if (mode.slowLane) {
      Res.string.pulse_caption_rule_slow_lane.text()
    } else {
      Res.string.pulse_caption_rule_full.text()
    }
    else -> {
      val until = clockLabel(mode.until, now, timeZone)
      if (mode.slowLane) {
        Res.string.pulse_caption_slow_lane_until.text(until)
      } else {
        Res.string.pulse_caption_full_until.text(until)
      }
    }
  }
}

private fun limitCaption(view: SpeedModeView, asSlowLane: Boolean, deviceName: UiText): UiText =
  when {
    asSlowLane -> Res.string.pulse_limit_for_slow_lane.text()
    view.controller == null || view.mode == SpeedMode.Full -> {
      Res.string.pulse_limit_for_device.text(deviceName)
    }
    else -> Res.string.pulse_limit_after_slow_lane.text()
  }

private val PopoverWidth = 280.dp
