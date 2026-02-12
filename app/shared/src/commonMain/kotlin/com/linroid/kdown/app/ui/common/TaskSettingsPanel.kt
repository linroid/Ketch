package com.linroid.kdown.app.ui.common

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.linroid.kdown.api.DownloadTask
import com.linroid.kdown.app.util.formatBytes
import com.linroid.kdown.app.util.priorityLabel

@Composable
fun TaskSettingsIcon(
  selected: Boolean,
  onClick: () -> Unit,
  modifier: Modifier = Modifier,
) {
  IconButton(
    onClick = onClick,
    modifier = modifier.size(28.dp),
    colors = IconButtonDefaults.iconButtonColors(
      contentColor = if (selected) {
        MaterialTheme.colorScheme.primary
      } else {
        MaterialTheme.colorScheme.onSurfaceVariant
      }
    )
  ) {
    Icon(
      Icons.Filled.Info,
      contentDescription = "Task info",
      modifier = Modifier.size(16.dp),
    )
  }
}

@Composable
fun TaskSettingsPanel(
  task: DownloadTask,
  modifier: Modifier = Modifier,
) {
  val segments by task.segments.collectAsState()

  Surface(
    modifier = modifier.fillMaxWidth(),
    color = MaterialTheme.colorScheme.surfaceContainerHigh,
    shape = RoundedCornerShape(8.dp),
  ) {
    Column(
      modifier = Modifier.padding(12.dp),
      verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
      CopyableInfoRow("URL", task.request.url)
      if (task.request.directory != null) {
        InfoRow("Directory", task.request.directory!!)
      }
      if (task.request.fileName != null) {
        InfoRow("File name", task.request.fileName!!)
      }
      InfoRow(
        "Connections",
        task.request.connections.toString()
      )
      if (segments.isNotEmpty()) {
        val completed = segments.count { it.isComplete }
        InfoRow(
          "Segments",
          "$completed / ${segments.size} complete"
        )
      }
      InfoRow(
        "Priority",
        priorityLabel(task.request.priority)
      )
      val limit = task.request.speedLimit
      InfoRow(
        "Speed limit",
        if (limit.isUnlimited) "Unlimited"
        else "${formatBytes(limit.bytesPerSecond)}/s"
      )
      InfoRow("Task ID", task.taskId)
    }
  }
}

@Composable
private fun CopyableInfoRow(
  label: String,
  value: String,
  modifier: Modifier = Modifier,
) {
  val clipboardManager = LocalClipboardManager.current
  Row(
    modifier = modifier.fillMaxWidth(),
    horizontalArrangement = Arrangement.spacedBy(12.dp),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    Text(
      text = label,
      style = MaterialTheme.typography.labelSmall,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
      modifier = Modifier.weight(0.3f),
    )
    Text(
      text = value,
      style = MaterialTheme.typography.labelSmall,
      color = MaterialTheme.colorScheme.onSurface,
      maxLines = 2,
      overflow = TextOverflow.Ellipsis,
      modifier = Modifier.weight(0.7f),
    )
    IconButton(
      onClick = {
        clipboardManager.setText(AnnotatedString(value))
      },
      modifier = Modifier.size(24.dp),
    ) {
      Icon(
        Icons.Filled.ContentCopy,
        contentDescription = "Copy",
        modifier = Modifier.size(14.dp),
        tint = MaterialTheme.colorScheme.onSurfaceVariant,
      )
    }
  }
}

@Composable
private fun InfoRow(
  label: String,
  value: String,
  modifier: Modifier = Modifier,
) {
  Row(
    modifier = modifier.fillMaxWidth(),
    horizontalArrangement = Arrangement.spacedBy(12.dp),
    verticalAlignment = Alignment.Top,
  ) {
    Text(
      text = label,
      style = MaterialTheme.typography.labelSmall,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
      modifier = Modifier.weight(0.3f),
    )
    Text(
      text = value,
      style = MaterialTheme.typography.labelSmall,
      color = MaterialTheme.colorScheme.onSurface,
      maxLines = 2,
      overflow = TextOverflow.Ellipsis,
      modifier = Modifier.weight(0.7f),
    )
  }
}
