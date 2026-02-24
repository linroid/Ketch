package com.linroid.ketch.ai

import kotlin.time.Instant

/**
 * Result of a resource discovery request.
 *
 * @param query the original query text
 * @param candidates ranked download candidates
 * @param sources pages that were fetched during discovery
 */
data class DiscoverResult(
  val query: String,
  val candidates: List<RankedCandidate>,
  val sources: List<Source>,
) {

  /**
   * A source page that was fetched during discovery.
   */
  data class Source(
    val url: String,
    val title: String,
    val fetchedAt: Instant,
  )
}
