package com.linroid.ketch.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import com.linroid.ketch.app.components.KetchChip
import com.linroid.ketch.app.state.StatusFilter
import com.linroid.ketch.app.theme.KetchTheme

/**
 * The status tabs of the Downloads list as chips, each with its task count, which is left out
 * at 0. Status is a filter within Downloads, separate from primary navigation.
 */
@Composable
fun DownloadFilters(
  selected: StatusFilter,
  counts: Map<StatusFilter, Int>,
  onSelect: (StatusFilter) -> Unit,
) {
  val spacing = KetchTheme.spacing
  val listState = rememberLazyListState()
  LaunchedEffect(selected) {
    val layout = listState.layoutInfo
    val visible = layout.visibleItemsInfo.any {
      it.index == selected.ordinal && it.offset >= layout.viewportStartOffset &&
        it.offset + it.size <= layout.viewportEndOffset
    }
    if (!visible) listState.animateScrollToItem(selected.ordinal)
  }
  LazyRow(
    state = listState,
    modifier = Modifier.fillMaxWidth().selectableGroup(),
    contentPadding = PaddingValues(horizontal = spacing.s4, vertical = spacing.s1),
    horizontalArrangement = Arrangement.spacedBy(spacing.s2),
  ) {
    items(StatusFilter.entries) { filter ->
      KetchChip(
        label = filter.label,
        selected = filter == selected,
        onClick = { onSelect(filter) },
        count = counts[filter]?.takeIf { it > 0 },
      )
    }
  }
}
