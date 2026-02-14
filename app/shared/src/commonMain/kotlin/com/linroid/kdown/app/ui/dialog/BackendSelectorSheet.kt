package com.linroid.kdown.app.ui.dialog

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.linroid.kdown.app.backend.BackendConfig
import com.linroid.kdown.app.backend.BackendEntry
import com.linroid.kdown.app.backend.BackendManager
import com.linroid.kdown.app.backend.ServerState
import com.linroid.kdown.app.ui.common.ConnectionStatusChip

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BackendSelectorSheet(
  backendManager: BackendManager,
  activeBackendId: String?,
  switchingBackendId: String?,
  serverState: ServerState,
  onSelectBackend: (BackendEntry) -> Unit,
  onRemoveBackend: (BackendEntry) -> Unit,
  onAddRemoteServer: () -> Unit,
  onDismiss: () -> Unit,
) {
  val sheetState = rememberModalBottomSheetState()
  val backends by backendManager.backends.collectAsState()

  ModalBottomSheet(
    onDismissRequest = onDismiss,
    sheetState = sheetState,
  ) {
    Column(
      modifier = Modifier
        .fillMaxWidth()
        .padding(bottom = 24.dp),
    ) {
      Text(
        text = "Select Backend",
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier.padding(
          horizontal = 24.dp, vertical = 8.dp,
        )
      )

      backends.forEach { entry ->
        val entryConnectionState by
          entry.connectionState.collectAsState()
        val isActive = entry.id == activeBackendId
        val isSwitching = entry.id == switchingBackendId

        ListItem(
          modifier = Modifier.clickable(
            enabled = !isSwitching &&
              switchingBackendId == null,
          ) {
            onSelectBackend(entry)
          },
          headlineContent = {
            Text(
              text = entry.label,
              fontWeight = if (isActive) {
                FontWeight.SemiBold
              } else {
                FontWeight.Normal
              }
            )
          },
          leadingContent = {
            Icon(
              imageVector = backendConfigIcon(
                entry.config
              ),
              contentDescription = entry.label,
              tint = if (isActive) {
                MaterialTheme.colorScheme.primary
              } else {
                MaterialTheme.colorScheme
                  .onSurfaceVariant
              }
            )
          },
          supportingContent = {
            Column {
              ConnectionStatusChip(
                state = entryConnectionState,
                isActive = isActive,
              )
              if (entry.isEmbedded &&
                backendManager.isLocalServerSupported
              ) {
                EmbeddedServerControls(
                  serverState = serverState,
                  onStartServer = { port, token ->
                    backendManager.startServer(
                      port, token
                    )
                  },
                  onStopServer = {
                    backendManager.stopServer()
                  }
                )
              }
            }
          },
          trailingContent = {
            Row(
              verticalAlignment =
                Alignment.CenterVertically,
              horizontalArrangement =
                Arrangement.spacedBy(4.dp)
            ) {
              if (isSwitching) {
                CircularProgressIndicator(
                  modifier = Modifier.size(20.dp),
                  strokeWidth = 2.dp,
                )
              } else if (isActive) {
                Icon(
                  imageVector = Icons.Filled.Check,
                  contentDescription = "Active",
                  tint =
                    MaterialTheme.colorScheme.primary,
                  modifier = Modifier.size(20.dp),
                )
              }
              if (!entry.isEmbedded) {
                IconButton(
                  onClick = {
                    onRemoveBackend(entry)
                  }
                ) {
                  Icon(
                    imageVector = Icons.Filled.Close,
                    contentDescription = "Remove",
                    tint = MaterialTheme.colorScheme
                      .onSurfaceVariant
                  )
                }
              }
            }
          }
        )
      }

      HorizontalDivider(
        modifier = Modifier.padding(vertical = 8.dp),
      )

      ListItem(
        modifier = Modifier.clickable {
          onAddRemoteServer()
        },
        headlineContent = {
          Text("Add Remote Server")
        },
        leadingContent = {
          Icon(
            imageVector = Icons.Filled.Add,
            contentDescription = "Add remote server",
            tint = MaterialTheme.colorScheme.primary,
          )
        }
      )
    }
  }
}

private fun backendConfigIcon(
  config: BackendConfig,
): ImageVector {
  return when (config) {
    is BackendConfig.Embedded ->
      Icons.Filled.PhoneAndroid
    is BackendConfig.Remote -> Icons.Filled.Cloud
  }
}
