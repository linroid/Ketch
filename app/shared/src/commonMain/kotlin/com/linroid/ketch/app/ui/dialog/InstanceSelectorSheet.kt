package com.linroid.ketch.app.ui.dialog

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.adaptive.currentWindowAdaptiveInfo
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.window.core.layout.WindowSizeClass
import com.linroid.ketch.app.components.KetchButton
import com.linroid.ketch.app.components.KetchButtonVariant
import com.linroid.ketch.app.components.KetchIconButton
import com.linroid.ketch.app.icons.KetchIcon
import com.linroid.ketch.app.icons.KetchIconImage
import com.linroid.ketch.app.instance.EmbeddedInstance
import com.linroid.ketch.app.instance.InstanceEntry
import com.linroid.ketch.app.instance.InstanceManager
import com.linroid.ketch.app.instance.RemoteInstance
import com.linroid.ketch.app.instance.ServerState
import com.linroid.ketch.app.theme.KetchTheme
import com.linroid.ketch.app.ui.common.ConnectionStatusChip

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
  val instances by instanceManager.instances.collectAsState()
  val colors = KetchTheme.colors
  val type = KetchTheme.typography
  val isCompact = !currentWindowAdaptiveInfo().windowSizeClass
    .isWidthAtLeastBreakpoint(WindowSizeClass.WIDTH_DP_MEDIUM_LOWER_BOUND)

  val instanceList: @Composable () -> Unit = {
    Column(
      modifier = Modifier
        .fillMaxWidth()
        .heightIn(max = 400.dp)
        .verticalScroll(rememberScrollState()),
      verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
      Text(
        "Choose where to manage your downloads.",
        style = type.bodyMedium,
        color = colors.onSurfaceVariant,
        modifier = Modifier.padding(bottom = 6.dp),
      )
      instances.forEach { entry ->
        val isActive = entry == activeInstance
        val isSwitching = entry == switchingInstance
        val shape = RoundedCornerShape(10.dp)
        Row(
          modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(if (isActive) colors.primaryContainer else colors.surface)
            .border(1.dp, if (isActive) colors.primary else colors.outlineVariant, shape)
            .selectable(
              selected = isActive,
              enabled = switchingInstance == null,
              role = Role.RadioButton,
              onClick = { onSelectInstance(entry) },
            )
            .padding(12.dp),
          horizontalArrangement = Arrangement.spacedBy(12.dp),
          verticalAlignment = Alignment.CenterVertically,
        ) {
          KetchIconImage(
            icon = if (entry is EmbeddedInstance) KetchIcon.Local else KetchIcon.Remote,
            size = 24.dp,
            tint = if (isActive) colors.primary else colors.onSurfaceVariant,
          )
          Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(4.dp),
          ) {
            Text(
              text = entry.label,
              style = type.bodyLarge.copy(fontWeight = FontWeight.SemiBold),
              color = colors.onBackground,
              maxLines = 2,
              overflow = TextOverflow.Ellipsis,
            )
            Text(
              text = if (entry is RemoteInstance) "${entry.host}:${entry.port}" else "This device",
              style = type.bodySmall,
              color = colors.onSurfaceVariant,
              maxLines = 2,
              overflow = TextOverflow.Ellipsis,
            )
            if (entry is RemoteInstance) {
              val connectionState by entry.connectionState.collectAsState()
              ConnectionStatusChip(state = connectionState, isActive = isActive)
            }
            if (entry is EmbeddedInstance && instanceManager.isLocalServerSupported) {
              EmbeddedServerControls(
                serverState = serverState,
                onStartServer = { port, _ -> instanceManager.startServer(port) },
                onStopServer = { instanceManager.stopServer() },
              )
            }
          }
          if (isSwitching) {
            CircularProgressIndicator(
              modifier = Modifier.size(20.dp).semantics { contentDescription = "Connecting" },
              strokeWidth = 2.dp,
            )
          } else if (isActive) {
            KetchIconImage(icon = KetchIcon.Check, size = 20.dp, tint = colors.primary)
          }
          if (entry is RemoteInstance) {
            KetchIconButton(
              icon = KetchIcon.Close,
              contentDescription = "Remove ${entry.label}",
              enabled = switchingInstance == null,
              onClick = { onRemoveInstance(entry) },
            )
          }
        }
      }
    }
  }

  if (isCompact) {
    ModalBottomSheet(
      onDismissRequest = onDismiss,
      sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
      containerColor = colors.surface,
    ) {
      Column(
        modifier = Modifier.fillMaxWidth().padding(start = 24.dp, end = 24.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
      ) {
        Text("Download instances", style = type.displaySmall, color = colors.onBackground)
        instanceList()
        KetchButton(
          text = "Add remote server",
          leadingIcon = KetchIcon.Plus,
          onClick = onAddRemoteServer,
          modifier = Modifier.fillMaxWidth(),
        )
      }
    }
  } else {
    AlertDialog(
      onDismissRequest = onDismiss,
      containerColor = colors.surface,
      title = { Text("Download instances", style = type.displaySmall, color = colors.onBackground) },
      text = { instanceList() },
      confirmButton = {
        KetchButton(text = "Add remote server", leadingIcon = KetchIcon.Plus, onClick = onAddRemoteServer)
      },
      dismissButton = {
        KetchButton(text = "Done", variant = KetchButtonVariant.Ghost, onClick = onDismiss)
      },
    )
  }
}
