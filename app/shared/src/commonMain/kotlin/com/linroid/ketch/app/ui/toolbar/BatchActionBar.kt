package com.linroid.ketch.app.ui.toolbar

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.linroid.ketch.app.components.KetchIconButton
import com.linroid.ketch.app.icons.KetchIcon

@Composable
fun BatchActionBar(
  hasActiveDownloads: Boolean,
  hasPausedDownloads: Boolean,
  hasCompletedDownloads: Boolean,
  onPauseAll: () -> Unit,
  onResumeAll: () -> Unit,
  onClearCompleted: () -> Unit,
  modifier: Modifier = Modifier,
) {
  Row(
    modifier = modifier,
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(2.dp),
  ) {
    if (hasActiveDownloads) {
      KetchIconButton(icon = KetchIcon.Pause, contentDescription = "Pause all downloads", onClick = onPauseAll)
    }
    if (hasPausedDownloads) {
      KetchIconButton(icon = KetchIcon.Play, contentDescription = "Resume all downloads", onClick = onResumeAll)
    }
    if (hasCompletedDownloads) {
      KetchIconButton(icon = KetchIcon.Trash, contentDescription = "Clear completed downloads", onClick = onClearCompleted)
    }
  }
}
