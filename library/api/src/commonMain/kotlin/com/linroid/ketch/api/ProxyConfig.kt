package com.linroid.ketch.api

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * How HTTP(S) downloads, HLS and DASH included, reach servers: the system's settings, directly,
 * or through [url]. FTP and BitTorrent ignore it. Loopback hosts always go directly. [toString]
 * masks [password].
 *
 * @property url the [ProxyMode.MANUAL] proxy, kept in other modes: `http://host:port` (80 by
 *   default; also the default scheme) or `socks5://host:port` (1080). Credentials go in
 *   [username] and [password], not the URL; [manual] moves them. Required in
 *   [ProxyMode.MANUAL].
 * @property username for HTTP Basic or SOCKS5 authentication
 * @property bypass hosts [ProxyMode.MANUAL] reaches directly, matched against the URL's host
 *   unresolved: a domain with its subdomains (a leading `.` or `*.` is optional), an IP, a CIDR
 *   range, `<local>` for names without a dot, or `*`; a `:port` is ignored
 */
@Serializable
data class ProxyConfig(
  val mode: ProxyMode = ProxyMode.SYSTEM,
  val url: String? = null,
  val username: String? = null,
  val password: String? = null,
  val bypass: List<String> = emptyList(),
) {
  init {
    if (mode == ProxyMode.MANUAL) require(!url.isNullOrBlank()) { "A manual proxy needs a URL" }
    if (url != null) {
      val parsed = requireNotNull(ProxyAddress.parse(url)) { "Invalid proxy URL" }
      require(parsed.username == null) { "Proxy credentials go in username and password" }
    }
    require(password == null || username != null) { "A proxy password needs a username" }
    require(bypass.all { ProxyBypass.isValid(it) }) {
      "Bypass entries must be host names, IP addresses or CIDR ranges"
    }
  }

  /** [url] parsed, or `null`. */
  val address: ProxyAddress? get() = url?.let { ProxyAddress.parse(it) }

  /** Whether [ProxyMode.MANUAL] reaches [host] directly: loopback or matching [bypass]. */
  fun bypasses(host: String): Boolean = ProxyBypass.isLoopback(host) ||
    bypass.any { ProxyBypass.matches(it, host) }

  override fun toString(): String =
    "ProxyConfig(mode=$mode, url=$url, username=$username, " +
      "password=${if (password == null) "null" else "***"}, bypass=$bypass)"

  companion object {
    /** The system's proxy settings. */
    val System: ProxyConfig = ProxyConfig()

    /** No proxy, whatever the system says. */
    val Direct: ProxyConfig = ProxyConfig(mode = ProxyMode.DIRECT)

    /** Whether [entry] is a valid [bypass] entry. */
    fun isValidBypass(entry: String): Boolean = ProxyBypass.isValid(entry)

    /**
     * The proxy at [url], moving its credentials (`socks5://user:secret@host`) to [username] and
     * [password].
     *
     * @throws IllegalArgumentException if [url] or a [bypass] entry is invalid
     */
    fun manual(url: String, bypass: List<String> = emptyList()): ProxyConfig {
      val address = requireNotNull(ProxyAddress.parse(url)) { "Invalid proxy URL" }
      return ProxyConfig(
        mode = ProxyMode.MANUAL,
        url = address.copy(username = null, password = null).toString(),
        username = address.username,
        password = address.password,
        bypass = bypass,
      )
    }
  }
}

/** Where [ProxyConfig] takes its proxy from. */
@Serializable
enum class ProxyMode {
  /**
   * The system's settings: on the JVM the `*_proxy` environment variables, then the default
   * `ProxySelector` (no PAC scripts); the active network's on Android; the system's on iOS.
   */
  @SerialName("system")
  SYSTEM,

  @SerialName("direct")
  DIRECT,

  /** The proxy at [ProxyConfig.url]. */
  @SerialName("manual")
  MANUAL,
}

/**
 * A proxy server parsed from a URL, such as `socks5://user:secret@127.0.0.1:1080`.
 *
 * @property host a host name or IP (IPv6 without brackets)
 */
data class ProxyAddress(
  val type: Type,
  val host: String,
  val port: Int,
  val username: String? = null,
  val password: String? = null,
) {
  /** A proxy's protocol. */
  enum class Type {
    /** Tunnels HTTPS with `CONNECT`. */
    HTTP,

    /** Also resolves host names. */
    SOCKS5,
  }

  /** The proxy's URL, its password masked. */
  override fun toString(): String {
    val scheme = if (type == Type.HTTP) "http" else "socks5"
    val user = username?.let { "${it.encodeUserInfo()}${if (password != null) ":***" else ""}@" }
    val printedHost = if (host.contains(':')) "[$host]" else host
    return "$scheme://${user.orEmpty()}$printedHost:$port"
  }

  companion object {
    /**
     * Parses `http://`, `socks5://` or `socks5h://` URLs (HTTP without a scheme) with optional
     * port and percent-encoded credentials; `null` for anything else, such as a path or HTTPS.
     */
    fun parse(url: String): ProxyAddress? {
      val trimmed = url.trim()
      if (trimmed.isEmpty() || trimmed.any { it.isWhitespace() }) return null
      val schemeEnd = trimmed.indexOf("://")
      val scheme = if (schemeEnd < 0) "http" else trimmed.substring(0, schemeEnd).lowercase()
      val type = when (scheme) {
        "http" -> Type.HTTP
        "socks5", "socks5h" -> Type.SOCKS5
        else -> return null
      }
      var rest = if (schemeEnd < 0) trimmed else trimmed.substring(schemeEnd + 3)
      rest = rest.removeSuffix("/")
      if (rest.any { it == '/' || it == '?' || it == '#' }) return null
      val at = rest.lastIndexOf('@')
      var username: String? = null
      var password: String? = null
      if (at >= 0) {
        val userInfo = rest.substring(0, at)
        rest = rest.substring(at + 1)
        val colon = userInfo.indexOf(':')
        username = (if (colon < 0) userInfo else userInfo.substring(0, colon))
          .decodeUserInfo() ?: return null
        password = if (colon < 0) null else userInfo.substring(colon + 1).decodeUserInfo()
          ?: return null
        if (username.isEmpty()) return null
      }
      val host: String
      val portText: String?
      if (rest.startsWith("[")) {
        val close = rest.indexOf(']')
        if (close < 0) return null
        host = rest.substring(1, close)
        if (IpAddress.parse(host)?.size != 16) return null
        val after = rest.substring(close + 1)
        portText = when {
          after.isEmpty() -> null
          after.startsWith(":") -> after.substring(1)
          else -> return null
        }
      } else {
        val colon = rest.lastIndexOf(':')
        if (colon >= 0 && rest.indexOf(':') != colon) return null
        host = if (colon < 0) rest else rest.substring(0, colon)
        portText = if (colon < 0) null else rest.substring(colon + 1)
        if (!isHostName(host) && IpAddress.parse(host) == null) return null
      }
      val port = if (portText == null) {
        if (type == Type.HTTP) 80 else 1080
      } else {
        portText.toIntOrNull()?.takeIf { it in 1..65535 && portText.all(Char::isDigit) }
          ?: return null
      }
      return ProxyAddress(type, host.lowercase(), port, username, password)
    }

    private fun isHostName(host: String): Boolean =
      host.isNotEmpty() && host.length <= 253 && host.split('.').all { label ->
        label.isNotEmpty() && label.length <= 63 &&
          label.all { it.isLetterOrDigit() || it == '-' || it == '_' } &&
          !label.startsWith('-') && !label.endsWith('-')
      }

    private fun String.decodeUserInfo(): String? {
      val source = encodeToByteArray()
      val decoded = ByteArray(source.size)
      var size = 0
      var i = 0
      while (i < source.size) {
        if (source[i] == '%'.code.toByte()) {
          if (i + 2 >= source.size) return null
          val high = source[i + 1].toInt().toChar().digitToIntOrNull(16) ?: return null
          val low = source[i + 2].toInt().toChar().digitToIntOrNull(16) ?: return null
          decoded[size++] = (high * 16 + low).toByte()
          i += 3
        } else {
          decoded[size++] = source[i++]
        }
      }
      return decoded.decodeToString(0, size)
    }

    private fun String.encodeUserInfo(): String = buildString {
      for (byte in this@encodeUserInfo.encodeToByteArray()) {
        val c = (byte.toInt() and 0xff).toChar()
        if (c.isLetterOrDigit() && c.code < 128 || c in "-._~") {
          append(c)
        } else {
          append('%')
          append((byte.toInt() and 0xff).toString(16).uppercase().padStart(2, '0'))
        }
      }
    }
  }
}
