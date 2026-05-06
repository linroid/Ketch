package com.linroid.ketch.app.ui.sidebar

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.linroid.ketch.app.components.KetchButton
import com.linroid.ketch.app.components.KetchSidebarItem
import com.linroid.ketch.app.icons.KetchIcon
import com.linroid.ketch.app.state.StatusFilter
import com.linroid.ketch.app.theme.KetchTheme

private val SIDEBAR_WIDTH = 220.dp

@Composable
fun SidebarNavigation(
  selectedFilter: StatusFilter,
  taskCounts: Map<StatusFilter, Int>,
  onFilterSelect: (StatusFilter) -> Unit,
  onAddClick: () -> Unit,
  modifier: Modifier = Modifier,
) {
  val colors = KetchTheme.colors
  Column(
    modifier = modifier
      .width(SIDEBAR_WIDTH)
      .fillMaxHeight()
      .background(colors.surfaceVariant)
      .padding(vertical = 12.dp),
  ) {
    KetchButton(
      text = "New Task",
      onClick = onAddClick,
      leadingIcon = KetchIcon.Plus,
      modifier = Modifier
        .padding(horizontal = 16.dp)
        .fillMaxWidth(),
    )

    Spacer(Modifier.height(16.dp))
    HorizontalDivider(
      modifier = Modifier.padding(horizontal = 16.dp),
      color = colors.outlineVariant,
    )
    Spacer(Modifier.height(8.dp))

    Text(
      text = "TASKS",
      style = KetchTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
      color = colors.onSurfaceVariant,
      modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
    )

    StatusFilter.entries.forEach { filter ->
      val count = taskCounts[filter] ?: 0
      KetchSidebarItem(
        label = filter.label,
        icon = filterIcon(filter),
        selected = selectedFilter == filter,
        onClick = { onFilterSelect(filter) },
        count = if (count > 0) count else null,
      )
    }
  }
}

internal fun filterIcon(filter: StatusFilter): KetchIcon = when (filter) {
  StatusFilter.All -> KetchIcon.All
  StatusFilter.Downloading -> KetchIcon.Active
  StatusFilter.Paused -> KetchIcon.Pause
  StatusFilter.Completed -> KetchIcon.Done
  StatusFilter.Failed -> KetchIcon.Failed
}
