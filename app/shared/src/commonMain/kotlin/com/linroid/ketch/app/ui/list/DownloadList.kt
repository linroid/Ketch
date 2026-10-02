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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import com.linroid.ketch.app.components.KetchButton
import com.linroid.ketch.app.components.KetchButtonVariant
import com.linroid.ketch.app.icons.KetchIcon
import com.linroid.ketch.app.icons.KetchIconImage
import com.linroid.ketch.app.state.StatusFilter
import com.linroid.ketch.app.state.TaskRow
import com.linroid.ketch.app.theme.KetchTheme

/**
 * The tasks of the Downloads list, or what to do when there are none.
 *
 * @param rows the rows to show, in order, from `TaskListModel.view`.
 * @param isEmpty whether the device has no downloads at all.
 * @param isFilterEmpty whether the tab and search leave none of them.
 */
@Composable
fun DownloadList(
  rows: List<TaskRow>,
  onAddDownload: () -> Unit,
  isEmpty: Boolean,
  isFilterEmpty: Boolean,
  selectedFilter: StatusFilter,
  onShowAllDownloads: () -> Unit,
  onClearSearch: () -> Unit,
  modifier: Modifier = Modifier,
  searchQuery: String = "",
  bottomPadding: Dp = KetchTheme.spacing.s4,
) {
  val spacing = KetchTheme.spacing
  when {
    isEmpty -> EmptyState(onAddDownload = onAddDownload, modifier = modifier.fillMaxSize())
    isFilterEmpty -> EmptyFilterState(
      filter = selectedFilter,
      searchQuery = searchQuery,
      onShowAllDownloads = onShowAllDownloads,
      onClearSearch = onClearSearch,
      modifier = modifier.fillMaxSize(),
    )
    else -> {
      val commands = rememberRowCommands()
      val listState = rememberLazyListState()
      LaunchedEffect(selectedFilter, searchQuery) { listState.scrollToItem(0) }
      LazyColumn(
        state = listState,
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(
          start = spacing.s4,
          end = spacing.s4,
          top = spacing.s2,
          bottom = bottomPadding,
        ),
        verticalArrangement = Arrangement.spacedBy(spacing.s2),
      ) {
        items(items = rows, key = { it.key.encode() }) { row ->
          DownloadListItem(row = row, commands = commands)
        }
      }
    }
  }
}

@Composable
private fun EmptyState(onAddDownload: () -> Unit, modifier: Modifier = Modifier) {
  val colors = KetchTheme.colors
  val spacing = KetchTheme.spacing
  Box(modifier = modifier, contentAlignment = Alignment.Center) {
    Column(
      horizontalAlignment = Alignment.CenterHorizontally,
      verticalArrangement = Arrangement.spacedBy(spacing.s1),
    ) {
      KetchIconImage(icon = KetchIcon.Active, size = spacing.s16, tint = colors.accent)
      Spacer(Modifier.height(spacing.s2))
      Text(
        text = "No downloads yet",
        style = KetchTheme.typography.titleL,
        color = colors.textPrimary,
      )
      Text(
        text = "Add a link to start your first download.",
        style = KetchTheme.typography.bodyS,
        color = colors.textSecondary,
      )
      Spacer(Modifier.height(spacing.s3))
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
  val colors = KetchTheme.colors
  val spacing = KetchTheme.spacing
  val searching = searchQuery.isNotBlank()
  val title = if (searching) {
    "No downloads match “${searchQuery.trim()}”"
  } else {
    when (filter) {
      StatusFilter.All -> "No downloads yet"
      StatusFilter.Downloading -> "Nothing downloading"
      StatusFilter.Waiting -> "Nothing waiting"
      StatusFilter.Done -> "No completed downloads yet"
      StatusFilter.Paused -> "No paused downloads"
      StatusFilter.Failed -> "Nothing needs attention"
    }
  }
  val hint = if (searching) {
    "Try a different file name or link, or clear your search."
  } else {
    when (filter) {
      StatusFilter.All -> "Add a download to get started."
      StatusFilter.Downloading -> "Start a new download or resume a paused one."
      StatusFilter.Waiting -> "Queued and scheduled downloads will appear here."
      StatusFilter.Done -> "Finished downloads will appear here."
      StatusFilter.Paused -> "Downloads you pause will appear here."
      StatusFilter.Failed -> "Downloads that fail or are canceled will appear here."
    }
  }
  val icon = if (searching) {
    KetchIcon.Search
  } else {
    when (filter) {
      StatusFilter.All -> KetchIcon.All
      StatusFilter.Downloading -> KetchIcon.Active
      StatusFilter.Waiting -> KetchIcon.Queued
      StatusFilter.Done -> KetchIcon.Done
      StatusFilter.Paused -> KetchIcon.Pause
      StatusFilter.Failed -> KetchIcon.Check
    }
  }
  Box(modifier = modifier.padding(spacing.s6), contentAlignment = Alignment.Center) {
    Column(
      horizontalAlignment = Alignment.CenterHorizontally,
      verticalArrangement = Arrangement.spacedBy(spacing.s2),
    ) {
      KetchIconImage(icon = icon, size = spacing.s12, tint = colors.textTertiary)
      Spacer(Modifier.height(spacing.s1))
      Text(
        text = title,
        style = KetchTheme.typography.bodyStrong,
        color = colors.textPrimary,
        textAlign = TextAlign.Center,
      )
      Text(
        text = hint,
        style = KetchTheme.typography.bodyS,
        color = colors.textSecondary,
        textAlign = TextAlign.Center,
      )
      Spacer(Modifier.height(spacing.s1))
      KetchButton(
        text = if (searching) "Clear search" else "Show all downloads",
        variant = KetchButtonVariant.Secondary,
        onClick = if (searching) onClearSearch else onShowAllDownloads,
      )
    }
  }
}
