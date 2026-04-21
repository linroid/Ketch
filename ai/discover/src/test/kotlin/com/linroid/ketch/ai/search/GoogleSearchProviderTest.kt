package com.linroid.ketch.ai.search

import kotlin.test.Test
import kotlin.test.assertEquals

class GoogleSearchProviderTest {

  @Test
  fun buildMultiSiteQuery_noSites_returnsQueryUnchanged() {
    val result = GoogleSearchProvider.buildMultiSiteQuery(
      "ffmpeg download",
      emptyList(),
    )
    assertEquals("ffmpeg download", result)
  }

  @Test
  fun buildMultiSiteQuery_singleSite_returnsQueryUnchanged() {
    val result = GoogleSearchProvider.buildMultiSiteQuery(
      "ffmpeg download",
      listOf("ffmpeg.org"),
    )
    assertEquals("ffmpeg download", result)
  }

  @Test
  fun buildMultiSiteQuery_multipleSites_joinsWithOR() {
    val result = GoogleSearchProvider.buildMultiSiteQuery(
      "download",
      listOf("example.com", "test.org"),
    )
    assertEquals(
      "download (site:example.com OR site:test.org)",
      result,
    )
  }
}
