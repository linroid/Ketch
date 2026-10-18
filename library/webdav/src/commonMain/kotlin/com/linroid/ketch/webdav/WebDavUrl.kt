package com.linroid.ketch.webdav

/**
 * A WebDAV link: `webdav://` or `dav://` for a server over HTTP, `webdavs://` or `davs://` over
 * HTTPS, with optional user information (`user:password@`) the source signs in with.
 *
 * @property httpUrl the same resource as an `http://` or `https://` URL, without the user
 *   information or a fragment
 * @property requestTarget the path and query requests name, as authentication digests them
 * @property credentials the user name and password of the link, percent-decoded, or `null`
 */
internal class WebDavUrl private constructor(
  val httpUrl: String,
  val requestTarget: String,
  val credentials: Credentials?,
) {
  /** Whether the link reaches its server over TLS. */
  val isSecure: Boolean get() = httpUrl.startsWith("https://")

  /** The last segment of the path, percent-decoded, or `null` when the path ends in `/`. */
  val fileName: String?
    get() = requestTarget.substringBefore('?').substringAfterLast('/')
      .takeIf { it.isNotEmpty() }
      ?.let { runCatching { percentDecode(it) }.getOrNull() }

  /** Leaves the credentials out, so a link can be logged. */
  override fun toString(): String = "WebDavUrl(secure=$isSecure)"

  companion object {
    /** The schemes of WebDAV links and the scheme of the HTTP URL each stands for. */
    private val SCHEMES = mapOf(
      "webdav" to "http",
      "dav" to "http",
      "webdavs" to "https",
      "davs" to "https",
    )

    /** Whether [url] is a WebDAV link, by its scheme. */
    fun isWebDav(url: String): Boolean {
      val separator = url.indexOf("://")
      if (separator <= 0) return false
      return url.substring(0, separator).lowercase() in SCHEMES
    }

    /**
     * Parses [url].
     *
     * @throws IllegalArgumentException when [url] is not a WebDAV link with a host, or its user
     *   information is not valid percent-encoded UTF-8
     */
    fun parse(url: String): WebDavUrl {
      val trimmed = url.trim()
      val separator = trimmed.indexOf("://")
      require(separator > 0) { "Not a WebDAV link" }
      val scheme = SCHEMES[trimmed.substring(0, separator).lowercase()]
        ?: throw IllegalArgumentException("Not a WebDAV link")
      val rest = trimmed.substring(separator + 3).substringBefore('#')
      val authorityEnd = rest.indexOfFirst { it == '/' || it == '?' }.let {
        if (it < 0) rest.length else it
      }
      val authority = rest.substring(0, authorityEnd)
      val at = authority.lastIndexOf('@')
      val hostPort = authority.substring(at + 1)
      require(hostPort.isNotEmpty() && !hostPort.startsWith(':')) { "The link has no host" }
      require(hostPort.none { it.isWhitespace() || it == '\\' }) { "Invalid host" }
      val credentials = if (at >= 0) {
        val info = authority.substring(0, at)
        val colon = info.indexOf(':')
        val user = if (colon < 0) info else info.substring(0, colon)
        val password = if (colon < 0) "" else info.substring(colon + 1)
        Credentials(percentDecode(user), percentDecode(password))
      } else {
        null
      }
      val target = rest.substring(authorityEnd).let {
        when {
          it.isEmpty() -> "/"
          it.startsWith('?') -> "/$it"
          else -> it
        }
      }.let(::percentEncodeTarget)
      return WebDavUrl("$scheme://$hostPort$target", target, credentials)
    }

    /**
     * [target] with each character a request target cannot hold, such as a space or a non-ASCII
     * letter, percent-encoded as UTF-8, and escapes left as they are: the target as HTTP clients
     * send it, which Digest answers must name exactly.
     */
    private fun percentEncodeTarget(target: String): String {
      if (target.all { it in TARGET_CHARS }) return target
      return buildString {
        for (byte in target.encodeToByteArray()) {
          val c = (byte.toInt() and 0xff)
          if (c < 0x80 && c.toChar() in TARGET_CHARS) {
            append(c.toChar())
          } else {
            append('%').append(HEX[c shr 4]).append(HEX[c and 0xf])
          }
        }
      }
    }

    private const val HEX = "0123456789ABCDEF"

    /** Unreserved and reserved characters (RFC 3986) and `%` of escapes. */
    private val TARGET_CHARS: Set<Char> =
      (('a'..'z') + ('A'..'Z') + ('0'..'9') + "-._~!$&'()*+,;=:@/?%".toList()).toSet()

    /**
     * [value] with each `%XX` replaced by its byte, read as UTF-8.
     *
     * @throws IllegalArgumentException for a malformed escape or invalid UTF-8
     */
    internal fun percentDecode(value: String): String {
      if ('%' !in value) return value
      val bytes = ArrayList<Byte>(value.length)
      var i = 0
      while (i < value.length) {
        if (value[i] == '%') {
          require(i + 2 <= value.lastIndex) { "Malformed escape" }
          val pair = value.substring(i + 1, i + 3)
          require(pair.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }) {
            "Malformed escape"
          }
          bytes += pair.toInt(16).toByte()
          i += 3
        } else {
          // Copy the run up to the next escape whole, so surrogate pairs stay together.
          val end = value.indexOf('%', i).let { if (it < 0) value.length else it }
          value.substring(i, end).encodeToByteArray().forEach { bytes += it }
          i = end
        }
      }
      return try {
        bytes.toByteArray().decodeToString(throwOnInvalidSequence = true)
      } catch (e: CharacterCodingException) {
        throw IllegalArgumentException("Invalid UTF-8 in escape", e)
      }
    }
  }
}

/** A user name and password. [toString] leaves the password out. */
internal class Credentials(val user: String, val password: String) {
  override fun toString(): String = "Credentials(user=$user)"
}
