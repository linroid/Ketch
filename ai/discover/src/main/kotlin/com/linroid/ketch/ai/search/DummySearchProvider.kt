package com.linroid.ketch.ai.search

/**
 * No-op search provider that returns empty results.
 * Used as a default when no search API is configured.
 */
internal class DummySearchProvider : SearchProvider {

  override suspend fun search(
    query: String,
    sites: List<String>,
    maxResults: Int,
  ): List<SearchResult> = emptyList()
}
