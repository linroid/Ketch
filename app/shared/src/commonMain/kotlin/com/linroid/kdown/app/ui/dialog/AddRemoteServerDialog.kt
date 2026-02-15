package com.linroid.kdown.app.ui.dialog

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.TextButton
import androidx.compose.material3.AssistChip
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.linroid.kdown.app.instance.DiscoveredServer

@Composable
fun AddRemoteServerDialog(
  onDismiss: () -> Unit,
  discovering: Boolean,
  discoveredServers: List<DiscoveredServer>,
  onDiscover: (port: Int) -> Unit,
  onAdd: (host: String, port: Int, token: String?) -> Unit,
) {
  var host by remember { mutableStateOf("") }
  var port by remember { mutableStateOf("8642") }
  var token by remember { mutableStateOf("") }
  val isValidHost = host.isNotBlank()
  val isValidPort = port.toIntOrNull()?.let {
    it in 1..65535
  } ?: false

  AlertDialog(
    onDismissRequest = onDismiss,
    title = { Text("Add Remote Server") },
    text = {
      Column(
        modifier = Modifier.verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp),
      ) {
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
        Button(
          onClick = {
            onDiscover(port.toIntOrNull() ?: 8642)
          },
          enabled = isValidPort && !discovering,
          modifier = Modifier.fillMaxWidth(),
        ) {
          if (discovering) {
            CircularProgressIndicator(
              modifier = Modifier.padding(end = 8.dp).size(16.dp),
              strokeWidth = 2.dp,
            )
            Text("Discovering...")
          } else {
            Text("Discover on LAN")
          }
        }
        if (discoveredServers.isNotEmpty()) {
          Text("Discovered Servers")
          discoveredServers.forEach { server ->
            AssistChip(
              onClick = {
                host = server.host
                port = server.port.toString()
              },
              label = {
                Text(
                  "${server.name} · ${server.host}:${server.port}" +
                    if (server.tokenRequired) " · token required" else ""
                )
              },
            )
          }
        }
      }
    },
    confirmButton = {
      Button(
        onClick = {
          onAdd(
            host.trim(),
            port.toIntOrNull() ?: 8642,
            token.trim().ifBlank { null }
          )
        },
        enabled = isValidHost && isValidPort,
      ) {
        Text("Add")
      }
    },
    dismissButton = {
      TextButton(onClick = onDismiss) {
        Text("Cancel")
      }
    }
  )
}
