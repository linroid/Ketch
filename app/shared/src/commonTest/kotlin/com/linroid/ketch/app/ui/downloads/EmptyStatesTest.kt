package com.linroid.ketch.app.ui.downloads

import com.linroid.ketch.app.state.StatusFilter
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class EmptyStatesTest {
  @Test
  fun emptyCopy_searchWithoutMatches_offersToClearIt() {
    val copy = emptyCopy(StatusFilter.All, " ubu ", "This Mac", slots = 3)

    assertEquals("No downloads match “ubu”", copy.title)
    assertEquals(EmptyAction.ClearSearch, copy.action)
  }

  @Test
  fun emptyCopy_searchForALink_offersToAddIt() {
    val copy = emptyCopy(StatusFilter.All, "https://example.com/a.iso", "NAS", slots = null)

    assertEquals(EmptyAction.AddLinks, copy.action)
    assertEquals("Add it to NAS instead.", copy.hint)
  }

  @Test
  fun emptyCopy_failedTab_saysNothingNeedsAttention() {
    val copy = emptyCopy(StatusFilter.Failed, "", "This Mac", slots = 3)

    assertEquals("Nothing needs attention", copy.title)
    assertEquals(EmptyAction.ShowAll, copy.action)
  }

  @Test
  fun emptyCopy_waitingTab_namesHowManyRunAtATime() {
    assertEquals(
      "This Mac runs 2 at a time.",
      emptyCopy(StatusFilter.Waiting, "", "This Mac", slots = 2).hint
    )
    assertEquals(
      "This Mac runs one download at a time.",
      emptyCopy(StatusFilter.Waiting, "", "This Mac", slots = 1).hint
    )
    assertNull(emptyCopy(StatusFilter.Waiting, "", "This Mac", slots = null).hint)
  }
}
