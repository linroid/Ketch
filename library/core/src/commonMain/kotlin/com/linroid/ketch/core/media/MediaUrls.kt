package com.linroid.ketch.core.media

import com.linroid.ketch.api.KetchError

internal fun mediaRequire(value: Boolean, message: String) {
  if (!value) throw KetchError.SourceError("media", detail = message)
}

/** Resolve HTTP references without interpreting query parameters or decoding signed paths. */
fun mediaUrl(base: String, reference: String): String {
  val ref = reference.trim().substringBefore('#')
  mediaRequire(ref.length <= 8192 && ref.none { it <= ' ' || it == '\\' }, "Invalid media URL")
  val root = HTTP_URL.matchEntire(base.substringBefore('#'))
  mediaRequire(root != null, "Media requires an HTTP or HTTPS URL")
  val scheme = root!!.groupValues[1].lowercase()
  val authority = root.groupValues[2]
  val path = root.groupValues[3].ifEmpty { "/" }
  val target = when {
    ref.startsWith("//") -> "$scheme:$ref"
    SCHEME.containsMatchIn(ref) -> ref
    ref.startsWith('/') -> "$scheme://$authority$ref"
    ref.startsWith('?') -> "$scheme://$authority$path$ref"
    ref.isEmpty() -> base.substringBefore('#')
    else -> "$scheme://$authority${path.substringBeforeLast('/')}/$ref"
  }
  val parsed = HTTP_URL.matchEntire(target)
  mediaRequire(parsed != null, "Media requires an HTTP or HTTPS URL")
  mediaRequire('@' !in parsed!!.groupValues[2], "Media URLs cannot contain credentials")
  mediaRequire(scheme != "https" || parsed.groupValues[1].lowercase() == "https",
    "Media cannot downgrade HTTPS to HTTP")
  val segments = mutableListOf<String>()
  for (segment in parsed.groupValues[3].split('/').drop(1)) {
    when (segment) {
      "." -> Unit
      ".." -> if (segments.isNotEmpty()) segments.removeAt(segments.lastIndex)
      else -> segments += segment
    }
  }
  val suffix = if (parsed.groupValues[3].endsWith("/..") ||
    parsed.groupValues[3].endsWith("/.")) "/" else ""
  return "${parsed.groupValues[1]}://${parsed.groupValues[2]}/" +
    segments.joinToString("/") + suffix + parsed.groupValues[4]
}

/** Keeps credentials on the same origin and only safe headers when crossing origins. */
fun mediaHeaders(
  from: String,
  to: String,
  headers: Map<String, String>,
): Map<String, String> {
  fun origin(url: String): String {
    val parts = HTTP_URL.matchEntire(url)!!
    return "${parts.groupValues[1].lowercase()}://${parts.groupValues[2].lowercase()}"
  }
  if (origin(from) == origin(to)) return headers
  return headers.filterKeys {
    it.lowercase() in setOf("user-agent", "accept", "accept-language", "accept-encoding")
  }
}

private val SCHEME = Regex("^[a-zA-Z][a-zA-Z0-9+.-]*:")
private val HTTP_URL = Regex("(https?)://([^/?#]+)([^?#]*)(\\?[^#]*)?", RegexOption.IGNORE_CASE)
