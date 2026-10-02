package com.linroid.ketch.app.ui.list

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import com.linroid.ketch.app.state.RowGroup
import com.linroid.ketch.app.state.TaskListView
import com.linroid.ketch.app.theme.KetchDensity
import com.linroid.ketch.app.theme.KetchTheme
import com.linroid.ketch.app.ui.downloads.actions.ListActions

/**
 * The tasks of [view] as two-line rows under sticky group headers, for cards narrower than the
 * table, touch screens, or when the list view is picked.
 *
 * @param bottomPadding room under the last row, such as for the phone's Add button.
 * @param groupAction a group header's own action, such as Retry all.
 */
@Composable
internal fun DownloadList(
  view: TaskListView,
  actions: ListActions,
  collapse: GroupCollapse,
  listState: LazyListState,
  modifier: Modifier = Modifier,
  bottomPadding: Dp = KetchTheme.spacing.s4,
  groupAction: @Composable RowScope.(RowGroup) -> Unit = {},
) {
  val spacing = KetchTheme.spacing
  val touch = KetchTheme.density == KetchDensity.Comfortable
  val groups = view.groups
  val entries = listEntries(groups, collapse)
  TaskLazyList(
    entries = entries,
    groups = groups,
    actions = actions,
    collapse = collapse,
    listState = listState,
    modifier = modifier,
    contentPadding = PaddingValues(top = spacing.s1, bottom = bottomPadding),
    header = { entry ->
      GroupHeader(
        entry = entry,
        onToggle = { collapse.toggle(entry.group) },
        height = if (touch) spacing.s8 else spacing.tableGroupHeaderHeight,
        padding = spacing.s4,
        trailing = { groupAction(entry.group) },
      )
    },
    row = { row -> DownloadListRow(row, actions, Modifier.then(placement())) },
  )
}
