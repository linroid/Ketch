package com.linroid.ketch.ai.search

import kotlinx.serialization.Serializable

/**
 * Interface for web search functionality.
 *
 * Implementations search the web for pages that may contain
 * downloadable resources matching the user's query.
 */
interface SearchProvider {

  /**
   * Searches the web for pages matching [query].
   *
   * @param query search query text
   * @param sites optional list of domains to restrict results to
   * @param maxResults maximum number of results to return
   * @return list of search results
   */
  suspend fun search(
    query: String,
    sites: List<String> = emptyList(),
    maxResults: Int = 10,
  ): List<SearchResult>
}

/**
 * A single search result.
 */
@Serializable
data class SearchResult(
  val url: String,
  val title: String,
  val snippet: String,
)
