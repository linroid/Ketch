package com.linroid.ketch.ai

/**
 * Input for a resource discovery request.
 *
 * @param query natural language description of the desired resource
 * @param sites websites discovery is limited to; empty means any public
 *   site. Each domain covers its subdomains, and a scheme, path, port or
 *   leading `www.` is ignored. Searches, page fetches, HEAD requests and
 *   the returned candidates all stay on these sites, but redirects a
 *   listed site answers with are followed, so a download it hands off to
 *   a CDN still resolves. [DiscoveryConfig.allowedDomains] caps the list.
 * @param maxResults maximum number of candidates to return
 * @param fileTypes optional file type filter (e.g., "iso", "zip")
 */
data class DiscoverQuery(
  val query: String,
  val sites: List<String> = emptyList(),
  val maxResults: Int = 10,
  val fileTypes: List<String> = emptyList(),
)
