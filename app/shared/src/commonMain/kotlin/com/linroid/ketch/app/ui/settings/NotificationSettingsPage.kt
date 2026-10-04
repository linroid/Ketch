package com.linroid.ketch.app.ui.settings

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import com.linroid.ketch.app.components.DevicePennant
import com.linroid.ketch.app.components.DevicePennantDefaults
import com.linroid.ketch.app.components.KetchButton
import com.linroid.ketch.app.components.KetchButtonSize
import com.linroid.ketch.app.components.KetchButtonVariant
import com.linroid.ketch.app.components.KetchSegmented
import com.linroid.ketch.app.components.KetchSwitch
import com.linroid.ketch.app.i18n.resolve
import com.linroid.ketch.app.instance.EmbeddedInstance
import com.linroid.ketch.app.instance.InstanceEntry
import com.linroid.ketch.app.instance.RemoteInstance
import com.linroid.ketch.app.instance.detail
import com.linroid.ketch.app.instance.displayName
import com.linroid.ketch.app.platform.SuccessFeedbackSupport
import com.linroid.ketch.app.platform.successFeedbackSupport
import com.linroid.ketch.app.state.AppState
import com.linroid.ketch.app.state.deviceId
import com.linroid.ketch.app.theme.KetchTheme
import com.linroid.ketch.config.NotificationMode
import com.linroid.ketch.config.NotificationSettings
import ketch.app.shared.generated.resources.Res
import ketch.app.shared.generated.resources.settings_notifications_all_finished
import ketch.app.shared.generated.resources.settings_notifications_all_finished_hint
import ketch.app.shared.generated.resources.settings_notifications_all_finished_off
import ketch.app.shared.generated.resources.settings_notifications_allow
import ketch.app.shared.generated.resources.settings_notifications_allow_button
import ketch.app.shared.generated.resources.settings_notifications_allow_hint
import ketch.app.shared.generated.resources.settings_notifications_allowed
import ketch.app.shared.generated.resources.settings_notifications_allowed_hint
import ketch.app.shared.generated.resources.settings_notifications_background
import ketch.app.shared.generated.resources.settings_notifications_background_none
import ketch.app.shared.generated.resources.settings_notifications_background_off
import ketch.app.shared.generated.resources.settings_notifications_background_on
import ketch.app.shared.generated.resources.settings_notifications_blocked
import ketch.app.shared.generated.resources.settings_notifications_blocked_hint
import ketch.app.shared.generated.resources.settings_notifications_browser
import ketch.app.shared.generated.resources.settings_notifications_devices
import ketch.app.shared.generated.resources.settings_notifications_devices_footer
import ketch.app.shared.generated.resources.settings_notifications_devices_group
import ketch.app.shared.generated.resources.settings_notifications_downloads
import ketch.app.shared.generated.resources.settings_notifications_failed
import ketch.app.shared.generated.resources.settings_notifications_finished
import ketch.app.shared.generated.resources.settings_notifications_footer
import ketch.app.shared.generated.resources.settings_notifications_mode_in_app
import ketch.app.shared.generated.resources.settings_notifications_mode_notify
import ketch.app.shared.generated.resources.settings_notifications_mode_off
import ketch.app.shared.generated.resources.settings_notifications_offline
import ketch.app.shared.generated.resources.settings_notifications_offline_hint
import ketch.app.shared.generated.resources.settings_notifications_success
import ketch.app.shared.generated.resources.settings_notifications_success_footer
import ketch.app.shared.generated.resources.settings_notifications_success_footer_phone
import ketch.app.shared.generated.resources.settings_notifications_success_sound
import ketch.app.shared.generated.resources.settings_notifications_success_sound_hint
import ketch.app.shared.generated.resources.settings_notifications_success_vibration
import ketch.app.shared.generated.resources.settings_notifications_success_vibration_hint
import ketch.app.shared.generated.resources.settings_notifications_this_device
import org.jetbrains.compose.resources.stringResource

/** Whether this app may show system notifications, where the platform asks the user first. */
internal enum class NotificationPermissionState {
  /** The user allowed notifications. */
  Granted,

  /** The user has not been asked yet. */
  Undecided,

  /** The user blocked notifications; only the platform's own settings can allow them again. */
  Denied,
}

/** The permission to notify, on platforms where the app has to ask for it. */
internal interface NotificationPermission {
  /** Whether notifications are allowed now. */
  val state: NotificationPermissionState

  /** Asks the user; call it only from a user action, such as a click. */
  fun request()
}

/**
 * The permission to notify where Settings asks for it, the web app's browser; `null` elsewhere,
 * where the host asks at the right moment itself.
 */
@Composable
internal expect fun rememberNotificationPermission(): NotificationPermission?

/**
 * Which events Ketch reports and how: as a notification, only inside the app, or not at all,
 * for which devices, and the success feedback. In the web app the browser's permission is asked
 * from here, when a report is turned on, never on load.
 */
@Composable
fun NotificationSettingsPage(state: AppState) {
  val appSettings = state.appSettings
  val settings = appSettings.config.notifications
  val permission = rememberNotificationPermission()
  val save = { transform: (NotificationSettings) -> NotificationSettings ->
    appSettings.saveNotifications(transform)
  }
  val askFor = { mode: NotificationMode ->
    if (mode == NotificationMode.Notify &&
      permission?.state == NotificationPermissionState.Undecided
    ) {
      permission.request()
    }
  }

  if (permission != null) BrowserPermissionGroup(permission)

  val finishedOn = settings.finished != NotificationMode.Off
  val notifies = settings.finished == NotificationMode.Notify ||
    settings.failed == NotificationMode.Notify
  SettingsGroup(
    title = stringResource(Res.string.settings_notifications_downloads),
    footer = stringResource(Res.string.settings_notifications_footer),
  ) {
    SettingsRow(
      title = stringResource(Res.string.settings_notifications_finished),
      trailing = {
        ModeSegmented(settings.finished) { mode ->
          save { it.copy(finished = mode) }
          askFor(mode)
        }
      },
    )
    SettingsRow(
      title = stringResource(Res.string.settings_notifications_failed),
      trailing = {
        ModeSegmented(settings.failed) { mode ->
          save { it.copy(failed = mode) }
          askFor(mode)
        }
      },
    )
    SettingsSwitchRow(
      title = stringResource(Res.string.settings_notifications_all_finished),
      description = if (finishedOn) {
        stringResource(Res.string.settings_notifications_all_finished_hint)
      } else {
        stringResource(Res.string.settings_notifications_all_finished_off)
      },
      checked = finishedOn && settings.queueDrained,
      enabled = finishedOn,
      onCheckedChange = { on -> save { it.copy(queueDrained = on) } },
    )
    SettingsSwitchRow(
      title = stringResource(Res.string.settings_notifications_background),
      description = stringResource(
        when {
          !notifies -> Res.string.settings_notifications_background_none
          settings.onlyInBackground -> Res.string.settings_notifications_background_on
          else -> Res.string.settings_notifications_background_off
        },
      ),
      checked = settings.onlyInBackground,
      enabled = notifies,
      onCheckedChange = { on -> save { it.copy(onlyInBackground = on) } },
    )
  }

  SettingsGroup(title = stringResource(Res.string.settings_notifications_devices_group)) {
    SettingsSwitchRow(
      title = stringResource(Res.string.settings_notifications_offline),
      description = stringResource(Res.string.settings_notifications_offline_hint),
      checked = settings.deviceOffline,
      onCheckedChange = { on -> save { it.copy(deviceOffline = on) } },
    )
  }

  if (successFeedbackSupport != SuccessFeedbackSupport.None) {
    SuccessFeedbackGroup(
      settings = settings,
      vibrates = successFeedbackSupport == SuccessFeedbackSupport.SoundAndVibration,
      save = save,
    )
  }

  DeviceMutesGroup(state, settings.mutedDevices) { deviceId, notify ->
    save {
      val muted = if (notify) it.mutedDevices - deviceId else it.mutedDevices + deviceId
      it.copy(mutedDevices = muted.distinct())
    }
  }
}

/**
 * Which success feedback plays when a download finishes or Discover finds downloads: a chime,
 * and on phones a vibration, each turned on by itself.
 */
@Composable
private fun SuccessFeedbackGroup(
  settings: NotificationSettings,
  vibrates: Boolean,
  save: ((NotificationSettings) -> NotificationSettings) -> Unit,
) {
  SettingsGroup(
    title = stringResource(Res.string.settings_notifications_success),
    footer = stringResource(
      if (vibrates) {
        Res.string.settings_notifications_success_footer_phone
      } else {
        Res.string.settings_notifications_success_footer
      },
    ),
  ) {
    SettingsSwitchRow(
      title = stringResource(Res.string.settings_notifications_success_sound),
      description = stringResource(Res.string.settings_notifications_success_sound_hint),
      checked = settings.successSound,
      onCheckedChange = { on -> save { it.copy(successSound = on) } },
    )
    if (vibrates) {
      SettingsSwitchRow(
        title = stringResource(Res.string.settings_notifications_success_vibration),
        description = stringResource(Res.string.settings_notifications_success_vibration_hint),
        checked = settings.successVibration,
        onCheckedChange = { on -> save { it.copy(successVibration = on) } },
      )
    }
  }
}

/** Whether the browser lets the web app notify, with the button that asks it. */
@Composable
private fun BrowserPermissionGroup(permission: NotificationPermission) {
  val colors = KetchTheme.colors
  SettingsGroup(title = stringResource(Res.string.settings_notifications_browser)) {
    when (permission.state) {
      NotificationPermissionState.Granted -> SettingsRow(
        title = stringResource(Res.string.settings_notifications_allowed),
        description = stringResource(Res.string.settings_notifications_allowed_hint),
        descriptionColor = colors.status.completed.color,
      )
      NotificationPermissionState.Undecided -> SettingsRow(
        title = stringResource(Res.string.settings_notifications_allow),
        description = stringResource(Res.string.settings_notifications_allow_hint),
        trailing = {
          KetchButton(
            text = stringResource(Res.string.settings_notifications_allow_button),
            onClick = permission::request,
            variant = KetchButtonVariant.Secondary,
            size = KetchButtonSize.Small,
          )
        },
      )
      NotificationPermissionState.Denied -> SettingsRow(
        title = stringResource(Res.string.settings_notifications_blocked),
        description = stringResource(Res.string.settings_notifications_blocked_hint),
        descriptionColor = colors.status.paused.color,
      )
    }
  }
}

/**
 * One switch per device: whether its events are reported. A remote device's events only arrive
 * while the app stays connected to it, so its switch is on only while it is also watched, and
 * turning it on watches it.
 */
@Composable
private fun DeviceMutesGroup(
  state: AppState,
  muted: List<String>,
  onChange: (deviceId: String, notify: Boolean) -> Unit,
) {
  val instances by state.instances.collectAsState()
  if (instances.size < 2) return
  SettingsGroup(
    title = stringResource(Res.string.settings_notifications_devices),
    footer = stringResource(Res.string.settings_notifications_devices_footer),
  ) {
    instances.forEach { entry ->
      key(entry.deviceId) {
        val notify = notifiesAbout(entry, muted)
        val toggle = { on: Boolean ->
          onChange(entry.deviceId, on)
          if (on && entry is RemoteInstance) state.instanceManager.setWatched(entry, true)
        }
        SettingsRow(
          title = entry.displayName.resolve(),
          description = if (entry is EmbeddedInstance) {
            stringResource(Res.string.settings_notifications_this_device)
          } else {
            entry.detail
          },
          leading = {
            DevicePennant(
              deviceId = entry.deviceId,
              name = entry.label,
              size = DevicePennantDefaults.Medium,
            )
          },
          trailing = { KetchSwitch(checked = notify, onCheckedChange = toggle) },
        )
      }
    }
  }
}

/** Whether [device]'s events are reported: not [muted], and for a remote device, watched. */
internal fun notifiesAbout(device: InstanceEntry, muted: List<String>): Boolean =
  device.deviceId !in muted && (device !is RemoteInstance || device.remoteConfig.watch)

@Composable
private fun ModeSegmented(value: NotificationMode, onSelect: (NotificationMode) -> Unit) {
  KetchSegmented(
    selected = value,
    options = NotificationMode.entries,
    label = { mode ->
      stringResource(
        when (mode) {
          NotificationMode.Notify -> Res.string.settings_notifications_mode_notify
          NotificationMode.InApp -> Res.string.settings_notifications_mode_in_app
          NotificationMode.Off -> Res.string.settings_notifications_mode_off
        },
      )
    },
    onSelect = onSelect,
  )
}
