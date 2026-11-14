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

/** 32 for an IPv4 literal, 128 for what reads as an IPv6 one, `null` for anything else. */
private fun addressBits(text: String): Int? {
  if (':' in text) {
    val groups = text.split(':')
    val hex = groups.all { group ->
      group.length <= 4 && group.all { it.isDigit() || it.lowercaseChar() in 'a'..'f' }
    }
    // At most one "::", which stands for the groups left out.
    val shortened = "::" in text
    val valid = hex && groups.size in 3..8 && "::" !in text.replaceFirst("::", ":") &&
      (groups.size == 8 || shortened) && (shortened || groups.none { it.isEmpty() })
    return if (valid) 128 else null
  }
  val parts = text.split('.')
  val valid = parts.size == 4 && parts.all { part ->
    part.length in 1..3 && part.all(Char::isDigit) && part.toInt() <= 255
  }
  return if (valid) 32 else null
}
