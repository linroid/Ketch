package com.linroid.ketch.torrent.tracker

import com.linroid.ketch.torrent.withoutUrls
import io.ktor.http.decodeURLQueryComponent

/** The most characters of a tracker's message that [trackerMessage] keeps. */
internal const val MAX_TRACKER_MESSAGE = 200

/**
 * The tracker refused a request and said why: [reason], as [trackerMessage] left it, or `null`
 * when nothing of it was left. The message is safe to log.
 */
internal class TrackerFailureException(val reason: String?) : IllegalArgumentException(
  if (reason == null) "Tracker rejected the request" else "Tracker rejected the request: $reason",
)

/**
 * Text a tracker sent (`failure reason`, `warning message` or a UDP error) made safe to keep, show
 * and log. Control characters become spaces, invisible formatting ones (bidirectional overrides,
 * zero-width spaces) are dropped and whitespace collapses. Every URL is cut to its scheme, host
 * and port, and secrets are masked as `***`: whatever of [trackerUrl]'s user info, path or query
 * could be a passkey, and any other run of 16 letters and digits or more that has both, as
 * passkeys and tokens do. At most [MAX_TRACKER_MESSAGE] characters; `null` when nothing is left.
 */
internal fun trackerMessage(raw: ByteArray, trackerUrl: String): String? {
  val visible = buildString {
    for (char in raw.copyOf(minOf(raw.size, MAX_TRACKER_BYTES)).decodeToString()) when {
      char.isISOControl() || char == '\u2028' || char == '\u2029' -> append(' ')
      char.category == CharCategory.FORMAT -> Unit
      else -> append(char)
    }
  }
  // Secrets go before URLs are cut: a URL ends at characters a passkey may hold, such as a
  // comma, so cutting first could leave most of a passkey behind, no longer whole to match.
  var text = visible
  for (secret in urlSecrets(trackerUrl)) text = text.replace(secret, "***", ignoreCase = true)
  text = withoutUrls(text)
  text = SECRET_LIKE.replace(text) { match ->
    val token = match.value
    if (token.any { it in '0'..'9' } && token.any { it !in '0'..'9' }) "***" else token
  }
  text = WHITESPACE.replace(text, " ").trim()
  if (text.isEmpty()) return null
  if (text.length <= MAX_TRACKER_MESSAGE) return text
  var end = MAX_TRACKER_MESSAGE - 1
  if (text[end - 1].isHighSurrogate()) end--
  return text.substring(0, end) + "…"
}

/**
 * The parts of [url] that could be credentials, raw and decoded, longest first: its user name
 * and password, and the path segments and query keys and values that hold a digit and have six
 * characters or more, or that have sixteen or more. `announce` and `announce.php` are not.
 */
private fun urlSecrets(url: String): List<String> {
  val rest = url.substringAfter("://", "")
  val authority = rest.substringBefore('/').substringBefore('?').substringBefore('#')
  val credentials = authority.substringBeforeLast('@', "").split(':').filter { it.length >= 4 }
  val parts = rest.removePrefix(authority).split('/', '?', '&', '=', '#', ';')
    .filter { part -> part.length >= 16 || part.length >= 6 && part.any { it in '0'..'9' } }
  return (credentials + parts).flatMap { part ->
    listOfNotNull(part, runCatching { part.decodeURLQueryComponent() }.getOrNull())
  }.filter { it.length >= 4 }.distinct().sortedByDescending { it.length }
}

// A message this long is cut long before it ends; reading on would only cost time.
private const val MAX_TRACKER_BYTES = 4096
private val SECRET_LIKE = Regex("[A-Za-z0-9]{16,}")
private val WHITESPACE = Regex("\\s+")
