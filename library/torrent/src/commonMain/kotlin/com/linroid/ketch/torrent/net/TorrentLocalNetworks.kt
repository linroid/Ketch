package com.linroid.ketch.torrent.net

/** An IPv4 address of this device on a private network, with its prefix length. */
internal data class LocalIpv4Network(val address: ByteArray, val prefixLength: Int) {
  init { require(address.size == 4 && prefixLength in 1..32) }

  /** Whether [host], an IPv4 address in dotted form, is on this network. */
  fun contains(host: ByteArray): Boolean {
    if (host.size != 4) return false
    val mask = if (prefixLength == 32) -1 else ((1L shl 32) - (1L shl (32 - prefixLength))).toInt()
    return (toInt(address) and mask) == (toInt(host) and mask)
  }

  /** Where a home router usually sits on this network: the first and the last host of a /24. */
  fun gatewayGuesses(): List<ByteArray> = listOf(1, 254).map { last ->
    address.copyOf().also { it[3] = last.toByte() }
  }.filterNot { it.contentEquals(address) }

  override fun equals(other: Any?): Boolean = other is LocalIpv4Network &&
    address.contentEquals(other.address) && prefixLength == other.prefixLength

  override fun hashCode(): Int = address.contentHashCode() * 31 + prefixLength

  private fun toInt(bytes: ByteArray): Int =
    bytes.fold(0) { value, byte -> (value shl 8) or (byte.toInt() and 255) }
}

/**
 * This device's private IPv4 networks (RFC 1918 and carrier-grade NAT), the ones a home router
 * maps ports for; loopback, link-local and public addresses are left out.
 */
internal expect suspend fun localIpv4Networks(): List<LocalIpv4Network>

/** Whether [address] is private: 10/8, 172.16/12, 192.168/16 or 100.64/10. */
internal fun privateIpv4(address: ByteArray): Boolean {
  if (address.size != 4) return false
  val a = address[0].toInt() and 255
  val b = address[1].toInt() and 255
  return a == 10 || a == 172 && b in 16..31 || a == 192 && b == 168 || a == 100 && b in 64..127
}
