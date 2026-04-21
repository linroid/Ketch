package com.linroid.ketch.ai.search

import kotlin.test.Test
import kotlin.test.assertEquals

class BingSearchProviderTest {

  @Test
  fun buildQuery_noSites_returnsQueryUnchanged() {
    val result = BingSearchProvider.buildQuery("ubuntu iso", emptyList())
    assertEquals("ubuntu iso", result)
  }

  @Test
  fun buildQuery_singleSite_appendsSiteOperator() {
    val result = BingSearchProvider.buildQuery(
      "ubuntu iso",
      listOf("ubuntu.com"),
    )
    assertEquals("ubuntu iso (site:ubuntu.com)", result)
  }

  @Test
  fun buildQuery_multipleSites_joinsWithOR() {
    val result = BingSearchProvider.buildQuery(
      "download",
      listOf("example.com", "test.org", "foo.net"),
    )
    assertEquals(
      "download (site:example.com OR site:test.org OR site:foo.net)",
      result,
    )
  }
}
