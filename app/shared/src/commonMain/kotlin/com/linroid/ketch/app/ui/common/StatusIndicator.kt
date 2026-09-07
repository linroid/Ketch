package com.linroid.ketch.app.ui.common

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.linroid.ketch.api.DownloadState
import com.linroid.ketch.app.icons.KetchIcon
import com.linroid.ketch.app.icons.KetchIconImage
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
    KetchIconImage(icon = icon, size = 20.dp, tint = colors.foreground)
  }
}

private fun stateIcon(state: DownloadState): KetchIcon = when (state) {
  is DownloadState.Downloading -> KetchIcon.Active
  is DownloadState.Queued -> KetchIcon.Queued
  is DownloadState.Scheduled -> KetchIcon.Scheduled
  is DownloadState.Paused -> KetchIcon.Pause
  is DownloadState.Completed -> KetchIcon.Done
  is DownloadState.Failed -> KetchIcon.Failed
  is DownloadState.Canceled -> KetchIcon.Close
}
