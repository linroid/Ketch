package com.linroid.ketch.ai.site

/**
 * Parses `robots.txt` content and checks URL access rules.
 */
class RobotsTxtParser internal constructor() {

  /**
   * Parses robots.txt [content] and returns the parsed result.
   *
   * Follows RFC 9309 groups: consecutive `User-agent` lines share one
   * group, and when any group names [userAgent] only those groups
   * apply; otherwise the `*` groups do.
   *
   * @param content raw robots.txt text
   * @param userAgent the user-agent to match rules for
   */
  fun parse(
    content: String,
    userAgent: String = DEFAULT_USER_AGENT,
  ): RobotsTxtRules {
    val sitemaps = mutableListOf<String>()
    val specific = GroupRules()
    val wildcard = GroupRules()
    var groupIsSpecific = false
    var groupIsWildcard = false
    var previousWasUserAgent = false

    for (rawLine in content.lineSequence()) {
      val line = rawLine.substringBefore('#').trim()
      if (line.isEmpty()) continue

      val colonIdx = line.indexOf(':')
      if (colonIdx < 0) continue

      val directive = line.substring(0, colonIdx).trim().lowercase()
      val value = line.substring(colonIdx + 1).trim()

      if (directive == "user-agent") {
        if (!previousWasUserAgent) {
          // A user-agent line after rules starts a new group.
          groupIsSpecific = false
          groupIsWildcard = false
        }
        if (value.equals(userAgent, ignoreCase = true)) {
          groupIsSpecific = true
          specific.matched = true
        } else if (value == "*") {
          groupIsWildcard = true
        }
        previousWasUserAgent = true
        continue
      }
      // Sitemaps belong to no group, so they don't end a user-agent run.
      if (directive == "sitemap") {
        if (value.isNotEmpty()) sitemaps.add(value)
        continue
      }
      previousWasUserAgent = false

      val targets = listOfNotNull(
        specific.takeIf { groupIsSpecific },
        wildcard.takeIf { groupIsWildcard },
      )
      when (directive) {
        "disallow", "allow" -> {
          if (value.isNotEmpty()) {
            val rule = RobotRule(path = value, allowed = directive == "allow")
            targets.forEach { it.rules.add(rule) }
          }
        }
        "crawl-delay" -> {
          targets.forEach { it.crawlDelay = value.toIntOrNull() }
        }
      }
    }

    val applied = if (specific.matched) specific else wildcard
    return RobotsTxtRules(
      rules = applied.rules,
      sitemaps = sitemaps,
      crawlDelay = applied.crawlDelay,
    )
  }

  /**
   * Checks if a URL [path] is allowed based on [rules].
   *
   * Uses longest-match semantics: more specific rules take
   * precedence, and an allow rule wins a tie with a disallow rule of
   * the same length.
   */
  fun isAllowed(path: String, rules: RobotsTxtRules): Boolean {
    if (rules.rules.isEmpty()) return true

    var bestMatch: RobotRule? = null
    var bestLength = -1

    for (rule in rules.rules) {
      if (!path.startsWith(rule.path)) continue
      val length = rule.path.length
      if (length > bestLength || (length == bestLength && rule.allowed)) {
        bestMatch = rule
        bestLength = length
      }
    }

    return bestMatch?.allowed ?: true
  }

  private class GroupRules {
    var matched = false
    val rules = mutableListOf<RobotRule>()
    var crawlDelay: Int? = null
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
