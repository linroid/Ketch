package com.linroid.ketch.ai.site

import com.linroid.ketch.ai.fetch.FetchResult
import com.linroid.ketch.ai.fetch.SafeFetcher
import com.linroid.ketch.api.log.KetchLogger
import com.linroid.ketch.config.SiteNames

/**
 * Reads the robots.txt rules of the sites discovery fetches pages from.
 *
 * @param robotsUserAgent product token matched against robots.txt
 *   `User-agent` lines
 */
internal class SiteProfiler(
  private val fetcher: SafeFetcher,
  private val robotsTxtParser: RobotsTxtParser = RobotsTxtParser(),
  private val robotsUserAgent: String = RobotsTxtParser.DEFAULT_USER_AGENT,
) {

  private val log = KetchLogger("SiteProfiler")

  /**
   * Checks if [urlPath] (path plus query) is allowed by [rules].
   *
   * @return `true` if allowed or [rules] is `null` (no robots.txt)
   */
  fun isAllowed(urlPath: String, rules: RobotsTxtRules?): Boolean =
    rules == null || robotsTxtParser.isAllowed(urlPath, rules)

  /**
   * Fetches and parses `robots.txt` at [origin] (`scheme://host[:port]`).
   *
   * Redirects are followed within the origin's site only, its subdomains included: the user
   * allowed discovery to contact this site, not the one a redirect hands off to. A robots.txt
   * that redirects to another site counts as missing.
   *
   * @return the rules for our user agent, or `null` when there is no
   *   readable robots.txt, which allows every path
   */
  suspend fun fetchRobotsRules(origin: String): RobotsTxtRules? {
    val url = "$origin/robots.txt"
    val site = SiteNames.normalize(origin)
    val result = fetcher.fetch(
      url = url,
      maxBytes = MAX_ROBOTS_BYTES,
      truncate = true,
      checkHop = { hop -> if (SiteNames.covers(site, hop.host)) null else "on another site" },
    )
    return when (result) {
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

  companion object {
    /** RFC 9309 requires parsing at least 500 KiB of robots.txt, and allows stopping there. */
    internal const val MAX_ROBOTS_BYTES = 500L * 1024
  }
}
