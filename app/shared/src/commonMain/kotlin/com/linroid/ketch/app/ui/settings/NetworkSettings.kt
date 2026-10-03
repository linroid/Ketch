package com.linroid.ketch.app.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import com.linroid.ketch.api.NetworkInterfaceInfo
import com.linroid.ketch.app.components.KetchButton
import com.linroid.ketch.app.components.KetchButtonSize
import com.linroid.ketch.app.components.KetchButtonVariant
import com.linroid.ketch.app.components.KetchChip
import com.linroid.ketch.app.i18n.SEPARATOR
import com.linroid.ketch.app.icons.KetchIcon
import com.linroid.ketch.app.instance.InstanceEntry
import com.linroid.ketch.app.state.AppState
import com.linroid.ketch.app.theme.KetchTheme
import com.linroid.ketch.app.util.pairingAddresses
import ketch.app.shared.generated.resources.Res
import ketch.app.shared.generated.resources.settings_action_refresh
import ketch.app.shared.generated.resources.settings_network_gone
import ketch.app.shared.generated.resources.settings_network_http_only
import ketch.app.shared.generated.resources.settings_network_looking
import ketch.app.shared.generated.resources.settings_network_networks
import ketch.app.shared.generated.resources.settings_network_none_found
import ketch.app.shared.generated.resources.settings_network_resets
import ketch.app.shared.generated.resources.settings_network_spread
import ketch.app.shared.generated.resources.settings_network_system_default
import ketch.app.shared.generated.resources.settings_network_unsupported
import org.jetbrains.compose.resources.stringResource

/**
 * The networks [device] spreads its HTTP requests over, as chips: the system's default
 * connection, or any set of its interfaces. The choice applies at once and lasts until the
 * device restarts.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun NetworkSettings(state: AppState, device: InstanceEntry) {
  val controller = state.settingsFor(device)
  LaunchedEffect(controller) { controller.loadNetworks() }
  val networks = controller.networks
  val error = controller.networkError
  val spacing = KetchTheme.spacing
  // A device that could not be asked has nothing to show besides the error.
  val unread = error != null && networks?.supported != true
  DeviceSettingsError(error, loaded = !unread) { controller.loadNetworks() }
  when {
    networks == null ->
      SettingsLoading(stringResource(Res.string.settings_network_looking, device.label))
    unread -> Unit
    !networks.supported -> SettingsNotice(
      text = stringResource(Res.string.settings_network_unsupported, device.label),
      tone = NoticeTone.Info,
    )
    else -> {
      val selected = networks.config.interfaceIds
      val available = networks.available.associateBy { it.id }
      // Keep selected interfaces that went away visible so they can be unticked.
      val missing = selected.filter { it !in available }
        .map { NetworkInterfaceInfo(id = it, name = it, addresses = emptyList()) }
      SettingsGroup(
        title = stringResource(Res.string.settings_network_networks),
        footer = stringResource(Res.string.settings_network_resets, device.label),
        action = {
          KetchButton(
            text = stringResource(Res.string.settings_action_refresh),
            onClick = { controller.loadNetworks() },
            variant = KetchButtonVariant.Ghost,
            size = KetchButtonSize.Small,
            leadingIcon = KetchIcon.Retry,
          )
        },
      ) {
        SettingsRow(
          title = stringResource(Res.string.settings_network_spread),
          description = if (networks.available.isEmpty() && missing.isEmpty()) {
            stringResource(Res.string.settings_network_none_found)
          } else {
            stringResource(Res.string.settings_network_http_only)
          },
        ) {
          FlowRow(
            horizontalArrangement = Arrangement.spacedBy(spacing.s2),
            verticalArrangement = Arrangement.spacedBy(spacing.s2),
          ) {
            KetchChip(
              label = stringResource(Res.string.settings_network_system_default),
              selected = selected.isEmpty(),
              onClick = { if (selected.isNotEmpty()) controller.selectNetworks(emptyList()) },
            )
            (networks.available + missing).forEach { info ->
              val ticked = info.id in selected
              KetchChip(
                label = chipLabel(info, gone = info.id !in available),
                selected = ticked,
                leadingIcon = if (info.id in available) KetchIcon.Network else KetchIcon.Warning,
                onClick = {
                  val ids = if (ticked) selected - info.id else selected + info.id
                  controller.selectNetworks(ids)
                },
              )
            }
          }
        }
      }
    }
  }
}

/**
 * "en0 · 192.168.1.20", "utun3" without a routable IPv4 address, whose IPv6 ones are too long
 * for a chip, or "en5 · not connected".
 */
@Composable
private fun chipLabel(info: NetworkInterfaceInfo, gone: Boolean): String {
  if (gone) return stringResource(Res.string.settings_network_gone, info.name)
  val address = pairingAddresses(listOf(info)).firstOrNull()
  return if (address == null) info.name else info.name + SEPARATOR + address
}
