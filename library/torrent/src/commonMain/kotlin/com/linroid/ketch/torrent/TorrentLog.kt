package com.linroid.ketch.torrent

import com.linroid.ketch.api.log.describeCauses
import io.ktor.http.Url

/**
 * Tracker URL reduced to its scheme, host and port for logs. Paths and queries often carry a
 * private tracker's passkey, so they are never logged.
 */
internal fun trackerLabel(url: String): String = try {
  val parsed = Url(url)
  "${parsed.protocol.name}://${parsed.host}:${parsed.port}"
} catch (_: Exception) {
  "<invalid tracker URL>"
}

/**
 * [describeCauses] with every embedded URL reduced to its [trackerLabel]. HTTP client errors
 * quote the request URL, which for trackers includes the passkey.
 */
internal fun Throwable.describeWithoutUrls(): String =
  EMBEDDED_URL.replace(describeCauses()) { trackerLabel(it.value) }

/** Info-hash prefix that keeps log lines short but still greppable. */
internal fun logHash(hex: String): String = hex.take(12)

internal fun TrackerTopic.logHash(): String = when (this) {
  is TrackerTopic.V1 -> logHash(hash.hex)
  is TrackerTopic.V2 -> logHash(hash.hex)
}

private val EMBEDDED_URL = Regex("""[A-Za-z][A-Za-z0-9+.-]*://[^\s,;"'()\[\]]+""")
