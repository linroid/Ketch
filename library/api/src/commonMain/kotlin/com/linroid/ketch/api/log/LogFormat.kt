package com.linroid.ketch.api.log

import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

/**
 * One-line summary of this error and its causes, for log lines that do not print a
 * stack trace, such as retry warnings:
 * `Network: Network error occurred <- ConnectTimeoutException: Connect timeout has expired`.
 * URLs quoted in the messages pass through [redactUrl].
 *
 * @suppress This is internal API and should not be used directly by library users.
 */
fun Throwable.describeCauses(): String {
  val seen = mutableListOf<Throwable>()
  var current: Throwable? = this
  while (current != null && seen.size < MAX_LOGGED_CAUSES && seen.none { it === current }) {
    seen += current
    current = current.cause
  }
  return seen.joinToString(" <- ") { error ->
    val name = error::class.simpleName ?: "Throwable"
    // Exception(cause) copies cause.toString() into its message; the next entry repeats it.
    val message = error.message?.takeIf { it.isNotBlank() && it != error.cause?.toString() }
      ?.replace('\n', ' ')
      ?.let(::redactUrlsIn)
    if (message == null) name else "$name: $message"
  }
}

/**
 * [url] in a form that is safe to log: the password in its user info and the values of
 * credential-like query parameters (such as `passkey`, `token` or `X-Amz-Signature`) are
 * masked, and a magnet link keeps only its topic and display name, because tracker and
 * source URLs can carry passkeys.
 *
 * @suppress This is internal API and should not be used directly by library users.
 */
fun redactUrl(url: String): String {
  if (url.startsWith("magnet:", ignoreCase = true)) return redactMagnet(url)
  val schemeEnd = url.indexOf("://")
  if (schemeEnd < 0) return url
  return redactQuery(redactUserInfo(url, schemeEnd + 3))
}

private fun redactUserInfo(url: String, authorityStart: Int): String {
  // Like FtpUrl, only a slash ends the credentials: an unencoded password may contain ? or #.
  // A query that holds an @ before any path is over-redacted, which never leaks.
  val authorityEnd = url.indexOf('/', authorityStart).let { if (it < 0) url.length else it }
  val at = url.lastIndexOf('@', authorityEnd - 1)
  if (at < authorityStart) return url
  val colon = url.indexOf(':', authorityStart)
  if (colon < 0 || colon > at) return url
  return url.substring(0, colon + 1) + "***" + url.substring(at)
}

private fun redactQuery(url: String): String {
  val queryStart = url.indexOf('?')
  if (queryStart < 0) return url
  val fragmentStart = url.indexOf('#', queryStart).let { if (it < 0) url.length else it }
  val query = url.substring(queryStart + 1, fragmentStart).split('&').joinToString("&") { param ->
    val key = param.substringBefore('=')
    if ('=' in param && SENSITIVE_QUERY_KEY.containsMatchIn(key)) "$key=***" else param
  }
  return url.substring(0, queryStart + 1) + query + url.substring(fragmentStart)
}

private fun redactMagnet(url: String): String {
  val query = url.substringAfter('?', "")
  val params = query.split('&').filter { it.isNotEmpty() }
  val kept = params.filter { param ->
    val key = param.substringBefore('=').lowercase()
    key == "xt" || key == "dn"
  }
  val omitted = params.size - kept.size
  return buildString {
    append(url.substringBefore('?'))
    if (kept.isNotEmpty()) append('?').append(kept.joinToString("&"))
    if (omitted > 0) append(" (+$omitted parameter").append(if (omitted > 1) "s)" else ")")
  }
}

/**
 * Every URL or magnet link quoted in [text] passed through [redactUrl]. Exception messages,
 * such as Ktor's timeouts, quote the full request URL.
 */
internal fun redactUrlsIn(text: String): String = EMBEDDED_URL.replace(text) { redactUrl(it.value) }

/**
 * Formats a console log line as `2026-01-31 14:03:12.345 [INFO] message`, followed by the
 * stack trace of [throwable] when present, so one write keeps a record together. URLs in the
 * stack trace's messages are redacted like those in log messages.
 */
internal fun formatLogLine(level: LogLevel, message: String, throwable: Throwable? = null): String {
  val line = "${logTimestamp()} [${level.name}] $message"
  if (throwable == null) return line
  return line + "\n" + redactUrlsIn(throwable.stackTraceToString().trimEnd())
}

@OptIn(ExperimentalTime::class)
private fun logTimestamp(): String {
  val time = Clock.System.now().toLocalDateTime(logTimeZone)
  return buildString {
    append(time.date).append(' ')
    append(time.hour.pad(2)).append(':').append(time.minute.pad(2)).append(':')
    append(time.second.pad(2)).append('.').append((time.nanosecond / 1_000_000).pad(3))
  }
}

// Some runtimes, such as WASI, have no local time zone database.
private val logTimeZone: TimeZone by lazy {
  try {
    TimeZone.currentSystemDefault()
  } catch (_: Exception) {
    TimeZone.UTC
  }
}

private fun Int.pad(length: Int): String = toString().padStart(length, '0')

/** Causes followed when describing or redacting an error chain, which may be cyclic. */
internal const val MAX_LOGGED_CAUSES = 8

private val EMBEDDED_URL = Regex("""(?:[A-Za-z][A-Za-z0-9+.-]*://|magnet:\?)[^\s,;"'()\[\]]+""")

private val SENSITIVE_QUERY_KEY = Regex(
  "pass|token|secret|key|sig|auth|credential|session",
  RegexOption.IGNORE_CASE,
)
