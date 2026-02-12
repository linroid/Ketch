package com.linroid.kdown.app.ui.common

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.linroid.kdown.api.DownloadPriority
import com.linroid.kdown.app.util.priorityLabel

@Composable
fun PriorityBadge(priority: DownloadPriority) {
  val color = when (priority) {
    DownloadPriority.LOW ->
      MaterialTheme.colorScheme.surfaceVariant
    DownloadPriority.NORMAL ->
      MaterialTheme.colorScheme.secondaryContainer
    DownloadPriority.HIGH ->
      MaterialTheme.colorScheme.tertiaryContainer
    DownloadPriority.URGENT ->
      MaterialTheme.colorScheme.errorContainer
  }
  val textColor = when (priority) {
    DownloadPriority.LOW ->
      MaterialTheme.colorScheme.onSurfaceVariant
    DownloadPriority.NORMAL ->
      MaterialTheme.colorScheme.onSecondaryContainer
    DownloadPriority.HIGH ->
      MaterialTheme.colorScheme.onTertiaryContainer
    DownloadPriority.URGENT ->
      MaterialTheme.colorScheme.onErrorContainer
  }
  Box(
    modifier = Modifier
      .background(
        color = color,
        shape = MaterialTheme.shapes.small,
      )
      .padding(horizontal = 6.dp, vertical = 2.dp),
  ) {
    Text(
      text = priorityLabel(priority),
      style = MaterialTheme.typography.labelSmall,
      color = textColor,
    )
  }
}
