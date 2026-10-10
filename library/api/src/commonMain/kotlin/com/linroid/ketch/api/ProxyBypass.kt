package com.linroid.ketch.api

/** Matches hosts against the [ProxyConfig.bypass] entries, without resolving them. */
internal object ProxyBypass {
  /** Whether [entry] is a domain name, an IP address, a CIDR range, `<local>` or `*`. */
  fun isValid(entry: String): Boolean {
    val value = entry.trim().lowercase()
    if (value.isEmpty() || value.any { it.isWhitespace() || it == ',' }) return false
    if (value == "*" || value == LOCAL) return true
    if ('/' in value) return parseRange(value) != null
    val host = withoutPort(value) ?: return false
    if (IpAddress.parse(host) != null) return true
    val domain = host.removePrefix("*.").removePrefix(".")
    return domain.isNotEmpty() && domain.split('.').all { label ->
      label.isNotEmpty() && label.all { it.isLetterOrDigit() || it == '-' || it == '_' }
    }
  }

  /** Whether [host], the host of a URL, matches the bypass [entry]. */
  fun matches(entry: String, host: String): Boolean {
    val value = entry.trim().lowercase()
    val target = normalize(host)
    if (value == "*") return true
    if (value == LOCAL) return '.' !in target && ':' !in target
    val address = IpAddress.parse(target)
    if ('/' in value) {
      val (network, prefix) = parseRange(value) ?: return false
      return address != null && address.size == network.size &&
        IpAddress.sameNetwork(address, network, prefix)
    }
    val entryHost = withoutPort(value) ?: return false
    val entryAddress = IpAddress.parse(entryHost)
    if (entryAddress != null) return address != null && address.contentEquals(entryAddress)
    if (address != null) return false
    val domain = entryHost.removePrefix("*.").removePrefix(".").removeSuffix(".")
    return target == domain || target.endsWith(".$domain")
  }

  /** Whether [host] is this machine: `localhost`, `*.localhost` or a loopback address. */
  fun isLoopback(host: String): Boolean {
    val target = normalize(host)
    if (target == "localhost" || target.endsWith(".localhost")) return true
    val address = IpAddress.parse(target) ?: return false
    return when (address.size) {
      4 -> address[0] == 127.toByte()
      else -> {
        val mapped = (0 until 10).all { address[it] == 0.toByte() } &&
          address[10] == 0xff.toByte() && address[11] == 0xff.toByte()
        if (mapped) {
          address[12] == 127.toByte()
        } else {
          (0 until 15).all { address[it] == 0.toByte() } && address[15] == 1.toByte()
        }
      }
    }
  }

  private fun normalize(host: String): String =
    host.trim().lowercase().removePrefix("[").removeSuffix("]").removeSuffix(".")

  /** [value] without a `:port` suffix or the brackets of an IPv6 address, or `null`. */
  private fun withoutPort(value: String): String? {
    if (value.startsWith("[")) {
      val close = value.indexOf(']')
      if (close < 0) return null
      val rest = value.substring(close + 1)
      if (rest.isNotEmpty() && !rest.isPort()) return null
      return value.substring(1, close)
    }
    val colon = value.indexOf(':')
    if (colon < 0 || value.lastIndexOf(':') != colon) return value
    return if (value.substring(colon).isPort()) value.substring(0, colon) else null
  }

  private fun String.isPort(): Boolean =
    length > 1 && startsWith(":") && substring(1).all { it.isDigit() }

  private fun parseRange(value: String): Pair<ByteArray, Int>? {
    val slash = value.indexOf('/')
    val network = IpAddress.parse(value.substring(0, slash).removePrefix("[").removeSuffix("]"))
      ?: return null
    val prefixText = value.substring(slash + 1)
    if (prefixText.isEmpty() || !prefixText.all { it.isDigit() } || prefixText.length > 3) {
      return null
    }
    val prefix = prefixText.toInt()
    if (prefix > network.size * 8) return null
    return network to prefix
  }

  private const val LOCAL = "<local>"
}

/** Parses IPv4 and IPv6 address literals without resolving anything. */
internal object IpAddress {
  /** The 4 or 16 bytes of [text], or `null` when it is not an address literal. */
  fun parse(text: String): ByteArray? {
    val value = text.substringBefore('%')
    return if (':' in value) parseV6(value) else parseV4(value)
  }

  /** Whether the first [prefix] bits of [address] and [network] are the same. */
  fun sameNetwork(address: ByteArray, network: ByteArray, prefix: Int): Boolean {
    for (bit in 0 until prefix) {
      val index = bit / 8
      val mask = 0x80 ushr (bit % 8)
      if ((address[index].toInt() and mask) != (network[index].toInt() and mask)) return false
    }
    return true
  }

  private fun parseV4(text: String): ByteArray? {
    val parts = text.split('.')
    if (parts.size != 4) return null
    val bytes = ByteArray(4)
    for ((index, part) in parts.withIndex()) {
      if (part.isEmpty() || part.length > 3 || !part.all { it.isDigit() }) return null
      val value = part.toInt()
      if (value > 255) return null
      bytes[index] = value.toByte()
    }
    return bytes
  }

  private fun parseV6(text: String): ByteArray? {
    val compressed = text.indexOf("::")
    if (compressed >= 0 && text.indexOf("::", compressed + 1) >= 0) return null
    val head = if (compressed >= 0) text.substring(0, compressed) else text
    val tail = if (compressed >= 0) text.substring(compressed + 2) else ""
    val headGroups = groups(head) ?: return null
    val tailGroups = groups(tail) ?: return null
    val total = headGroups.size + tailGroups.size
    if (compressed < 0 && total != 16) return null
    if (compressed >= 0 && total > 14) return null
    val bytes = ByteArray(16)
    headGroups.forEachIndexed { index, byte -> bytes[index] = byte }
    tailGroups.forEachIndexed { index, byte -> bytes[16 - tailGroups.size + index] = byte }
    return bytes
  }

  /** The bytes of colon-separated hex groups, the last of which may be an IPv4 address. */
  private fun groups(text: String): List<Byte>? {
    if (text.isEmpty()) return emptyList()
    val parts = text.split(':')
    val bytes = mutableListOf<Byte>()
    for ((index, part) in parts.withIndex()) {
      if (index == parts.lastIndex && '.' in part) {
        parseV4(part)?.let { bytes.addAll(it.toList()) } ?: return null
        continue
      }
      if (part.isEmpty() || part.length > 4) return null
      val value = part.toIntOrNull(16)?.takeIf { part.all { it.digitToIntOrNull(16) != null } }
        ?: return null
      bytes.add((value shr 8).toByte())
      bytes.add(value.toByte())
    }
    return bytes
  }
}
