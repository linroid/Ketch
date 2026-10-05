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
 * @param contentFilter whether to hide results that look unsafe, such as links through URL
 *   shorteners or download aggregators, piracy, look-alike websites and installers over plain
 *   HTTP; [DiscoverResult.filtered] counts them. Private and local addresses, sites outside
 *   [sites] and [excludedUrls] are dropped either way
 * @param userDevice the device the user searches from, when known. Like [downloadDevice], it is
 *   a hint the agent may use to pick builds for that system and CPU when the request names
 *   none, never a limit
 * @param downloadDevice the device that downloads the files, when known, such as a server the
 *   app adds them to; it may be the same as [userDevice]
 */
data class DiscoverQuery(
  val query: String,
  val sites: List<String> = emptyList(),
  val maxResults: Int = 10,
  val fileTypes: List<String> = emptyList(),
  val history: List<DiscoverTurn> = emptyList(),
  val excludedUrls: Set<String> = emptySet(),
  val contentFilter: Boolean = true,
  val userDevice: DiscoverDevice? = null,
  val downloadDevice: DiscoverDevice? = null,
)

/**
 * A device a discovery request is for, as it reports its system.
 *
 * @param os its operating system, such as "Mac OS X", "Windows 11" or "Android 15"
 * @param arch its CPU architecture, such as "aarch64" or "amd64"; blank when unknown
 */
data class DiscoverDevice(
  val os: String,
  val arch: String = "",
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
