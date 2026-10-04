package com.linroid.ketch.config

import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder

/**
 * Whether AI discovery asks before it opens a website: fetching a page or checking a file's
 * details with a HEAD request. Searching through the search provider never asks.
 *
 * Saved as [id]. An id this version does not know loads as [AskPerSite] rather than failing the
 * whole config file.
 *
 * @property id value stored in `config.toml`.
 */
@Serializable(with = PageAccessModeSerializer::class)
enum class PageAccessMode(val id: String) {
  /** Opens websites without asking. */
  Allow(id = "allow"),

  /** Asks the first time a search opens each website; an allowed site stays allowed. */
  AskPerSite(id = "ask-site"),

  /** Asks before every page and file check. */
  AskEveryTime(id = "ask"),
}

internal object PageAccessModeSerializer : KSerializer<PageAccessMode> {
  override val descriptor: SerialDescriptor =
    PrimitiveSerialDescriptor("com.linroid.ketch.config.PageAccessMode", PrimitiveKind.STRING)

  override fun serialize(encoder: Encoder, value: PageAccessMode) {
    encoder.encodeString(value.id)
  }

  override fun deserialize(decoder: Decoder): PageAccessMode {
    val id = decoder.decodeString()
    return PageAccessMode.entries.firstOrNull { it.id == id } ?: PageAccessMode.AskPerSite
  }
}

/**
 * When AI discovery may open websites, persisted under `[ai.access]`.
 *
 * @property mode whether to ask before opening a website.
 * @property trustedSites sites opened without asking whatever the [mode], each covering its
 *   subdomains. The app stores them as bare domains; entries are matched as
 *   [SiteNames.normalize] leaves them, so hand-written `Blender.org`, `www.blender.org` or
 *   `https://blender.org/` work too.
 */
@Serializable
data class PageAccessSettings(
  val mode: PageAccessMode = PageAccessMode.AskPerSite,
  val trustedSites: List<String> = emptyList(),
) {
  /** Whether [host] is one of [trustedSites] or a subdomain of one. */
  fun trusts(host: String): Boolean = trustedDomains().any { SiteNames.covers(it, host) }

  /**
   * Whether discovery may open [host] without asking: the [mode] allows every site, or
   * [trustedSites] or [allowedSites] cover it.
   *
   * @param allowedSites sites the user allowed for the current chat or run, as
   *   [SiteNames.normalize] leaves them.
   */
  fun allowsWithoutAsking(host: String, allowedSites: Collection<String> = emptyList()): Boolean =
    mode == PageAccessMode.Allow || trusts(host) || allowedSites.any { SiteNames.covers(it, host) }

  /** These settings with [site] trusted, normalized and listed once. */
  fun trusting(site: String): PageAccessSettings {
    val normalized = SiteNames.normalize(site)
    if (normalized.isEmpty() || normalized in trustedDomains()) return this
    return copy(trustedSites = trustedSites + normalized)
  }

  /** These settings without [site] among the [trustedSites], however either is written. */
  fun distrusting(site: String): PageAccessSettings {
    val normalized = SiteNames.normalize(site)
    return copy(
      trustedSites = trustedSites.filterNot { it == site || SiteNames.normalize(it) == normalized },
    )
  }

  // The trusted sites as SiteNames.normalize leaves them, blanks dropped; a function, as a
  // property would be saved with the settings.
  private fun trustedDomains(): List<String> =
    trustedSites.map(SiteNames::normalize).filter { it.isNotEmpty() }
}

/** How AI discovery reads and matches website names. */
object SiteNames {
  /**
   * Reduces a user-typed site or a URL's host to a bare lowercase domain, dropping the scheme,
   * credentials, port, path, a `*.` wildcard and a leading `www.`; blank when nothing is left.
   *
   * `www.` stays when dropping it would leave a domain that unrelated sites share (see
   * [isPublicSuffix]): `www.github.io` names one site, `github.io` every GitHub Pages site, and
   * an answer about the one must never cover the others.
   */
  fun normalize(site: String): String {
    val authority = site.trim().lowercase()
      .substringAfter("://")
      .substringBefore('/').substringBefore('?').substringBefore('#')
      .substringAfterLast('@')
    // An IPv6 literal keeps its brackets, as a URL's host has them.
    if (authority.startsWith('[')) {
      return authority.substringBefore(']').let { if (it.length > 1) "$it]" else "" }
    }
    val host = authority
      .substringBefore(':')
      .removePrefix("*.")
      .trim('.')
    val bare = host.removePrefix("www.")
    return if (bare == host || isPublicSuffix(bare)) host else bare
  }

  /**
   * Whether [domain], lowercase, is a suffix unrelated sites register their names under rather
   * than a site of its own: a top-level domain such as `com`, a country's second level such as
   * `co.uk` or `com.au`, or a domain a hosting service gives its users' sites, such as
   * `github.io`. A short list of the common ones, not the whole Public Suffix List.
   */
  internal fun isPublicSuffix(domain: String): Boolean {
    // IP literals, IPv6 in brackets or IPv4 in digits and dots, are addresses, not suffixes.
    if (domain.startsWith('[') || domain.all { it.isDigit() || it == '.' }) return false
    val labels = domain.split('.')
    return labels.size < 2 ||
      domain in SharedHostSuffixes ||
      (labels.size == 2 && labels[1].length == 2 && labels[0] in CountrySecondLevels)
  }

  /**
   * Whether [site], a domain as [normalize] leaves it, covers [host]: the same domain or a
   * subdomain of it, so `ubuntu.com` covers `releases.ubuntu.com` but not `notubuntu.com`.
   */
  fun covers(site: String, host: String): Boolean {
    if (site.isEmpty()) return false
    val normalized = host.lowercase().trimEnd('.').let {
      // java.net.URI reports IPv6 hosts with brackets; other parsers drop them.
      if (':' in it && !it.startsWith('[')) "[$it]" else it
    }
    return normalized == site || normalized.endsWith(".$site")
  }

  /**
   * [url] in the form discovery compares links by: the scheme and host lowercased, a default
   * port (80 for http, 443 for https) and the fragment dropped, the path and query kept as they
   * are. Text that is not an absolute URL comes back trimmed.
   */
  fun canonicalUrl(url: String): String {
    val trimmed = url.trim()
    val schemeEnd = trimmed.indexOf("://")
    if (schemeEnd <= 0) return trimmed
    val scheme = trimmed.substring(0, schemeEnd).lowercase()
    val rest = trimmed.substring(schemeEnd + 3).substringBefore('#')
    val authorityEnd = rest.indexOfAny(charArrayOf('/', '?')).takeIf { it >= 0 } ?: rest.length
    val authority = rest.substring(0, authorityEnd)
    val tail = rest.substring(authorityEnd)
    val userInfo = authority.substringBeforeLast('@', missingDelimiterValue = "")
    var hostPort = authority.substringAfterLast('@').lowercase()
    val defaultPort = when (scheme) {
      "http" -> ":80"
      "https" -> ":443"
      else -> null
    }
    if (defaultPort != null && hostPort.endsWith(defaultPort)) {
      hostPort = hostPort.removeSuffix(defaultPort)
    }
    val credentials = if (userInfo.isEmpty()) "" else "$userInfo@"
    return "$scheme://$credentials$hostPort$tail"
  }

  /** Second-level labels many countries register names under, as in `co.uk` or `com.au`. */
  private val CountrySecondLevels = setOf(
    "ac", "co", "com", "edu", "gob", "gov", "ltd", "mil", "ne", "net", "nom", "or", "org",
    "plc", "sch"
  )

  /** Domains hosting services give their users' sites, from the Public Suffix List. */
  private val SharedHostSuffixes = setOf(
    "appspot.com", "azurestaticapps.net", "azurewebsites.net", "bitbucket.io", "blogspot.com",
    "cloudfront.net", "codeberg.page", "duckdns.org", "firebaseapp.com", "fly.dev",
    "github.io", "githubusercontent.com", "gitlab.io", "glitch.me", "herokuapp.com",
    "neocities.org", "netlify.app", "ngrok-free.app", "ngrok.io", "onrender.com", "pages.dev",
    "r2.dev", "readthedocs.io", "s3.amazonaws.com", "surge.sh", "vercel.app", "web.app",
    "workers.dev"
  )
}
