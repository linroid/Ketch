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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.linroid.kdown.app.instance.EmbeddedInstance
import com.linroid.kdown.app.instance.InstanceEntry
import com.linroid.kdown.app.instance.InstanceManager
import com.linroid.kdown.app.instance.RemoteInstance
import com.linroid.kdown.app.instance.ServerState
import com.linroid.kdown.app.ui.common.ConnectionStatusChip

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun InstanceSelectorSheet(
  instanceManager: InstanceManager,
  activeInstance: InstanceEntry?,
  switchingInstance: InstanceEntry?,
  serverState: ServerState,
  onSelectInstance: (InstanceEntry) -> Unit,
  onRemoveInstance: (InstanceEntry) -> Unit,
  onAddRemoteServer: () -> Unit,
  onDismiss: () -> Unit,
) {
  val sheetState = rememberModalBottomSheetState()
  val instances by instanceManager.instances.collectAsState()

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
        text = "Select Instance",
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier.padding(
          horizontal = 24.dp, vertical = 8.dp,
        )
      )

      instances.forEach { entry ->
        val isActive = entry == activeInstance
        val isSwitching = entry == switchingInstance

        ListItem(
          modifier = Modifier.clickable(
            enabled = !isSwitching &&
              switchingInstance == null,
          ) {
            onSelectInstance(entry)
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
              imageVector = if (entry is EmbeddedInstance) {
                Icons.Filled.PhoneAndroid
              } else {
                Icons.Filled.Cloud
              },
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
              if (entry is RemoteInstance) {
                val entryConnectionState by
                  entry.connectionState.collectAsState()
                ConnectionStatusChip(
                  state = entryConnectionState,
                  isActive = isActive,
                )
              }
              if (entry is EmbeddedInstance &&
                instanceManager.isLocalServerSupported
              ) {
                EmbeddedServerControls(
                  serverState = serverState,
                  onStartServer = { port, token ->
                    instanceManager.startServer(
                      port, token
                    )
                  },
                  onStopServer = {
                    instanceManager.stopServer()
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
              if (entry !is EmbeddedInstance) {
                IconButton(
                  onClick = {
                    onRemoveInstance(entry)
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
