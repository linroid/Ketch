package com.linroid.ketch.app.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.linroid.ketch.api.NetworkInterfaceInfo
import com.linroid.ketch.app.components.KetchBadge
import com.linroid.ketch.app.components.KetchBadgeTone
import com.linroid.ketch.app.components.KetchButton
import com.linroid.ketch.app.components.KetchButtonSize
import com.linroid.ketch.app.components.KetchButtonVariant
import com.linroid.ketch.app.state.InstanceSettingsController
import com.linroid.ketch.app.theme.KetchTheme

/**
 * Picks the network interfaces the active instance spreads HTTP
 * requests over.
 *
 * @param instanceLabel name of the instance whose interfaces are shown.
 */
@Composable
fun NetworkSettings(
  controller: InstanceSettingsController,
  instanceLabel: String,
) {
  LaunchedEffect(controller) { controller.loadNetworks() }
  val networks = controller.networks
  Column(verticalArrangement = Arrangement.spacedBy(24.dp)) {
    controller.networkError?.let {
      SettingsNotice(text = it, tone = NoticeTone.Error)
    }
    when {
      networks == null -> Text(
        text = "Looking for network interfaces on $instanceLabel…",
        style = KetchTheme.typography.bodyMedium,
        color = KetchTheme.colors.onSurfaceVariant,
      )
      !networks.supported -> SettingsNotice(
        text = "$instanceLabel can't choose network interfaces, so downloads use " +
          "the system's default connection.",
        tone = NoticeTone.Info,
      )
      else -> {
        val selected = networks.config.interfaceIds
        val available = networks.available.associateBy { it.id }
        // Keep selected interfaces that went away visible so they can be
        // unticked.
        val missing = selected.filter { it !in available }
          .map { NetworkInterfaceInfo(id = it, name = it, addresses = emptyList()) }
        SettingsGroup(
          title = "Network interfaces",
          footer = if (selected.isEmpty()) {
            "None selected: downloads use the system's default connection."
          } else {
            "HTTP downloads are spread across the ticked interfaces; FTP and " +
              "torrents use the default connection. Resets when $instanceLabel restarts."
          },
          action = {
            KetchButton(
              text = "Refresh",
              onClick = { controller.loadNetworks() },
              variant = KetchButtonVariant.Ghost,
              size = KetchButtonSize.Small,
            )
          },
        ) {
          if (networks.available.isEmpty() && missing.isEmpty()) {
            SettingsRow(
              title = "No interfaces found",
              description = "Connect to a network, then refresh.",
            )
          }
          (networks.available + missing).forEach { info ->
            val checked = info.id in selected
            val gone = info.id !in available
            SettingsRow(
              title = info.name,
              description = if (gone) {
                "Not connected right now"
              } else {
                info.addresses.joinToString(", ").ifEmpty { "No address" }
              },
              modifier = Modifier.toggleable(
                value = checked,
                role = Role.Checkbox,
                onValueChange = { tick ->
                  controller.selectNetworks(
                    if (tick) selected + info.id else selected - info.id,
                  )
                },
              ),
              leading = { Checkbox(checked = checked, onCheckedChange = null) },
              trailing = if (gone) {
                { KetchBadge("Offline", KetchBadgeTone.Warning) }
              } else {
                null
              },
            )
          }
        }
      }
    }
  }
}
