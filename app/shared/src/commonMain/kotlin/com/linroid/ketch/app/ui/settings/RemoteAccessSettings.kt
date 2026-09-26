package com.linroid.ketch.app.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp
import com.linroid.ketch.app.components.KetchButton
import com.linroid.ketch.app.components.KetchButtonSize
import com.linroid.ketch.app.components.KetchButtonVariant
import com.linroid.ketch.app.instance.ServerState
import com.linroid.ketch.app.state.portError
import com.linroid.ketch.app.theme.KetchTheme
import com.linroid.ketch.config.ServerConfig
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/**
 * The local server that lets the web app and other devices control
 * this one.
 *
 * Settings are saved immediately but read when the server starts, so a
 * running server offers a restart once they differ.
 *
 * @param config saved server settings.
 * @param onChange persist edited settings.
 */
@Composable
fun RemoteAccessSettings(
  config: ServerConfig,
  serverState: ServerState,
  onChange: (ServerConfig) -> Unit,
  onStart: () -> Unit,
  onStop: () -> Unit,
) {
  val colors = KetchTheme.colors
  val running = serverState as? ServerState.Running
  // Auto start only matters at launch, so it never calls for a restart.
  val restartNeeded = running != null &&
    running.config.copy(autoStart = config.autoStart) != config
  val lanOpen = !config.isLoopbackOnly

  Column(verticalArrangement = Arrangement.spacedBy(24.dp)) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
      SettingsGroup {
        SettingsSwitchRow(
          title = "Server",
          checked = running != null,
          onCheckedChange = { on -> if (on) onStart() else onStop() },
          description = when (serverState) {
            is ServerState.Running -> "Running on port ${serverState.port}"
            is ServerState.Failed -> "Couldn't start: ${serverState.message}"
            ServerState.Stopped -> "Turn on to use the web app or control this device " +
              "from another one."
          },
          descriptionColor = when (serverState) {
            is ServerState.Running -> colors.success
            is ServerState.Failed -> colors.error
            ServerState.Stopped -> colors.onSurfaceVariant
          },
        )
        SettingsSwitchRow(
          title = "Start automatically",
          description = "Turn the server on whenever Ketch opens.",
          checked = config.autoStart,
          onCheckedChange = { onChange(config.copy(autoStart = it)) },
        )
      }
      if (restartNeeded) {
        SettingsNotice(
          text = "Restart the server to apply your changes.",
          tone = NoticeTone.Warning,
          action = {
            KetchButton(
              text = "Restart",
              onClick = {
                onStop()
                onStart()
              },
              variant = KetchButtonVariant.Secondary,
              size = KetchButtonSize.Small,
            )
          },
        )
      }
    }

    SettingsGroup(title = "Access") {
      SettingsSwitchRow(
        title = "Allow other devices",
        description = if (lanOpen) {
          "Devices that can reach this one over the network can connect."
        } else {
          "Only apps on this device can connect."
        },
        checked = lanOpen,
        onCheckedChange = { open ->
          onChange(
            config.copy(
              host = if (open) ServerConfig.ANY_HOST else ServerConfig.LOOPBACK_HOST,
            ),
          )
        },
      )
      val token = config.apiToken.orEmpty()
      SettingsRow(
        title = "Access token",
        description = when {
          token.isNotEmpty() -> "Other devices must enter this token to connect."
          lanOpen -> "Without a token, anyone on your network can control your downloads."
          else -> "Optional while only this device can connect."
        },
        descriptionColor = if (token.isEmpty() && lanOpen) colors.warning
          else colors.onSurfaceVariant,
      ) {
        SettingsTextInput(
          value = token,
          onCommit = { onChange(config.copy(apiToken = it.ifBlank { null })) },
          placeholder = "No token",
          secret = true,
          mono = true,
          actions = {
            KetchButton(
              text = "Generate",
              onClick = { onChange(config.copy(apiToken = newToken())) },
              variant = KetchButtonVariant.Ghost,
              size = KetchButtonSize.Small,
            )
          },
        )
      }
      SettingsRow(
        title = "Port",
        description = "Other devices connect to this port.",
      ) {
        SettingsTextInput(
          value = config.port.toString(),
          onCommit = { onChange(config.copy(port = it.toInt())) },
          normalize = { it.trim().toIntOrNull()?.toString() ?: it.trim() },
          validate = ::portError,
          numeric = true,
          mono = true,
          width = 140.dp,
        )
      }
      SettingsSwitchRow(
        title = "Announce on the local network",
        description = "Lets Ketch on other devices find this one automatically.",
        checked = config.mdnsEnabled && lanOpen,
        enabled = lanOpen,
        onCheckedChange = { onChange(config.copy(mdnsEnabled = it)) },
      )
    }
  }
}

/** A random, unguessable token for the server. */
@OptIn(ExperimentalUuidApi::class)
private fun newToken(): String = Uuid.random().toHexString()
