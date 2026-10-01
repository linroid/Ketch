package com.linroid.ketch.app.ui.shell

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class PhoneTopBarTest {

  @Test
  fun searchSuggestion_link_offersToDownloadIt() {
    val suggestion = searchSuggestion("https://example.com/files/ubuntu.iso", discover = true)

    assertEquals(
      SearchSuggestion.Download(
        urls = listOf("https://example.com/files/ubuntu.iso"),
        withOptions = false,
      ),
      suggestion,
    )
    assertEquals("Download on This phone", suggestion?.label("This phone"))
    assertEquals("ubuntu.iso", suggestion?.detail)
  }

  @Test
  fun searchSuggestion_curlCommand_opensTheAddSheet() {
    val suggestion = searchSuggestion("curl https://example.com/a.iso -H 'Cookie: a=1'", false)

    assertEquals(SearchSuggestion.Download(listOf("https://example.com/a.iso"), true), suggestion)
  }

  @Test
  fun searchSuggestion_plainText_searchesDiscoverOnlyWhereOffered() {
    assertEquals(
      SearchSuggestion.Discover("blender for mac"),
      searchSuggestion("  blender   for mac ", discover = true),
    )
    assertNull(searchSuggestion("blender for mac", discover = false))
    assertNull(searchSuggestion("   ", discover = true))
  }

  @Test
  fun nestedScroll_scrollingDown_collapsesTheBarBeforeTheContent() {
    val chrome = PhoneChromeState()
    chrome.heightOffsetLimit = -100f
    val connection = chrome.nestedScrollConnection

    val consumed = connection.onPreScroll(Offset(0f, -60f), NestedScrollSource.UserInput)
    connection.onPreScroll(Offset(0f, -60f), NestedScrollSource.UserInput)

    assertEquals(-60f, consumed.y)
    assertEquals(1f, chrome.collapsedFraction)
  }

  @Test
  fun nestedScroll_scrollingUp_bringsTheBarBackAtOnce() {
    val chrome = PhoneChromeState()
    chrome.heightOffsetLimit = -100f
    val connection = chrome.nestedScrollConnection
    connection.onPreScroll(Offset(0f, -100f), NestedScrollSource.UserInput)
    connection.onPostScroll(Offset(0f, -300f), Offset.Zero, NestedScrollSource.UserInput)

    connection.onPreScroll(Offset(0f, 40f), NestedScrollSource.UserInput)

    assertEquals(0.6f, chrome.collapsedFraction)
    assertEquals(300f, chrome.contentOffset)
  }

  @Test
  fun nestedScroll_contentReachesItsTop_forgetsHowFarItScrolled() {
    val chrome = PhoneChromeState()
    val connection = chrome.nestedScrollConnection
    connection.onPostScroll(Offset(0f, -300f), Offset.Zero, NestedScrollSource.UserInput)

    connection.onPostScroll(Offset(0f, 200f), Offset(0f, 50f), NestedScrollSource.UserInput)

    assertEquals(0f, chrome.contentOffset)
  }
}
