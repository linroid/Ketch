package com.linroid.ketch.app.ui.list

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.linroid.ketch.api.DownloadTask
import com.linroid.ketch.app.components.KetchButton
import com.linroid.ketch.app.components.KetchButtonVariant
import com.linroid.ketch.app.icons.KetchIcon
import com.linroid.ketch.app.icons.KetchIconImage
import com.linroid.ketch.app.state.StatusFilter
import com.linroid.ketch.app.theme.KetchTheme
import kotlinx.coroutines.CoroutineScope

@Composable
fun DownloadList(
  tasks: List<DownloadTask>,
  onAddDownload: () -> Unit,
  isEmpty: Boolean,
  isFilterEmpty: Boolean,
  selectedFilter: StatusFilter,
  onShowAllDownloads: () -> Unit,
  onClearSearch: () -> Unit,
  searchQuery: String = "",
  bottomPadding: Dp = 16.dp,
  scope: CoroutineScope,
  modifier: Modifier = Modifier,
) {
  when {
    isEmpty -> {
      EmptyState(onAddDownload = onAddDownload, modifier = modifier.fillMaxSize())
    }
    isFilterEmpty -> {
      EmptyFilterState(
        filter = selectedFilter,
        searchQuery = searchQuery,
        onShowAllDownloads = onShowAllDownloads,
        onClearSearch = onClearSearch,
        modifier = modifier.fillMaxSize(),
      )
    }
    else -> {
      val listState = rememberLazyListState()
      LaunchedEffect(selectedFilter, searchQuery) { listState.scrollToItem(0) }
      LazyColumn(
        state = listState,
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(
          start = 16.dp,
          end = 16.dp,
          top = 8.dp,
          bottom = bottomPadding,
        ),
        verticalArrangement = Arrangement.spacedBy(10.dp),
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
private fun EmptyState(onAddDownload: () -> Unit, modifier: Modifier = Modifier) {
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
        "Add a link to start your first download.",
        style = KetchTheme.typography.bodyMedium,
        color = KetchTheme.colors.onSurfaceVariant,
      )
      Spacer(Modifier.height(12.dp))
      KetchButton("Add download", onClick = onAddDownload, leadingIcon = KetchIcon.Plus)

    }
  }
}

@Composable
private fun EmptyFilterState(
  filter: StatusFilter,
  searchQuery: String,
  onShowAllDownloads: () -> Unit,
  onClearSearch: () -> Unit,
  modifier: Modifier = Modifier,
) {
  val searching = searchQuery.isNotBlank()
  val title = if (searching) "No matching downloads" else when (filter) {
    StatusFilter.All -> "No downloads yet"
    StatusFilter.Downloading -> "No active downloads"
    StatusFilter.Completed -> "No completed downloads yet"
    StatusFilter.Paused -> "No paused downloads"
    StatusFilter.Failed -> "No failed or canceled downloads"
  }
  val hint = if (searching) "Try a different file name or URL, or clear your search." else when (filter) {
    StatusFilter.All -> "Add a download to get started."
    StatusFilter.Downloading -> "Start a new download or resume a paused one."
    StatusFilter.Completed -> "Finished downloads will appear here."
    StatusFilter.Paused -> "Downloads you pause will appear here."
    StatusFilter.Failed -> "Downloads that fail or are canceled will appear here."
  }
  Box(
    modifier = modifier.padding(24.dp),
    contentAlignment = Alignment.Center,
  ) {
    Column(
      horizontalAlignment = Alignment.CenterHorizontally,
      verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
      KetchIconImage(
        icon = if (searching) KetchIcon.Search else when (filter) {
          StatusFilter.All -> KetchIcon.All
          StatusFilter.Downloading -> KetchIcon.Active
          StatusFilter.Completed -> KetchIcon.Done
          StatusFilter.Paused -> KetchIcon.Pause
          StatusFilter.Failed -> KetchIcon.Check
        },
        size = 48.dp,
        tint = KetchTheme.colors.onSurfaceVariant.copy(alpha = 0.5f),
      )
      Spacer(Modifier.height(4.dp))
      Text(
        text = title,
        style = KetchTheme.typography.bodyLarge.copy(fontWeight = FontWeight.SemiBold),
        color = KetchTheme.colors.onBackground,
        textAlign = TextAlign.Center,
      )
      Text(
        text = hint,
        style = KetchTheme.typography.bodyMedium,
        color = KetchTheme.colors.onSurfaceVariant,
        textAlign = TextAlign.Center,
      )
      Spacer(Modifier.height(4.dp))
      KetchButton(
        text = if (searching) "Clear search" else "Show all downloads",
        variant = KetchButtonVariant.Secondary,
        onClick = if (searching) onClearSearch else onShowAllDownloads,
      )
    }
  }
}
