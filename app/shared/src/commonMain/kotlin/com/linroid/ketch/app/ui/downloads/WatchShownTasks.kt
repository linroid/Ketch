package com.linroid.ketch.app.ui.downloads

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.snapshotFlow
import com.linroid.ketch.app.platform.isMobilePlatform
import com.linroid.ketch.app.state.AppState
import com.linroid.ketch.app.state.ShownSegmentsInterval
import com.linroid.ketch.app.state.TaskKey
import com.linroid.ketch.app.state.followSegments
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged

/** How long the rows on screen stay the same before their devices are asked for them. */
private const val SETTLE_MS = 250L

/**
 * Follows the segments of the rows [listState] shows, for their lane strips, every
 * [ShownSegmentsInterval] while the list shows; scrolling asks again once the rows settle.
 * Phones leave them out: the inspector asks for its task's segments, and a remote device's
 * segments cost data on the move.
 */
@OptIn(FlowPreview::class)
@Composable
internal fun WatchShownTasks(state: AppState, listState: LazyListState) {
  if (isMobilePlatform) return
  LaunchedEffect(state, listState) {
    snapshotFlow {
      listState.layoutInfo.visibleItemsInfo.mapNotNullTo(HashSet()) { item ->
        (item.key as? String)?.let(TaskKey::decode)
      }
    }
      .distinctUntilChanged()
      .debounce(SETTLE_MS)
      .collectLatest { keys ->
        if (keys.isNotEmpty()) state.followSegments(keys, ShownSegmentsInterval)
      }
  }
}
