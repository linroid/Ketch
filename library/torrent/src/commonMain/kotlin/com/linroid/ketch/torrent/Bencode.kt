package com.linroid.ketch.torrent

/**
 * Bencode encoder/decoder for BitTorrent metadata.
 *
 * Supports the four bencode types:
 * - **Integers**: `i42e`
 * - **Byte strings**: `4:spam`
 * - **Lists**: `l...e`
 * - **Dictionaries**: `d...e` (keys are byte strings, sorted)
 */
internal object Bencode {

  /**
   * Decodes a bencoded [ByteArray] into a Kotlin object.
   *
   * @return one of: [Long], [ByteArray], [List], or [Map]
   * @throws IllegalArgumentException on malformed input
   */
  fun decode(data: ByteArray): Any {
    val result = decodeAt(data, 0)
    return result.first
  }

  /**
   * Encodes a Kotlin object into a bencoded [ByteArray].
   *
   * Accepts: [Long], [Int], [ByteArray], [String], [List], or
   * [Map] (with [String] or [ByteArray] keys).
   */
  fun encode(value: Any): ByteArray {
    val buffer = mutableListOf<Byte>()
    encodeInto(value, buffer)
    return buffer.toByteArray()
  }

  // -- Decoding ----------------------------------------------------------

  private fun decodeAt(data: ByteArray, offset: Int): Pair<Any, Int> {
    require(offset < data.size) { "Unexpected end of data at $offset" }
    return when (data[offset].toInt().toChar()) {
      'i' -> decodeInt(data, offset)
      'l' -> decodeList(data, offset)
      'd' -> decodeDict(data, offset)
      in '0'..'9' -> decodeString(data, offset)
      else -> throw IllegalArgumentException(
        "Unexpected byte '${data[offset].toInt().toChar()}' " +
          "at offset $offset"
      )
    }
  }

  private fun decodeInt(
    data: ByteArray,
    offset: Int,
  ): Pair<Long, Int> {
    var i = offset + 1 // skip 'i'
    val end = data.indexOf('e'.code.toByte(), i)
    require(end > i) { "Malformed integer at offset $offset" }
    val str = data.decodeToString(i, end)
    val value = str.toLongOrNull()
      ?: throw IllegalArgumentException(
        "Invalid integer '$str' at offset $offset"
      )
    return value to (end + 1)
  }

  private fun decodeString(
    data: ByteArray,
    offset: Int,
  ): Pair<ByteArray, Int> {
    val colon = data.indexOf(':'.code.toByte(), offset)
    require(colon > offset) {
      "Malformed string length at offset $offset"
    }
    val lenStr = data.decodeToString(offset, colon)
    val len = lenStr.toIntOrNull()
      ?: throw IllegalArgumentException(
        "Invalid string length '$lenStr' at offset $offset"
      )
    val start = colon + 1
    val end = start + len
    require(end <= data.size) {
      "String overflows data at offset $offset (len=$len)"
    }
    return data.copyOfRange(start, end) to end
  }

  private fun decodeList(
    data: ByteArray,
    offset: Int,
  ): Pair<List<Any>, Int> {
    val list = mutableListOf<Any>()
    var i = offset + 1 // skip 'l'
    while (i < data.size && data[i] != 'e'.code.toByte()) {
      val (value, next) = decodeAt(data, i)
      list.add(value)
      i = next
    }
    require(i < data.size) { "Unterminated list at offset $offset" }
    return list to (i + 1) // skip 'e'
  }

  private fun decodeDict(
    data: ByteArray,
    offset: Int,
  ): Pair<Map<String, Any>, Int> {
    val map = linkedMapOf<String, Any>()
    var i = offset + 1 // skip 'd'
    while (i < data.size && data[i] != 'e'.code.toByte()) {
      val (keyBytes, afterKey) = decodeString(data, i)
      val key = keyBytes.decodeToString()
      val (value, afterValue) = decodeAt(data, afterKey)
      map[key] = value
      i = afterValue
    }
    require(i < data.size) {
      "Unterminated dictionary at offset $offset"
    }
    return map to (i + 1) // skip 'e'
  }

  // -- Encoding ----------------------------------------------------------

  private fun encodeInto(value: Any, buffer: MutableList<Byte>) {
    when (value) {
      is Long -> encodeInt(value, buffer)
      is Int -> encodeInt(value.toLong(), buffer)
      is ByteArray -> encodeBytes(value, buffer)
      is String -> encodeBytes(value.encodeToByteArray(), buffer)
      is List<*> -> encodeList(value, buffer)
      is Map<*, *> -> encodeDict(value, buffer)
      else -> throw IllegalArgumentException(
        "Cannot bencode ${value::class.simpleName}"
      )
    }
  }

  private fun encodeInt(value: Long, buffer: MutableList<Byte>) {
    buffer.add('i'.code.toByte())
    value.toString().encodeToByteArray().forEach { buffer.add(it) }
    buffer.add('e'.code.toByte())
  }

  private fun encodeBytes(
    value: ByteArray,
    buffer: MutableList<Byte>,
  ) {
    value.size.toString().encodeToByteArray()
      .forEach { buffer.add(it) }
    buffer.add(':'.code.toByte())
    value.forEach { buffer.add(it) }
  }

  private fun encodeList(
    value: List<*>,
    buffer: MutableList<Byte>,
  ) {
    buffer.add('l'.code.toByte())
    for (item in value) {
      encodeInto(item ?: continue, buffer)
    }
    buffer.add('e'.code.toByte())
  }

  private fun encodeDict(
    value: Map<*, *>,
    buffer: MutableList<Byte>,
  ) {
    buffer.add('d'.code.toByte())
    // Bencode dictionaries must have sorted keys
    val sorted = value.entries.sortedBy { (k, _) ->
      when (k) {
        is String -> k
        is ByteArray -> k.decodeToString()
        else -> k.toString()
      }
    }
    for ((k, v) in sorted) {
      val keyBytes = when (k) {
        is String -> k.encodeToByteArray()
        is ByteArray -> k
        else -> k.toString().encodeToByteArray()
      }
      encodeBytes(keyBytes, buffer)
      encodeInto(v ?: continue, buffer)
    }
    buffer.add('e'.code.toByte())
  }

  // indexOf helper for ByteArray
  private fun ByteArray.indexOf(byte: Byte, start: Int): Int {
    for (i in start until size) {
      if (this[i] == byte) return i
    }
    return -1
  }

  private fun ByteArray.decodeToString(start: Int, end: Int): String {
    return copyOfRange(start, end).decodeToString()
  }
}
