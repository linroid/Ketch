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
import com.linroid.ketch.app.instance.EmbeddedInstance
import com.linroid.ketch.app.instance.InstanceEntry
import com.linroid.ketch.app.instance.RemoteInstance
import com.linroid.ketch.app.instance.detail
import com.linroid.ketch.app.instance.displayName
import com.linroid.ketch.app.state.AppState
import com.linroid.ketch.app.state.deviceId
import com.linroid.ketch.app.theme.KetchTheme
import com.linroid.ketch.config.NotificationMode
import com.linroid.ketch.config.NotificationSettings

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
 * and for which devices. In the web app the browser's permission is asked from here, when a
 * report is turned on, never on load.
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
    title = "Downloads",
    footer = "Notify uses system notifications. In app only stays inside Ketch.",
  ) {
    SettingsRow(
      title = "Download finished",
      trailing = {
        ModeSegmented(settings.finished) { mode ->
          save { it.copy(finished = mode) }
          askFor(mode)
        }
      },
    )
    SettingsRow(
      title = "Download failed",
      trailing = {
        ModeSegmented(settings.failed) { mode ->
          save { it.copy(failed = mode) }
          askFor(mode)
        }
      },
    )
    SettingsSwitchRow(
      title = "All downloads finished",
      description = if (finishedOn) {
        "One message when the queue is empty."
      } else {
        "Follows Download finished, which is off."
      },
      checked = finishedOn && settings.queueDrained,
      enabled = finishedOn,
      onCheckedChange = { on -> save { it.copy(queueDrained = on) } },
    )
    SettingsSwitchRow(
      title = "Only when Ketch is in the background",
      description = when {
        !notifies -> "Nothing is set to Notify."
        settings.onlyInBackground -> "While Ketch is in front, they show inside it."
        else -> "Notifications also show while Ketch is in front."
      },
      checked = settings.onlyInBackground,
      enabled = notifies,
      onCheckedChange = { on -> save { it.copy(onlyInBackground = on) } },
    )
  }

  SettingsGroup(title = "Devices") {
    SettingsSwitchRow(
      title = "Devices going offline",
      description = "Shown as a message inside Ketch.",
      checked = settings.deviceOffline,
      onCheckedChange = { on -> save { it.copy(deviceOffline = on) } },
    )
  }

  DeviceMutesGroup(state, settings.mutedDevices) { deviceId, notify ->
    save {
      val muted = if (notify) it.mutedDevices - deviceId else it.mutedDevices + deviceId
      it.copy(mutedDevices = muted.distinct())
    }
  }
}

/** Whether the browser lets the web app notify, with the button that asks it. */
@Composable
private fun BrowserPermissionGroup(permission: NotificationPermission) {
  val colors = KetchTheme.colors
  SettingsGroup(title = "Browser notifications") {
    when (permission.state) {
      NotificationPermissionState.Granted -> SettingsRow(
        title = "Notifications are allowed",
        description = "Shown while the Ketch tab is in the background.",
        descriptionColor = colors.status.completed.color,
      )
      NotificationPermissionState.Undecided -> SettingsRow(
        title = "Allow notifications",
        description = "Your browser asks once. Ketch notifies only as set below.",
        trailing = {
          KetchButton(
            text = "Allow",
            onClick = permission::request,
            variant = KetchButtonVariant.Secondary,
            size = KetchButtonSize.Small,
          )
        },
      )
      NotificationPermissionState.Denied -> SettingsRow(
        title = "Notifications are blocked",
        description = "Allow them for this site in your browser's settings.",
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
    title = "Notify me about these devices",
    footer = "Ketch stays connected to the devices it reports on.",
  ) {
    instances.forEach { entry ->
      key(entry.deviceId) {
        val notify = notifiesAbout(entry, muted)
        val toggle = { on: Boolean ->
          onChange(entry.deviceId, on)
          if (on && entry is RemoteInstance) state.instanceManager.setWatched(entry, true)
        }
        SettingsRow(
          title = entry.displayName,
          description = if (entry is EmbeddedInstance) "This device" else entry.detail,
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
      when (mode) {
        NotificationMode.Notify -> "Notify"
        NotificationMode.InApp -> "In app only"
        NotificationMode.Off -> "Off"
      }
    },
    onSelect = onSelect,
  )
}
