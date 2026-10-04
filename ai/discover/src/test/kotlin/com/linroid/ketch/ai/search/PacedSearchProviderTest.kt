package com.linroid.ketch.ai.search

import com.linroid.ketch.ai.fetch.RateLimiter
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class PacedSearchProviderTest {

  @Test
  fun search_concurrentRuns_startOneSpacingApart() = runTest {
    val starts = mutableListOf<Long>()
    val delegate = object : SearchProvider {
      override suspend fun search(
        query: String,
        sites: List<String>,
        maxResults: Int,
      ): List<SearchResult> {
        starts += testScheduler.currentTime
        return emptyList()
      }
    }
    val pacing = RateLimiter(delayMs = 1100) { testScheduler.currentTime }
    val paced = PacedSearchProvider(delegate, pacing)

    // Different queries and sites, as separate runs would send them.
    val searches = listOf(
      "ubuntu iso" to emptyList<String>(),
      "ffmpeg" to listOf("ffmpeg.org"),
      "blender" to emptyList(),
    )
    searches.map { (query, sites) -> launch { paced.search(query, sites, maxResults = 5) } }
      .joinAll()

    assertEquals(listOf(0L, 1100L, 2200L), starts)
  }
}
