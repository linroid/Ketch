package com.linroid.ketch.ai.site

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RobotsTxtParserTest {

  private val parser = RobotsTxtParser()

  @Test
  fun parse_emptyContent_returnsEmptyRules() {
    val rules = parser.parse("")
    assertTrue(rules.rules.isEmpty())
    assertTrue(rules.sitemaps.isEmpty())
    assertNull(rules.crawlDelay)
  }

  @Test
  fun parse_wildcardUserAgent_matchesRules() {
    val content = """
      User-agent: *
      Disallow: /private/
      Disallow: /admin/
      Allow: /public/
    """.trimIndent()

    val rules = parser.parse(content)
    assertEquals(3, rules.rules.size)
    assertEquals("/private/", rules.rules[0].path)
    assertFalse(rules.rules[0].allowed)
    assertEquals("/admin/", rules.rules[1].path)
    assertFalse(rules.rules[1].allowed)
    assertEquals("/public/", rules.rules[2].path)
    assertTrue(rules.rules[2].allowed)
  }

  @Test
  fun parse_specificUserAgent_matchesOurBot() {
    val content = """
      User-agent: KetchBot
      Disallow: /ketch-blocked/

      User-agent: Googlebot
      Disallow: /google-blocked/
    """.trimIndent()

    val rules = parser.parse(content, userAgent = "KetchBot")
    assertEquals(1, rules.rules.size)
    assertEquals("/ketch-blocked/", rules.rules[0].path)
  }

  @Test
  fun parse_extractsSitemaps() {
    val content = """
      User-agent: *
      Disallow:

      Sitemap: https://example.com/sitemap.xml
      Sitemap: https://example.com/sitemap2.xml
    """.trimIndent()

    val rules = parser.parse(content)
    assertEquals(2, rules.sitemaps.size)
    assertEquals(
      "https://example.com/sitemap.xml",
      rules.sitemaps[0],
    )
    assertEquals(
      "https://example.com/sitemap2.xml",
      rules.sitemaps[1],
    )
  }

  @Test
  fun parse_extractsCrawlDelay() {
    val content = """
      User-agent: *
      Crawl-delay: 10
      Disallow: /slow/
    """.trimIndent()

    val rules = parser.parse(content)
    assertEquals(10, rules.crawlDelay)
  }

  @Test
  fun parse_ignoresComments() {
    val content = """
      # This is a comment
      User-agent: * # inline comment
      Disallow: /blocked/ # reason
    """.trimIndent()

    val rules = parser.parse(content)
    assertEquals(1, rules.rules.size)
    assertEquals("/blocked/", rules.rules[0].path)
  }

  @Test
  fun parse_emptyDisallow_isIgnored() {
    val content = """
      User-agent: *
      Disallow:
    """.trimIndent()

    val rules = parser.parse(content)
    assertTrue(rules.rules.isEmpty())
  }

  // -- isAllowed tests --

  @Test
  fun isAllowed_noRules_returnsTrue() {
    val rules = RobotsTxtRules(
      rules = emptyList(),
      sitemaps = emptyList(),
      crawlDelay = null,
    )
    assertTrue(parser.isAllowed("/anything", rules))
  }

  @Test
  fun isAllowed_disallowedPath_returnsFalse() {
    val rules = RobotsTxtRules(
      rules = listOf(
        RobotRule(path = "/admin/", allowed = false),
      ),
      sitemaps = emptyList(),
      crawlDelay = null,
    )
    assertFalse(parser.isAllowed("/admin/settings", rules))
  }

  @Test
  fun isAllowed_allowedPath_returnsTrue() {
    val rules = RobotsTxtRules(
      rules = listOf(
        RobotRule(path = "/admin/", allowed = false),
      ),
      sitemaps = emptyList(),
      crawlDelay = null,
    )
    assertTrue(parser.isAllowed("/public/page", rules))
  }

  @Test
  fun isAllowed_longerMatchWins() {
    val rules = RobotsTxtRules(
      rules = listOf(
        RobotRule(path = "/admin/", allowed = false),
        RobotRule(path = "/admin/public/", allowed = true),
      ),
      sitemaps = emptyList(),
      crawlDelay = null,
    )
    // More specific allow wins
    assertTrue(
      parser.isAllowed("/admin/public/page", rules),
    )
    // Less specific disallow applies
    assertFalse(
      parser.isAllowed("/admin/secret", rules),
    )
  }
}
