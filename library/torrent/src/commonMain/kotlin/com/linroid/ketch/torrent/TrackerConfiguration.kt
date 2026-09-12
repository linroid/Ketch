package com.linroid.ketch.torrent

import io.ktor.http.Url

/** Validated replacement, captured before lifecycle operations suspend. */
internal class TrackerConfiguration private constructor(val tiers: List<List<String>>) {
  companion object {
    fun prepare(tiers: List<List<String>>): TrackerConfiguration {
      require(tiers.size <= 256 && tiers.sumOf { it.size.toLong() } <= 256) {
        "Tracker configuration exceeds limit"
      }
      val snapshot = tiers.map { tier ->
        tier.map { url ->
          require(url.length in 1..8192) { "Invalid tracker URL length" }
          val valid = try {
            val scheme = url.substringBefore("://", "").lowercase()
            val parsed = Url(url)
            scheme in setOf("http", "https", "udp") &&
              parsed.host.isNotEmpty() && parsed.port in 1..65535 &&
              (scheme != "udp" || (parsed.user == null && parsed.password == null))
          } catch (_: Exception) { false }
          require(valid) { "Invalid tracker URL" }
          url
        }.distinct()
      }.filter { it.isNotEmpty() }
      return TrackerConfiguration(snapshot)
    }
  }
}
