package com.linroid.ketch.app.ui.downloads

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.linroid.ketch.app.components.KetchIconButton
import com.linroid.ketch.app.components.KetchTextField
import com.linroid.ketch.app.icons.KetchIcon
import com.linroid.ketch.app.state.AppState
import com.linroid.ketch.app.state.StatusFilter
import com.linroid.ketch.app.theme.KetchTheme
import com.linroid.ketch.app.ui.DownloadFilters
import com.linroid.ketch.app.ui.list.DownloadList
import com.linroid.ketch.app.ui.toolbar.BatchActionBar
import com.linroid.ketch.app.ui.toolbar.KetchToolbar

/** Width tiers of the window, which set how the app lays itself out. */
enum class LayoutTier {
  /** Phones, narrower than [KetchLayoutInfo.MediumWidth]. */
  Compact,

  /** Tablets and narrow windows. */
  Medium,

  /** Wide windows, from [KetchLayoutInfo.ExpandedWidth]. */
  Expanded,
}

/**
 * The room the window gives the app, which screens adapt to.
 *
 * @property tier width tier of the window.
 * @property windowWidth width of the window.
 */
@Immutable
data class KetchLayoutInfo(
  val tier: LayoutTier,
  val windowWidth: Dp,
) {
  companion object {
    /** Narrowest window of the [LayoutTier.Medium] tier. */
    val MediumWidth: Dp = 600.dp

    /** Narrowest window of the [LayoutTier.Expanded] tier. */
    val ExpandedWidth: Dp = 1024.dp

    /** Layout of a window [windowWidth] wide. */
    fun of(windowWidth: Dp): KetchLayoutInfo = KetchLayoutInfo(
      tier = when {
        windowWidth < MediumWidth -> LayoutTier.Compact
        windowWidth < ExpandedWidth -> LayoutTier.Medium
        else -> LayoutTier.Expanded
      },
      windowWidth = windowWidth,
    )
  }
}

/**
 * The Downloads page: the header with search and bulk actions, the status tabs and the tasks of
 * the active device, from [AppState.taskList].
 */
@Composable
fun DownloadsScreen(state: AppState, layout: KetchLayoutInfo, modifier: Modifier = Modifier) {
  val scope = rememberCoroutineScope()
  val spacing = KetchTheme.spacing
  val tasks by state.tasks.collectAsState()
  val rows by state.taskList.rows.collectAsState()
  val view by state.taskList.view.collectAsState()
  val counts by state.taskList.counts.collectAsState()
  val pulse by state.pulse.state.collectAsState()
  val filter = state.statusFilter
  val query = state.searchQuery
  val title = if (filter == StatusFilter.All) "Downloads" else filter.label
  val hasActive = (counts[StatusFilter.Downloading] ?: 0) > 0
  val hasPaused = (counts[StatusFilter.Paused] ?: 0) > 0
  val hasCompleted = (counts[StatusFilter.Done] ?: 0) > 0
  val shown = remember(view) { view.rows.map { it.task } }
  // Rows arrive shortly after the tasks; until then the list stays blank rather than empty.
  val loading = rows.isEmpty() && tasks.isNotEmpty()

  Column(modifier) {
    if (layout.tier == LayoutTier.Compact) {
      CompactHeader(
        title = title,
        hasActive = hasActive,
        hasPaused = hasPaused,
        hasCompleted = hasCompleted,
        state = state,
      )
      KetchTextField(
        value = query,
        onValueChange = { state.searchQuery = it },
        placeholder = "Search downloads…",
        leadingIcon = KetchIcon.Search,
        modifier = Modifier.fillMaxWidth()
          .padding(horizontal = spacing.s4, vertical = spacing.s2),
      )
    } else {
      KetchToolbar(
        title = title,
        downloadCount = view.matched,
        searchQuery = query,
        onSearchQueryChange = { state.searchQuery = it },
        bandwidthBytesPerSec = pulse.totalSpeed,
        globalCapBytesPerSec = pulse.cap.takeUnless { it.isUnlimited }?.bytesPerSecond,
        hasActiveDownloads = hasActive,
        hasPausedDownloads = hasPaused,
        hasCompletedDownloads = hasCompleted,
        onPauseAll = { state.pauseAll() },
        onResumeAll = { state.resumeAll() },
        onClearCompleted = { state.clearCompleted() },
        onAddClick = { state.requestAddDownload() },
      )
    }
    DownloadFilters(
      selected = filter,
      counts = counts,
      onSelect = { state.statusFilter = it },
    )
    DownloadList(
      tasks = shown,
      onAddDownload = { state.requestAddDownload() },
      isEmpty = tasks.isEmpty(),
      isFilterEmpty = !loading && view.rows.isEmpty() && rows.isNotEmpty(),
      selectedFilter = filter,
      onShowAllDownloads = { state.statusFilter = StatusFilter.All },
      onClearSearch = { state.searchQuery = "" },
      searchQuery = query,
      bottomPadding = spacing.s6,
      scope = scope,
      modifier = Modifier.weight(1f),
    )
  }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CompactHeader(
  title: String,
  hasActive: Boolean,
  hasPaused: Boolean,
  hasCompleted: Boolean,
  state: AppState,
) {
  val colors = KetchTheme.colors
  TopAppBar(
    title = {
      Text(
        text = title,
        style = KetchTheme.typography.titleM,
        fontWeight = FontWeight.SemiBold,
      )
    },
    actions = {
      KetchIconButton(
        icon = KetchIcon.Plus,
        contentDescription = "Add download",
        onClick = { state.requestAddDownload() },
        tint = colors.accent,
      )
      BatchActionBar(
        hasActiveDownloads = hasActive,
        hasPausedDownloads = hasPaused,
        hasCompletedDownloads = hasCompleted,
        onPauseAll = { state.pauseAll() },
        onResumeAll = { state.resumeAll() },
        onClearCompleted = { state.clearCompleted() },
      )
    },
    colors = TopAppBarDefaults.topAppBarColors(containerColor = colors.surface),
  )
}
