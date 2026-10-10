package com.linroid.ketch.api

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * How HTTP(S) downloads, HLS and DASH included, reach their servers: following the system's proxy
 * settings, directly, or through the proxy at [url]. FTP and BitTorrent downloads do not use it.
 *
 * Requests to loopback hosts (`localhost`, `*.localhost`, `127.0.0.0/8` and `::1`) always go
 * directly, as do those whose host matches [bypass] in [ProxyMode.MANUAL] mode.
 *
 * [toString] masks [password], so the configuration can be logged.
 *
 * @property mode where the proxy settings come from.
 * @property url the proxy [ProxyMode.MANUAL] uses, also kept while another mode is chosen:
 *   `http://host:port` for an HTTP proxy, which also carries HTTPS through `CONNECT` tunnels,
 *   or `socks5://host:port` (or `socks5h://`) for a SOCKS5 proxy, which resolves host names
 *   itself. Without a scheme it is an HTTP proxy; without a port, 80 for HTTP and 1080 for
 *   SOCKS5. Credentials go in [username] and [password], not in the URL; [manual] moves them
 *   there. Must be set in [ProxyMode.MANUAL] mode.
 * @property username user name for the proxy, sent with HTTP Basic authentication or SOCKS5
 *   username/password authentication; `null` for none.
 * @property password password for [username].
 * @property bypass hosts [ProxyMode.MANUAL] connects to directly, matched against the host of
 *   each request's URL as written, without resolving it: a domain name (`example.com`,
 *   `.example.com` and `*.example.com` all match `example.com` and its subdomains), an IP
 *   address, a CIDR range such as `10.0.0.0/8` or `fd00::/8`, `<local>` for host names without
 *   a dot, or `*` for every host. A `:port` suffix is ignored.
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

  /** The proxy at [url], or `null` when there is none. */
  val address: ProxyAddress? get() = url?.let { ProxyAddress.parse(it) }

  /**
   * Whether a request to [host] goes directly in [ProxyMode.MANUAL] mode: loopback hosts and
   * those that match [bypass]. [host] is the host of a URL, an IPv6 address with or without
   * brackets.
   */
  fun bypasses(host: String): Boolean = ProxyBypass.isLoopback(host) ||
    bypass.any { ProxyBypass.matches(it, host) }

  override fun toString(): String =
    "ProxyConfig(mode=$mode, url=$url, username=$username, " +
      "password=${if (password == null) "null" else "***"}, bypass=$bypass)"

  companion object {
    /** Follows the system's proxy settings. */
    val System: ProxyConfig = ProxyConfig()

    /** Connects directly, whatever the system's proxy settings are. */
    val Direct: ProxyConfig = ProxyConfig(mode = ProxyMode.DIRECT)

    /** Whether [entry] can go in [bypass]: a host name, IP address, CIDR range or wildcard. */
    fun isValidBypass(entry: String): Boolean = ProxyBypass.isValid(entry)

    /**
     * The proxy at [url], which may carry credentials (`socks5://user:secret@host:1080`); they
     * move to [username] and [password].
     *
     * @throws IllegalArgumentException if [url] is not a supported proxy URL, or [bypass]
     *   holds an entry that is not a host, address or range
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
   * The system's settings. On the JVM, the `https_proxy`, `http_proxy`, `all_proxy` and
   * `no_proxy` environment variables (in either case), then the JVM's default `ProxySelector`,
   * which reads the `http.proxyHost` family of system properties, and the operating system's
   * settings when `java.net.useSystemProxies` is `true`. Android uses the active network's
   * proxy and iOS the system's. Proxy auto-configuration (PAC) scripts are not evaluated on
   * the JVM.
   */
  @SerialName("system")
  SYSTEM,

  /** No proxy. */
  @SerialName("direct")
  DIRECT,

  /** The proxy at [ProxyConfig.url]. */
  @SerialName("manual")
  MANUAL,
}

/**
 * A proxy server, parsed from a URL such as `http://proxy.lan:3128` or
 * `socks5://user:secret@127.0.0.1:1080`.
 *
 * @property type the protocol spoken with the proxy.
 * @property host the proxy's host name or IP address, IPv6 addresses without brackets.
 * @property port the proxy's port.
 * @property username user name from the URL, if any.
 * @property password password from the URL, if any.
 */
data class ProxyAddress(
  val type: Type,
  val host: String,
  val port: Int,
  val username: String? = null,
  val password: String? = null,
) {
  /** Protocols a proxy can speak. */
  enum class Type {
    /** An HTTP proxy, which tunnels HTTPS with `CONNECT`. */
    HTTP,

    /** A SOCKS5 proxy, which also resolves host names. */
    SOCKS5,
  }

  /** The URL of this proxy, with any credentials masked. */
  override fun toString(): String {
    val scheme = if (type == Type.HTTP) "http" else "socks5"
    val user = username?.let { "${it.encodeUserInfo()}${if (password != null) ":***" else ""}@" }
    val printedHost = if (host.contains(':')) "[$host]" else host
    return "$scheme://${user.orEmpty()}$printedHost:$port"
  }

  companion object {
    /**
     * Parses a proxy [url]: `http://`, `socks5://` or `socks5h://`, a host, an optional port and
     * optional percent-encoded credentials. A URL without a scheme is an HTTP proxy. Returns
     * `null` for anything else, including HTTPS proxies and URLs with a path.
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
