package com.linroid.ketch.ai.agent

import com.linroid.ketch.config.SiteNames
import java.net.URI

/**
 * The websites a discovery run is limited to.
 *
 * Each domain allows itself and its subdomains, so `ubuntu.com` covers
 * `releases.ubuntu.com` but not `notubuntu.com`. Without domains the
 * allowlist is unrestricted and any public host is allowed; the SSRF
 * checks in `UrlValidator` apply either way.
 *
 * The allowlist limits the URLs the agent asks for and the candidates it
 * returns, not the redirects a server answers with: a listed site that
 * hands a download off to its CDN is still followed.
 */
internal class SiteAllowlist private constructor(
  /** Normalized domains; empty when unrestricted. */
  val domains: List<String>,
) {

  /** Whether the run is limited to [domains]. */
  val isRestricted: Boolean get() = domains.isNotEmpty()

  /** Whether [host] is one of [domains] or a subdomain of one. */
  fun allowsHost(host: String): Boolean =
    !isRestricted || domains.any { SiteNames.covers(it, host) }

  /**
   * Whether the host of [url] is allowed. While restricted, a URL
   * without a parsable host is not.
   */
  fun allows(url: String): Boolean {
    if (!isRestricted) return true
    val host = try {
      URI(url).host
    } catch (_: Exception) {
      null
    }
    return host != null && allowsHost(host)
  }

  override fun toString(): String = if (isRestricted) domains.joinToString() else "any site"

  companion object {
    /** Allows every public host. */
    val Unrestricted: SiteAllowlist = SiteAllowlist(emptyList())

    /**
     * Builds an allowlist from user-typed [sites], each reduced to a bare domain by
     * [SiteNames.normalize].
     *
     * @throws IllegalArgumentException if [sites] has non-blank entries
     *   but none of them names a domain
     */
    fun of(sites: List<String>): SiteAllowlist {
      val entries = sites.filter { it.isNotBlank() }
      val domains = entries.map(SiteNames::normalize).filter { it.isNotEmpty() }.distinct()
      require(entries.isEmpty() || domains.isNotEmpty()) {
        "No website domain in: ${entries.joinToString()}"
      }
      return SiteAllowlist(domains)
    }

    /**
     * The allowlist for one run: the [requested] sites (from the query)
     * narrowed to the [configured] ones (from the engine config). Either
     * list alone applies as is; with both empty the run is unrestricted.
     *
     * @throws IllegalArgumentException if a list has entries but no
     *   domain, or if both are set and do not overlap
     */
    fun forRun(configured: List<String>, requested: List<String>): SiteAllowlist {
      val cap = of(configured)
      val wanted = of(requested)
      if (!cap.isRestricted) return wanted
      if (!wanted.isRestricted) return cap
      val overlap = wanted.domains.flatMap { site ->
        if (cap.allowsHost(site)) {
          listOf(site)
        } else {
          // Configured domains narrower than the requested site.
          cap.domains.filter { it.endsWith(".$site") }
        }
      }.distinct()
      require(overlap.isNotEmpty()) {
        "None of the requested websites ($wanted) are within the allowed domains ($cap)"
      }
      return SiteAllowlist(overlap)
    }
  }
}
