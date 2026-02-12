package com.linroid.kdown.app.ui.toolbar

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CleaningServices
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

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
      IconButton(onClick = onPauseAll) {
        Icon(
          Icons.Filled.Pause,
          contentDescription = "Pause all",
          tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
      }
    }
    if (hasPausedDownloads) {
      IconButton(onClick = onResumeAll) {
        Icon(
          Icons.Filled.PlayArrow,
          contentDescription = "Resume all",
          tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
      }
    }
    if (hasCompletedDownloads) {
      IconButton(onClick = onClearCompleted) {
        Icon(
          Icons.Filled.CleaningServices,
          contentDescription = "Clear completed",
          tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
      }
    }
  }
}
