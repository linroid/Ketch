package com.linroid.ketch.app

import com.linroid.ketch.app.state.AiCandidate
import com.linroid.ketch.app.state.AiDiscoverDraft
import com.linroid.ketch.app.state.AiDiscoverState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AiDiscoverDraftTest {
  @Test
  fun refiningSearchKeepsResultContextUntilSubmitted() {
    val draft = AiDiscoverDraft()
    draft.query = "  Ubuntu ISO  "
    draft.sites = "ubuntu.com"
    draft.prepareSearch()
    draft.selected = setOf("https://example.com/old.iso")
    draft.query = "Blender"
    assertEquals("Ubuntu ISO", draft.submittedQuery)
    draft.prepareSearch()
    assertEquals("Blender", draft.submittedQuery)
    assertTrue(draft.selected.isEmpty())
    assertEquals("ubuntu.com", draft.sites)
  }

  @Test
  fun onlyCurrentUniqueSelectedLinksCanBeDownloaded() {
    val candidate = AiCandidate("https://example.com/file.zip", "A file",
      confidence = 0.9f, description = "")
    val draft = AiDiscoverDraft()
    draft.selected = setOf(candidate.url, "https://example.com/stale.zip")
    val state = AiDiscoverState.Results(listOf(candidate, candidate.copy(title = "Duplicate")))
    assertEquals(listOf(candidate), draft.selectedCandidates(state))
    assertTrue(draft.selectedCandidates(AiDiscoverState.Loading).isEmpty())
    assertTrue(draft.selectedCandidates(AiDiscoverState.Results(emptyList())).isEmpty())
  }
}
