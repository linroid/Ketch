package com.linroid.ketch.app.ui.dialog

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import com.linroid.ketch.app.components.KetchButton
import com.linroid.ketch.app.components.KetchButtonVariant
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.linroid.ketch.app.state.DiscoveryState

@Composable
fun AddRemoteServerDialog(
  onDismiss: () -> Unit,
  discoveryState: DiscoveryState,
  onDiscover: (port: Int) -> Unit,
  onStopDiscovery: () -> Unit,
  onAdd: (host: String, port: Int, token: String?) -> Unit,
  initialHost: String = "",
  initialPort: String = "8642",
  authRequired: Boolean = false,
) {
  var host by remember { mutableStateOf(initialHost) }
  var port by remember { mutableStateOf(initialPort) }
  var token by remember { mutableStateOf("") }
  val isValidHost = host.isNotBlank()
  val isValidPort = port.toIntOrNull()?.let {
    it in 1..65535
  } ?: false

  val discovering =
    discoveryState is DiscoveryState.Discovering
  val servers = when (discoveryState) {
    is DiscoveryState.Discovering -> discoveryState.servers
    is DiscoveryState.Finished -> discoveryState.servers
    else -> emptyList()
  }

  AlertDialog(
    onDismissRequest = onDismiss,
    title = {
      Text(
        if (authRequired) "Authentication Required"
        else "Add Remote Server"
      )
    },
    text = {
      Column(
        modifier = Modifier.verticalScroll(
          rememberScrollState()
        ),
        verticalArrangement = Arrangement.spacedBy(12.dp),
      ) {
        if (authRequired) {
          Text(
            text = "The server requires an API token" +
              " to connect. Please enter the token below.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
          )
        }
        OutlinedTextField(
          value = host,
          onValueChange = { host = it },
          modifier = Modifier.fillMaxWidth(),
          label = { Text("Host") },
          singleLine = true,
          placeholder = { Text("192.168.1.5") },
        )
        OutlinedTextField(
          value = port,
          onValueChange = { port = it },
          modifier = Modifier.fillMaxWidth(),
          label = { Text("Port") },
          singleLine = true,
          placeholder = { Text("8642") },
          isError = port.isNotBlank() && !isValidPort,
          supportingText = if (port.isNotBlank() &&
            !isValidPort
          ) {
            { Text("Port must be 1-65535") }
          } else {
            null
          }
        )
        OutlinedTextField(
          value = token,
          onValueChange = { token = it },
          modifier = Modifier.fillMaxWidth(),
          label = { Text("API Token") },
          singleLine = true,
          placeholder = { Text("Optional") },
        )
        if (discovering) {
          KetchButton(
            text = "Stop",
            onClick = onStopDiscovery,
            variant = KetchButtonVariant.Secondary,
            modifier = Modifier.fillMaxWidth(),
          )
        } else {
          KetchButton(
            text = "Discover on LAN",
            onClick = { onDiscover(port.toIntOrNull() ?: 8642) },
            enabled = isValidPort,
            modifier = Modifier.fillMaxWidth(),
          )
        }
        if (discoveryState is DiscoveryState.Error) {
          Text(
            text = discoveryState.message,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
          )
        }
        if (discoveryState is DiscoveryState.Finished &&
          servers.isEmpty()
        ) {
          Text(
            text = "No servers found on your network",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme
              .onSurfaceVariant,
          )
        }
        if (servers.isNotEmpty()) {
          Text(
            text = "Found on your network",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme
              .onSurfaceVariant,
          )
          servers.forEach { server ->
            ListItem(
              headlineContent = { Text(server.name) },
              supportingContent = {
                val info = buildString {
                  append("${server.host}:${server.port}")
                  if (server.tokenRequired) {
                    append(" · Token required")
                  }
                }
                Text(info)
              },
              leadingContent = {
                Icon(
                  Icons.Filled.Dns,
                  contentDescription = null,
                )
              },
              modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(8.dp))
                .clickable {
                  host = server.host
                  port = server.port.toString()
                },
              colors = ListItemDefaults.colors(
                containerColor =
                  MaterialTheme.colorScheme.surfaceVariant
                    .copy(alpha = 0.5f),
              ),
            )
          }
        }
      }
    },
    confirmButton = {
      KetchButton(
        text = if (authRequired) "Connect" else "Add",
        onClick = {
          onAdd(
            host.trim(),
            port.toIntOrNull() ?: 8642,
            token.trim().ifBlank { null },
          )
        },
        enabled = isValidHost && isValidPort,
      )
    },
    dismissButton = {
      KetchButton(
        text = "Cancel",
        onClick = onDismiss,
        variant = KetchButtonVariant.Ghost,
      )
    }
  )
}
