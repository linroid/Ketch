package com.linroid.ketch.ai.site

/**
 * Parses `robots.txt` content and checks URL access rules.
 */
class RobotsTxtParser internal constructor() {

  /**
   * Parses robots.txt [content] and returns the parsed result.
   *
   * @param content raw robots.txt text
   * @param userAgent the user-agent to match rules for
   */
  fun parse(
    content: String,
    userAgent: String = DEFAULT_USER_AGENT,
  ): RobotsTxtRules {
    val sitemaps = mutableListOf<String>()
    val rules = mutableListOf<RobotRule>()
    var crawlDelay: Int? = null
    var inMatchingGroup = false
    var inAnyGroup = false

    for (rawLine in content.lineSequence()) {
      val line = rawLine.substringBefore('#').trim()
      if (line.isEmpty()) continue

      val colonIdx = line.indexOf(':')
      if (colonIdx < 0) continue

      val directive = line.substring(0, colonIdx).trim().lowercase()
      val value = line.substring(colonIdx + 1).trim()

      when (directive) {
        "sitemap" -> {
          if (value.isNotEmpty()) sitemaps.add(value)
        }
        "user-agent" -> {
          val matches = value == "*" ||
            value.equals(userAgent, ignoreCase = true)
          if (matches) {
            inMatchingGroup = true
            inAnyGroup = true
          } else if (inAnyGroup) {
            // Starting a new non-matching group
            inMatchingGroup = false
            inAnyGroup = false
          }
        }
        "disallow" -> {
          if (inMatchingGroup && value.isNotEmpty()) {
            rules.add(RobotRule(path = value, allowed = false))
          }
        }
        "allow" -> {
          if (inMatchingGroup && value.isNotEmpty()) {
            rules.add(RobotRule(path = value, allowed = true))
          }
        }
        "crawl-delay" -> {
          if (inMatchingGroup) {
            crawlDelay = value.toIntOrNull()
          }
        }
      }
    }

    return RobotsTxtRules(
      rules = rules,
      sitemaps = sitemaps,
      crawlDelay = crawlDelay,
    )
  }

  /**
   * Checks if a URL [path] is allowed based on [rules].
   *
   * Uses longest-match semantics: more specific rules take
   * precedence.
   */
  fun isAllowed(path: String, rules: RobotsTxtRules): Boolean {
    if (rules.rules.isEmpty()) return true

    var bestMatch: RobotRule? = null
    var bestLength = -1

    for (rule in rules.rules) {
      if (path.startsWith(rule.path) &&
        rule.path.length > bestLength
      ) {
        bestMatch = rule
        bestLength = rule.path.length
      }
    }

    return bestMatch?.allowed ?: true
  }

  companion object {
    const val DEFAULT_USER_AGENT = "KetchBot"
  }
}

/**
 * Parsed robots.txt rules for a specific user-agent.
 */
data class RobotsTxtRules(
  val rules: List<RobotRule>,
  val sitemaps: List<String>,
  val crawlDelay: Int?,
)

/**
 * A single allow/disallow rule from robots.txt.
 */
data class RobotRule(
  val path: String,
  val allowed: Boolean,
)
