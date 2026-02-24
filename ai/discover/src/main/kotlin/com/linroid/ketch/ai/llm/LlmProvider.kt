package com.linroid.ketch.ai.llm

import com.linroid.ketch.ai.search.SearchResult

/**
 * Interface for LLM-based resource discovery.
 *
 * Implementations use a large language model to extract and rank
 * downloadable resources from fetched page content.
 */
interface LlmProvider {

  /**
   * Discovers downloadable resources by analyzing fetched pages
   * in context of the user's [query].
   *
   * @param query the user's natural language search query
   * @param searchHits search engine results for context
   * @param fetchedPages pages that were fetched and extracted
   * @return discovery result with ranked candidates
   */
  suspend fun discoverResources(
    query: String,
    searchHits: List<SearchResult>,
    fetchedPages: List<FetchedResource>,
  ): ResourceDiscoveryResult
}

/**
 * A fetched and extracted page for LLM analysis.
 */
data class FetchedResource(
  val url: String,
  val title: String,
  val content: String,
)

/**
 * Result of LLM-based resource discovery.
 */
data class ResourceDiscoveryResult(
  val candidates: List<RankedCandidate>,
)

/**
 * A download URL extracted and ranked by the LLM.
 */
data class RankedCandidate(
  val url: String,
  val title: String,
  val fileName: String?,
  val fileSize: Long?,
  val mimeType: String?,
  val sourceUrl: String,
  val confidence: Float,
  val description: String,
)
