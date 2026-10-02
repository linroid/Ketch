package com.linroid.ketch.app.ui.discover

import androidx.compose.runtime.Composable
import com.linroid.ketch.app.state.AppState
import com.linroid.ketch.app.ui.AiDiscoveryPage

/**
 * The Discover destination: an AI search for downloads. Adding the chosen results shows them in
 * the Downloads list.
 */
@Composable
fun DiscoverScreen(state: AppState) {
  val draft = state.aiDiscover.draft
  AiDiscoveryPage(
    state = state.aiDiscoverState,
    draft = draft,
    onCancelSearch = { state.resetAiDiscover() },
    onDiscover = { query, sites -> state.aiDiscover(query, sites) },
    onDownloadSelected = { candidates ->
      state.aiDownloadSelected(candidates)
      draft.selected = emptySet()
      state.showDownloads()
    },
  )
}
