package com.linroid.ketch.api.log

import com.linroid.ketch.api.InternalKetchApi
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

/**
 * This error and its causes on one line, URLs redacted, for logs without a stack trace:
 * `Network: Network error occurred <- ConnectTimeoutException: Connect timeout has expired`.
 *
 * @suppress
 */
@InternalKetchApi
fun Throwable.describeCauses(): String {
  val seen = mutableListOf<Throwable>()
  var current: Throwable? = this
  while (current != null && seen.size < MAX_LOGGED_CAUSES && seen.none { it === current }) {
    seen += current
    current = current.cause
  }
  // Coroutines recover an exception as a copy of it whose cause is the original.
  val shown = seen.filterNot { it.isCopyOfItsCause() }.ifEmpty { seen }
  return shown.joinToString(" <- ") { error ->
    val name = error::class.simpleName ?: "Throwable"
    val message = error.message?.let { ownMessage(it, error.cause) }
      ?.takeIf { it.isNotBlank() }
      ?.replace('\n', ' ')
      ?.let(::redactUrlsIn)
    if (message == null) name else "$name: $message"
  }
}

private fun Throwable.isCopyOfItsCause(): Boolean {
  val cause = cause ?: return false
  return cause::class == this::class && cause.message == message
}

/**
 * What [message] says beyond its [cause], which the next entry shows: `Exception(cause)` copies
 * `cause.toString()` into its message, and wrappers such as the JDK's TLS errors end theirs with
 * it (`PKIX path validation failed: java.security.cert.CertPathValidatorException: ...`) or with
 * the cause's message. A message ends with a cause's message only by copying it when that is
 * long enough and follows a space or colon, so `Stream closed` keeps its words before `closed`.
 */
private fun ownMessage(message: String, cause: Throwable?): String {
  if (cause == null) return message
  val described = cause.toString()
  if (message.endsWith(described)) return message.dropLast(described.length).trimEnd(' ', ':')
  val copied = cause.message?.takeIf { it.length >= MIN_COPIED_MESSAGE } ?: return message
  if (!message.endsWith(copied)) return message
  val own = message.dropLast(copied.length)
  val atBoundary = own.isEmpty() || own.last() == ' ' || own.last() == ':'
  return if (atBoundary) own.trimEnd(' ', ':') else message
}

/**
 * [url] safe to log: its password and credential-like query values (`passkey`, `token`,
 * `X-Amz-Signature`) masked, and a magnet link cut to its topic and name.
 *
 * @suppress
 */
@InternalKetchApi
fun redactUrl(url: String): String {
  if (url.startsWith("magnet:", ignoreCase = true)) return redactMagnet(url)
  val schemeEnd = url.indexOf("://")
  if (schemeEnd < 0) return url
  return redactQuery(redactUserInfo(url, schemeEnd + 3))
}

private fun redactUserInfo(url: String, authorityStart: Int): String {
  // Only a slash ends the credentials, as passwords may hold ? or #; over-redacting never leaks.
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
 * [text] with every URL and magnet link [redacted][redactUrl], as exception messages quote them.
 *
 * @suppress
 */
@InternalKetchApi
fun redactUrlsIn(text: String): String = EMBEDDED_URL.replace(text) { redactUrl(it.value) }

/**
 * A log record as `2026-01-31 14:03:12.345 [INFO] message`, followed by [throwable]'s stack
 * trace with URLs redacted.
 *
 * @suppress
 */
@InternalKetchApi
fun formatLogLine(level: LogLevel, message: String, throwable: Throwable? = null): String {
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

/** How many causes of a (possibly cyclic) error chain are followed. */
internal const val MAX_LOGGED_CAUSES = 8

/** How long a cause's message must be for [ownMessage] to take one ending with it for a copy. */
private const val MIN_COPIED_MESSAGE = 16

private val EMBEDDED_URL = Regex("""(?:[A-Za-z][A-Za-z0-9+.-]*://|magnet:\?)[^\s,;"'()\[\]]+""")

private val SENSITIVE_QUERY_KEY = Regex(
  "pass|token|secret|key|sig|auth|credential|session",
  RegexOption.IGNORE_CASE,
)
