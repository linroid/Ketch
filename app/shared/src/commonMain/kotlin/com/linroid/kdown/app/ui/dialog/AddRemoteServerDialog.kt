package com.linroid.kdown.app.ui.dialog

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
fun AddRemoteServerDialog(
  onDismiss: () -> Unit,
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
