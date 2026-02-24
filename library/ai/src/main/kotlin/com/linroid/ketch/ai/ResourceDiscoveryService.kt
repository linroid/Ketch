package com.linroid.ketch.ai

import com.linroid.ketch.ai.fetch.ContentExtractor
import com.linroid.ketch.ai.fetch.FetchResult
import com.linroid.ketch.ai.fetch.RateLimiter
import com.linroid.ketch.ai.fetch.SafeFetcher
import com.linroid.ketch.ai.fetch.UrlValidator
import com.linroid.ketch.ai.fetch.ValidationResult
import com.linroid.ketch.ai.llm.FetchedResource
import com.linroid.ketch.ai.llm.LlmProvider
import com.linroid.ketch.ai.llm.RankedCandidate
import com.linroid.ketch.ai.search.SearchProvider
import com.linroid.ketch.ai.site.RobotsTxtParser
import com.linroid.ketch.ai.site.SiteProfileStore
import com.linroid.ketch.api.log.KetchLogger
import java.net.URI
import kotlin.time.Instant
import kotlin.time.TimeSource

/**
 * Orchestrates the AI resource discovery pipeline.
 *
 * Coordinates search, fetching, content extraction, and LLM
 * analysis to discover downloadable resources from a natural
 * language query.
 */
class ResourceDiscoveryService internal constructor(
  private val llmProvider: LlmProvider,
  private val searchProvider: SearchProvider,
  private val fetcher: SafeFetcher,
  private val urlValidator: UrlValidator,
  private val contentExtractor: ContentExtractor,
  private val siteProfileStore: SiteProfileStore,
  private val rateLimiter: RateLimiter,
  private val config: AiConfig,
) {

  private val log = KetchLogger("DiscoveryService")

  /**
   * Discovers downloadable resources matching [query].
   *
   * @throws IllegalArgumentException if the query text is blank
   */
  suspend fun discover(query: DiscoverQuery): DiscoverResult {
    require(query.query.isNotBlank()) { "Query must not be blank" }

    val startMark = TimeSource.Monotonic.markNow()
    log.i { "Discovery: query=\"${query.query}\"" }

    // 1. Search
    val searchHits = searchProvider.search(
      query = query.query,
      sites = query.sites,
      maxResults = query.maxResults.coerceAtMost(
        config.fetcher.maxFetchesPerRequest
      ),
    )
    log.d { "Search returned ${searchHits.size} hits" }

    // 2. Filter by allowlist and URL validation
    val allowedDomains = config.discovery.allowedDomains +
      query.sites
    val filteredHits = searchHits.filter { hit ->
      val validation = urlValidator.validate(hit.url)
      if (validation is ValidationResult.Blocked) {
        log.d { "Filtered out: ${hit.url} (${validation.reason})" }
        return@filter false
      }
      if (allowedDomains.isNotEmpty()) {
        val hitDomain = extractDomain(hit.url)
        val allowed = allowedDomains.any { domain ->
          hitDomain == domain ||
            hitDomain.endsWith(".$domain")
        }
        if (!allowed) {
          log.d { "Filtered out: ${hit.url} (not in allowlist)" }
        }
        allowed
      } else {
        true
      }
    }
    log.d { "After filtering: ${filteredHits.size} hits" }

    // 3. Fetch pages
    val sources = mutableListOf<DiscoverResult.Source>()
    val fetchedPages = mutableListOf<FetchedResource>()
    val maxFetches = config.fetcher.maxFetchesPerRequest
      .coerceAtMost(filteredHits.size)

    for (hit in filteredHits.take(maxFetches)) {
      val domain = extractDomain(hit.url)

      // Check robots.txt via site profile
      val siteEntry = siteProfileStore.get(domain)
      if (siteEntry != null) {
        val profile = siteEntry.profile
        val path = URI(hit.url).path ?: "/"
        val rules = profile.robotsTxtRules
        if (rules != null) {
          val parser = RobotsTxtParser()
          if (!parser.isAllowed(path, rules)) {
            log.d { "Blocked by robots.txt: ${hit.url}" }
            continue
          }
        }
      }

      if (!rateLimiter.acquire(domain)) {
        log.d { "Rate limited: $domain" }
        continue
      }

      try {
        when (val result = fetcher.fetch(hit.url)) {
          is FetchResult.Success -> {
            val title = contentExtractor.extractTitle(
              result.content
            ).ifEmpty { hit.title }
            val text = contentExtractor.extract(result.content)

            fetchedPages.add(
              FetchedResource(
                url = hit.url,
                title = title,
                content = text,
              )
            )
            sources.add(
              DiscoverResult.Source(
                url = hit.url,
                title = title,
                fetchedAt = Instant.fromEpochMilliseconds(
                  System.currentTimeMillis()
                ),
              )
            )
          }
          is FetchResult.Failed -> {
            log.d { "Fetch failed: ${hit.url} (${result.reason})" }
          }
        }
      } finally {
        rateLimiter.release()
      }
    }

    log.d { "Fetched ${fetchedPages.size} pages" }

    // 4. LLM analysis
    val llmResult = llmProvider.discoverResources(
      query = query.query,
      searchHits = filteredHits,
      fetchedPages = fetchedPages,
    )

    // 5. Deduplicate and return
    val candidates = deduplicateCandidates(llmResult.candidates)
      .take(query.maxResults)

    val elapsed = startMark.elapsedNow()
    log.i {
      "Discovery complete: ${candidates.size} candidates" +
        " in ${elapsed.inWholeMilliseconds}ms"
    }

    return DiscoverResult(
      query = query.query,
      candidates = candidates,
      sources = sources,
    )
  }

  /**
   * Deduplicates candidates by URL, keeping the highest confidence.
   */
  private fun deduplicateCandidates(
    candidates: List<RankedCandidate>,
  ): List<RankedCandidate> {
    return candidates
      .groupBy { it.url }
      .map { (_, group) ->
        group.maxBy { it.confidence }
      }
      .sortedByDescending { it.confidence }
  }

  private fun extractDomain(url: String): String {
    return try {
      URI(url).host?.lowercase() ?: ""
    } catch (_: Exception) {
      ""
    }
  }
}
