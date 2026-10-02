package com.linroid.ketch.app.ui.devices

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.input.ImeAction
import com.linroid.ketch.app.components.KetchButton
import com.linroid.ketch.app.components.KetchButtonVariant
import com.linroid.ketch.app.components.KetchIconButton
import com.linroid.ketch.app.components.KetchMenu
import com.linroid.ketch.app.components.KetchTextField
import com.linroid.ketch.app.icons.KetchIcon
import com.linroid.ketch.app.instance.DevicePresence
import com.linroid.ketch.app.instance.RemoteInstance
import com.linroid.ketch.app.state.AppState
import com.linroid.ketch.app.theme.KetchTheme
import com.linroid.ketch.app.ui.common.AdaptiveModal

/**
 * The ⋯ button of a device card and its menu: show the device's downloads, open its settings,
 * rename it and, for a remote device, whether the app stays connected to it and removing it.
 */
@Composable
internal fun DeviceMenuButton(
  state: AppState,
  device: DevicePresence,
  onRename: () -> Unit,
  onRemove: () -> Unit,
) {
  var open by remember { mutableStateOf(false) }
  val remote = device.entry as? RemoteInstance
  Box {
    KetchIconButton(
      icon = KetchIcon.More,
      onClick = { open = true },
      contentDescription = "More for ${device.name}",
    )
    KetchMenu(expanded = open, onDismissRequest = { open = false }, title = device.name) {
      item("Show downloads", onClick = { state.showDeviceTab(device.entry) }, icon = KetchIcon.All)
      item(
        label = "Settings for this device…",
        onClick = { state.openDeviceSettings(device.entry) },
        icon = KetchIcon.Settings,
      )
      divider()
      item("Rename…", onClick = onRename)
      if (remote != null) {
        item(
          label = "Stay connected",
          onClick = { state.instanceManager.setWatched(remote, !device.watched) },
          checked = device.watched,
        )
        divider()
        item("Remove…", onClick = onRemove, icon = KetchIcon.Trash, destructive = true)
      }
    }
  }
}

/**
 * Asks for a new name for [device]. A remote device takes it at once; this device's name is the
 * one other devices see it by, used from the next launch. A blank name goes back to the
 * device's own.
 */
@Composable
internal fun RenameDeviceDialog(state: AppState, device: DevicePresence, onDismiss: () -> Unit) {
  val remote = device.entry as? RemoteInstance
  val current = remote?.remoteConfig?.name ?: state.appSettings.config.name.orEmpty()
  var name by remember { mutableStateOf(current) }
  val focus = remember { FocusRequester() }
  val save = {
    state.renameDevice(device.entry, name)
    onDismiss()
  }
  AdaptiveModal(
    onDismissRequest = onDismiss,
    title = { Text("Rename ${device.name}") },
    dismissible = name == current,
    dismissButton = {
      KetchButton(text = "Cancel", variant = KetchButtonVariant.Secondary, onClick = onDismiss)
    },
    confirmButton = { KetchButton(text = "Rename", onClick = save) },
  ) {
    Text(
      text = if (remote != null) {
        "Leave it empty for the name it announces."
      } else {
        "Your other devices see this name after a restart."
      },
      style = KetchTheme.typography.body,
      color = KetchTheme.colors.textSecondary,
    )
    KetchTextField(
      value = name,
      onValueChange = { name = it },
      placeholder = remote?.let { "${it.host}:${it.port}" } ?: device.entry.label,
      label = "Name",
      keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
      keyboardActions = KeyboardActions(onDone = { save() }),
      modifier = Modifier.fillMaxWidth().focusRequester(focus),
    )
    // Here, in the modal's own window, the field is attached by the time the effect runs.
    LaunchedEffect(focus) { runCatching { focus.requestFocus() } }
  }
}

/** Asks before removing [device], whose downloads keep running on it. */
@Composable
internal fun RemoveDeviceDialog(state: AppState, device: DevicePresence, onDismiss: () -> Unit) {
  AdaptiveModal(
    onDismissRequest = onDismiss,
    title = { Text("Remove ${device.name}?") },
    dismissButton = {
      KetchButton(text = "Cancel", variant = KetchButtonVariant.Secondary, onClick = onDismiss)
    },
    confirmButton = {
      KetchButton(
        text = "Remove",
        variant = KetchButtonVariant.Danger,
        onClick = {
          state.removeInstance(device.entry)
          onDismiss()
        },
      )
    },
  ) {
    Text(
      text = "Ketch stops showing its downloads here. They keep running on ${device.name}, " +
        "and you can add it again later.",
      style = KetchTheme.typography.body,
      color = KetchTheme.colors.textSecondary,
    )
  }
}
