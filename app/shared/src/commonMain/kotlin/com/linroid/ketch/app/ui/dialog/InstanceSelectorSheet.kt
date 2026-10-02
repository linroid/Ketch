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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.window.core.layout.WindowSizeClass
import com.linroid.ketch.app.components.DevicePennant
import com.linroid.ketch.app.components.DevicePennantDefaults
import com.linroid.ketch.app.components.KetchButton
import com.linroid.ketch.app.components.KetchButtonVariant
import com.linroid.ketch.app.components.KetchIconButton
import com.linroid.ketch.app.icons.KetchIcon
import com.linroid.ketch.app.icons.KetchIconImage
import com.linroid.ketch.app.instance.EmbeddedInstance
import com.linroid.ketch.app.instance.InstanceEntry
import com.linroid.ketch.app.instance.InstanceManager
import com.linroid.ketch.app.instance.RemoteInstance
import com.linroid.ketch.app.instance.detail
import com.linroid.ketch.app.instance.displayName
import com.linroid.ketch.app.state.deviceId
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
  val spacing = KetchTheme.spacing
  val window = LocalWindowInfo.current.containerSize
  val windowWidth = with(LocalDensity.current) { window.width.toDp() }
  val isCompact = windowWidth.value < WindowSizeClass.WIDTH_DP_MEDIUM_LOWER_BOUND

  val instanceList: @Composable () -> Unit = {
    Column(
      modifier = Modifier
        .fillMaxWidth()
        .heightIn(max = 400.dp)
        .verticalScroll(rememberScrollState()),
      verticalArrangement = Arrangement.spacedBy(spacing.s2),
    ) {
      Text(
        "Choose a device to see and manage its downloads.",
        style = type.bodyS,
        color = colors.textSecondary,
        modifier = Modifier.padding(bottom = spacing.s1),
      )
      instances.forEach { entry ->
        val isActive = entry == activeInstance
        val isSwitching = entry == switchingInstance
        val shape = KetchTheme.shapes.md
        Row(
          modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(if (isActive) colors.accentSoft else colors.surface)
            .border(1.dp, if (isActive) colors.accent else colors.hairline, shape)
            .selectable(
              selected = isActive,
              enabled = switchingInstance == null,
              role = Role.RadioButton,
              onClick = { onSelectInstance(entry) },
            )
            .padding(spacing.s3),
          horizontalArrangement = Arrangement.spacedBy(spacing.s3),
          verticalAlignment = Alignment.CenterVertically,
        ) {
          DevicePennant(
            deviceId = entry.deviceId,
            name = entry.label,
            size = DevicePennantDefaults.Large,
          )
          Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(spacing.s1),
          ) {
            Text(
              text = entry.displayName,
              style = type.bodyStrong,
              color = colors.textPrimary,
              maxLines = 2,
              overflow = TextOverflow.Ellipsis,
            )
            if (entry.detail != entry.displayName) {
              Text(
                text = entry.detail,
                style = type.caption,
                color = colors.textSecondary,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
              )
            }
            if (entry is RemoteInstance) {
              val connectionState by entry.connectionState.collectAsState()
              ConnectionStatusChip(state = connectionState, isActive = isActive)
            }
            if (entry is EmbeddedInstance && instanceManager.isLocalServerSupported) {
              EmbeddedServerControls(
                serverState = serverState,
                onStartServer = { instanceManager.startServer() },
                onStopServer = { instanceManager.stopServer() },
              )
            }
          }
          if (isSwitching) {
            CircularProgressIndicator(
              modifier = Modifier.size(spacing.s5).semantics { contentDescription = "Connecting" },
              strokeWidth = 2.dp,
            )
          } else if (isActive) {
            KetchIconImage(icon = KetchIcon.Check, size = spacing.s5, tint = colors.accent)
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
        modifier = Modifier
          .fillMaxWidth()
          .padding(start = spacing.s6, end = spacing.s6, bottom = spacing.s6),
        verticalArrangement = Arrangement.spacedBy(spacing.s4),
      ) {
        Text("Devices", style = type.titleL, color = colors.textPrimary)
        instanceList()
        KetchButton(
          text = "Add device",
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
      title = { Text("Devices", style = type.titleL, color = colors.textPrimary) },
      text = { instanceList() },
      confirmButton = {
        KetchButton(
          text = "Add device",
          leadingIcon = KetchIcon.Plus,
          onClick = onAddRemoteServer,
        )
      },
      dismissButton = {
        KetchButton(text = "Done", variant = KetchButtonVariant.Ghost, onClick = onDismiss)
      },
    )
  }
}
