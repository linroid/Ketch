package com.linroid.ketch.ai.fetch

import com.linroid.ketch.api.log.KetchLogger
import com.linroid.ketch.api.log.describeCauses
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.InetAddress
import java.net.URI
import java.net.UnknownHostException

/**
 * Validates URLs against SSRF attacks by blocking requests to
 * private/local IP ranges and non-HTTP(S) schemes.
 *
 * [validate] checks a URL before it is requested, and
 * [resolvePublicAddresses] applies the same host check again when the
 * HTTP client connects (see [ValidatingDns]). Both look the host up with
 * [resolve], so a name that answers the first lookup with a public
 * address and the second with a private one is still refused.
 *
 * @param resolve looks up every address of a host name; it blocks, so
 *   [validate] calls it on [Dispatchers.IO]
 */
internal class UrlValidator(
  private val resolve: (String) -> Array<InetAddress> = InetAddress::getAllByName,
) {

  private val log = KetchLogger("UrlValidator")

  /**
   * Validates the given [url] for safety.
   *
   * Checks performed:
   * 1. URL must be well-formed
   * 2. Scheme must be `http` or `https`
   * 3. Hostname must not resolve to a private/local IP
   * 4. Hostname must not be an internal-looking name
   *
   * The host is looked up on [Dispatchers.IO], so this is safe to call
   * from a UI thread; Android refuses lookups on its main thread.
   *
   * @return [ValidationResult.Valid] if the URL is safe to fetch,
   *   or [ValidationResult.Blocked] with a reason otherwise
   */
  suspend fun validate(url: String): ValidationResult {
    val uri = try {
      URI(url)
    } catch (_: Exception) {
      // The URL is left out: the reason is logged, and URLs can carry credentials.
      return ValidationResult.Blocked("Malformed URL")
    }

    val scheme = uri.scheme?.lowercase()
    if (scheme !in ALLOWED_SCHEMES) {
      return ValidationResult.Blocked(
        "Blocked scheme: $scheme (only http/https allowed)"
      )
    }

    val host = uri.host
      ?: return ValidationResult.Blocked("Missing host in URL")

    try {
      withContext(Dispatchers.IO) { resolvePublicAddresses(host) }
    } catch (e: BlockedHostException) {
      return ValidationResult.Blocked(e.reason)
    }
    return ValidationResult.Valid(uri)
  }

  /**
   * Resolves [host] and returns its addresses, provided the name does
   * not look internal and none of the addresses is private or local.
   * It blocks while the host is looked up.
   *
   * @throws BlockedHostException if the host fails either check or
   *   cannot be resolved
   */
  fun resolvePublicAddresses(host: String): List<InetAddress> {
    if (isInternalHostname(host)) {
      throw BlockedHostException("Blocked internal hostname: $host")
    }

    val addresses = try {
      resolve(host)
    } catch (e: Exception) {
      log.d { "Lookup failed for $host: ${e.describeCauses()}" }
      throw BlockedHostException("DNS resolution failed for: $host", e)
    }

    for (addr in addresses) {
      if (isBlockedAddress(addr)) {
        throw BlockedHostException(
          "Blocked private/local IP: ${addr.hostAddress} (host: $host)"
        )
      }
    }
    return addresses.toList()
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

/**
 * Thrown when a host is refused for the given [reason]. It is an
 * [UnknownHostException] so that an HTTP client treats a refused lookup
 * like one that found no address and does not connect.
 */
internal class BlockedHostException(
  val reason: String,
  cause: Throwable? = null,
) : UnknownHostException(reason) {
  init {
    if (cause != null) initCause(cause)
  }
}

/** Result of URL validation. */
internal sealed interface ValidationResult {
  /** The URL is safe to fetch. */
  data class Valid(val uri: URI) : ValidationResult

  /** The URL was blocked for the given [reason]. */
  data class Blocked(val reason: String) : ValidationResult
}
