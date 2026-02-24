package com.linroid.ketch.ai.site

import kotlin.time.Instant

/**
 * Profile information for a site, including robots.txt rules,
 * discovered sitemaps, and RSS/Atom feeds.
 */
data class SiteProfile(
  val domain: String,
  val robotsTxtRules: RobotsTxtRules?,
  val hasRobotsTxt: Boolean,
  val sitemaps: List<String>,
  val rssFeeds: List<String>,
  val crawlDelay: Int?,
  val lastProfiled: Instant,
)
