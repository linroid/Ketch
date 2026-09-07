package com.linroid.ketch.app.ui.dialog

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.linroid.ketch.app.components.KetchButton
import com.linroid.ketch.app.components.KetchButtonSize
import com.linroid.ketch.app.components.KetchButtonVariant
import com.linroid.ketch.app.components.KetchIconButton
import com.linroid.ketch.app.icons.KetchIcon
import com.linroid.ketch.app.instance.ServerState
import com.linroid.ketch.app.theme.KetchTheme

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
        horizontalArrangement = Arrangement.spacedBy(8.dp),
      ) {
        Text(
          text = "Server on :${serverState.port}",
          style = KetchTheme.typography.labelSmall,
          color = KetchTheme.colors.primary,
        )
        KetchIconButton(
          icon = KetchIcon.Close,
          contentDescription = "Stop server",
          onClick = onStopServer,
          size = KetchButtonSize.Small,
          tint = KetchTheme.colors.error,
        )
      }
    }
    is ServerState.Stopped -> {
      KetchButton(
        text = "Start server",
        onClick = { onStartServer(8642, null) },
        leadingIcon = KetchIcon.Local,
        variant = KetchButtonVariant.Ghost,
        size = KetchButtonSize.Small,
        modifier = Modifier.padding(top = 2.dp),
      )
    }
  }
}
