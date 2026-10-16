package com.linroid.ketch.core.engine

/**
 * The scheme, host and port of a URL, without user info or path.
 *
 * @property scheme the scheme in lower case
 * @property host the host in lower case, IPv6 literals without brackets
 * @property port the port the URL names, else the scheme's default; `null` when neither is known
 */
internal class UrlAuthority(val scheme: String, val host: String, val port: Int?) {
  companion object {
    private val AUTHORITY = Regex("""^([A-Za-z][A-Za-z0-9+.\-]*)://([^/?#]*)""")

    /** The authority of [url], or `null` when it has no scheme and network host. */
    fun parse(url: String): UrlAuthority? {
      val match = AUTHORITY.find(url.trim()) ?: return null
      val scheme = match.groupValues[1].lowercase()
      val hostPort = match.groupValues[2].substringAfterLast('@')
      val host: String
      val portText: String
      if (hostPort.startsWith('[')) {
        host = hostPort.substring(1).substringBefore(']')
        portText = hostPort.substringAfter(']', "").removePrefix(":")
      } else {
        host = hostPort.substringBefore(':')
        portText = hostPort.substringAfter(':', "")
      }
      if (host.isEmpty()) return null
      val port = portText.toIntOrNull()?.takeIf { it in 1..65535 } ?: defaultPort(scheme)
      return UrlAuthority(scheme, host.lowercase(), port)
    }

    /** The port [scheme] uses unless a URL names one, or `null` for other schemes. */
    fun defaultPort(scheme: String): Int? = when (scheme) {
      "http" -> 80
      "https" -> 443
      "ftp" -> 21
      "ftps" -> 990
      else -> null
    }
  }
}
