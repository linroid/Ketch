package com.linroid.ketch.app.state

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

sealed interface AiDiscoverState {
  data object Idle : AiDiscoverState
  data object Loading : AiDiscoverState
  data class Results(val candidates: List<AiCandidate>) : AiDiscoverState
  data class Error(val message: String) : AiDiscoverState
}

/** Shell-owned draft survives navigation between discovery and downloads. */
class AiDiscoverDraft {
  var query by mutableStateOf("")
  var sites by mutableStateOf("")
  var showSites by mutableStateOf(false)
  var submittedQuery by mutableStateOf("")
  var selected by mutableStateOf(setOf<String>())

  fun prepareSearch() {
    submittedQuery = query.trim()
    selected = emptySet()
  }

  fun selectedCandidates(state: AiDiscoverState): List<AiCandidate> =
    (state as? AiDiscoverState.Results)?.candidates.orEmpty()
      .distinctBy { it.url }.filter { it.url in selected }
}

