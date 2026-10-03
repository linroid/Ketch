package com.linroid.ketch.ai

import kotlin.time.Instant

/**
 * Result of a resource discovery request.
 *
 * @param query the original query text
 * @param candidates ranked download candidates
 * @param sources pages that were fetched during discovery
 * @param summary the agent's short reply to the user, in plain text; model
 *   output that fetched pages may have shaped, so show it as text only.
 *   Blank when the agent gave none
 */
data class DiscoverResult(
  val query: String,
  val candidates: List<RankedCandidate>,
  val sources: List<Source>,
  val summary: String = "",
) {

  /**
   * A source page that was fetched during discovery.
   *
   * @param title the page's title as one line of plain text, or [url] when it has none
   */
  data class Source(
    val url: String,
    val title: String,
    val fetchedAt: Instant,
  )
}
