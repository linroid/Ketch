package com.linroid.ketch.ai.search

import com.linroid.ketch.ai.fetch.RateLimiter

/**
 * Starts the searches of [delegate] at least [RateLimiter]'s delay apart, whichever discovery
 * run makes them. Several runs can go on at once, and a search API answers queries over its
 * rate limit with an error: Brave's free plan allows one a second.
 *
 * @param pacing spaces the searches; the default starts them 1.1 s apart
 */
internal class PacedSearchProvider(
  private val delegate: SearchProvider,
  private val pacing: RateLimiter = RateLimiter(delayMs = SPACING_MS),
) : SearchProvider {

  override suspend fun search(
    query: String,
    sites: List<String>,
    maxResults: Int,
  ): List<SearchResult> = pacing.withPermit(KEY) { delegate.search(query, sites, maxResults) }

  private companion object {
    /** One query a second, with a margin for the time a request takes to reach the API. */
    const val SPACING_MS = 1100L

    /** Every search shares one key, so they are spaced as if to one host. */
    const val KEY = "search"
  }
}
