package com.linroid.ketch.app.ui.inspector

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import com.linroid.ketch.app.components.KetchIconButton
import com.linroid.ketch.app.icons.KetchIcon
import com.linroid.ketch.app.state.AppState
import com.linroid.ketch.app.state.TaskKey
import com.linroid.ketch.app.state.TaskRow
import com.linroid.ketch.app.theme.KetchTheme

/** Where the task inspector shows. */
enum class InspectorPlacement {
  /** Beside the list, on wide windows. */
  Docked,

  /** Floating over the list. */
  Overlay,

  /** In a bottom sheet, on phones. */
  Sheet,
}

/**
 * What the inspector shows about the task [taskKey]: its name, status and progress. The docked,
 * overlay and sheet containers belong to the Downloads page, which places this inside them.
 *
 * @param placement the container this is shown in.
 * @param onClose closes the inspector.
 */
@Composable
fun TaskInspector(
  state: AppState,
  taskKey: TaskKey?,
  placement: InspectorPlacement,
  onClose: () -> Unit,
) {
  val rows by state.taskList.rows.collectAsState()
  val row = remember(rows, taskKey) { taskKey?.let { key -> rows.firstOrNull { it.key == key } } }
  val spacing = KetchTheme.spacing
  val padding = if (placement == InspectorPlacement.Sheet) spacing.s4 else spacing.s3
  Column(
    modifier = Modifier.fillMaxWidth().padding(padding),
    verticalArrangement = Arrangement.spacedBy(spacing.s2),
  ) {
    Row(
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(spacing.s2),
    ) {
      Text(
        text = row?.name ?: "No download selected",
        style = KetchTheme.typography.titleM,
        color = KetchTheme.colors.textPrimary,
        maxLines = 2,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.weight(1f),
      )
      KetchIconButton(icon = KetchIcon.Close, contentDescription = "Close", onClick = onClose)
    }
    if (row != null) Summary(row)
  }
}

@Composable
private fun Summary(row: TaskRow) {
  val content = row.content
  val colors = KetchTheme.colors
  val type = KetchTheme.typography
  Text(text = content.statusText, style = type.bodyStrong, color = colors.textPrimary)
  if (content.detail.isNotEmpty()) {
    Text(text = content.detail, style = type.bodyS, color = colors.textSecondary)
  }
  val numbers = listOf(content.size, content.speed, content.time).filter { it.isNotEmpty() }
  if (numbers.isNotEmpty()) {
    Text(text = numbers.joinToString(" · "), style = type.caption, color = colors.textTertiary)
  }
  Text(
    text = "${row.device.name} · Added ${content.added}",
    style = type.caption,
    color = colors.textTertiary,
  )
}
