package com.linroid.ketch.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.linroid.ketch.app.state.StatusFilter

/** Status is a filter within Downloads, separate from primary navigation. */
@Composable
fun DownloadFilters(
  selected: StatusFilter,
  counts: Map<StatusFilter, Int>,
  onSelect: (StatusFilter) -> Unit,
) {
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
    contentPadding = PaddingValues(horizontal = 16.dp),
    horizontalArrangement = Arrangement.spacedBy(8.dp),
  ) {
    items(StatusFilter.entries) { filter ->
      FilterChip(
        selected = filter == selected,
        onClick = { onSelect(filter) },
        label = { Text(filter.label) },
        trailingIcon = {
          Text((counts[filter] ?: 0).toString())
        },
      )
    }
  }
}
