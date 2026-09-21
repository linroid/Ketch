package com.linroid.ketch.app.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.linroid.ketch.app.components.KetchButton
import com.linroid.ketch.app.components.KetchButtonVariant
import com.linroid.ketch.app.instance.ServerState
import com.linroid.ketch.app.theme.KetchTheme
import com.linroid.ketch.config.ServerConfig
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.OutlinedTextField
import androidx.compose.ui.text.input.KeyboardType

/**
 * Daemon server settings, plus start/stop for the local server.
 *
 * Saved values are read when the server next starts, so the card offers
 * a restart while it is running.
 *
 * @param config saved server settings.
 * @param serverState whether the local server is running.
 * @param onSave persist the edited settings.
 * @param onStart start the local server on the saved port.
 * @param onStop stop the local server.
 */
@Composable
fun ServerSettingsCard(
  config: ServerConfig,
  serverState: ServerState,
  compact: Boolean,
  onSave: (ServerConfig) -> Unit,
  onStart: () -> Unit,
  onStop: () -> Unit,
  modifier: Modifier = Modifier,
) {
  val colors = KetchTheme.colors
  var port by remember(config) { mutableStateOf(config.port.toString()) }
  var token by remember(config) {
    mutableStateOf(config.apiToken.orEmpty())
  }
  var mdns by remember(config) { mutableStateOf(config.mdnsEnabled) }

  val parsedPort = port.toIntOrNull()
  val portError = parsedPort == null || parsedPort !in 1..65535
  val edited = if (portError) null else config.copy(
    port = parsedPort,
    apiToken = token.trim().ifBlank { null },
    mdnsEnabled = mdns,
  )
  val running = serverState as? ServerState.Running

  SettingsCard(
    title = "Server",
    description = "Expose this instance over HTTP so other devices " +
      "and the web app can control it.",
    compact = compact,
    modifier = modifier,
  ) {
    Text(
      text = running?.let { "Running on port ${it.port}" }
        ?: "Not running",
      style = KetchTheme.typography.bodySmall,
      color = if (running != null) colors.success else colors.onSurfaceVariant,
    )

    SettingsTextField(
      value = port,
      onValueChange = { port = it },
      label = "Port",
      supportingText = if (portError) {
        "Enter a port between 1 and 65535."
      } else {
        "Clients connect to this port on your network address."
      },
      numeric = true,
    )
    OutlinedTextField(
      value = token,
      onValueChange = { token = it },
      label = { Text("API token (optional)") },
      placeholder = { Text("Leave empty for no authentication") },
      supportingText = {
        Text("Clients must send this token to control this instance.")
      },
      singleLine = true,
      visualTransformation = PasswordVisualTransformation(),
      keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
      shape = RoundedCornerShape(12.dp),
      modifier = Modifier.fillMaxWidth(),
    )
    Row(
      modifier = Modifier.fillMaxWidth(),
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
      Text(
        text = "Announce on the local network (mDNS)",
        style = KetchTheme.typography.bodySmall,
        color = colors.onSurfaceVariant,
        modifier = Modifier.weight(1f),
      )
      Switch(
        checked = mdns,
        onCheckedChange = { mdns = it },
        modifier = Modifier.semantics {
          contentDescription = "Announce the server on the local network"
        },
      )
    }

    SettingsHint(
      if (running != null) {
        "Restart the server to apply saved changes."
      } else {
        "Changes apply the next time the server starts."
      },
    )

    FlowRow(
      modifier = Modifier.fillMaxWidth(),
      horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
      verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
      if (running != null) {
        KetchButton(
          text = "Stop",
          onClick = onStop,
          variant = KetchButtonVariant.Ghost,
        )
        KetchButton(
          text = "Restart",
          onClick = {
            onStop()
            onStart()
          },
          variant = KetchButtonVariant.Secondary,
        )
      } else {
        KetchButton(
          text = "Start",
          onClick = onStart,
          variant = KetchButtonVariant.Secondary,
          enabled = edited != null,
        )
      }
      KetchButton(
        text = "Save",
        onClick = { edited?.let(onSave) },
        enabled = edited != null && edited != config,
      )
    }
  }
}
