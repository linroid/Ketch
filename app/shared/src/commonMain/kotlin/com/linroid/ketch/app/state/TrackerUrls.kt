package com.linroid.ketch.app.state

/** Extra trackers the torrent engine uses at most; later ones are ignored. */
const val MAX_EXTRA_TRACKERS = 64

private val TRACKER_SCHEMES = setOf("http", "https", "udp")
private val WHITESPACE = Regex("\\s+")

/** A typed tracker URL that could not be added, and why. */
data class RejectedTracker(val url: String, val reason: String)

/**
 * Outcome of [addTrackers].
 *
 * @property trackers the list with the accepted URLs appended.
 * @property rejected typed URLs that are not tracker URLs, in typed order.
 */
data class TrackerAddition(
  val trackers: List<String>,
  val rejected: List<RejectedTracker>,
)

/** Tracker URLs in [text], separated by whitespace or new lines, without repeats. */
fun parseTrackers(text: String): List<String> =
  text.split(WHITESPACE).filter { it.isNotEmpty() }.distinct()

/**
 * Appends the tracker URLs typed or pasted in [text] to [current],
 * skipping ones already there and rejecting ones that aren't tracker
 * URLs; see [trackerUrlError].
 */
fun addTrackers(current: List<String>, text: String): TrackerAddition {
  val typed = parseTrackers(text)
  val rejected = typed.mapNotNull { url -> trackerUrlError(url)?.let { RejectedTracker(url, it) } }
  val accepted = typed.filter { trackerUrlError(it) == null && it !in current }
  return TrackerAddition(current + accepted, rejected)
}

/**
 * Why [url] is not an announce URL the torrent engine accepts, or `null`
 * when it is: `http`, `https` or `udp` with a host and a valid port.
 * `udp` needs an explicit port and cannot carry a user name or password.
 */
fun trackerUrlError(url: String): String? {
  if (url.length > 8192) return "Too long."
  if (url.any { it.isWhitespace() }) return "Contains spaces."
  val parts = splitTrackerUrl(url) ?: return "Start it with http://, https:// or udp://."
  if (parts.host.isEmpty()) return "Missing the host name."
  if (parts.scheme == "udp" && parts.hasUserInfo) {
    return "UDP trackers can't include a user name or password."
  }
  val port = parts.port ?: return if (parts.scheme == "udp") "UDP trackers need a port." else null
  val valid = port.isNotEmpty() && port.all { it in '0'..'9' } && port.toIntOrNull() in 1..65535
  return if (valid) null else "Use a port from 1 to 65535."
}

/** Host of the tracker [url], or [url] itself when it has none. */
fun trackerHost(url: String): String =
  splitTrackerUrl(url)?.host?.takeIf { it.isNotEmpty() } ?: url

private class TrackerUrlParts(
  val scheme: String,
  val hasUserInfo: Boolean,
  val host: String,
  // Text after the host's colon; `null` when there is no colon.
  val port: String?,
)

/** Splits a tracker URL with an accepted scheme, or returns `null`. */
private fun splitTrackerUrl(url: String): TrackerUrlParts? {
  val scheme = url.substringBefore("://", "").lowercase()
  if (scheme !in TRACKER_SCHEMES) return null
  val authority = url.substringAfter("://").takeWhile { it != '/' && it != '?' && it != '#' }
  val hostPort = authority.substringAfterLast('@')
  val hasUserInfo = '@' in authority
  if (hostPort.startsWith('[')) {
    // Bracketed IPv6 literal, e.g. [2001:db8::1]:6969.
    val end = hostPort.indexOf(']')
    val rest = if (end < 0) "" else hostPort.substring(end + 1)
    if (end < 0 || (rest.isNotEmpty() && !rest.startsWith(':'))) {
      return TrackerUrlParts(scheme, hasUserInfo, host = "", port = null)
    }
    val port = rest.takeIf { it.isNotEmpty() }?.substring(1)
    return TrackerUrlParts(scheme, hasUserInfo, hostPort.substring(1, end), port)
  }
  val port = if (':' in hostPort) hostPort.substringAfter(':') else null
  return TrackerUrlParts(scheme, hasUserInfo, hostPort.substringBefore(':'), port)
}
