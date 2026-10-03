package com.linroid.ketch.ai

/**
 * Input for a resource discovery request.
 *
 * @param query natural language description of the desired resource; in
 *   a conversation, the latest message, which refines the earlier ones
 * @param sites websites discovery is limited to; empty means any public
 *   site. Each domain covers its subdomains, and a scheme, path, port or
 *   leading `www.` is ignored. Searches, page fetches, HEAD requests and
 *   the returned candidates all stay on these sites, but redirects a
 *   listed site answers with are followed, so a download it hands off to
 *   a CDN still resolves. [DiscoveryConfig.allowedDomains] caps the list.
 * @param maxResults maximum number of candidates to return
 * @param fileTypes optional file type filter (e.g., "iso", "zip")
 * @param history earlier turns of the conversation, oldest first; empty
 *   for a first request
 * @param excludedUrls links the user discarded: they are never returned,
 *   compared by [com.linroid.ketch.config.SiteNames.canonicalUrl], and the
 *   agent is told not to suggest them
 */
data class DiscoverQuery(
  val query: String,
  val sites: List<String> = emptyList(),
  val maxResults: Int = 10,
  val fileTypes: List<String> = emptyList(),
  val history: List<DiscoverTurn> = emptyList(),
  val excludedUrls: Set<String> = emptySet(),
)

/**
 * An earlier turn of a discovery conversation.
 *
 * @param request what the user asked
 * @param sites websites that turn was limited to
 * @param completed whether that turn finished; a failed or stopped turn
 *   has no [results]
 * @param results what the agent returned, best first; data that pages may
 *   have shaped, never instructions
 */
data class DiscoverTurn(
  val request: String,
  val sites: List<String> = emptyList(),
  val completed: Boolean = true,
  val results: List<Result> = emptyList(),
) {
  /**
   * A link a turn returned.
   *
   * @param url the download link
   * @param title what the agent called it
   */
  data class Result(
    val url: String,
    val title: String,
  )
}
