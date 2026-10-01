package com.linroid.ketch.app.ui.dialog

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import com.linroid.ketch.app.components.KetchButton
import com.linroid.ketch.app.components.KetchButtonSize
import com.linroid.ketch.app.components.KetchButtonVariant
import com.linroid.ketch.app.components.KetchIconButton
import com.linroid.ketch.app.icons.KetchIcon
import com.linroid.ketch.app.instance.ServerState
import com.linroid.ketch.app.theme.KetchTheme

/**
 * Sharing of the embedded device in the device picker: "Sharing on :8642" (or, while only apps
 * on this device may connect, "Apps on this device · :8642") with a button that stops it, or a
 * button that starts it with the saved settings. Pairing lives in Settings › Sharing.
 */
@Composable
fun EmbeddedServerControls(
  serverState: ServerState,
  onStartServer: () -> Unit,
  onStopServer: () -> Unit,
) {
  when (serverState) {
    is ServerState.Running -> {
      Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(KetchTheme.spacing.s1),
      ) {
        Text(
          text = if (serverState.config.isLoopbackOnly) {
            "Apps on this device · :${serverState.port}"
          } else {
            "Sharing on :${serverState.port}"
          },
          style = KetchTheme.typography.caption,
          color = KetchTheme.colors.status.completed.color,
        )
        KetchIconButton(
          icon = KetchIcon.Stop,
          contentDescription = "Stop sharing",
          onClick = onStopServer,
          size = KetchButtonSize.Small,
        )
      }
    }
    is ServerState.Stopped, is ServerState.Failed -> {
      KetchButton(
        text = if (serverState is ServerState.Failed) "Retry sharing" else "Share this device",
        onClick = onStartServer,
        leadingIcon = if (serverState is ServerState.Failed) KetchIcon.Retry else KetchIcon.Server,
        variant = KetchButtonVariant.Ghost,
        size = KetchButtonSize.Small,
        tooltip = (serverState as? ServerState.Failed)?.let { "Couldn't share: ${it.message}" },
      )
    }
  }
}
