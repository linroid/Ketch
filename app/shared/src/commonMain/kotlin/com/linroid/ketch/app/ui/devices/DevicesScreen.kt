package com.linroid.ketch.app.ui.devices

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.ui.Modifier
import com.linroid.ketch.app.components.KetchButton
import com.linroid.ketch.app.components.KetchButtonVariant
import com.linroid.ketch.app.components.KetchSidebarItem
import com.linroid.ketch.app.icons.KetchIcon
import com.linroid.ketch.app.instance.InstanceEntry
import com.linroid.ketch.app.instance.RemoteInstance
import com.linroid.ketch.app.state.AppState
import com.linroid.ketch.app.theme.KetchTheme
import com.linroid.ketch.remote.ConnectionState

/**
 * The Devices page: every device the app knows, which one the app shows, and a way to add
 * another. Choosing a device switches the app to it.
 */
@Composable
fun DevicesScreen(state: AppState) {
  val instances by state.instances.collectAsState()
  val active by state.activeInstance.collectAsState()
  val spacing = KetchTheme.spacing
  Column(
    modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(spacing.s6),
    verticalArrangement = Arrangement.spacedBy(spacing.s1),
  ) {
    Text(
      text = "Devices",
      style = KetchTheme.typography.pageTitle,
      color = KetchTheme.colors.textPrimary,
      modifier = Modifier.padding(bottom = spacing.s3),
    )
    instances.forEach { entry ->
      key(entry) {
        DeviceItem(
          entry = entry,
          selected = entry == active,
          onClick = { state.switchInstance(entry) },
        )
      }
    }
    KetchButton(
      text = "Add a device",
      onClick = { state.showAddRemoteDialog = true },
      variant = KetchButtonVariant.Secondary,
      leadingIcon = KetchIcon.Plus,
      modifier = Modifier.padding(top = spacing.s3),
    )
  }
}

@Composable
private fun DeviceItem(entry: InstanceEntry, selected: Boolean, onClick: () -> Unit) {
  val status = if (entry is RemoteInstance) {
    val connection by entry.connectionState.collectAsState()
    connection.label()
  } else {
    "This device"
  }
  KetchSidebarItem(
    label = entry.label,
    icon = if (entry is RemoteInstance) KetchIcon.Remote else KetchIcon.Local,
    selected = selected,
    onClick = onClick,
    trailing = {
      Text(
        text = status,
        style = KetchTheme.typography.caption,
        color = KetchTheme.colors.textTertiary,
      )
    },
  )
}

private fun ConnectionState.label(): String = when (this) {
  ConnectionState.Connected -> "Connected"
  ConnectionState.Connecting -> "Connecting…"
  is ConnectionState.Disconnected -> "Offline"
  ConnectionState.Unauthorized -> "Needs a token"
}
