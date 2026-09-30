package com.linroid.ketch.ai.site

import com.linroid.ketch.ai.fetch.FetchResult
import com.linroid.ketch.ai.fetch.SafeFetcher
import com.linroid.ketch.api.log.KetchLogger
import kotlin.time.Instant

/**
 * Profiles a site by fetching robots.txt, discovering sitemaps,
 * and looking for RSS/Atom feed links.
 *
 * @param robotsUserAgent product token matched against robots.txt
 *   `User-agent` lines
 */
class SiteProfiler internal constructor(
  private val fetcher: SafeFetcher,
  private val robotsTxtParser: RobotsTxtParser = RobotsTxtParser(),
  private val robotsUserAgent: String = RobotsTxtParser.DEFAULT_USER_AGENT,
) {

  private val log = KetchLogger("SiteProfiler")

  /**
   * Profiles the given [domain] by fetching robots.txt and
   * optionally the homepage for feed discovery.
   */
  suspend fun profile(domain: String): SiteProfile {
    log.d { "Profiling $domain" }
    val robotsTxtRules = fetchRobotsRules("https://$domain")
    val sitemaps = robotsTxtRules?.sitemaps.orEmpty().toMutableList()
    val crawlDelay = robotsTxtRules?.crawlDelay

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
      hasRobotsTxt = robotsTxtRules != null,
      sitemaps = sitemaps,
      rssFeeds = rssFeeds,
      crawlDelay = crawlDelay,
      lastProfiled = Instant.fromEpochMilliseconds(
        System.currentTimeMillis()
      ),
    )
  }

  /**
   * Checks if [urlPath] is allowed by the robots.txt in [profile].
   *
   * @return `true` if allowed or no robots.txt exists
   */
  fun isAllowed(
    urlPath: String,
    profile: SiteProfile,
  ): Boolean = isAllowed(urlPath, profile.robotsTxtRules)

  /**
   * Checks if [urlPath] (path plus query) is allowed by [rules].
   *
   * @return `true` if allowed or [rules] is `null` (no robots.txt)
   */
  internal fun isAllowed(urlPath: String, rules: RobotsTxtRules?): Boolean =
    rules == null || robotsTxtParser.isAllowed(urlPath, rules)

  /**
   * Fetches and parses `robots.txt` at [origin] (`scheme://host[:port]`).
   *
   * @return the rules for our user agent, or `null` when there is no
   *   readable robots.txt, which allows every path
   */
  internal suspend fun fetchRobotsRules(origin: String): RobotsTxtRules? {
    val url = "$origin/robots.txt"
    return when (val result = fetcher.fetch(url, MAX_ROBOTS_BYTES, truncate = true)) {
      is FetchResult.Success -> {
        // A longer file is parsed up to the limit (RFC 9309, section 2.5).
        // The line the cut went through is dropped: a partial Allow path
        // would allow more than the full rule does.
        val content = if (result.truncated) {
          result.content.substringBeforeLast('\n', missingDelimiterValue = "")
        } else {
          result.content
        }
        val rules = robotsTxtParser.parse(content, robotsUserAgent)
        log.d {
          "robots.txt for $origin: ${rules.rules.size} rules, " +
            "${rules.sitemaps.size} sitemaps, crawlDelay=${rules.crawlDelay}"
        }
        rules
      }
      is FetchResult.Failed -> {
        log.d { "No robots.txt for $origin: ${result.reason}" }
        null
      }
    }
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
    /** RFC 9309 requires parsing at least 500 KiB of robots.txt, and allows stopping there. */
    internal const val MAX_ROBOTS_BYTES = 500L * 1024

    private val FEED_LINK_PATTERN = Regex(
      "<link[^>]*type=\"application/" +
        "(?:rss|atom)\\+xml\"[^>]*href=\"([^\"]+)\"",
      RegexOption.IGNORE_CASE,
    )
  }
}
