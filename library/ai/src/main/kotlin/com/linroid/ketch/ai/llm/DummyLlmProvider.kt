package com.linroid.ketch.ai.llm

import com.linroid.ketch.ai.search.SearchResult

/**
 * No-op LLM provider that returns empty results.
 * Used as a fallback when AI is disabled or no LLM is configured.
 */
internal class DummyLlmProvider : LlmProvider {

  override suspend fun discoverResources(
    query: String,
    searchHits: List<SearchResult>,
    fetchedPages: List<FetchedResource>,
  ): ResourceDiscoveryResult {
    return ResourceDiscoveryResult(candidates = emptyList())
  }
}
