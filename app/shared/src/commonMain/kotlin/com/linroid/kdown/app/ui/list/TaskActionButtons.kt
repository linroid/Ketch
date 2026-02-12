package com.linroid.kdown.app.ui.list

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.material3.adaptive.currentWindowAdaptiveInfo
import androidx.window.core.layout.WindowSizeClass
import com.linroid.kdown.api.DownloadState

@Composable
fun TaskActionButtons(
  state: DownloadState,
  onPause: () -> Unit,
  onResume: () -> Unit,
  onCancel: () -> Unit,
  onRetry: () -> Unit,
  modifier: Modifier = Modifier,
) {
  Row(
    modifier = modifier,
    horizontalArrangement = Arrangement.spacedBy(2.dp),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    when (state) {
      is DownloadState.Downloading,
      is DownloadState.Pending -> {
        ActionIcon(
          icon = Icons.Filled.Pause,
          description = "Pause",
          tint = MaterialTheme.colorScheme.onSurfaceVariant,
          onClick = onPause,
        )
        ActionIcon(
          icon = Icons.Filled.Close,
          description = "Cancel",
          tint = MaterialTheme.colorScheme.error,
          onClick = onCancel,
        )
      }
      is DownloadState.Paused -> {
        ActionIcon(
          icon = Icons.Filled.PlayArrow,
          description = "Resume",
          tint = MaterialTheme.colorScheme.primary,
          onClick = onResume,
        )
        ActionIcon(
          icon = Icons.Filled.Close,
          description = "Cancel",
          tint = MaterialTheme.colorScheme.error,
          onClick = onCancel,
        )
      }
      is DownloadState.Failed,
      is DownloadState.Canceled -> {
        ActionIcon(
          icon = Icons.Filled.Refresh,
          description = "Retry",
          tint = MaterialTheme.colorScheme.primary,
          onClick = onRetry,
        )
      }
      is DownloadState.Completed,
      is DownloadState.Scheduled,
      is DownloadState.Queued,
      is DownloadState.Idle -> {}
    }
  }
}

@Composable
private fun ActionIcon(
  icon: androidx.compose.ui.graphics.vector.ImageVector,
  description: String,
  tint: androidx.compose.ui.graphics.Color,
  onClick: () -> Unit,
) {
  val windowSizeClass =
    currentWindowAdaptiveInfo().windowSizeClass
  val isCompact = !windowSizeClass
    .isWidthAtLeastBreakpoint(
      WindowSizeClass.WIDTH_DP_MEDIUM_LOWER_BOUND
    )
  val buttonSize = if (isCompact) 48.dp else 32.dp
  val iconSize = if (isCompact) 22.dp else 18.dp
  IconButton(
    onClick = onClick,
    modifier = Modifier.size(buttonSize),
  ) {
    Icon(
      imageVector = icon,
      contentDescription = description,
      modifier = Modifier.size(iconSize),
      tint = tint,
    )
  }
}
