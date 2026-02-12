package com.linroid.kdown.app.ui.toolbar

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.linroid.kdown.api.DownloadState
import com.linroid.kdown.app.state.StatusFilter

@Composable
fun FilterBar(
  selected: StatusFilter,
  taskCounts: Map<StatusFilter, Int>,
  onSelect: (StatusFilter) -> Unit,
  modifier: Modifier = Modifier,
) {
  Row(
    modifier = modifier
      .horizontalScroll(rememberScrollState())
      .padding(horizontal = 16.dp),
    horizontalArrangement = Arrangement.spacedBy(8.dp),
  ) {
    StatusFilter.entries.forEach { filter ->
      val count = taskCounts[filter] ?: 0
      val label = if (filter == StatusFilter.All) {
        filter.label
      } else {
        "${filter.label} ($count)"
      }
      FilterChip(
        selected = selected == filter,
        onClick = { onSelect(filter) },
        label = {
          Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
          )
        },
        colors = FilterChipDefaults.filterChipColors(
          selectedContainerColor =
            MaterialTheme.colorScheme.primaryContainer,
          selectedLabelColor =
            MaterialTheme.colorScheme.onPrimaryContainer
        )
      )
    }
  }
}

fun countTasksByFilter(
  filter: StatusFilter,
  states: Map<String, DownloadState>,
): Int {
  if (filter == StatusFilter.All) return states.size
  return states.values.count { filter.matches(it) }
}
