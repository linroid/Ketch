package com.linroid.ketch.ai

/**
 * Input for a resource discovery request.
 *
 * @param query natural language description of the desired resource
 * @param sites optional domain allowlist to constrain discovery
 * @param maxResults maximum number of candidates to return
 * @param fileTypes optional file type filter (e.g., "iso", "zip")
 */
data class DiscoverQuery(
  val query: String,
  val sites: List<String> = emptyList(),
  val maxResults: Int = 10,
  val fileTypes: List<String> = emptyList(),
)
