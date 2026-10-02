package com.linroid.ketch.app.ui.common

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import com.linroid.ketch.app.components.KetchButton
import com.linroid.ketch.app.components.KetchButtonSize
import com.linroid.ketch.app.components.KetchButtonVariant
import com.linroid.ketch.app.components.KetchIconButton
import com.linroid.ketch.app.icons.KetchIcon
import com.linroid.ketch.app.state.TaskRow
import com.linroid.ketch.app.state.formatSpeedLimit
import com.linroid.ketch.app.theme.KetchElevationLevel
import com.linroid.ketch.app.theme.KetchTheme
import com.linroid.ketch.app.theme.ketchSurface
import com.linroid.ketch.app.util.priorityLabel

/** Button that shows or hides a task's [TaskSettingsPanel]. */
@Composable
fun TaskSettingsIcon(
  selected: Boolean,
  onClick: () -> Unit,
  modifier: Modifier = Modifier,
) {
  KetchButton(
    text = "Details",
    leadingIcon = KetchIcon.Info,
    variant = if (selected) KetchButtonVariant.Secondary else KetchButtonVariant.Ghost,
    size = KetchButtonSize.Small,
    modifier = modifier,
    onClick = onClick,
  )
}

/**
 * What a task asked for: its link, destination, connections, priority and speed limit.
 *
 * @param onCopyLink copies the task's link.
 */
@Composable
fun TaskSettingsPanel(
  row: TaskRow,
  onCopyLink: () -> Unit,
  modifier: Modifier = Modifier,
) {
  val colors = KetchTheme.colors
  val spacing = KetchTheme.spacing
  val request = row.request
  Column(
    modifier = modifier
      .fillMaxWidth()
      .ketchSurface(KetchElevationLevel.E0, KetchTheme.shapes.sm, colors.surfaceSunken)
      .padding(spacing.s3),
    verticalArrangement = Arrangement.spacedBy(spacing.s1),
  ) {
    InfoRow("URL", request.url) {
      KetchIconButton(
        icon = KetchIcon.Copy,
        contentDescription = "Copy link",
        size = KetchButtonSize.Small,
        onClick = onCopyLink,
      )
    }
    request.destination?.let { InfoRow("Destination", it.value) }
    InfoRow("Connections", if (request.connections > 0) "${request.connections}" else "Auto")
    if (row.segments.isNotEmpty()) {
      val completed = row.segments.count { it.isComplete }
      InfoRow("Segments", "$completed / ${row.segments.size} complete")
    }
    InfoRow("Priority", priorityLabel(request.priority))
    InfoRow("Speed limit", formatSpeedLimit(request.speedLimit))
    InfoRow("Task ID", row.task.taskId)
  }
}

@Composable
private fun InfoRow(
  label: String,
  value: String,
  trailing: (@Composable () -> Unit)? = null,
) {
  val colors = KetchTheme.colors
  val type = KetchTheme.typography
  Row(
    modifier = Modifier.fillMaxWidth(),
    horizontalArrangement = Arrangement.spacedBy(KetchTheme.spacing.s3),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    Text(
      text = label,
      style = type.caption,
      color = colors.textSecondary,
      modifier = Modifier.weight(LabelWeight),
    )
    Text(
      text = value,
      style = type.caption,
      color = colors.textPrimary,
      maxLines = 2,
      overflow = TextOverflow.Ellipsis,
      modifier = Modifier.weight(ValueWeight),
    )
    trailing?.invoke()
  }
}

private const val LabelWeight = 0.3f
private const val ValueWeight = 0.7f
