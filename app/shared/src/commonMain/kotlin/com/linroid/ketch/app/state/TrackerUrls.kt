package com.linroid.ketch.app.state

/** Extra trackers the torrent engine uses at most; later ones are ignored. */
const val MAX_EXTRA_TRACKERS = 64

private val TRACKER_SCHEMES = setOf("http", "https", "udp")

/** Tracker URLs in [text], one per line: trimmed, without blank lines or repeats. */
fun parseTrackers(text: String): List<String> =
  text.lines().map { it.trim() }.filter { it.isNotEmpty() }.distinct()

/**
 * Why the lines of [text] are not all tracker URLs, or `null` when they
 * are; see [isTrackerUrl].
 */
fun trackersError(text: String): String? {
  val invalid = parseTrackers(text).filterNot(::isTrackerUrl)
  return when (invalid.size) {
    0 -> null
    1 -> "Not a tracker URL: ${invalid.single()}"
    else -> "${invalid.size} lines aren't tracker URLs, including ${invalid.first()}"
  }
}

/**
 * Whether [url] is an announce URL the torrent engine accepts: `http`,
 * `https` or `udp` with a host, and a valid port. `udp` needs an explicit
 * port and cannot carry a user name or password.
 */
fun isTrackerUrl(url: String): Boolean {
  if (url.length > 8192 || url.any { it.isWhitespace() }) return false
  val scheme = url.substringBefore("://", "").lowercase()
  if (scheme !in TRACKER_SCHEMES) return false
  val authority = url.substringAfter("://").takeWhile { it != '/' && it != '?' && it != '#' }
  if (scheme == "udp" && '@' in authority) return false
  val hostPort = authority.substringAfterLast('@')
  val host: String
  val port: String?
  if (hostPort.startsWith('[')) {
    // Bracketed IPv6 literal, e.g. [2001:db8::1]:6969.
    val end = hostPort.indexOf(']')
    if (end < 0) return false
    host = hostPort.substring(1, end)
    val rest = hostPort.substring(end + 1)
    if (rest.isNotEmpty() && !rest.startsWith(':')) return false
    port = rest.takeIf { it.isNotEmpty() }?.substring(1)
  } else {
    host = hostPort.substringBefore(':')
    port = if (':' in hostPort) hostPort.substringAfter(':') else null
  }
  if (host.isEmpty()) return false
  if (port == null) return scheme != "udp"
  return port.all { it in '0'..'9' } && port.toIntOrNull() in 1..65535
}
