package com.linroid.ketch.ai.fetch

import java.net.InetAddress
import java.net.URI

/**
 * Validates URLs against SSRF attacks by blocking requests to
 * private/local IP ranges and non-HTTP(S) schemes.
 */
internal class UrlValidator {

  /**
   * Validates the given [url] for safety.
   *
   * Checks performed:
   * 1. URL must be well-formed
   * 2. Scheme must be `http` or `https`
   * 3. Hostname must not resolve to a private/local IP
   * 4. Hostname must not be an internal-looking name
   *
   * @return [ValidationResult.Valid] if the URL is safe to fetch,
   *   or [ValidationResult.Blocked] with a reason otherwise
   */
  fun validate(url: String): ValidationResult {
    val uri = try {
      URI(url)
    } catch (_: Exception) {
      return ValidationResult.Blocked("Malformed URL: $url")
    }

    val scheme = uri.scheme?.lowercase()
    if (scheme !in ALLOWED_SCHEMES) {
      return ValidationResult.Blocked(
        "Blocked scheme: $scheme (only http/https allowed)"
      )
    }

    val host = uri.host
      ?: return ValidationResult.Blocked("Missing host in URL")

    if (isInternalHostname(host)) {
      return ValidationResult.Blocked(
        "Blocked internal hostname: $host"
      )
    }

    val addresses = try {
      InetAddress.getAllByName(host)
    } catch (_: Exception) {
      return ValidationResult.Blocked(
        "DNS resolution failed for: $host"
      )
    }

    for (addr in addresses) {
      if (isBlockedAddress(addr)) {
        return ValidationResult.Blocked(
          "Blocked private/local IP: ${addr.hostAddress} (host: $host)"
        )
      }
    }

    return ValidationResult.Valid(uri)
  }

  private fun isInternalHostname(host: String): Boolean {
    val lower = host.lowercase()
    return !lower.contains('.') ||
      lower.endsWith(".local") ||
      lower.endsWith(".internal") ||
      lower.endsWith(".localhost") ||
      lower == "localhost"
  }

  private fun isBlockedAddress(addr: InetAddress): Boolean {
    return addr.isLoopbackAddress ||
      addr.isLinkLocalAddress ||
      addr.isSiteLocalAddress ||
      addr.isAnyLocalAddress ||
      isCarrierGradeNat(addr)
  }

  /**
   * Checks for 100.64.0.0/10 (Carrier-Grade NAT, RFC 6598).
   */
  private fun isCarrierGradeNat(addr: InetAddress): Boolean {
    val bytes = addr.address
    if (bytes.size != 4) return false
    val first = bytes[0].toInt() and 0xFF
    val second = bytes[1].toInt() and 0xFF
    // 100.64.0.0/10 = 100.64.0.0 - 100.127.255.255
    return first == 100 && second in 64..127
  }

  companion object {
    private val ALLOWED_SCHEMES = setOf("http", "https")
  }
}

/** Result of URL validation. */
internal sealed interface ValidationResult {
  /** The URL is safe to fetch. */
  data class Valid(val uri: URI) : ValidationResult

  /** The URL was blocked for the given [reason]. */
  data class Blocked(val reason: String) : ValidationResult
}
