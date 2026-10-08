package com.linroid.ketch.app.state

import com.linroid.ketch.app.i18n.UiText
import com.linroid.ketch.app.i18n.spanText
import com.linroid.ketch.app.i18n.text
import com.linroid.ketch.app.instance.TrackerListStatus
import ketch.app.shared.generated.resources.Res
import ketch.app.shared.generated.resources.intake_tracker_no_host
import ketch.app.shared.generated.resources.intake_tracker_port
import ketch.app.shared.generated.resources.intake_tracker_scheme
import ketch.app.shared.generated.resources.intake_tracker_spaces
import ketch.app.shared.generated.resources.intake_tracker_too_long
import ketch.app.shared.generated.resources.intake_tracker_udp_port
import ketch.app.shared.generated.resources.intake_tracker_udp_user_info
import ketch.app.shared.generated.resources.settings_torrent_list_built_in
import ketch.app.shared.generated.resources.settings_torrent_list_failed
import ketch.app.shared.generated.resources.settings_torrent_list_fallback
import ketch.app.shared.generated.resources.settings_torrent_list_stale
import ketch.app.shared.generated.resources.settings_torrent_list_status
import ketch.app.shared.generated.resources.settings_torrent_list_updating
import kotlin.time.Instant

/** Extra trackers the torrent engine uses at most; later ones are ignored. */
const val MAX_EXTRA_TRACKERS = 128

private val TRACKER_SCHEMES = setOf("http", "https", "udp")
private val WHITESPACE = Regex("\\s+")

/** A typed tracker URL that could not be added, and why: [problem], such as "Too long.". */
data class RejectedTracker(val url: String, val problem: UiText)

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
fun trackerUrlError(url: String): UiText? {
  if (url.length > 8192) return Res.string.intake_tracker_too_long.text()
  if (url.any { it.isWhitespace() }) return Res.string.intake_tracker_spaces.text()
  val parts = splitTrackerUrl(url) ?: return Res.string.intake_tracker_scheme.text()
  if (parts.host.isEmpty()) return Res.string.intake_tracker_no_host.text()
  if (parts.scheme == "udp" && parts.hasUserInfo) {
    return Res.string.intake_tracker_udp_user_info.text()
  }
  val port = parts.port ?: return if (parts.scheme == "udp") {
    Res.string.intake_tracker_udp_port.text()
  } else {
    null
  }
  val valid = port.isNotEmpty() && port.all { it in '0'..'9' } && port.toIntOrNull() in 1..65535
  return if (valid) null else Res.string.intake_tracker_port.text()
}

/**
 * The [listed] trackers of a subscribed list that torrents skip: the engine uses the [extra]
 * trackers, then the list's, up to [MAX_EXTRA_TRACKERS] usable ones in all.
 */
fun unusedListedTrackers(extra: List<String>, listed: List<String>): Set<String> {
  val used = (extra + listed).distinct().filter { trackerUrlError(it) == null }
  return used.drop(MAX_EXTRA_TRACKERS).toSet() - extra.toSet()
}

/**
 * A short name for the tracker list at [url]: "ngosang/trackerslist · trackers_best.txt" for a
 * file on GitHub, otherwise its host and file, such as "lists.example.org · best.txt".
 */
fun trackerListName(url: String): String {
  val rest = url.substringAfter("://")
  val host = rest.substringBefore('/')
  val path = rest.substringAfter('/', "").substringBefore('?').substringBefore('#')
    .split('/').filter { it.isNotEmpty() }
  val file = path.lastOrNull() ?: return host
  return if (host == "raw.githubusercontent.com" && path.size >= 4) {
    "${path[0]}/${path[1]} · $file"
  } else {
    "$host · $file"
  }
}

/** Whether [url] can be a tracker list's address: `http` or `https` with a host, no spaces. */
fun isTrackerListUrl(url: String): Boolean {
  val scheme = url.substringBefore("://", "").lowercase()
  val host = url.substringAfter("://").takeWhile { it != '/' && it != '?' && it != '#' }
  return (scheme == "http" || scheme == "https") && host.isNotEmpty() &&
    url.none { it.isWhitespace() }
}

/**
 * What a subscribed tracker list holds, as of [now]: how many trackers and how old they are, or
 * that a download is running or failed.
 */
fun trackerListStatusText(status: TrackerListStatus, now: Instant): UiText {
  val updatedAt = status.updatedAt
  val count = status.trackers.size
  return when {
    status.updating -> Res.string.settings_torrent_list_updating.text()
    updatedAt != null -> {
      val age = spanText(now - updatedAt)
      if (status.failed) {
        Res.plurals.settings_torrent_list_stale.text(count, count, age)
      } else {
        Res.plurals.settings_torrent_list_status.text(count, count, age)
      }
    }
    status.failed && count > 0 -> Res.plurals.settings_torrent_list_fallback.text(count)
    status.failed -> Res.string.settings_torrent_list_failed.text()
    count > 0 -> Res.plurals.settings_torrent_list_built_in.text(count)
    else -> Res.string.settings_torrent_list_updating.text()
  }
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
