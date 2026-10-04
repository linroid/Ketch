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
 * @param title the agent's short name for the search, such as "Blender 4.2 for Apple silicon",
 *   to name a conversation by: one line of plain text of at most 60 characters. Untrusted model
 *   output like [summary], so show it as text only. Blank when the agent gave none, as it may
 *   for a follow-up
 * @param filtered how many of the agent's candidates the content filter hid
 *   ([DiscoverQuery.contentFilter]); they are not in [candidates], while [summary] may still
 *   speak of them
 */
data class DiscoverResult(
  val query: String,
  val candidates: List<RankedCandidate>,
  val sources: List<Source>,
  val summary: String = "",
  val title: String = "",
  val filtered: Int = 0,
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
