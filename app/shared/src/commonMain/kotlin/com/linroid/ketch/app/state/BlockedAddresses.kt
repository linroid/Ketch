package com.linroid.ketch.app.state

private val SEPARATORS = Regex("[\\s,;]+")

/**
 * Outcome of [addBlockedAddresses].
 *
 * @property addresses the list with the accepted entries appended.
 * @property rejected typed entries that are no address, block or range, in typed order.
 */
data class BlockedAddition(val addresses: List<String>, val rejected: List<String>)

/**
 * Appends the entries typed or pasted in [text], separated by spaces, commas or new lines, to
 * [current], skipping ones already there and rejecting ones [isBlockedAddress] does not take.
 */
fun addBlockedAddresses(current: List<String>, text: String): BlockedAddition {
  val typed = text.split(SEPARATORS).filter { it.isNotEmpty() }.distinct()
  val rejected = typed.filterNot(::isBlockedAddress)
  val accepted = typed.filter { isBlockedAddress(it) && it !in current }
  return BlockedAddition(current + accepted, rejected)
}

/**
 * Whether [entry] is what the torrent engine blocks peers by: an IPv4 or IPv6 address, a CIDR
 * block such as `203.0.113.0/24`, or a range of two addresses of one kind joined by a dash.
 */
fun isBlockedAddress(entry: String): Boolean {
  if (entry.length > 100) return false
  if ('/' in entry) {
    val bits = addressBits(entry.substringBefore('/')) ?: return false
    val prefix = entry.substringAfter('/').toIntOrNull() ?: return false
    return prefix in 0..bits
  }
  if ('-' in entry) {
    val first = addressBits(entry.substringBefore('-')) ?: return false
    return addressBits(entry.substringAfter('-')) == first
  }
  return addressBits(entry) != null
}

/** 32 for an IPv4 literal, 128 for an IPv6 one, `null` for anything else. */
private fun addressBits(text: String): Int? {
  if (':' !in text) return if (isIpv4(text)) 32 else null
  // An IPv4 tail, as in `::ffff:192.0.2.1`, stands for the last two groups.
  val tail = text.substringAfterLast(':')
  val head = if ('.' in tail) {
    if (!isIpv4(tail)) return null
    text.substringBeforeLast(':') + ":0:0"
  } else {
    text
  }
  val halves = head.split("::")
  if (halves.size > 2) return null
  fun groups(part: String): Int? {
    if (part.isEmpty()) return 0
    val groups = part.split(':')
    val hex = groups.all { group ->
      group.length in 1..4 && group.all { it.isDigit() || it.lowercaseChar() in 'a'..'f' }
    }
    return if (hex) groups.size else null
  }
  val first = groups(halves[0]) ?: return null
  val last = if (halves.size == 2) groups(halves[1]) ?: return null else 0
  // "::" stands for at least one group left out; without it all eight are written.
  val valid = if (halves.size == 2) first + last <= 7 else first == 8
  return if (valid) 128 else null
}

private fun isIpv4(text: String): Boolean {
  val parts = text.split('.')
  return parts.size == 4 && parts.all { part ->
    part.length in 1..3 && part.all(Char::isDigit) && part.toInt() <= 255
  }
}
