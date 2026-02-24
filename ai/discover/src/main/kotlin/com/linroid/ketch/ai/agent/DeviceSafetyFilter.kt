package com.linroid.ketch.ai.agent

import java.net.URI

/**
 * Evaluates download URLs for device safety, scoring them based on
 * heuristics like HTTPS usage, domain trust, piracy signals, and
 * content-type consistency.
 */
internal class DeviceSafetyFilter {

  /**
   * Evaluates the safety of a download URL.
   *
   * @param url the download URL
   * @param sourcePageUrl the page where the link was found
   * @param contentType Content-Type header value, if known
   * @param extension file extension from the URL
   * @param context surrounding text or description for piracy signal
   *   detection
   * @return safety evaluation result
   */
  fun evaluate(
    url: String,
    sourcePageUrl: String = "",
    contentType: String? = null,
    extension: String = "",
    context: String = "",
  ): SafetyEvaluation {
    val host = hostOf(url)
    val lowerUrl = url.lowercase()
    val lowerContext = context.lowercase()
    val ext = extension.lowercase().ifEmpty {
      extensionOf(lowerUrl)
    }

    // Hard-block checks
    if (isUrlShortener(host)) {
      return SafetyEvaluation(
        score = 0f,
        blocked = true,
        reason = "URL shortener: $host",
      )
    }
    if (isAggregatorDomain(host)) {
      return SafetyEvaluation(
        score = 0f,
        blocked = true,
        reason = "Free-download aggregator: $host",
      )
    }
    if (hasPiracySignal(lowerUrl) || hasPiracySignal(lowerContext)) {
      return SafetyEvaluation(
        score = 0f,
        blocked = true,
        reason = "Piracy signal detected",
      )
    }
    if (isHighRiskExtension(ext) && !isTrustedDomain(host)) {
      return SafetyEvaluation(
        score = 0f,
        blocked = true,
        reason = "High-risk extension .$ext from untrusted source",
      )
    }

    // Scoring
    var score = BASE_SCORE
    val notes = mutableListOf<String>()

    if (lowerUrl.startsWith("https://")) {
      score += 0.1f
      notes.add("HTTPS")
    } else if (lowerUrl.startsWith("http://")) {
      score -= 0.2f
      notes.add("HTTP (no TLS)")
    }

    if (isTrustedDomain(host)) {
      score += 0.2f
      notes.add("Trusted domain: $host")
    }

    if (contentType != null && ext.isNotEmpty()) {
      if (isContentTypeMismatch(contentType, ext)) {
        score -= 0.3f
        notes.add("Content-type mismatch: $contentType vs .$ext")
      }
    }

    if (hasChecksumMention(lowerContext)) {
      score += 0.15f
      notes.add("Checksums available")
    }

    val finalScore = score.coerceIn(0f, 1f)
    return SafetyEvaluation(
      score = finalScore,
      blocked = finalScore < BLOCK_THRESHOLD,
      reason = notes.joinToString("; "),
    )
  }

  private fun hostOf(url: String): String {
    return try {
      URI(url).host?.lowercase() ?: ""
    } catch (_: Exception) {
      ""
    }
  }

  private fun extensionOf(lowerUrl: String): String {
    val path = try {
      URI(lowerUrl).path ?: ""
    } catch (_: Exception) {
      ""
    }
    val dot = path.lastIndexOf('.')
    return if (dot >= 0) path.substring(dot + 1) else ""
  }

  private fun isUrlShortener(host: String): Boolean {
    return URL_SHORTENERS.any { host == it || host.endsWith(".$it") }
  }

  private fun isAggregatorDomain(host: String): Boolean {
    return AGGREGATOR_PATTERNS.any { host.contains(it) }
  }

  private fun isTrustedDomain(host: String): Boolean {
    return TRUSTED_DOMAINS.any { host == it || host.endsWith(".$it") }
  }

  private fun hasPiracySignal(text: String): Boolean {
    return PIRACY_SIGNALS.any { text.contains(it) }
  }

  private fun isHighRiskExtension(ext: String): Boolean {
    return ext in HIGH_RISK_EXTENSIONS
  }

  private fun isContentTypeMismatch(
    contentType: String,
    ext: String,
  ): Boolean {
    val ct = contentType.lowercase()
    return when {
      ext == "pdf" && "pdf" !in ct && "octet" !in ct -> true
      ext == "zip" && "zip" !in ct && "octet" !in ct -> true
      ext == "iso" && "iso" !in ct && "octet" !in ct -> true
      ext in HIGH_RISK_EXTENSIONS &&
        ("text/html" in ct || "text/plain" in ct) -> true
      else -> false
    }
  }

  private fun hasChecksumMention(text: String): Boolean {
    return CHECKSUM_KEYWORDS.any { text.contains(it) }
  }

  companion object {
    private const val BASE_SCORE = 0.7f
    private const val BLOCK_THRESHOLD = 0.3f

    private val URL_SHORTENERS = setOf(
      "bit.ly", "t.co", "tinyurl.com", "goo.gl", "ow.ly",
      "is.gd", "buff.ly", "adf.ly", "bl.ink", "soo.gd",
    )

    private val AGGREGATOR_PATTERNS = setOf(
      "freedownload", "softonic", "filehippo", "download.cnet",
      "freeware", "crackwatch",
    )

    private val TRUSTED_DOMAINS = setOf(
      "github.com", "gitlab.com", "sourceforge.net",
      "releases.ubuntu.com", "download.mozilla.org",
      "dl.google.com", "download.oracle.com",
      "downloads.apache.org", "mirror.apache.org",
      "repo.maven.apache.org", "cdn.kernel.org",
      "pypi.org", "npmjs.com", "crates.io",
      "rubygems.org", "nuget.org",
    )

    private val PIRACY_SIGNALS = setOf(
      "crack", "keygen", "patch", "loader", "warez",
      "torrent", "nulled", "pirate",
    )

    private val HIGH_RISK_EXTENSIONS = setOf(
      "exe", "msi", "dmg", "pkg", "apk",
    )

    private val CHECKSUM_KEYWORDS = setOf(
      "sha256", "sha512", "md5", "checksum", "signature",
      "gpg", "pgp", "hash",
    )
  }
}

/**
 * Result of a device safety evaluation.
 *
 * @param score safety score between 0.0 and 1.0
 * @param blocked whether the URL should be blocked
 * @param reason human-readable explanation
 */
internal data class SafetyEvaluation(
  val score: Float,
  val blocked: Boolean,
  val reason: String,
)
