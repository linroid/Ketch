package com.linroid.ketch.app.state

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/** Where the current Discover search is. */
sealed interface AiDiscoverState {
  /** No search has run since the last reset, or the last one was stopped. */
  data object Idle : AiDiscoverState

  /** A search is running. */
  data object Loading : AiDiscoverState

  /** The search finished with [candidates], best first; it may have found none. */
  data class Results(val candidates: List<AiCandidate>) : AiDiscoverState

  /** The search failed with [message]. */
  data class Error(val message: String) : AiDiscoverState
}

/**
 * The Discover form, selection and target, kept while the user visits other pages.
 *
 * @property query the search being typed.
 * @property sites websites to limit it to, separated by commas or spaces.
 * @property showSites whether the website field shows.
 * @property submittedQuery the search the results are for.
 * @property selected links of the selected results.
 * @property target id of the device to add to; `null` adds to the active one.
 */
class AiDiscoverDraft {
  var query by mutableStateOf("")
  var sites by mutableStateOf("")
  var showSites by mutableStateOf(false)
  var submittedQuery by mutableStateOf("")
  var selected by mutableStateOf(setOf<String>())
  var target by mutableStateOf<String?>(null)

  /** Takes [query] as the search the next results are for, and clears the selection. */
  fun prepareSearch() {
    submittedQuery = query.trim()
    selected = emptySet()
  }

  /** The websites in [sites], without blanks. */
  fun siteList(): List<String> = parseSites(sites)

  /** The selected results of [state], each link once. */
  fun selectedCandidates(state: AiDiscoverState): List<AiCandidate> =
    (state as? AiDiscoverState.Results)?.candidates.orEmpty()
      .distinctBy { it.url }.filter { it.url in selected }

  /** Selects or deselects [candidate]. */
  fun toggle(candidate: AiCandidate) {
    selected = if (candidate.url in selected) selected - candidate.url else selected + candidate.url
  }
}

/** Websites typed as "ubuntu.com, blender.org" or "ubuntu.com blender.org". */
internal fun parseSites(text: String): List<String> =
  text.split(",", " ").map { it.trim() }.filter { it.isNotEmpty() }
