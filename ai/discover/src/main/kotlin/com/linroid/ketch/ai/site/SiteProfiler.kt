package com.linroid.ketch.ai.site

import com.linroid.ketch.ai.fetch.FetchResult
import com.linroid.ketch.ai.fetch.SafeFetcher
import com.linroid.ketch.api.log.KetchLogger
import kotlin.time.Instant

/**
 * Profiles a site by fetching robots.txt, discovering sitemaps,
 * and looking for RSS/Atom feed links.
 */
class SiteProfiler internal constructor(
  private val fetcher: SafeFetcher,
  private val robotsTxtParser: RobotsTxtParser = RobotsTxtParser(),
) {

  private val log = KetchLogger("SiteProfiler")

  /**
   * Profiles the given [domain] by fetching robots.txt and
   * optionally the homepage for feed discovery.
   */
  suspend fun profile(domain: String): SiteProfile {
    log.d { "Profiling $domain" }
    val robotsResult = fetchRobotsTxt(domain)
    val sitemaps = mutableListOf<String>()
    var robotsTxtRules: RobotsTxtRules? = null
    var hasRobotsTxt = false
    var crawlDelay: Int? = null

    when (robotsResult) {
      is FetchResult.Success -> {
        hasRobotsTxt = true
        robotsTxtRules = robotsTxtParser.parse(robotsResult.content)
        sitemaps.addAll(robotsTxtRules.sitemaps)
        crawlDelay = robotsTxtRules.crawlDelay
        log.d {
          "robots.txt: ${robotsTxtRules.rules.size} rules, " +
            "${sitemaps.size} sitemaps, crawlDelay=$crawlDelay"
        }
      }
      is FetchResult.Failed -> {
        log.d { "No robots.txt: ${robotsResult.reason}" }
      }
    }

    // Try default sitemap location if none in robots.txt
    if (sitemaps.isEmpty()) {
      val defaultSitemap = "https://$domain/sitemap.xml"
      when (val result = fetcher.fetch(defaultSitemap)) {
        is FetchResult.Success -> {
          if (result.content.contains("<urlset") ||
            result.content.contains("<sitemapindex")
          ) {
            sitemaps.add(defaultSitemap)
          }
        }
        is FetchResult.Failed -> {
          // Not found, that's fine
        }
      }
    }

    // Look for RSS/Atom feeds on homepage
    val rssFeeds = discoverFeeds(domain)

    return SiteProfile(
      domain = domain,
      robotsTxtRules = robotsTxtRules,
      hasRobotsTxt = hasRobotsTxt,
      sitemaps = sitemaps,
      rssFeeds = rssFeeds,
      crawlDelay = crawlDelay,
      lastProfiled = Instant.fromEpochMilliseconds(
        System.currentTimeMillis()
      ),
    )
  }

  /**
   * Checks if [urlPath] is allowed by robots.txt for [domain].
   *
   * @return `true` if allowed or no robots.txt exists
   */
  fun isAllowed(
    urlPath: String,
    profile: SiteProfile,
  ): Boolean {
    val rules = profile.robotsTxtRules ?: return true
    return robotsTxtParser.isAllowed(urlPath, rules)
  }

  private suspend fun fetchRobotsTxt(
    domain: String,
  ): FetchResult {
    return fetcher.fetch("https://$domain/robots.txt")
  }

  private suspend fun discoverFeeds(
    domain: String,
  ): List<String> {
    val result = fetcher.fetch("https://$domain/")
    if (result !is FetchResult.Success) return emptyList()

    val feeds = mutableListOf<String>()
    val content = result.content

    // Look for <link rel="alternate" type="application/rss+xml">
    FEED_LINK_PATTERN.findAll(content).forEach { match ->
      val href = match.groupValues[1]
      if (href.isNotEmpty()) {
        val feedUrl = if (href.startsWith("http")) {
          href
        } else {
          "https://$domain${if (href.startsWith("/")) "" else "/"}$href"
        }
        feeds.add(feedUrl)
      }
    }

    return feeds.distinct()
  }

  companion object {
    private val FEED_LINK_PATTERN = Regex(
      "<link[^>]*type=\"application/" +
        "(?:rss|atom)\\+xml\"[^>]*href=\"([^\"]+)\"",
      RegexOption.IGNORE_CASE,
    )
  }
}
