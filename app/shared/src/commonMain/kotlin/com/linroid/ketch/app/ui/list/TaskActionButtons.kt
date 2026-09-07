package com.linroid.ketch.app.ui.list

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.adaptive.currentWindowAdaptiveInfo
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.window.core.layout.WindowSizeClass
import com.linroid.ketch.api.DownloadState
import com.linroid.ketch.app.components.KetchButtonSize
import com.linroid.ketch.app.components.KetchIconButton
import com.linroid.ketch.app.icons.KetchIcon
import com.linroid.ketch.app.theme.KetchTheme

@Composable
fun TaskActionButtons(
  state: DownloadState,
  onPause: () -> Unit,
  onResume: () -> Unit,
  onCancel: () -> Unit,
  onRetry: () -> Unit,
  modifier: Modifier = Modifier,
) {
  val isCompact = !currentWindowAdaptiveInfo().windowSizeClass
    .isWidthAtLeastBreakpoint(WindowSizeClass.WIDTH_DP_MEDIUM_LOWER_BOUND)
  val size = if (isCompact) KetchButtonSize.Large else KetchButtonSize.Medium
  val colors = KetchTheme.colors

  Row(
    modifier = modifier,
    horizontalArrangement = Arrangement.spacedBy(2.dp),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    when (state) {
      is DownloadState.Downloading -> {
        Action(KetchIcon.Pause, colors.onSurfaceVariant, size, onPause)
        Action(KetchIcon.Close, colors.error, size, onCancel)
      }
      is DownloadState.Paused -> {
        Action(KetchIcon.Play, colors.primary, size, onResume)
        Action(KetchIcon.Close, colors.error, size, onCancel)
      }
      is DownloadState.Failed,
      is DownloadState.Canceled -> {
        Action(KetchIcon.Retry, colors.primary, size, onRetry)
      }
      is DownloadState.Completed,
      is DownloadState.Scheduled,
      is DownloadState.Queued -> {}
    }
  }
}

@Composable
private fun Action(
  icon: KetchIcon,
  tint: Color,
  size: KetchButtonSize,
  onClick: () -> Unit,
) {
  KetchIconButton(icon = icon, onClick = onClick, size = size, tint = tint)
}
