package com.linroid.ketch.app.ui.downloads

import com.linroid.ketch.app.i18n.load
import com.linroid.ketch.app.i18n.verbatim
import com.linroid.ketch.app.state.StatusFilter
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class EmptyStatesTest {
  private val mac = verbatim("This Mac")
  private val nas = verbatim("NAS")

  @Test
  fun emptyCopy_searchWithoutMatches_offersToClearIt() = runTest {
    val copy = emptyCopy(StatusFilter.All, " ubu ", mac, slots = 3)

    assertEquals("No downloads match “ubu”", copy.title.load())
    assertEquals(EmptyAction.ClearSearch, copy.action)
    assertEquals("Clear search", copy.actionLabel.load())
  }

  @Test
  fun emptyCopy_searchForALink_offersToAddIt() = runTest {
    val copy = emptyCopy(StatusFilter.All, "https://example.com/a.iso", nas, slots = null)

    assertEquals(EmptyAction.AddLinks, copy.action)
    assertEquals("Add it to NAS instead.", copy.hint.load())
    assertEquals("Add this link", copy.actionLabel.load())
  }

  @Test
  fun emptyCopy_searchForTwoLinks_speaksOfThem() = runTest {
    val copy = emptyCopy(
      filter = StatusFilter.All,
      query = "https://example.com/a.iso https://example.com/b.iso",
      deviceName = nas,
      slots = null,
    )

    assertEquals("These links aren't in your downloads", copy.title.load())
    assertEquals("Add them to NAS instead.", copy.hint.load())
    assertEquals("Add these links", copy.actionLabel.load())
  }

  @Test
  fun offlineCopy_unreachableDevice_offersNoButton() = runTest {
    val offline = offlineCopy(nas, unauthorized = false)
    val refused = offlineCopy(nas, unauthorized = true)

    assertEquals("Can't reach NAS", offline.title.load())
    assertEquals("NAS needs a new access token", refused.title.load())
    assertNull(offline.action)
  }

  @Test
  fun emptyCopy_failedTab_saysNothingNeedsAttention() = runTest {
    val copy = emptyCopy(StatusFilter.Failed, "", mac, slots = 3)

    assertEquals("Nothing needs attention", copy.title.load())
    assertEquals(EmptyAction.ShowAll, copy.action)
  }

  @Test
  fun emptyCopy_waitingTab_namesHowManyRunAtATime() = runTest {
    assertEquals(
      "This Mac runs 2 at a time.",
      emptyCopy(StatusFilter.Waiting, "", mac, slots = 2).hint.load()
    )
    assertEquals(
      "This Mac runs one download at a time.",
      emptyCopy(StatusFilter.Waiting, "", mac, slots = 1).hint.load()
    )
    assertNull(emptyCopy(StatusFilter.Waiting, "", mac, slots = null).hint)
  }

  @Test
  fun fleetEmptyCopy_addsToTheTargetDevice() = runTest {
    val copy = fleetEmptyCopy(nas)

    assertEquals("No downloads on any device", copy.title.load())
    assertEquals("Add a link to NAS", copy.actionLabel.load())
  }
}
