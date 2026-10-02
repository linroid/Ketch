package com.linroid.ketch.app.util

import com.linroid.ketch.api.NetworkInterfaceInfo

/**
 * Where a Ketch device listens and the code that lets another device control it, as a pairing
 * link carries them.
 *
 * The link reads `ketch://pair?host=192.168.1.20&port=8642&name=Lins-MacBook-Pro#token=…`. The
 * token sits only in the fragment, which browsers and HTTP clients never send, so it stays out of
 * requests, server logs and proxies.
 *
 * @property host host name or IP address; an IPv6 address without brackets.
 * @property port port of the device's server.
 * @property token access token of the server, or `null` when it needs none.
 * @property name name the device goes by, shown before connecting; `null` when unknown.
 * @property secure whether the server is reached over HTTPS.
 */
data class PairingLink(
  val host: String,
  val port: Int = DEFAULT_PORT,
  val token: String? = null,
  val name: String? = null,
  val secure: Boolean = false,
) {
  init {
    require(host.isNotBlank()) { "host must not be blank" }
    require(port in PORTS) { "port must be between 1 and 65535" }
  }

  /** `host:port`, with an IPv6 host in brackets, as the address field takes it. */
  val address: String
    get() = "${if (':' in host) "[$host]" else host}:$port"

  /** The `ketch://pair` link, with the token only in the fragment. */
  fun toUri(): String = buildString {
    append(SCHEME).append("://").append(PAIR_HOST)
    append("?host=").append(percentEncode(host))
    append("&port=").append(port)
    if (name != null) append("&name=").append(percentEncode(name))
    if (secure) append("&secure=1")
    if (token != null) append("#token=").append(percentEncode(token))
  }

  /** The device's web app, such as `http://192.168.1.20:8642/#token=…`. */
  fun webAppUrl(): String = buildString {
    append(if (secure) "https" else "http").append("://").append(address).append('/')
    if (token != null) append("#token=").append(percentEncode(token))
  }

  companion object {
    /** Port a Ketch server listens on unless told otherwise. */
    const val DEFAULT_PORT: Int = 8642

    /** Scheme of pairing links. */
    const val SCHEME: String = "ketch"

    private const val PAIR_HOST = "pair"
    private const val HTTP_PORT = 80
    private const val HTTPS_PORT = 443
    private val PORTS = 1..65535

    /**
     * Reads a pairing link, a web app address or a plain address:
     * `ketch://pair?host=…&port=…&name=…#token=…`, `http(s)://host[:port]/#token=…` or
     * `host[:port]`. A plain address uses [DEFAULT_PORT]; a web address the port of its scheme.
     *
     * @return the link, or `null` when [text] is none of these.
     */
    fun parse(text: String): PairingLink? {
      val input = text.trim()
      if (input.isEmpty() || input.any { it.isWhitespace() }) return null
      val schemeEnd = input.indexOf("://")
      if (schemeEnd < 0) return parseAddress(input, DEFAULT_PORT)
      val rest = input.substring(schemeEnd + 3)
      val token = parameters(rest.substringAfter('#', ""))["token"]?.ifEmpty { null }
      val beforeFragment = rest.substringBefore('#')
      return when (input.substring(0, schemeEnd).lowercase()) {
        SCHEME -> parsePairLink(beforeFragment, token)
        "http" -> parseAddress(authorityOf(beforeFragment), HTTP_PORT)?.copy(token = token)
        "https" -> {
          parseAddress(authorityOf(beforeFragment), HTTPS_PORT)?.copy(token = token, secure = true)
        }
        else -> null
      }
    }

    private fun parsePairLink(text: String, token: String?): PairingLink? {
      val target = text.substringBefore('?').trimEnd('/')
      if (!target.equals(PAIR_HOST, ignoreCase = true)) return null
      val query = parameters(text.substringAfter('?', ""))
      val host = query["host"]?.takeIf { it.isNotBlank() && isHost(it) } ?: return null
      val port = query["port"]?.let { it.toIntOrNull() ?: return null } ?: DEFAULT_PORT
      if (port !in PORTS) return null
      return PairingLink(
        host = host.removePrefix("[").removeSuffix("]"),
        port = port,
        token = token,
        name = query["name"]?.ifBlank { null },
        secure = query["secure"].let { it == "1" || it.equals("true", ignoreCase = true) },
      )
    }

    private fun authorityOf(text: String): String =
      text.substringBefore('/').substringBefore('?')

    // host, host:port, [v6]:port, or a bare IPv6 address.
    private fun parseAddress(text: String, defaultPort: Int): PairingLink? {
      val host: String
      val portText: String?
      when {
        text.startsWith('[') -> {
          val close = text.indexOf(']')
          if (close < 0) return null
          host = text.substring(1, close)
          val after = text.substring(close + 1)
          portText = when {
            after.isEmpty() -> null
            after.startsWith(':') -> after.substring(1)
            else -> return null
          }
        }
        text.count { it == ':' } == 1 -> {
          host = text.substringBefore(':')
          portText = text.substringAfter(':')
        }
        else -> {
          host = text
          portText = null
        }
      }
      if (host.isEmpty() || !isHost(host)) return null
      val port = if (portText == null) defaultPort else portText.toIntOrNull() ?: return null
      if (port !in PORTS) return null
      return PairingLink(host = host, port = port)
    }

    private fun isHost(text: String): Boolean = text.none { it in "/?#@ " }

    private fun parameters(text: String): Map<String, String> =
      text.split('&').filter { it.isNotEmpty() }.associate { pair ->
        percentDecode(pair.substringBefore('='), plusAsSpace = true) to
          percentDecode(pair.substringAfter('=', ""), plusAsSpace = true)
      }
  }
}

/**
 * The addresses another device on the network can reach this one at: the IPv4 addresses of
 * [interfaces], private ones (`10/8`, `172.16/12`, `192.168/16`) first, without loopback and
 * link-local ones. IPv6 addresses are left out, since a phone on the same Wi-Fi reaches IPv4
 * reliably and a link-local IPv6 address needs an interface name.
 */
fun pairingAddresses(interfaces: List<NetworkInterfaceInfo>): List<String> {
  val ipv4 = interfaces.flatMap { it.addresses }
    .mapNotNull { address -> ipv4Octets(address)?.let { address to it } }
    .filterNot { (_, octets) -> octets[0] == LOOPBACK || isLinkLocal(octets) }
    .distinctBy { it.first }
  return ipv4.sortedBy { (_, octets) -> if (isPrivate(octets)) 0 else 1 }.map { it.first }
}

/** Whether [address] is an IPv4 address of a private network. */
fun isPrivateIpv4(address: String): Boolean = ipv4Octets(address)?.let(::isPrivate) == true

private fun ipv4Octets(address: String): List<Int>? {
  val parts = address.split('.')
  if (parts.size != 4) return null
  return parts.map { part -> part.toIntOrNull()?.takeIf { it in 0..255 } ?: return null }
}

private fun isPrivate(octets: List<Int>): Boolean = when (octets[0]) {
  10 -> true
  172 -> octets[1] in 16..31
  192 -> octets[1] == 168
  else -> false
}

private fun isLinkLocal(octets: List<Int>): Boolean = octets[0] == 169 && octets[1] == 254

private const val LOOPBACK = 127

private const val UNRESERVED = "-._~"
private const val HEX = "0123456789ABCDEF"

private fun percentEncode(text: String): String = buildString {
  for (byte in text.encodeToByteArray()) {
    val char = byte.toInt().toChar()
    if (byte >= 0 && (char.isLetterOrDigit() || char in UNRESERVED)) {
      append(char)
    } else {
      val value = byte.toInt() and 0xFF
      append('%').append(HEX[value shr 4]).append(HEX[value and 0x0F])
    }
  }
}
