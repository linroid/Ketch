package com.linroid.ketch.torrent.net

/**
 * Addresses Ketch connects to no peer at and accepts none from: single addresses
 * (`203.0.113.7`, `2001:db8::1`), CIDR blocks (`203.0.113.0/24`, `2001:db8::/32`) and ranges
 * (`203.0.113.1-203.0.113.99`), IPv4 or IPv6. An IPv4-mapped IPv6 address is matched as the IPv4
 * one it maps. Trackers and DHT nodes are not peers and are not filtered.
 */
internal class TorrentIpFilter private constructor(private val ranges: List<Range>) {
  /** [first] to [last], both of one family and included. */
  private class Range(val first: ByteArray, val last: ByteArray)

  /** Whether nothing is blocked, so callers skip parsing hosts. */
  val isEmpty: Boolean get() = ranges.isEmpty()

  /** Whether [host], a numeric address, is blocked; a name or anything unreadable is not. */
  fun blocks(host: String): Boolean {
    if (ranges.isEmpty()) return false
    val address = numericAddress(host)?.let(::unmapped) ?: return false
    return ranges.any { range ->
      range.first.size == address.size && compare(range.first, address) <= 0 &&
        compare(address, range.last) <= 0
    }
  }

  companion object {
    /** Blocks nothing. */
    val NONE = TorrentIpFilter(emptyList())

    /** The most entries a filter takes. */
    const val MAX_ENTRIES = 10_000

    /**
     * The filter of [entries], ignoring blank ones.
     *
     * @throws IllegalArgumentException for an entry that is no address, block or range, or
     *   past [MAX_ENTRIES]
     */
    fun parse(entries: List<String>): TorrentIpFilter {
      val kept = entries.map { it.trim() }.filter { it.isNotEmpty() }
      require(kept.size <= MAX_ENTRIES) { "At most $MAX_ENTRIES blocked addresses" }
      if (kept.isEmpty()) return NONE
      return TorrentIpFilter(
        kept.map { entry ->
          requireNotNull(range(entry)) { "Not an address, a CIDR block or a range: $entry" }
        }
      )
    }

    /** The filter of the entries of [entries] it can read; [onInvalid] is told the others. */
    fun lenient(entries: List<String>, onInvalid: (String) -> Unit = {}): TorrentIpFilter =
      parse(entries.filter { entry ->
        (entry.isBlank() || isValid(entry)).also { valid -> if (!valid) onInvalid(entry) }
      }.take(MAX_ENTRIES))

    /** Whether [entry] is an address, a CIDR block or a range [parse] takes. */
    fun isValid(entry: String): Boolean = range(entry.trim()) != null

    private fun range(entry: String): Range? {
      if (entry.length > 100) return null
      if ('/' in entry) {
        val address = numericAddress(entry.substringBefore('/'))?.let(::unmapped) ?: return null
        val prefix = entry.substringAfter('/').toIntOrNull() ?: return null
        if (prefix !in 0..address.size * 8) return null
        val first = address.copyOf()
        val last = address.copyOf()
        for (bit in prefix until address.size * 8) {
          val mask = 0x80 ushr (bit % 8)
          first[bit / 8] = (first[bit / 8].toInt() and mask.inv()).toByte()
          last[bit / 8] = (last[bit / 8].toInt() or mask).toByte()
        }
        return Range(first, last)
      }
      // An IPv6 address holds no dash, so one always separates the ends of a range.
      if ('-' in entry) {
        val first = numericAddress(entry.substringBefore('-').trim())?.let(::unmapped)
          ?: return null
        val last = numericAddress(entry.substringAfter('-').trim())?.let(::unmapped)
          ?: return null
        if (first.size != last.size || compare(first, last) > 0) return null
        return Range(first, last)
      }
      val address = numericAddress(entry)?.let(::unmapped) ?: return null
      return Range(address, address)
    }

    /** The four bytes of an IPv4-mapped IPv6 address, else [address] as it is. */
    private fun unmapped(address: ByteArray): ByteArray {
      val mapped = address.size == 16 && (0 until 10).all { address[it] == 0.toByte() } &&
        address[10] == 0xff.toByte() && address[11] == 0xff.toByte()
      return if (mapped) address.copyOfRange(12, 16) else address
    }

    private fun compare(left: ByteArray, right: ByteArray): Int {
      for (index in left.indices) {
        val order = (left[index].toInt() and 255) - (right[index].toInt() and 255)
        if (order != 0) return order
      }
      return 0
    }
  }
}
