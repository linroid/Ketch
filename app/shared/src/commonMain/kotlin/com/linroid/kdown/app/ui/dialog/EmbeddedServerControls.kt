package com.linroid.kdown.app.ui.dialog

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Computer
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.linroid.kdown.app.backend.ServerState

@Composable
fun EmbeddedServerControls(
  serverState: ServerState,
  onStartServer: (port: Int, token: String?) -> Unit,
  onStopServer: () -> Unit,
) {
  when (serverState) {
    is ServerState.Running -> {
      Row(
        modifier = Modifier.padding(top = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement =
          Arrangement.spacedBy(8.dp)
      ) {
        Text(
          text = "Server on :${serverState.port}",
          style = MaterialTheme.typography.labelSmall,
          color = MaterialTheme.colorScheme.primary,
        )
        FilledTonalIconButton(
          onClick = onStopServer,
          modifier = Modifier.size(24.dp),
          colors = IconButtonDefaults
            .filledTonalIconButtonColors(
              containerColor = MaterialTheme.colorScheme
                .errorContainer,
              contentColor = MaterialTheme.colorScheme
                .onErrorContainer
            )
        ) {
          Icon(
            imageVector = Icons.Filled.Close,
            contentDescription = "Stop server",
            modifier = Modifier.size(14.dp),
          )
        }
      }
    }
    is ServerState.Stopped -> {
      TextButton(
        onClick = { onStartServer(8642, null) },
        modifier = Modifier.padding(top = 2.dp),
        contentPadding = PaddingValues(
          horizontal = 8.dp, vertical = 0.dp,
        )
      ) {
        Icon(
          imageVector = Icons.Filled.Computer,
          contentDescription = null,
          modifier = Modifier.size(14.dp),
        )
        Spacer(Modifier.size(4.dp))
        Text(
          text = "Start Server",
          style = MaterialTheme.typography.labelSmall,
        )
      }
    }
  }
}
