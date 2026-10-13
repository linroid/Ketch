package com.linroid.ketch.torrent.net

/** Parses literals only. Network-provided compact contacts never cause DNS lookups. */
internal fun numericAddress(host: String): ByteArray? {
  fun ipv4(value: String): ByteArray? {
    val parts = value.split('.')
    if (parts.size != 4) return null
    val output = ByteArray(4)
    for ((index, part) in parts.withIndex()) {
      if (part.isEmpty() || part.any { it !in '0'..'9' } || part.length > 3) return null
      val number = part.toIntOrNull() ?: return null
      if (number !in 0..255) return null
      output[index] = number.toByte()
    }
    return output
  }
  var value = host.removeSurrounding("[", "]")
  if (':' !in value) return ipv4(value)
  if ('.' in value) {
    val tail = ipv4(value.substringAfterLast(':')) ?: return null
    value = value.substringBeforeLast(':') + ":" +
      (((tail[0].toInt() and 255) shl 8) or (tail[1].toInt() and 255)).toString(16) + ":" +
      (((tail[2].toInt() and 255) shl 8) or (tail[3].toInt() and 255)).toString(16)
  }
  val halves = value.split("::")
  if (halves.size > 2) return null
  fun groups(part: String): List<Int>? {
    if (part.isEmpty()) return emptyList()
    return part.split(':').map { group ->
      if (group.isEmpty() || group.length > 4 || group.any { it !in "0123456789abcdefABCDEF" }) {
        return null
      }
      group.toInt(16)
    }
  }
  val first = groups(halves[0]) ?: return null
  val last = if (halves.size == 2) groups(halves[1]) ?: return null else emptyList()
  val missing = 8 - first.size - last.size
  if (halves.size == 1 && missing != 0 || halves.size == 2 && missing < 1) return null
  val words = first + List(missing) { 0 } + last
  return ByteArray(16) { index -> (words[index / 2] ushr (if (index % 2 == 0) 8 else 0)).toByte() }
}

/**
 * Keeps address families intact; reverse DNS may turn ::1 into IPv4 localhost. An IPv4-mapped
 * address (`::ffff:a.b.c.d`, as some trackers list in `peers6`) is the IPv4 peer it maps, so the
 * same peer never appears under two hosts.
 */
internal fun numericHost(bytes: ByteArray): String {
  require(bytes.size == 4 || bytes.size == 16)
  val mapped = bytes.size == 16 && (0 until 10).all { bytes[it] == 0.toByte() } &&
    bytes[10] == 0xff.toByte() && bytes[11] == 0xff.toByte()
  return if (bytes.size == 4 || mapped) {
    bytes.copyOfRange(bytes.size - 4, bytes.size).joinToString(".") {
      (it.toInt() and 255).toString()
    }
  } else {
    (0 until 8).joinToString(":") { index ->
      (((bytes[index * 2].toInt() and 255) shl 8) or
        (bytes[index * 2 + 1].toInt() and 255)).toString(16)
    }
  }
}

internal fun publicTorrentAddress(address: ByteArray): Boolean {
  if (address.size == 16) {
    return address[0].toInt() and 224 == 32 &&
      !(address[0] == 0x20.toByte() && address[1] == 1.toByte() &&
        address[2] == 0x0d.toByte() && address[3] == 0xb8.toByte())
  }
  if (address.size != 4) return false
  val a = address[0].toInt() and 255
  val b = address[1].toInt() and 255
  val c = address[2].toInt() and 255
  return a !in listOf(0, 10, 127) && a < 224 && !(a == 100 && b in 64..127) &&
    !(a == 169 && b == 254) && !(a == 172 && b in 16..31) &&
    !(a == 192 && (b == 168 || b == 0 && c in listOf(0, 2))) &&
    !(a == 198 && (b in 18..19 || b == 51 && c == 100)) &&
    !(a == 203 && b == 0 && c == 113)
}
