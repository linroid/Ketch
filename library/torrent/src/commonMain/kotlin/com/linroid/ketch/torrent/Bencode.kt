package com.linroid.ketch.torrent

import okio.Buffer
import okio.ByteString
import okio.ByteString.Companion.encodeUtf8
import okio.ByteString.Companion.toByteString

/** Bounded bencode codec with byte-preserving keys and exact source ranges. */
internal object Bencode {
  internal data class Node(val value: Any, val start: Int, val end: Int) {
    val bytes: ByteArray? get() = value as? ByteArray
    val integer: Long? get() = value as? Long
    @Suppress("UNCHECKED_CAST")
    val list: List<Node>? get() = value as? List<Node>
    @Suppress("UNCHECKED_CAST")
    val dictionary: Map<ByteString, Node>? get() = value as? Map<ByteString, Node>

    operator fun get(key: String): Node? = dictionary?.get(key.encodeUtf8())
    fun text(): String? = bytes?.decodeToString(throwOnInvalidSequence = true)

    fun legacyValue(): Any = when (val data = value) {
      is List<*> -> list!!.map { it.legacyValue() }
      is Map<*, *> -> dictionary!!.mapKeys {
        it.key.toByteArray().decodeToString(throwOnInvalidSequence = true)
      }.mapValues { it.value.legacyValue() }
      else -> data
    }
  }

  fun decode(data: ByteArray): Any = parse(data).legacyValue()

  fun parse(data: ByteArray, maxBytes: Int = 4 * 1024 * 1024, maxNodes: Int = 100_000): Node {
    val node = parsePrefix(data, maxBytes, maxNodes)
    require(node.end == data.size) { "Trailing bencode data" }
    return node
  }

  /** Parses a header followed by binary payload (BEP 9). */
  fun parsePrefix(
    data: ByteArray,
    maxBytes: Int = 4 * 1024 * 1024,
    maxNodes: Int = 100_000,
  ): Node {
    require(data.size <= maxBytes) { "Bencode exceeds size limit" }
    require(maxNodes in 1..1_000_000)
    return Parser(data, maxNodes).read(0)
  }

  fun encode(value: Any): ByteArray {
    val buffer = Buffer()
    write(value, buffer, 0)
    return buffer.readByteArray()
  }

  private class Parser(private val data: ByteArray, private val maxNodes: Int) {
    private var cursor = 0
    private var nodes = 0

    fun read(depth: Int): Node {
      require(depth < 64 && ++nodes <= maxNodes) { "Bencode structure exceeds limits" }
      require(cursor < data.size) { "Unexpected end of bencode" }
      val start = cursor
      val value: Any = when (data[cursor].toInt().toChar()) {
        'i' -> integer()
        'l' -> {
          cursor++
          val items = mutableListOf<Node>()
          while (!consumeEnd()) items.add(read(depth + 1))
          items
        }
        'd' -> {
          cursor++
          val items = linkedMapOf<ByteString, Node>()
          var previous: ByteString? = null
          while (!consumeEnd()) {
            val key = bytes().toByteString()
            require(previous == null || previous < key) { "Unsorted or duplicate bencode key" }
            previous = key
            items[key] = read(depth + 1)
          }
          items
        }
        in '0'..'9' -> bytes()
        else -> throw IllegalArgumentException("Invalid bencode tag at $cursor")
      }
      return Node(value, start, cursor)
    }

    private fun consumeEnd(): Boolean {
      require(cursor < data.size) { "Unterminated bencode container" }
      return if (data[cursor] == 'e'.code.toByte()) {
        cursor++
        true
      } else false
    }

    private fun integer(): Long {
      val start = ++cursor
      while (cursor < data.size && data[cursor] != 'e'.code.toByte()) {
        require(cursor - start < 20) { "Bencode integer exceeds Long" }
        cursor++
      }
      require(cursor < data.size) { "Unterminated integer" }
      val text = data.decodeToString(start, cursor++)
      require(text.matches(Regex("0|-?[1-9][0-9]*"))) { "Noncanonical integer" }
      return text.toLongOrNull() ?: throw IllegalArgumentException("Bencode integer overflow")
    }

    private fun bytes(): ByteArray {
      val start = cursor
      while (cursor < data.size && data[cursor] != ':'.code.toByte()) {
        require(data[cursor] in '0'.code.toByte()..'9'.code.toByte()) { "Invalid string length" }
        require(cursor - start < 10) { "String length overflow" }
        cursor++
      }
      require(cursor < data.size && cursor > start) { "Missing string length" }
      require(cursor - start == 1 || data[start] != '0'.code.toByte()) {
        "Noncanonical string length"
      }
      val length = data.decodeToString(start, cursor++).toIntOrNull()
        ?: throw IllegalArgumentException("String length overflow")
      require(length <= data.size - cursor) { "Truncated byte string" }
      val result = data.copyOfRange(cursor, cursor + length)
      cursor += length
      return result
    }
  }

  private fun write(value: Any, out: Buffer, depth: Int) {
    require(depth < 64 && out.size <= 4 * 1024 * 1024) { "Bencode exceeds limits" }
    when (value) {
      is Node -> write(value.value, out, depth)
      is Int -> write(value.toLong(), out, depth)
      is Long -> out.writeUtf8("i${value}e")
      is String -> write(value.encodeToByteArray(), out, depth)
      is ByteString -> write(value.toByteArray(), out, depth)
      is ByteArray -> {
        require(value.size <= 4 * 1024 * 1024) { "Bencode string exceeds limit" }
        out.writeUtf8("${value.size}:")
        out.write(value)
      }
      is List<*> -> {
        out.writeByte('l'.code)
        value.forEach { write(requireNotNull(it) { "Null bencode value" }, out, depth + 1) }
        out.writeByte('e'.code)
      }
      is Map<*, *> -> {
        val entries = value.entries.map { (key, item) ->
          val bytes = when (key) {
            is String -> key.encodeUtf8()
            is ByteArray -> key.toByteString()
            is ByteString -> key
            else -> throw IllegalArgumentException("Invalid bencode key")
          }
          bytes to requireNotNull(item) { "Null bencode value" }
        }.sortedBy { it.first }
        require(entries.zipWithNext().all { it.first.first != it.second.first }) {
          "Duplicate binary bencode key"
        }
        out.writeByte('d'.code)
        entries.forEach { (key, item) ->
          write(key, out, depth + 1)
          write(item, out, depth + 1)
        }
        out.writeByte('e'.code)
      }
      else -> throw IllegalArgumentException("Unsupported bencode value")
    }
    require(out.size <= 4 * 1024 * 1024) { "Bencode exceeds size limit" }
  }
}
