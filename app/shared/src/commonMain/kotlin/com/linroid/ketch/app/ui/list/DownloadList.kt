package com.linroid.ketch.app.ui.list

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.linroid.ketch.api.DownloadTask
import com.linroid.ketch.app.components.KetchButton
import com.linroid.ketch.app.icons.KetchIcon
import com.linroid.ketch.app.icons.KetchIconImage
import com.linroid.ketch.app.state.StatusFilter
import com.linroid.ketch.app.theme.KetchTheme
import kotlinx.coroutines.CoroutineScope

@Composable
fun DownloadList(
  tasks: List<DownloadTask>,
  isEmpty: Boolean,
  isFilterEmpty: Boolean,
  selectedFilter: StatusFilter,
  scope: CoroutineScope,
  onAddClick: () -> Unit,
  modifier: Modifier = Modifier,
) {
  when {
    isEmpty -> {
      EmptyState(
        modifier = modifier.fillMaxSize(),
        onAddClick = onAddClick,
      )
    }
    isFilterEmpty -> {
      EmptyFilterState(
        filter = selectedFilter,
        modifier = modifier.fillMaxSize(),
      )
    }
    else -> {
      LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(
          start = 16.dp,
          end = 16.dp,
          top = 8.dp,
          bottom = 16.dp,
        ),
        verticalArrangement = Arrangement.spacedBy(12.dp),
      ) {
        items(
          items = tasks,
          key = { it.taskId },
        ) { task ->
          DownloadListItem(
            task = task,
            scope = scope,
          )
        }
      }
    }
  }
}

@Composable
private fun EmptyState(
  modifier: Modifier = Modifier,
  onAddClick: () -> Unit,
) {
  Box(
    modifier = modifier,
    contentAlignment = Alignment.Center,
  ) {
    Column(
      horizontalAlignment = Alignment.CenterHorizontally,
      verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
      KetchIconImage(
        icon = KetchIcon.Active,
        size = 64.dp,
        tint = KetchTheme.colors.primary.copy(alpha = 0.6f),
      )
      Spacer(modifier = Modifier.height(8.dp))
      Text(
        text = "No downloads yet",
        style = KetchTheme.typography.displaySmall.copy(fontWeight = FontWeight.SemiBold),
        color = KetchTheme.colors.onBackground,
      )
      Text(
        text = "Click \"New Task\" to start downloading",
        style = KetchTheme.typography.bodyMedium,
        color = KetchTheme.colors.onSurfaceVariant,
        textAlign = TextAlign.Center,
      )
      Spacer(modifier = Modifier.height(16.dp))
      KetchButton(
        text = "New Task",
        onClick = onAddClick,
        leadingIcon = KetchIcon.Plus,
      )
    }
  }
}

@Composable
private fun EmptyFilterState(
  filter: StatusFilter,
  modifier: Modifier = Modifier,
) {
  Box(
    modifier = modifier,
    contentAlignment = Alignment.Center,
  ) {
    Column(
      horizontalAlignment = Alignment.CenterHorizontally,
      verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
      KetchIconImage(
        icon = KetchIcon.Filter,
        size = 48.dp,
        tint = KetchTheme.colors.onSurfaceVariant.copy(alpha = 0.4f),
      )
      Spacer(modifier = Modifier.height(4.dp))
      Text(
        text = "No ${filter.label.lowercase()} downloads",
        style = KetchTheme.typography.bodyLarge,
        color = KetchTheme.colors.onSurfaceVariant,
      )
      Text(
        text = "Try a different category",
        style = KetchTheme.typography.bodySmall,
        color = KetchTheme.colors.onSurfaceDim,
      )
    }
  }
}
