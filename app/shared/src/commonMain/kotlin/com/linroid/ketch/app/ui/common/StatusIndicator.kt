package com.linroid.ketch.app.ui.common

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.Cancel
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Inbox
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.linroid.ketch.api.DownloadState
import com.linroid.ketch.app.theme.LocalDownloadStateColors

@Composable
fun StatusIndicator(
  state: DownloadState,
  modifier: Modifier = Modifier,
) {
  val stateColors = LocalDownloadStateColors.current
  val colors = stateColors.forState(state)
  val icon = stateIcon(state)

  Box(
    modifier = modifier
      .size(36.dp)
      .clip(CircleShape)
      .background(colors.background),
    contentAlignment = Alignment.Center,
  ) {
    Icon(
      imageVector = icon,
      contentDescription = stateLabel(state),
      tint = colors.foreground,
      modifier = Modifier.size(20.dp),
    )
  }
}

private fun stateIcon(state: DownloadState): ImageVector {
  return when (state) {
    is DownloadState.Downloading -> Icons.Filled.ArrowDownward
    is DownloadState.Queued -> Icons.Filled.Inbox
    is DownloadState.Scheduled -> Icons.Filled.Schedule
    is DownloadState.Paused -> Icons.Filled.Pause
    is DownloadState.Completed -> Icons.Filled.CheckCircle
    is DownloadState.Failed -> Icons.Filled.ErrorOutline
    is DownloadState.Canceled -> Icons.Filled.Cancel
  }
}

private fun stateLabel(state: DownloadState): String {
  return when (state) {
    is DownloadState.Downloading -> "Downloading"
    is DownloadState.Queued -> "Queued"
    is DownloadState.Scheduled -> "Scheduled"
    is DownloadState.Paused -> "Paused"
    is DownloadState.Completed -> "Completed"
    is DownloadState.Failed -> "Failed"
    is DownloadState.Canceled -> "Canceled"
  }
}
