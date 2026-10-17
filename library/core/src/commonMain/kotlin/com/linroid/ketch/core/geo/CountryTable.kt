package com.linroid.ketch.core.geo

/**
 * IP ranges and their countries, read from DB-IP's CSV lines (`start,end,CC`, sorted, covering
 * every address). IPv6 ranges are kept by their first 64 bits, the network part.
 */
internal class CountryTable private constructor(
  private val v4Starts: LongArray,
  private val v4Codes: ShortArray,
  private val v6Starts: LongArray,
  private val v6Codes: ShortArray,
) {
  /** The ISO 3166 code of [ip]'s country; `null` for names, reserved and unknown addresses. */
  fun countryOf(ip: String): String? {
    val address = parseIp(ip) ?: return null
    val code = when (address) {
      is IpKey.V4 -> find(v4Starts, v4Codes, address.value)
      is IpKey.V6 -> find(v6Starts, v6Codes, address.value)
    }
    if (code == 0.toShort()) return null
    return charArrayOf((code.toInt() shr 8).toChar(), (code.toInt() and 0xFF).toChar())
      .concatToString()
  }

  val size: Int get() = v4Starts.size + v6Starts.size

  companion object {
    /** Reads [lines], skipping those that do not parse or are out of order. */
    fun parse(lines: Sequence<String>): CountryTable {
      val v4 = Builder(compareUnsigned = false)
      val v6 = Builder(compareUnsigned = true)
      for (line in lines) {
        val fields = line.split(',')
        if (fields.size < 3) continue
        val code = codeOf(fields[2].trim().trim('"'))
        when (val start = parseIp(fields[0].trim('"'))) {
          is IpKey.V4 -> v4.add(start.value, code)
          is IpKey.V6 -> v6.add(start.value, code)
          null -> Unit
        }
      }
      return CountryTable(v4.starts(), v4.codes(), v6.starts(), v6.codes())
    }

    private fun codeOf(text: String): Short {
      if (text.length != 2 || text == "ZZ" || !text.all { it in 'A'..'Z' }) return 0
      return ((text[0].code shl 8) or text[1].code).toShort()
    }
  }

  private class Builder(private val compareUnsigned: Boolean) {
    private var starts = LongArray(1024)
    private var codes = ShortArray(1024)
    private var size = 0

    fun add(start: Long, code: Short) {
      if (size > 0) {
        val last = starts[size - 1]
        val order = if (compareUnsigned) start.toULong().compareTo(last.toULong())
        else start.compareTo(last)
        if (order < 0) return
        // Adjacent ranges of one country are one range; a range inside the previous one's first
        // 64 bits replaces it.
        if (codes[size - 1] == code) return
        if (order == 0) {
          codes[size - 1] = code
          return
        }
      }
      if (size == starts.size) {
        starts = starts.copyOf(size * 2)
        codes = codes.copyOf(size * 2)
      }
      starts[size] = start
      codes[size] = code
      size++
    }

    fun starts() = starts.copyOf(size)

    fun codes() = codes.copyOf(size)
  }

  private fun find(starts: LongArray, codes: ShortArray, key: Long): Short {
    val unsigned = starts === v6Starts
    var low = 0
    var high = starts.size - 1
    var found = -1
    while (low <= high) {
      val mid = (low + high) ushr 1
      val order = if (unsigned) starts[mid].toULong().compareTo(key.toULong())
      else starts[mid].compareTo(key)
      if (order <= 0) {
        found = mid
        low = mid + 1
      } else {
        high = mid - 1
      }
    }
    return if (found < 0) 0 else codes[found]
  }
}

internal sealed interface IpKey {
  /** An IPv4 address, as an unsigned number. */
  data class V4(val value: Long) : IpKey

  /** An IPv6 address's first 64 bits. */
  data class V6(val value: Long) : IpKey
}

/**
 * [text] as an IP address; `null` when it is not one. IPv4-mapped IPv6 addresses
 * (`::ffff:a.b.c.d`) read as IPv4.
 */
internal fun parseIp(text: String): IpKey? {
  if ('.' in text && ':' !in text) return parseIpv4(text)?.let { IpKey.V4(it) }
  if (':' !in text) return null
  val address = text.substringBefore('%')
  val halves = address.split("::")
  if (halves.size > 2) return null
  fun groups(part: String): List<Int>? {
    if (part.isEmpty()) return emptyList()
    return buildList {
      val pieces = part.split(':')
      for ((index, piece) in pieces.withIndex()) {
        if (index == pieces.lastIndex && '.' in piece) {
          val v4 = parseIpv4(piece) ?: return null
          add((v4 shr 16).toInt())
          add((v4 and 0xFFFF).toInt())
        } else {
          if (piece.isEmpty() || piece.length > 4) return null
          add(piece.toIntOrNull(16) ?: return null)
        }
      }
    }
  }
  val head = groups(halves[0]) ?: return null
  val tail = if (halves.size == 2) groups(halves[1]) ?: return null else emptyList()
  val missing = 8 - head.size - tail.size
  if (missing < 0 || (halves.size == 1 && missing != 0) || (halves.size == 2 && missing < 1)) {
    return null
  }
  val all = head + List(missing) { 0 } + tail
  if (all.subList(0, 5).all { it == 0 } && all[5] == 0xFFFF) {
    return IpKey.V4((all[6].toLong() shl 16) or all[7].toLong())
  }
  var value = 0L
  for (group in all.subList(0, 4)) value = (value shl 16) or group.toLong()
  return IpKey.V6(value)
}

private fun parseIpv4(text: String): Long? {
  val parts = text.split('.')
  if (parts.size != 4) return null
  var value = 0L
  for (part in parts) {
    if (part.isEmpty() || part.length > 3 || !part.all { it.isDigit() }) return null
    val byte = part.toInt()
    if (byte > 255) return null
    value = (value shl 8) or byte.toLong()
  }
  return value
}
