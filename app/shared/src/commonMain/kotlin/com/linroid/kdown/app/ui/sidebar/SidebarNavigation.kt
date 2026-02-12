package com.linroid.kdown.app.ui.sidebar

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.linroid.kdown.app.state.StatusFilter

private val SIDEBAR_WIDTH = 200.dp

@Composable
fun SidebarNavigation(
  selectedFilter: StatusFilter,
  taskCounts: Map<StatusFilter, Int>,
  onFilterSelect: (StatusFilter) -> Unit,
  onAddClick: () -> Unit,
  modifier: Modifier = Modifier,
) {
  Column(
    modifier = modifier
      .width(SIDEBAR_WIDTH)
      .fillMaxHeight()
      .background(MaterialTheme.colorScheme.surfaceContainerLow)
      .padding(vertical = 12.dp),
  ) {
    // Add download button
    FloatingActionButton(
      onClick = onAddClick,
      modifier = Modifier
        .padding(horizontal = 16.dp)
        .fillMaxWidth(),
      containerColor = MaterialTheme.colorScheme.primary,
      contentColor = MaterialTheme.colorScheme.onPrimary,
      shape = RoundedCornerShape(12.dp),
    ) {
      Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
      ) {
        Icon(
          Icons.Filled.Add,
          contentDescription = null,
          modifier = Modifier.size(20.dp),
        )
        Text(
          text = "New Task",
          style = MaterialTheme.typography.labelLarge,
          fontWeight = FontWeight.SemiBold,
        )
      }
    }

    Spacer(modifier = Modifier.height(16.dp))
    HorizontalDivider(
      modifier = Modifier.padding(horizontal = 16.dp),
      color = MaterialTheme.colorScheme.outlineVariant,
    )
    Spacer(modifier = Modifier.height(8.dp))

    // Category label
    Text(
      text = "TASKS",
      style = MaterialTheme.typography.labelSmall,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
      fontWeight = FontWeight.SemiBold,
      modifier = Modifier.padding(
        horizontal = 20.dp, vertical = 8.dp,
      )
    )

    // Navigation items
    StatusFilter.entries.forEach { filter ->
      val count = taskCounts[filter] ?: 0
      SidebarItem(
        icon = filterIcon(filter),
        label = filter.label,
        count = count,
        selected = selectedFilter == filter,
        onClick = { onFilterSelect(filter) },
      )
    }
  }
}

@Composable
private fun SidebarItem(
  icon: ImageVector,
  label: String,
  count: Int,
  selected: Boolean,
  onClick: () -> Unit,
) {
  val bgColor = if (selected) {
    MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f)
  } else {
    MaterialTheme.colorScheme.surfaceContainerLow
  }
  val contentColor = if (selected) {
    MaterialTheme.colorScheme.primary
  } else {
    MaterialTheme.colorScheme.onSurfaceVariant
  }

  Row(
    modifier = Modifier
      .fillMaxWidth()
      .padding(horizontal = 8.dp, vertical = 1.dp)
      .clip(RoundedCornerShape(8.dp))
      .background(bgColor)
      .clickable(onClick = onClick)
      .padding(horizontal = 12.dp, vertical = 10.dp),
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(12.dp),
  ) {
    Icon(
      imageVector = icon,
      contentDescription = label,
      modifier = Modifier.size(20.dp),
      tint = contentColor,
    )
    Text(
      text = label,
      style = MaterialTheme.typography.bodyMedium,
      color = if (selected) {
        MaterialTheme.colorScheme.onSurface
      } else {
        MaterialTheme.colorScheme.onSurfaceVariant
      },
      fontWeight = if (selected) {
        FontWeight.SemiBold
      } else {
        FontWeight.Normal
      },
      modifier = Modifier.weight(1f),
    )
    if (count > 0) {
      Box(
        modifier = Modifier
          .background(
            color = if (selected) {
              MaterialTheme.colorScheme.primary.copy(alpha = 0.2f)
            } else {
              MaterialTheme.colorScheme.surfaceContainerHigh
            },
            shape = CircleShape,
          )
          .padding(horizontal = 8.dp, vertical = 2.dp),
        contentAlignment = Alignment.Center,
      ) {
        Text(
          text = count.toString(),
          style = MaterialTheme.typography.labelSmall,
          color = contentColor,
          fontWeight = FontWeight.SemiBold,
        )
      }
    }
  }
}

internal fun filterIcon(filter: StatusFilter): ImageVector {
  return when (filter) {
    StatusFilter.All -> Icons.Filled.Folder
    StatusFilter.Downloading -> Icons.Filled.ArrowDownward
    StatusFilter.Paused -> Icons.Filled.Pause
    StatusFilter.Completed -> Icons.Filled.CheckCircle
    StatusFilter.Failed -> Icons.Filled.ErrorOutline
  }
}
