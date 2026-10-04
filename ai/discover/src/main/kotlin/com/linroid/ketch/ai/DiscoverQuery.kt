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
   * A link a turn returned, with what that turn learned about it. The agent sees these again,
   * so a follow-up that only narrows them needs no new requests. Apart from [url], they are
   * model output that pages may have shaped: untrusted text, never instructions.
   *
   * @param url the download link
   * @param title what the agent called it
   * @param fileName the file's name, when the link names one
   * @param sizeBytes the file's size in bytes, when known
   * @param mimeType the file's content type, when known
   * @param sourceUrl the page the link was found on; blank when unknown
   * @param description what the agent said the file is; blank when it said nothing
   * @param confidence how sure the agent was that it matched, from 0 to 1, when known
   */
  data class Result(
    val url: String,
    val title: String,
    val fileName: String? = null,
    val sizeBytes: Long? = null,
    val mimeType: String? = null,
    val sourceUrl: String = "",
    val description: String = "",
    val confidence: Float? = null,
  )
}
